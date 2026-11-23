// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Experimental game-frame-only restoration. OCR supplies a tight line rectangle;
 * only pixels resembling contrasting text strokes are replaced with pixels from
 * a nearby, matching part of the same decoded video frame. Everything else in
 * the returned tile is transparent, leaving live scenery visible underneath.
 *
 * This deliberately uses no blur, opaque rectangle, screenshots or cloud API.
 * Complex, highly textured backgrounds may still produce imperfect repairs.
 */
internal object PortalNaturalTextInpaint {
    data class Patch(val bounds: Rect, val image: Bitmap, val darkLettering: Boolean)

    /** Immutable pixels from a decoded game/video frame, safe to retain after Bitmap.recycle(). */
    class Frame private constructor(val width: Int, val height: Int, val pixels: IntArray) {
        companion object {
            fun capture(bitmap: Bitmap): Frame {
                require(!bitmap.isRecycled)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return Frame(bitmap.width, bitmap.height, pixels)
            }
        }
    }

    private fun luma(color: Int): Int =
        (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    fun restore(gameFrame: Bitmap, recognizedBounds: Rect): Patch? =
        if (gameFrame.isRecycled) null else restore(Frame.capture(gameFrame), recognizedBounds)

    /** Prefer a matching, text-free historical frame; otherwise sample neighboring
     * scenery. The resulting alpha channel covers only detected glyph strokes. */
    fun restore(gameFrame: Frame, recognizedBounds: Rect, history: List<Frame> = emptyList()): Patch? {
        if (gameFrame.width < 32 || gameFrame.height < 32) return null
        val width = gameFrame.width
        val height = gameFrame.height
        val core = Rect(recognizedBounds)
        if (!core.intersect(0, 0, width, height) || core.width() < 3 || core.height() < 3)
            return null
        val tile = Rect(max(0, core.left - 4), max(0, core.top - 4),
            min(width, core.right + 4), min(height, core.bottom + 4))
        val pixels = gameFrame.pixels
        fun pixel(x: Int, y: Int) = pixels[y * width + x]

        // Background reference comes only from the narrow ring *outside* OCR.
        // A ring minimizes the effect of a bright/dark scene elsewhere in frame.
        var ringLuma = 0L
        var ringCount = 0
        for (y in tile.top until tile.bottom step 2) {
            for (x in tile.left until tile.right step 2) {
                if (x >= core.left && x < core.right && y >= core.top && y < core.bottom) continue
                ringLuma += luma(pixel(x, y))
                ringCount++
            }
        }
        if (ringCount == 0) return null
        val backgroundLuma = (ringLuma / ringCount).toInt()
        var darkCount = 0
        var brightCount = 0
        for (y in core.top until core.bottom) {
            for (x in core.left until core.right) {
                val value = luma(pixel(x, y))
                if (value < backgroundLuma - 30) darkCount++
                if (value > backgroundLuma + 30) brightCount++
            }
        }
        val darkInk = darkCount >= brightCount
        val inkCount = max(darkCount, brightCount)
        val coreArea = core.width() * core.height()
        if (inkCount < max(3, coreArea / 120)) return null
        // If the entire line rectangle differs from its border, use a more
        // conservative threshold to avoid destroying illustrated backgrounds.
        val threshold = if (inkCount > coreArea * 2 / 5) 52 else 26
        val tileW = tile.width()
        val tileH = tile.height()
        val mask = BooleanArray(tileW * tileH)
        for (y in core.top until core.bottom) {
            for (x in core.left until core.right) {
                val value = luma(pixel(x, y))
                if (if (darkInk) value < backgroundLuma - threshold
                    else value > backgroundLuma + threshold) {
                    mask[(y - tile.top) * tileW + x - tile.left] = true
                }
            }
        }
        // Cover antialiased edges and one-pixel shadows around letter strokes.
        val expanded = mask.copyOf()
        for (y in core.top until core.bottom) {
            for (x in core.left until core.right) {
                if (!mask[(y - tile.top) * tileW + x - tile.left]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val sx = x + dx
                    val sy = y + dy
                    if (sx >= core.left - 1 && sx <= core.right &&
                        sy >= core.top - 1 && sy <= core.bottom &&
                        sx >= tile.left && sx < tile.right && sy >= tile.top && sy < tile.bottom)
                        expanded[(sy - tile.top) * tileW + sx - tile.left] = true
                }
            }
        }

        // Choose a real nearby source patch by matching the surrounding ring.
        // The chosen offset never overlaps the recognized text rectangle.
        val horizontal = core.width() + 8
        val vertical = core.height() + 8
        val shifts = arrayOf(0 to -vertical, 0 to vertical,
            -horizontal to 0, horizontal to 0,
            -horizontal to -vertical, horizontal to -vertical,
            -horizontal to vertical, horizontal to vertical)
        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        for ((dx, dy) in shifts) {
            if (tile.left + dx < 0 || tile.right + dx > width ||
                tile.top + dy < 0 || tile.bottom + dy > height) continue
            var error = 0L
            var samples = 0
            for (y in tile.top until tile.bottom step 3) {
                for (x in tile.left until tile.right step 3) {
                    if (x >= core.left && x < core.right && y >= core.top && y < core.bottom) continue
                    error += distance(pixel(x, y), pixel(x + dx, y + dy))
                    samples++
                }
            }
            if (samples == 0) continue
            val score = error * 100 / samples + (abs(dx) + abs(dy)).toLong()
            if (score < bestScore) {
                bestScore = score
                bestDx = dx
                bestDy = dy
            }
        }

        // Historical candidates are aligned with the original frame by the
        // unchanged border around OCR. Allow tiny motion in either direction.
        // Reject an unchanged subtitle by requiring the ink pixels themselves
        // to differ, while the scenery outside the letters remains similar.
        var temporal: Frame? = null
        var temporalDx = 0
        var temporalDy = 0
        var temporalScore = Long.MAX_VALUE
        val inkSamples = ArrayList<Pair<Int, Int>>(min(128, coreArea))
        for (y in core.top until core.bottom step 2)
            for (x in core.left until core.right step 2)
                if (expanded[(y - tile.top) * tileW + x - tile.left] && inkSamples.size < 128)
                    inkSamples.add(x to y)
        for (old in history.asReversed()) {
            if (old.width != width || old.height != height) continue
            for (dy in -4..4 step 2) for (dx in -4..4 step 2) {
                if (tile.left + dx < 0 || tile.right + dx > width ||
                    tile.top + dy < 0 || tile.bottom + dy > height) continue
                var error = 0L
                var count = 0
                for (y in tile.top until tile.bottom step 3)
                    for (x in tile.left until tile.right step 3) {
                        if (x in core.left until core.right && y in core.top until core.bottom) continue
                        error += distance(pixel(x,y), old.pixels[(y+dy)*width+x+dx])
                        count++
                    }
                if (count < 4) continue
                val meanError = error / count
                if (meanError > 53) continue // Different scene or camera cut.
                if (inkSamples.isEmpty()) continue
                var inkDifference = 0L
                for ((x,y) in inkSamples)
                    inkDifference += distance(pixel(x,y),old.pixels[(y+dy)*width+x+dx])
                if (inkDifference / inkSamples.size < 29) continue // Old subtitle still present.
                val score = meanError * 100 + (abs(dx)+abs(dy))*3
                if (score < temporalScore) {
                    temporal = old;temporalDx=dx;temporalDy=dy;temporalScore=score
                }
            }
        }

        val restored = IntArray(tileW * tileH)
        for (y in tile.top until tile.bottom) {
            for (x in tile.left until tile.right) {
                val index = (y - tile.top) * tileW + x - tile.left
                if (!expanded[index]) continue
                val color = if (temporal != null) {
                    temporal.pixels[(y+temporalDy)*width+x+temporalDx]
                } else if (bestScore != Long.MAX_VALUE && bestScore < 7200) {
                    pixel(x + bestDx, y + bestDy)
                } else {
                    // At screen edges, extend pixels from the nearest safe side.
                    val sourceX = if (x - core.left < core.right - x)
                        max(0, core.left - 4) else min(width - 1, core.right + 3)
                    val sourceY = if (y - core.top < core.bottom - y)
                        max(0, core.top - 4) else min(height - 1, core.bottom + 3)
                    val fromX = pixel(sourceX, y.coerceIn(0, height - 1))
                    val fromY = pixel(x.coerceIn(0, width - 1), sourceY)
                    if (abs(luma(fromX) - backgroundLuma) < abs(luma(fromY) - backgroundLuma))
                        fromX else fromY
                }
                restored[index] = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
            }
        }
        val image = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        image.setPixels(restored, 0, tileW, 0, 0, tileW, tileH)
        return Patch(tile, image, backgroundLuma >= 145)
    }
}
