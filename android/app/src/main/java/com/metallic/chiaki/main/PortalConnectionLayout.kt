// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Reveals the actual stream beneath the connecting scene. The centered circle/hold were
 * observed in Portal footage; the final expansion timing is an adaptation because the
 * source camera cuts before the screen corners are continuously visible.
 */
class PortalConnectionLayout(context: Context) : FrameLayout(context) {
    private val aperture = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        color = 0xffb3a3ff.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val expansion = PathInterpolator(.2f, 0f, .1f, 1f)
    private var animator: ValueAnimator? = null
    private var progress = 0f
    val isRevealing: Boolean get() = animator != null

    init { isClickable = true }

    fun showConnecting() {
        cancelReveal()
        progress = 0f
        alpha = 1f
        visibility = VISIBLE
        for(index in 0 until childCount) getChildAt(index).apply {
            animate().cancel()
            alpha = 1f
        }
        invalidate()
    }

    /** JB callers invoke this only after MediaCodec has produced its first real frame.
     * The existing SurfaceView remains behind this overlay: a circular hole
     * reveals live video rather than a captured still or fabricated placeholder.
     */
    fun reveal(onComplete: () -> Unit = {}) {
        if(isRevealing) return
        if(visibility != VISIBLE || width == 0 || height == 0 || !PortalMotion.enabled(context)) {
            visibility = GONE
            onComplete()
            return
        }
        PortalMenuAudio.play(context,PortalMenuAudio.Cue.REVEAL)
        for(index in 0 until childCount) getChildAt(index).let { child ->
            if(child !is PortalBackdropView) child.animate().alpha(0f).setDuration(180L).start()
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1780L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    visibility = GONE
                    onComplete()
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if(progress <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val cx = width * .5f
        val cy = height * .5f
        val initial = min(height * .3315f, width * .40f)
        // Hold the real decoder image inside the starfield opening first;
        // then expand the aperture past the screen corners in one sweep.
        val expansionProgress = ((progress - .72f) / .28f).coerceIn(0f, 1f)
        val eased = expansion.getInterpolation(expansionProgress)
        val pulse=(sin(progress*11f)*min(progress,.65f)*2.2f)
        val radius = initial + pulse + (hypot(cx, cy) - initial+2f) * eased
        val imageAlpha = (progress / .1f).coerceIn(0f, 1f)
        // An isolated layer makes only this foreground scene transparent; it never
        // captures, copies or substitutes the decoder's SurfaceView content.
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        aperture.alpha = (255 * imageAlpha).toInt()
        canvas.drawCircle(cx, cy, radius, aperture)
        rim.alpha = (180 * imageAlpha * (1f - eased)).toInt()
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
            progress * 250f - 90f, 290f, false, rim)
        // A second, very soft concentric highlight evokes the Portal's
        // illuminated circular transition without painting over live video.
        rim.alpha = (54 * imageAlpha * (1f-eased)).toInt()
        rim.strokeWidth=resources.displayMetrics.density*4f
        canvas.drawCircle(cx,cy,radius+resources.displayMetrics.density*4f,rim)
        rim.strokeWidth=resources.displayMetrics.density*1.5f
        canvas.restoreToCount(layer)
    }

    private fun cancelReveal() {
        animator?.apply {
            removeAllListeners()
            removeAllUpdateListeners()
            cancel()
        }
        animator = null
    }

    override fun onDetachedFromWindow() {
        cancelReveal()
        super.onDetachedFromWindow()
    }
}
