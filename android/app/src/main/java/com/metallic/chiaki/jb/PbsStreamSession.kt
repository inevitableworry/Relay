// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.metallic.chiaki.lib.ControllerState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class PbsStatistics(val decodedWidth: Int, val decodedHeight: Int, val decodedFrames: Long,
                         val decodedFps: Double, val receivedVideoBytes: Long, val server: PbsStatus?,
                         val sampledAtMs: Long = 0, val receivedAudioBytes: Long = 0,
                         val videoMbps: Double = 0.0, val audioMbps: Double = 0.0,
                         val frameAgeMs: Long = 0, val streamLagMs: Long = 0,
                         val statusRttMs: Long = 0, val audioUnderruns: Int = 0)
class PbsProfileException(message: String, cause: Throwable? = null): IOException(message, cause)

/** Native Android player for the PBS framed H.264/PCM and virtual-controller interfaces. */
class PbsStreamSession(val client: PbsClient, private val listener: Listener): Closeable {
    interface Listener {
        fun onConnecting() {}
        fun onCapabilities(config: PbsConfig) {}
        fun onControllerAssigned(player: Int) {}
        /** Called only after MediaCodec has produced a frame for the Surface. */
        fun onConnected(config: PbsConfig) {}
        fun onVideoSizeChanged(width: Int, height: Int) {}
        fun onStatistics(statistics: PbsStatistics) {}
        fun onError(message: String) {}
        fun onConnectionInterrupted(error: PbsConnectionException, playedForMs: Long) { onError(error.message ?: "The PS5 connection was interrupted") }
        fun onPlaybackInterrupted() {}
        fun onProfileUnavailable(message: String) { onError(message) }
        /** A requested control is unavailable; video/audio and other controls keep running. */
        fun onInputUnavailable(message: String) {}
        fun onStopped() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val input = PbsInputQueue()
    private val inputWake = Channel<Unit>(Channel.CONFLATED)
    private val allowedButtons = AtomicInteger(PbsProtocol.LEGACY_BUTTON_MASK and PbsProtocol.NATIVE_BUTTON_MASK)
    private val warnedButtons = AtomicInteger()
    private val audioEnabled = AtomicBoolean(true)
    private data class Pulse(val button: PbsProtocol.Button, val until: Long)
    private val pulse = AtomicReference<Pulse?>(null)
    private val rendered = AtomicLong()
    private val videoBytes = AtomicLong()
    private val audioBytes = AtomicLong()
    private val lastRenderedAt = AtomicLong()
    private val firstRenderedAt = AtomicLong()
    private val lastVideoPacketAt = AtomicLong()
    private val playbackLagMs = AtomicLong()
    private val connected = AtomicBoolean()
    @Volatile private var decodedWidth = 0
    @Volatile private var decodedHeight = 0
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var job: Job? = null
    @Volatile private var closed = false
    private data class ServerSnapshot(val status: PbsStatus, val sampledAt: Long, val rtt: Long)
    val isRunning: Boolean get() = job?.isActive == true

