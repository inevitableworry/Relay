// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in normalized *decoded game video* coordinates, never window coordinates. */
internal data class PortalVideoCrop(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f })
        require(left < right && top < bottom)
    }

    /** Quantize to integer pixels without losing the last pixel at the right/bottom edge. */
    fun pixels(frameWidth: Int, frameHeight: Int): PortalPixelCrop {
        require(frameWidth > 0 && frameHeight > 0)
        val x = floor(left * frameWidth).toInt().coerceIn(0, frameWidth - 1)
        val y = floor(top * frameHeight).toInt().coerceIn(0, frameHeight - 1)
        val endX = ceil(right * frameWidth).toInt().coerceIn(x + 1, frameWidth)
        val endY = ceil(bottom * frameHeight).toInt().coerceIn(y + 1, frameHeight)
        return PortalPixelCrop(x, y, endX - x, endY - y)
    }

    /** Fixed-point storage is deterministic across locale changes and display resolutions. */
    fun encode(): String = listOf(left, top, right, bottom)
        .joinToString(",") { (it * STORAGE_SCALE).roundToInt().toString() }

    companion object {
        private const val STORAGE_SCALE = 10_000f
        const val MIN_SPAN = 0.025f
        val FULL = PortalVideoCrop(0f, 0f, 1f, 1f)

        /** Safe to use with untrusted / obsolete preference values; null means use a preset. */
        fun decode(value: String?): PortalVideoCrop? {
            val parts = value?.split(',') ?: return null
            if (parts.size != 4) return null
            val units = parts.map { it.trim().toIntOrNull() ?: return null }
            if (units.any { it !in 0..STORAGE_SCALE.toInt() }) return null
            return runCatching {
                PortalVideoCrop(
                    units[0] / STORAGE_SCALE, units[1] / STORAGE_SCALE,
                    units[2] / STORAGE_SCALE, units[3] / STORAGE_SCALE,
                )
            }.getOrNull()
        }

        /** Convert preset names to rectangles, keeping one canonical video-only geometry model. */
        fun preset(name: String): PortalVideoCrop = when (name) {
            "SUBTITLES" -> PortalVideoCrop(0f, 0.6f, 1f, 1f)
            "CENTER" -> PortalVideoCrop(0f, 0.2f, 1f, 0.8f)
            "TOP" -> PortalVideoCrop(0f, 0f, 1f, 0.4f)
            else -> FULL
        }

        /** Drag/resizing may cross edges; clip to 0..1 and enforce a usable minimum span. */
        fun fromDrag(
            x0: Float, y0: Float, x1: Float, y1: Float,
            minSpan: Float = MIN_SPAN,
        ): PortalVideoCrop {
            require(listOf(x0, y0, x1, y1, minSpan).all { it.isFinite() })
            require(minSpan > 0f && minSpan <= 1f)
            val (l, r) = clampSpan(min(x0, x1), max(x0, x1), minSpan)
            val (t, b) = clampSpan(min(y0, y1), max(y0, y1), minSpan)
            return PortalVideoCrop(l, t, r, b)
        }

        fun fromPixels(crop: PortalPixelCrop, width: Int, height: Int): PortalVideoCrop {
            require(width > 0 && height > 0)
            require(crop.x >= 0 && crop.y >= 0 && crop.width > 0 && crop.height > 0)
            require(crop.x.toLong() + crop.width <= width && crop.y.toLong() + crop.height <= height)
            return PortalVideoCrop(
                crop.x.toFloat() / width, crop.y.toFloat() / height,
                (crop.x + crop.width).toFloat() / width,
                (crop.y + crop.height).toFloat() / height,
            )
        }

        private fun clampSpan(start: Float, end: Float, span: Float): Pair<Float, Float> {
            val l = start.coerceIn(0f, 1f)
            val r = end.coerceIn(0f, 1f)
            if (r - l >= span) return l to r
            val center = ((l + r) / 2f).coerceIn(span / 2f, 1f - span / 2f)
            return (center - span / 2f) to (center + span / 2f)
        }
    }
}

/** The only coordinates that can be passed to Bitmap.createBitmap for game OCR input. */
internal data class PortalPixelCrop(val x: Int, val y: Int, val width: Int, val height: Int)
