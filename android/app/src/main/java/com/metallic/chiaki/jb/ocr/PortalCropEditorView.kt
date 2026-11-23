// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Optional edit-only overlay for selecting a game-video OCR crop. Coordinates
 * are normalized within [setVideoViewport], which must be the SurfaceView's
 * visible rectangle in THIS view's local coordinate system. The editor never
 * reads pixels or requests capture/recording permissions.
 *
 * Add this View above the game SurfaceView only while the user is editing;
 * remove/hide it to restore normal touch and controller input to the game.
 */
internal class PortalCropEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2239D5F9 }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff39D5F9.toInt() }
    private val handleRadius = 8f * resources.displayMetrics.density
    private val touchRadius = 30f * resources.displayMetrics.density

    private val videoViewport = RectF()
    private var hasViewport = false
    private var region = PortalVideoCrop.preset("SUBTITLES")
    private var onChange: ((PortalVideoCrop) -> Unit)? = null
    private var touchMode = Mode.NONE
    private var anchorX = 0f
    private var anchorY = 0f
    private var original = region

    val crop: PortalVideoCrop get() = region

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Game text crop. Drag a corner to resize, drag inside to move, or tap outside to draw a new crop. Arrow keys move the crop."
    }

    fun setCrop(crop: PortalVideoCrop) {
        region = crop
        invalidate()
    }

    fun setOnCropChangedListener(listener: ((PortalVideoCrop) -> Unit)?) {
        onChange = listener
    }

    /** The SurfaceView bounds in editor coordinates; may include letterboxed margins elsewhere. */
    fun setVideoViewport(bounds: RectF) {
        require(bounds.width() > 0f && bounds.height() > 0f)
        videoViewport.set(bounds)
        hasViewport = true
        invalidate()
    }

    private fun viewport(): RectF = if (hasViewport) videoViewport
        else RectF(0f, 0f, width.toFloat(), height.toFloat())

    private fun displayRect(vp: RectF): RectF = RectF(
        vp.left + region.left * vp.width(), vp.top + region.top * vp.height(),
        vp.left + region.right * vp.width(), vp.top + region.bottom * vp.height(),
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vp = viewport()
        if (vp.width() <= 0f || vp.height() <= 0f) return
        val rect = displayRect(vp)
        canvas.save()
        canvas.clipRect(vp)
        canvas.drawRect(vp.left, vp.top, vp.right, rect.top, shadePaint)
        canvas.drawRect(vp.left, rect.bottom, vp.right, vp.bottom, shadePaint)
        canvas.drawRect(vp.left, rect.top, rect.left, rect.bottom, shadePaint)
        canvas.drawRect(rect.right, rect.top, vp.right, rect.bottom, shadePaint)
        canvas.drawRect(rect, selectedPaint)
        canvas.drawRect(rect, outlinePaint)
        for (x in floatArrayOf(rect.left, rect.right)) {
            for (y in floatArrayOf(rect.top, rect.bottom)) {
                canvas.drawCircle(x, y, handleRadius, handlePaint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val vp = viewport()
        if (vp.width() <= 0f || vp.height() <= 0f) return false
        val x = ((event.x - vp.left) / vp.width()).coerceIn(0f, 1f)
        val y = ((event.y - vp.top) / vp.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!vp.contains(event.x, event.y)) return false
                requestFocus()
                val rect = displayRect(vp)
                touchMode = hitMode(event.x, event.y, rect)
                original = region
                anchorX = x
                anchorY = y
                if (touchMode == Mode.DRAW) update(PortalVideoCrop.fromDrag(x, y, x, y))
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (touchMode) {
                    Mode.TOP_LEFT -> update(PortalVideoCrop.fromDrag(x, y, original.right, original.bottom))
                    Mode.TOP_RIGHT -> update(PortalVideoCrop.fromDrag(original.left, y, x, original.bottom))
                    Mode.BOTTOM_LEFT -> update(PortalVideoCrop.fromDrag(x, original.top, original.right, y))
                    Mode.BOTTOM_RIGHT -> update(PortalVideoCrop.fromDrag(original.left, original.top, x, y))
                    Mode.MOVE -> {
                        val dx = (x - anchorX).coerceIn(-original.left, 1f - original.right)
                        val dy = (y - anchorY).coerceIn(-original.top, 1f - original.bottom)
                        update(PortalVideoCrop(original.left + dx, original.top + dy, original.right + dx, original.bottom + dy))
                    }
                    Mode.DRAW -> update(PortalVideoCrop.fromDrag(anchorX, anchorY, x, y))
                    Mode.NONE -> return false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchMode == Mode.NONE) return false
                touchMode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchMode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** D-pad support for Android TV and controller navigation during crop editing. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = if (event.isShiftPressed) 0.05f else 0.01f
        val (dx, dy) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -step to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> step to 0f
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -step
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to step
            else -> return super.onKeyDown(keyCode, event)
        }
        val shiftedX = dx.coerceIn(-region.left, 1f - region.right)
        val shiftedY = dy.coerceIn(-region.top, 1f - region.bottom)
        update(PortalVideoCrop(region.left + shiftedX, region.top + shiftedY,
            region.right + shiftedX, region.bottom + shiftedY))
        return true
    }

    private fun hitMode(x: Float, y: Float, rect: RectF): Mode {
        for ((px, py, mode) in listOf(
            Triple(rect.left, rect.top, Mode.TOP_LEFT),
            Triple(rect.right, rect.top, Mode.TOP_RIGHT),
            Triple(rect.left, rect.bottom, Mode.BOTTOM_LEFT),
            Triple(rect.right, rect.bottom, Mode.BOTTOM_RIGHT),
        )) {
            if (abs(x - px) <= touchRadius && abs(y - py) <= touchRadius) return mode
        }
        return if (rect.contains(x, y)) Mode.MOVE else Mode.DRAW
    }

    private fun update(next: PortalVideoCrop) {
        if (next == region) return
        region = next
        onChange?.invoke(next)
        invalidate()
    }

    private enum class Mode { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE, DRAW }
}