    @Synchronized
    fun start(surface: Surface, requestedHeight: Int? = null, videoBitrateBps: Int? = null,
              minQp: Int? = null, audioBufferMs: Int = 50, recoveryDeadlineMs: Long = Long.MAX_VALUE) {
        check(!closed) { "The streaming session is closed" }
        val previous = job
        previous?.cancel()
        client.cancelRequests()
        val run = generation.incrementAndGet()
        input.clear(); pulse.set(null)
        allowedButtons.set(PbsProtocol.LEGACY_BUTTON_MASK and PbsProtocol.NATIVE_BUTTON_MASK)
        warnedButtons.set(0)
        post(run) { listener.onConnecting() }
        job = scope.launch {
            awaitPreviousPbsRun(previous)
            rendered.set(0); videoBytes.set(0); audioBytes.set(0); connected.set(false)
            lastRenderedAt.set(SystemClock.elapsedRealtime()); playbackLagMs.set(0)
            firstRenderedAt.set(0)
            lastVideoPacketAt.set(SystemClock.elapsedRealtime())
            decodedWidth = 0; decodedHeight = 0
            var token: String? = null
            val sequence = AtomicLong(1)
            var failure: Exception? = null
            var stage = PbsFailureStage.CONNECT
            try {
              val remaining = if(recoveryDeadlineMs == Long.MAX_VALUE) Long.MAX_VALUE
                  else (recoveryDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1)
              coroutineScope {
                val deadline = if(remaining == Long.MAX_VALUE) null else launch {
                    delay(remaining)
                    if(!connected.get()) {
                        client.cancelRequests()
                        throw IOException("The connection could not recover within 45 seconds. Check your network and try again.")
                    }
                }
                try {
                if (!surface.isValid) throw IOException("The video display is unavailable")
                // A previous reply may have been lost. Release that exact owner before any new acquisition.
                stage = PbsFailureStage.CLEANUP
                client.releaseController()
                stage = PbsFailureStage.CONTROLLER
                val controller = client.controllerStatus()
                if (!controller.available) throw IOException("This PS5 payload does not support controller input")
                if (controller.cleanupRequired) throw IOException("The PS5 controller service needs to be restarted (code ${controller.lastRc})")
                if (controller.connected && (client.isRemote || client.partyStatus() == null))
                    throw PbsOwnershipException("Another controller session is still connected to this PS5. Disconnect it before retrying.")
                if (controller.allowedButtons == 0) throw IOException("The PS5 payload does not advertise usable controller buttons")
                allowedButtons.set(controller.allowedButtons)
                val owner = client.openController()
                token = owner
                post(run) {listener.onControllerAssigned(client.playerSlot)}
                // Own the controller before changing the global capture profile. Keep only neutral
                // input alive while the encoder is preparing; gameplay starts after a decoded frame.
                val preparation = launch {
                    try {
                        while(isActive) {
                            client.awaitControllerWritable()
                            client.sendController(owner, sequence.getAndIncrement(), PbsProtocol.PadState())
                            delay(100)
                        }
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) {
                        client.cancelRequests()
                        throw PbsFailures.connection(error, PbsFailureStage.CONTROLLER) ?: error
                    }
                }
                stage = PbsFailureStage.CONNECT
                val config = try { waitForCapture(requestedHeight, videoBitrateBps, minQp) }
                    finally { preparation.cancelAndJoin() }
                post(run) { listener.onCapabilities(config) }
                coroutineScope {
                    val serverSnapshot = AtomicReference<ServerSnapshot?>(null)
                    worker(PbsFailureStage.VIDEO) { video(surface, config, run) }
                    if (config.audio != null && audioEnabled.get()) worker(PbsFailureStage.AUDIO) { audio(config.audio, audioBufferMs) }
                    else if (audioEnabled.get()) throw IOException("The PS5 payload is not providing audio; turn off audio to continue without sound")
                    worker(PbsFailureStage.CONTROLLER) {
                        while (isActive) {
                            client.awaitControllerWritable()
                            val before = SystemClock.elapsedRealtime()
                            val sample = input.next(before)
                            if (!input.isCurrent(sample)) continue
                            var state = sample.state
                            pulse.get()?.let { p ->
                                if (before < p.until) state = state.copy(buttons = state.buttons or p.button.mask)
                                else pulse.compareAndSet(p, null)
                            }
                            val unsupported = state.buttons and allowedButtons.get().inv()
                            if (unsupported != 0) {
                                notifyInputUnavailable(unsupported)
                                state = state.copy(buttons = state.buttons and allowedButtons.get())
                            }
                            try {
                                client.sendController(owner, sequence.getAndIncrement(), state)
                            } catch (error: PbsHttpException) {
                                if (error.statusCode != 400 || state.buttons and PbsProtocol.Button.CREATE.mask == 0) throw error
                                // A mismatched/older service must not terminate playback on Create.
                                allowedButtons.getAndUpdate { it and PbsProtocol.Button.CREATE.mask.inv() }
                                input.clear(); pulse.set(null)
                                notifyInputUnavailable(PbsProtocol.Button.CREATE.mask)
                                client.sendController(owner, sequence.getAndIncrement(), PbsProtocol.PadState())
                            }
                            input.acknowledged(sample, SystemClock.elapsedRealtime())
                            val heartbeat = (32L - (SystemClock.elapsedRealtime() - before)).coerceAtLeast(1L)
                            withTimeoutOrNull(heartbeat) { inputWake.receive() }
                        }
                    }
                    worker(PbsFailureStage.STATUS) {
                        var missedStatus = 0
                        var consecutiveMissed = 0
                        while (isActive) {
                            delay(1000)
                            val requestAt = SystemClock.elapsedRealtime()
                            val status = try { client.status() }
                            catch (error: IOException) {
                                // Statistics are optional. One failed poll must not kill healthy media/input.
                                if(error is PbsHttpException && error.statusCode in listOf(401, 403)) throw error
                                serverSnapshot.set(null)
                                missedStatus++; consecutiveMissed++
                                if(consecutiveMissed == 1 || consecutiveMissed % 5 == 0)
                                    PbsDiagnostics.event(client.isRemote, "status_missed", PbsFailureStage.STATUS,
                                        PbsFailures.reason(error), count = missedStatus)
                                continue
                            }
                            val arrived = SystemClock.elapsedRealtime()
                            serverSnapshot.set(ServerSnapshot(status, arrived, arrived - requestAt))
                            if(consecutiveMissed > 0) {
                                PbsDiagnostics.event(client.isRemote, "status_resumed", PbsFailureStage.STATUS, count = missedStatus)
                                consecutiveMissed = 0
                            }
                        }
                    }
                    worker(PbsFailureStage.STATUS) {
                        var lastTime = SystemClock.elapsedRealtime()
                        var lastFrames = 0L
                        var lastVideoBytes = 0L
                        var lastAudioBytes = 0L
                        var lastUnderruns = 0
                        while(isActive) {
                            delay(1000)
                            val now = SystemClock.elapsedRealtime()
                            // Local evidence continues every second even if optional HTTP status stalls.
                            val server = serverSnapshot.get()?.takeIf { now - it.sampledAt <= 2500 }
                            val frames = rendered.get()
                            val elapsed = (now - lastTime).coerceAtLeast(1)
                            val videoCount = videoBytes.get(); val audioCount = audioBytes.get()
                            val underruns = runCatching { audioTrack?.underrunCount ?: 0 }.getOrDefault(0)
                            val statistics = PbsStatistics(decodedWidth, decodedHeight, frames,
                                (frames - lastFrames) * 1000.0 / elapsed, videoCount, server?.status,
                                now, audioCount, (videoCount - lastVideoBytes) * .008 / elapsed,
                                (audioCount - lastAudioBytes) * .008 / elapsed,
                                (now - lastRenderedAt.get()).coerceAtLeast(0), playbackLagMs.get(),
                                server?.rtt ?: -1L, (underruns - lastUnderruns).coerceAtLeast(0))
                            if (com.metallic.chiaki.BuildConfig.DEBUG) {
                                android.util.Log.d("RelayJbMetrics", String.format(java.util.Locale.US,
                                    "t=%d width=%d height=%d frames=%d fps=%.2f video_mbps=%.3f audio_mbps=%.3f age_ms=%d lag_ms=%d rtt_ms=%d underruns=%d",
                                    now, decodedWidth, decodedHeight, frames, statistics.decodedFps,
                                    statistics.videoMbps, statistics.audioMbps, statistics.frameAgeMs,
                                    statistics.streamLagMs, statistics.statusRttMs, statistics.audioUnderruns))
                            }
                            lastTime = now; lastFrames = frames
                            lastVideoBytes = videoCount; lastAudioBytes = audioCount; lastUnderruns = underruns
                            post(run) { listener.onStatistics(statistics) }
                        }
                    }
                }
                } finally { deadline?.cancel() }
              }
            } catch (_: CancellationException) {
                // Lifecycle cancellation is an intentional stop, not a connection error.
            } catch (e: Exception) {
                failure = e
            } finally {
                input.setEnabled(false); pulse.set(null)
                client.cancelRequests()
                if(failure != null) post(run) { listener.onPlaybackInterrupted() }
                if(failure != null && connected.get()) {
                    try { client.markStreamStateUnverified() }
                    catch(error: Exception) { failure = PbsOwnershipException("The interrupted console state could not be recorded safely.", error) }
                }
                withContext(NonCancellable) {
                    token?.takeIf { !client.isRemote }?.let { owner ->
                        // The gateway neutralizes remote input; LAN cleanup sends one current neutral state.
                        runCatching { client.sendController(owner, sequence.getAndIncrement(), PbsProtocol.PadState()) }
                    }
                    try { client.releaseController() }
                    catch(error: Exception) {
                        PbsDiagnostics.event(client.isRemote, "cleanup_unverified", PbsFailureStage.CLEANUP, PbsFailures.reason(error))
                        try { client.markStreamStateUnverified() }
                        catch(recordError: Exception) {
                            if(failure != null) failure = PbsOwnershipException("The console cleanup state could not be recorded safely.", recordError)
                        }
                        if(failure != null && error is PbsOwnershipException) failure = error
                    }
                }
                failure?.let { error ->
                    val transport = PbsFailures.connection(error, stage)
                    PbsDiagnostics.event(client.isRemote, "session_failed", transport?.stage ?: stage, PbsFailures.reason(error))
                    when {
                        error is PbsProfileException -> post(run) { listener.onProfileUnavailable(error.message ?: "The selected video profile could not start") }
                        transport != null -> {
                            val playedForMs = if(firstRenderedAt.get() > 0)
                                (lastRenderedAt.get() - firstRenderedAt.get()).coerceAtLeast(0) else 0L
                            post(run) { listener.onConnectionInterrupted(transport, playedForMs) }
                        }
                        else -> post(run) { listener.onError(error.message ?: "The PS5 stream ended unexpectedly") }
                    }
                }
                post(run) { listener.onStopped() }
            }
        }
    }

