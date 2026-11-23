// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.metallic.chiaki.common.PortalPreferences

object JbQualityEnvironment {
    fun create(context: Context, config: PbsConfig, preferences: PortalPreferences): JbQualityPolicy {
        val hardware = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                (if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated
                else !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android."))
        }
        val heights = config.resolutions.filter { height ->
            val width = height * 16 / 9
            hardware.any { codec ->
                runCatching {
                    codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                        .areSizeAndRateSupported(width, height, config.fps.toDouble())
                }.getOrDefault(false)
            }
        }.toSet()
        val display = context.resources.displayMetrics
        val videoHeight = minOf(maxOf(display.widthPixels, display.heightPixels) * 9 / 16,
            minOf(display.widthPixels, display.heightPixels))
        return JbQualityPolicy(config.resolutions, heights, videoHeight, JbQualityPolicy.Settings(
            preferences.jbAutoQuality, preferences.jbHeight, preferences.jbAutoMaxHeight,
            preferences.jbMaxBandwidthMbps, preferences.jbVideoBitrateMbps, preferences.jbMinQp),
            config.rateControl, if (preferences.jbAudio) config.audio?.bitrateBps ?: 0 else 0)
    }
}
