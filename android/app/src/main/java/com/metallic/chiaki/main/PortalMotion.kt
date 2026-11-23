// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.min

/** Timings observed from actual footage; interpolation is an approximation, not a Sony asset. */
object PortalMotion {
    const val CONTENT_DURATION_MS = 300L
    const val PANEL_DURATION_MS = 300L
    private val easeOut = PathInterpolator(.20f, 0f, .10f, 1f)
    private val panelVersions = WeakHashMap<View, Int>()
    private val shines=WeakHashMap<View,ValueAnimator>()

    var reduceMotion = false

    fun enabled(context: Context): Boolean = !reduceMotion &&
        !com.metallic.chiaki.common.PortalPreferences(context).reduceMotion && runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    /** Call after replacing a page's content. Direction is -1 for back and +1 for forward. */
    fun crossfade(view: View, direction: Int = 0) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (!enabled(view.context)) {
            view.alpha = 1f
            view.translationX = 0f
            return
        }
        view.alpha = 0f
        view.translationX = direction.coerceIn(-1, 1) * 18f * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationX(0f).setDuration(CONTENT_DURATION_MS)
            .setInterpolator(easeOut).start()
    }

    /** Opens/closes the right sheet and ignores stale posted openings during rapid toggles. */
    fun showPanel(overlay: View, panel: View, show: Boolean, onComplete: () -> Unit = {}) {
        val version = (panelVersions[panel] ?: 0) + 1
        panelVersions[panel] = version
        overlay.animate().cancel()
        panel.animate().cancel()
        if (!enabled(overlay.context)) {
            overlay.visibility = if (show) View.VISIBLE else View.GONE
            overlay.alpha = 1f
            panel.alpha = 1f
            panel.translationX = 0f
            onComplete()
            return
        }
        if (show) {
            val opening = overlay.visibility != View.VISIBLE
            overlay.visibility = View.VISIBLE
            if (opening) overlay.alpha = 0f
            panel.post {
                if (panelVersions[panel] != version) return@post
                if (opening) {
                    panel.translationX = panel.width.toFloat()
                    panel.alpha = 0f
                }
                overlay.animate().alpha(1f).setDuration(180L).setInterpolator(easeOut).start()
                panel.animate().translationX(0f).alpha(1f).setDuration(PANEL_DURATION_MS)
                    .setInterpolator(easeOut).withEndAction {
                        if (panelVersions[panel] == version) onComplete()
                    }.start()
            }
        } else {
            // Mystic 6.0 footage 09:56.61–09:56.91: content fades, then the plate retracts.
            // Source-PTS frames sampled every 50 ms bound the overall close to about 300 ms.
            overlay.animate().alpha(0f).setDuration(PANEL_DURATION_MS).setInterpolator(easeOut).start()
            panel.animate().translationX(panel.width.toFloat()).alpha(0f).setDuration(PANEL_DURATION_MS)
                .setInterpolator(easeOut).withEndAction {
                    if (panelVersions[panel] == version) {
                        overlay.visibility = View.GONE
                        overlay.alpha = 1f
                        panel.alpha = 1f
                        panel.translationX = 0f
                        onComplete()
                    }
                }.start()
        }
    }

    /** The caller supplies the white focus-outline drawable; enlargement stays restrained. */
    fun focus(view: View, hasFocus: Boolean) {
        view.animate().cancel()
        val scale = if (hasFocus) 1.018f else 1f
        if (!enabled(view.context)) {
            view.scaleX = scale
            view.scaleY = scale
        } else {
            view.animate().scaleX(scale).scaleY(scale).setDuration(140L).setInterpolator(easeOut).start()
        }
        if(hasFocus) shine(view)
        else shines.remove(view)?.cancel()
    }

    /** Short reflective glint on focused and confirmed Portal cards.
     * Does not alter the existing card/tile background or block touch input.
     * Disabled by Reduce Motion and Android animation scale preferences. */
    fun shine(view:View) {
        if(!enabled(view.context) || view.width==0 || view.height==0) return
        shines.remove(view)?.cancel()
        val w=view.width
        val h=view.height
        val pill=view is TextView
        val radius=if(pill) h*.5f else min(h*.17f,view.resources.displayMetrics.density*16f)
        val glint=object:Drawable() {
            private val brush=Paint(Paint.ANTI_ALIAS_FLAG)
            var position=-.4f
            override fun draw(canvas:Canvas) {
                val stripe=(w*.19f).coerceAtLeast(view.resources.displayMetrics.density*28f)
                val center=-stripe+(w+stripe*2)*position
                val isLight=pill && view is TextView && view.currentTextColor==Color.BLACK
                val alpha=if(isLight) 36 else 58
                brush.shader=LinearGradient(center-stripe,0f,center+stripe,h*.25f,
                    intArrayOf(Color.TRANSPARENT,Color.argb(alpha,255,255,255),Color.TRANSPARENT),
                    floatArrayOf(0f,.50f,1f),Shader.TileMode.CLAMP)
                val saved=canvas.save()
                canvas.clipPath(Path().apply {
                    addRoundRect(RectF(bounds),radius,radius,Path.Direction.CW)
                })
                canvas.drawRect(bounds,brush)
                canvas.restoreToCount(saved)
                brush.shader=null
            }
            override fun setAlpha(alpha:Int) {}
            override fun setColorFilter(filter:android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
        glint.setBounds(0,0,w,h)
        view.overlay.add(glint)
        val animation=ValueAnimator.ofFloat(0f,1f).apply {
            duration=690L
            interpolator=easeOut
            addUpdateListener {
                glint.position=it.animatedValue as Float
                view.invalidate()
            }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator:Animator) {
                    view.overlay.remove(glint)
                    if(shines[view]===animator) shines.remove(view)
                }
            })
        }
        shines[view]=animation
        animation.start()
    }
}