    private fun CoroutineScope.worker(stage: PbsFailureStage, block: suspend CoroutineScope.() -> Unit): Job = launch {
        try { block() }
        catch (e: Exception) { client.cancelRequests(); throw PbsFailures.connection(e, stage) ?: e }
    }

    private suspend fun waitForCapture(requestedHeight: Int?, videoBitrateBps: Int?, minQp: Int?): PbsConfig {
        var initial = client.config()
        val idleDeadline = SystemClock.elapsedRealtime() + 20000
        while (initial.profileChanging && SystemClock.elapsedRealtime() < idleDeadline) {
            delay(250); initial = client.config()
        }
        if (initial.profileChanging) throw PbsProfileException("The PS5 is still applying another capture profile")
        if(client.playerSlot > 1) {
            // Player 1 owns video settings. Guests subscribe to the same encoded
            // picture/audio and must never reconfigure the shared PS5 encoder.
            while(!initial.ready && SystemClock.elapsedRealtime()<idleDeadline) {
                delay(250);initial=client.config()
            }
            if(!initial.ready || !initial.codec.startsWith("avc1."))
                throw PbsProfileException("Player 1's shared stream is not ready")
            return initial
        }
        if(client.captureGuard?.requiresFrameVerification() == true) {
            // An old risk marker permits observation of the existing capture, never a new
            // profile mutation. Actual decoded dimensions decide whether it can be cleared.
            while(!initial.ready && SystemClock.elapsedRealtime() < idleDeadline) {
                delay(250); initial = client.config()
            }
            if(!initial.ready || !initial.codec.startsWith("avc1.")) throw PbsCaptureQuarantinedException()
            return initial.copy(recoveryVerificationOnly = true)
        }
        if (requestedHeight != null && requestedHeight !in initial.resolutions)
            throw PbsProfileException("The PS5 does not advertise ${requestedHeight}p capture")
        fun matches(config: PbsConfig): Boolean = (requestedHeight == null || config.height == requestedHeight) &&
            (videoBitrateBps == null || config.rateControl?.bitrateBps == videoBitrateBps) &&
            (minQp == null || config.rateControl?.activeMinQp == minQp)
        if (!matches(initial) || initial.profileError) {
            try { client.requestProfile(PbsProfileRequest(requestedHeight ?: initial.height, videoBitrateBps, minQp)) }
            catch (e: PbsHttpException) {
                if (e.statusCode in listOf(400, 409, 422, 501)) throw PbsProfileException(e.message ?: "The PS5 rejected this capture profile", e)
                throw e
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 20000
        var config = initial
        do {
            currentCoroutineContext().ensureActive()
            config = client.config()
            if (config.profileError) throw PbsProfileException("The PS5 could not start ${config.profileErrorHeight}p with the requested encoder settings")
            if (config.ready && matches(config)) {
                if (!config.codec.startsWith("avc1.")) throw IOException("The PS5 is not serving H.264 video")
                return config
            }
            delay(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw PbsProfileException("The PS5 did not start the requested capture mode; active size is ${config.width} × ${config.height}")
    }

    private suspend fun video(surface: Surface, config: PbsConfig, run: Long) = coroutineScope {
        val sps = PbsProtocol.nalFromHex(config.sps, 7)
        val pps = PbsProtocol.nalFromHex(config.pps, 8)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
            (config.width.toLong() * config.height * 3 / 2).coerceIn(2 * 1024 * 1024, PbsProtocol.MAX_VIDEO_PACKET.toLong()).toInt())
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        val hardware = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                isHardware(info) && runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        .areSizeAndRateSupported(config.width, config.height, config.fps.toDouble())
                }.getOrDefault(false)
        } ?: throw PbsProfileException("This Android device has no hardware H.264 decoder for ${config.width} × ${config.height}")
        val codec = MediaCodec.createByCodecName(hardware.name)
        var started = false
        val packets = Channel<PbsProtocol.Packet>(3)
        var reader: Job? = null
        try {
            codec.configure(format, surface, null, 0)
            codec.start(); started = true
            reader = worker(PbsFailureStage.VIDEO) {
                client.openMedia("/frames").use { stream ->
                    while (isActive) {
                        val packet = PbsProtocol.readPacket(stream.input, PbsProtocol.MAX_VIDEO_PACKET)
                        lastVideoPacketAt.set(SystemClock.elapsedRealtime())
                        videoBytes.addAndGet(packet.data.size.toLong())
                        packets.send(packet)
                    }
                }
            }
            val info = MediaCodec.BufferInfo()
            val clock = PbsProtocol.VideoClock()
            var pending: PbsProtocol.Packet? = null
            var lastOutput = SystemClock.elapsedRealtime()
            var playbackOriginMs = Long.MIN_VALUE
            while (isActive) {
                if (pending == null) pending = packets.tryReceive().getOrNull()
                pending?.let { packet ->
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("The hardware decoder did not return an input buffer")
                        buffer.clear()
                        if (packet.data.size > buffer.remaining()) throw IOException("The PS5 video frame exceeds this device's decoder buffer")
                        buffer.put(packet.data)
                        codec.queueInputBuffer(index, 0, packet.data.size, clock.timeUs(packet.pts90k), 0)
                        pending = null
                    }
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        val codedWidth = output.getInteger(MediaFormat.KEY_WIDTH)
                        val codedHeight = output.getInteger(MediaFormat.KEY_HEIGHT)
                        decodedWidth = if (output.containsKey("crop-right") && output.containsKey("crop-left"))
                            output.getInteger("crop-right") - output.getInteger("crop-left") + 1 else codedWidth
                        decodedHeight = if (output.containsKey("crop-bottom") && output.containsKey("crop-top"))
                            output.getInteger("crop-bottom") - output.getInteger("crop-top") + 1 else codedHeight
                        val w = decodedWidth; val h = decodedHeight
                        post(run) { listener.onVideoSizeChanged(w, h) }
                        continue
                    }
                    if (index < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codec.releaseOutputBuffer(index, false)
                        continue
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(index, false)
                        throw IOException("The hardware decoder reached the end of the PS5 stream")
                    }
                    codec.releaseOutputBuffer(index, true)
                    rendered.incrementAndGet(); lastOutput = SystemClock.elapsedRealtime()
                    if (playbackOriginMs == Long.MIN_VALUE) playbackOriginMs = lastOutput - info.presentationTimeUs / 1000L
                    lastRenderedAt.set(lastOutput)
                    playbackLagMs.set((lastOutput - playbackOriginMs - info.presentationTimeUs / 1000L).coerceAtLeast(0))
                    if (connected.compareAndSet(false, true)) {
                        firstRenderedAt.set(lastOutput)
                        val actual = config.copy(width = decodedWidth.takeIf { it > 0 } ?: config.width,
                            height = decodedHeight.takeIf { it > 0 } ?: config.height)
                        client.confirmDecodedCapture(decodedWidth, decodedHeight)
                        post(run) { listener.onConnected(actual) }
                    }
                }
                if (SystemClock.elapsedRealtime() - lastOutput > 10000) {
                    if(SystemClock.elapsedRealtime() - lastVideoPacketAt.get() >= 6000)
                        throw PbsConnectionException(PbsFailureStage.VIDEO, "media_stalled", "The PS5 video connection stopped delivering frames.")
                    throw PbsProfileException("Video is arriving, but no frame was decoded from the PS5 for ten seconds")
                }
                delay(1)
            }
        } catch (error: MediaCodec.CodecException) {
            throw PbsProfileException("Android could not sustain the selected PS5 video profile", error)
        } finally {
            reader?.cancel()
            packets.cancel()
            if (started) runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun isHardware(info: MediaCodecInfo): Boolean = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    private suspend fun audio(format: PbsAudioFormat, bufferMs: Int) {
        if (format.codec != "pcm_f32le" || format.bits != 32 || format.channels !in 1..2 || format.rate !in 8000..192000)
            throw IOException("The PS5 supplied an unsupported audio format")
        val mask = if (format.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(format.rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        if (minimum <= 0) throw IOException("This device cannot play the PS5 audio format")
        val builder = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(format.rate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, format.rate * format.channels * 4 * bufferMs.coerceIn(20, 100) / 1000))
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = builder.build()
        audioTrack = track
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("Android could not initialize PS5 audio playback")
            track.setVolume(if (audioEnabled.get()) 1f else 0f)
            track.play()
            client.openMedia("/audio").use { stream ->
                while (currentCoroutineContext().isActive) {
                    val packet = PbsProtocol.readPacket(stream.input, PbsProtocol.MAX_AUDIO_PACKET)
                    audioBytes.addAndGet(packet.data.size.toLong())
                    if (packet.data.size % (format.channels * 4) != 0) throw IOException("The PS5 sent a truncated PCM audio block")
                    val samples = FloatArray(packet.data.size / 4)
                    ByteBuffer.wrap(packet.data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples)
                    for (i in samples.indices) samples[i] = if (samples[i].isFinite()) samples[i].coerceIn(-1f, 1f) else 0f
                    var offset = 0
                    while (offset < samples.size && currentCoroutineContext().isActive) {
                        val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) throw IOException("Android audio output failed (code $written)")
                        if (written == 0) delay(1) else offset += written
                    }
                }
            }
        } finally {
            audioTrack = null
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }

    fun setControllerState(state: ControllerState) {
        if (!closed && input.offer(PbsProtocol.controller(state), SystemClock.elapsedRealtime()))
            inputWake.trySend(Unit)
    }
    fun setInputEnabled(enabled: Boolean) {
        input.setEnabled(enabled)
        if (!enabled) { pulse.set(null); inputWake.trySend(Unit) }
    }
    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled.set(enabled)
        runCatching { audioTrack?.setVolume(if (enabled) 1f else 0f) }
    }
    fun supportsButton(button: PbsProtocol.Button): Boolean = !closed && allowedButtons.get() and button.mask == button.mask

