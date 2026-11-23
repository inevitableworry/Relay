// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

/** Decisions use real stream samples; link speed and advertised 4K alone never prove playback. */
class JbQualityPolicy(
    advertisedHeights: List<Int>,
    decoderHeights: Set<Int>,
    displayVideoHeight: Int,
    private val settings: Settings,
    private val rate: PbsRateControl?,
    private val audioBps: Long
) {
    data class Settings(val automatic: Boolean, val manualHeight: Int = 1080, val maxHeight: Int = 2160,
                        val maxBandwidthMbps: Int = 0, val videoBitrateMbps: Int = 0, val minQp: Int = 18)
    data class Sample(val timeMs: Long, val decodedFps: Double, val sourceFps: Double,
                      val frameAgeMs: Long, val lagMs: Long, val videoMbps: Double, val audioMbps: Double,
                      val audioUnderruns: Int = 0)
    data class Decision(val request: PbsProfileRequest, val reason: String)

    private val deviceHeights = advertisedHeights.distinct().sorted().filter { it in decoderHeights }
    private val autoHeights = deviceHeights.filter {
        it <= settings.maxHeight && it <= maxOf(720, displayVideoHeight) &&
            (videoBudgetBps() == null || videoBudgetBps()!! >= minimumBps(it))
    }
    private val rejectedUntil = mutableMapOf<Int, Long>()
    private val rateScales = mutableMapOf<Int, Double>()
    private val adaptiveBitrate = rate != null && settings.videoBitrateMbps == 0
    private var current: PbsProfileRequest? = null
    private var changedAt = 0L
    private var lastSampleAt = 0L
    private var badSamples = 0
    private var healthySince = 0L

    init {
        require(deviceHeights.isNotEmpty()) { "This Android device has no hardware H.264 decoder for the PS5's advertised profiles" }
        require(settings.maxBandwidthMbps == 0 || settings.maxBandwidthMbps in 6..120)
        require(settings.videoBitrateMbps in 0..100 && settings.minQp in 18..35)
        require(audioBps >= 0)
        if (settings.maxBandwidthMbps > 0 || settings.videoBitrateMbps > 0 || settings.minQp != 18)
            require(rate != null) { "Maximum bandwidth and encoder tuning require the updated Remote Play JB service" }
        if (settings.automatic) require(autoHeights.isNotEmpty()) {
            "The bandwidth limit is too low for a supported PS5 video profile and the selected audio"
        }
    }

    fun initial(nowMs: Long): PbsProfileRequest {
        val height = if (settings.automatic) autoHeights.lastOrNull { it <= 1080 } ?: autoHeights.first()
        else settings.manualHeight.also {
            require(it in deviceHeights) { "The PS5 or this Android hardware decoder does not support ${it}p at 60 fps" }
        }
        return request(height).also { started(it, nowMs) }
    }

    /** Reset sampling after a reconnect; failed higher profiles retain their temporary backoff. */
    fun started(request: PbsProfileRequest, nowMs: Long) {
        current = request
        changedAt = nowMs
        lastSampleAt = 0
        badSamples = 0
        healthySince = 0
    }

    /** Fold/window changes retain a working profile unless the new screen ceiling is lower. */
    fun resume(request: PbsProfileRequest, nowMs: Long): PbsProfileRequest {
        val height = if (settings.automatic) autoHeights.lastOrNull { it <= request.height } ?: autoHeights.first()
            else settings.manualHeight
        var candidate = this.request(height)
        if (adaptiveBitrate && candidate.videoBitrateBps != null && request.videoBitrateBps != null) {
            val base = candidate.videoBitrateBps!!
            val retained = minOf(base, request.videoBitrateBps)
            rateScales[height] = retained.toDouble() / base
            candidate = candidate.copy(videoBitrateBps = retained)
        }
        return candidate.also { started(it, nowMs) }
    }

    fun sample(sample: Sample): Decision? {
        val active = current ?: return null
        // A fixed resolution can still optimize its automatic encoder target.
        // Explicit bitrate overrides never participate in bitrate adaptation.
        if ((!settings.automatic && !adaptiveBitrate) || sample.timeMs <= lastSampleAt) return null
        val gap = if (lastSampleAt == 0L) 0 else sample.timeMs - lastSampleAt
        lastSampleAt = sample.timeMs
        // Startup, network changes and suspended UI must not accumulate stale evidence.
        if (sample.timeMs - changedAt < 12_000 || gap > 4_000) {
            badSamples = 0; healthySince = 0; return null
        }
        val fps = sample.sourceFps.takeIf { it.isFinite() && it >= 1.0 } ?: 60.0
        val badPace = !sample.decodedFps.isFinite() || sample.decodedFps < fps * .85
        val overBudget = settings.maxBandwidthMbps > 0 &&
            (sample.videoMbps + sample.audioMbps) * 1.10 > settings.maxBandwidthMbps * 1.05
        val bad = badPace || sample.frameAgeMs > 300 || sample.lagMs > 180 || overBudget || sample.audioUnderruns > 1
        if (bad) {
            badSamples=(badSamples+1).coerceAtMost(5)
            healthySince = 0
            if (badSamples < 4 || sample.timeMs - changedAt < 15_000) return null
            val networkPressure = sample.lagMs > 180 || overBudget || sample.frameAgeMs > 300
            val currentScale = rateScales[active.height] ?: 1.0
            if (adaptiveBitrate && (networkPressure || !settings.automatic) && currentScale > .60) {
                rateScales[active.height] = (currentScale * .75).coerceAtLeast(.55)
                val candidate = request(active.height)
                if (candidate.videoBitrateBps != active.videoBitrateBps)
                    return decide(candidate, if(settings.automatic)
                        "Auto reduced the video budget after sustained stream delay"
                        else "Optimized the ${active.height}p bitrate after sustained playback pressure", sample.timeMs)
            }
            if (!settings.automatic) return null // Keep the selected size even when bandwidth remains constrained.
            val lower = usable(sample.timeMs).lastOrNull { it < active.height } ?: return null
            autoHeights.filter { it >= active.height }.forEach { rejectedUntil[it] = sample.timeMs + 180_000 }
            return decide(request(lower), "Auto selected ${lower}p after sustained playback pressure", sample.timeMs)
        }
        badSamples = 0
        val healthy = sample.decodedFps >= fps * .94 && sample.frameAgeMs < 150 &&
            sample.lagMs < 90 && sample.audioUnderruns == 0
        if (!healthy) { healthySince = 0; return null }
        if (healthySince == 0L) healthySince = sample.timeMs
        if (sample.timeMs - healthySince < 45_000 || sample.timeMs - changedAt < 60_000) return null
        val scale = rateScales[active.height] ?: 1.0
        if (adaptiveBitrate && scale < 1.0) {
            rateScales[active.height] = (scale + .15).coerceAtMost(1.0)
            val candidate = request(active.height)
            if (candidate.videoBitrateBps != active.videoBitrateBps)
                return decide(candidate, if(settings.automatic)
                    "Auto increased the video budget after stable playback"
                    else "Restored ${active.height}p detail after stable playback", sample.timeMs)
        }
        if (!settings.automatic) return null
        val higher = usable(sample.timeMs).firstOrNull { it > active.height } ?: return null
        return decide(request(higher), "Auto is checking ${higher}p after stable playback", sample.timeMs)
    }

    /** Only used for an Auto capture/decoder failure, never authentication or controller failures. */
    fun rejectCurrent(nowMs: Long): Decision? {
        if (!settings.automatic) return null
        val active = current ?: return null
        autoHeights.filter { it >= active.height }.forEach { rejectedUntil[it] = nowMs + 300_000 }
        val lower = usable(nowMs).lastOrNull { it < active.height } ?: return null
        return decide(request(lower), "Auto returned to ${lower}p because the higher profile could not start", nowMs)
    }

    private fun usable(nowMs: Long) = autoHeights.filter { (rejectedUntil[it] ?: 0L) <= nowMs }
    private fun decide(request: PbsProfileRequest, reason: String, nowMs: Long): Decision {
        started(request, nowMs)
        return Decision(request, reason)
    }

    fun request(height: Int): PbsProfileRequest {
        require(height in deviceHeights)
        if (rate == null) return PbsProfileRequest(height)
        val requested = if (settings.videoBitrateMbps > 0) settings.videoBitrateMbps * 1_000_000L
            else recommendedBps(height).toLong()
        val limit = videoBudgetBps() ?: rate.maxBps.toLong()
        require(limit >= rate.minBps) { "The bandwidth limit leaves no supported video budget after audio" }
        val base = requested.coerceAtMost(limit).coerceAtMost(rate.maxBps.toLong())
        val target = (base * (rateScales[height] ?: 1.0)).toLong()
            .coerceIn(rate.minBps.toLong(), rate.maxBps.toLong()).toInt()
        return PbsProfileRequest(height, target, settings.minQp.coerceIn(rate.minQp, rate.maxQp))
    }

    private fun videoBudgetBps(): Long? = if (settings.maxBandwidthMbps == 0) null
        else ((settings.maxBandwidthMbps * 1_000_000L * 10L / 11L) - audioBps).coerceAtLeast(0L)

    companion object {
        // Starting targets, not measured network limits; ongoing samples determine adaptation.
        fun recommendedBps(height: Int) = when (height) {720 -> 6_000_000;1080 -> 12_000_000;1440 -> 22_000_000;2160 -> 40_000_000;else -> error("Unsupported profile")}
        private fun minimumBps(height: Int) = when (height) {720 -> 2_000_000;1080 -> 4_000_000;1440 -> 8_000_000;2160 -> 16_000_000;else -> Int.MAX_VALUE}
    }
}