    private fun notifyInputUnavailable(buttons: Int) {
        val previous = warnedButtons.getAndUpdate { it or buttons }
        if (previous and buttons == buttons) return
        val message = if (buttons and PbsProtocol.Button.CREATE.mask != 0)
            "Native captures need the updated Remote Play JB service on your PS5. Your stream can continue."
        else "The running PS5 service does not support this controller button. Your stream can continue."
        post(generation.get()) { listener.onInputUnavailable(message) }
    }

    fun pulseButton(button: PbsProtocol.Button, durationMs: Long = 120): Boolean {
        if (closed) return false
        if (!supportsButton(button)) { notifyInputUnavailable(button.mask); return false }
        pulse.set(Pulse(button, SystemClock.elapsedRealtime() + durationMs.coerceIn(80, 2000)))
        inputWake.trySend(Unit)
        return true
    }
    /** Opens Sony's own Create UI. It does not pretend a recording has started. */
    fun openCreateMenu() = pulseButton(PbsProtocol.Button.CREATE)

    @Synchronized
    fun stop() {
        input.clear(); pulse.set(null)
        job?.cancel()
        // Pause/flush also unblocks a pending AudioTrack.WRITE_BLOCKING call.
        runCatching { audioTrack?.pause(); audioTrack?.flush() }
        client.cancelRequests()
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        // Leave the client open until the neutral/disconnect cleanup has run.
        val previous = job
        scope.launch { previous?.join(); client.close(); scope.cancel() }
    }

    private fun post(run: Long, action: () -> Unit) {
        main.post { if (run == generation.get() && !closed) action() }
    }
}
