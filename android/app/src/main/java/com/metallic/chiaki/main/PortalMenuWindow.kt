// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.metallic.chiaki.R
import java.util.function.Consumer

/**
 * Native blur uses a separate window. Unsupported devices keep an in-window sheet over a
 * bounded, composited View/SurfaceView snapshot rather than leaving sharp text behind cards.
 * https://source.android.com/docs/core/display/window-blurs
 */
internal class PortalMenuWindow(
    private val activity:AppCompatActivity,
    private val content:View,
    private val overlay:FrameLayout,
    private val requestClose:()->Unit,
    private val dismissed:()->Unit
) {
    private val manager=activity.getSystemService(WindowManager::class.java)
    // The original Portal sheet is tinted glass, not a near-opaque charcoal
    // rectangle. Keep the scene visible through the blur and retain a slight
    // violet/warm gradient, especially toward the bottom of the game image.
    private val tint=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x783e3544,0x8b342c3c.toInt(),0x9d28242f.toInt()))
    private var blurRadius=80
    private var listening=false
    private var nativeWindow=false
    private var visible=false
    private var fallback:FrameLayout?=null
    private var snapshot:Bitmap?=null
    private var snapshotVersion=0
    private var fallbackWidth=0
    private var fallbackHeight=0
    private val capture=PortalMenuSnapshot(activity,overlay)
    private val blurListener=Consumer<Boolean>(::onBlurChanged)
    private val dialog:AppCompatDialog=object:AppCompatDialog(activity,R.style.PortalQuickMenuWindow) {
        // Keep the window attached until the menu has started its intentional close.
        override fun cancel() { requestClose() }
        override fun dispatchKeyEvent(event:KeyEvent):Boolean {
            when(event.keyCode) {
                KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_MODE,KeyEvent.KEYCODE_BUTTON_C -> {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) requestClose()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) currentFocus?.performClick()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }.apply {
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        setTitle("Quick menu")
        setContentView(content)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        setOnDismissListener {onDialogDismissed()}
    }

    val isVisible:Boolean get()=visible

    fun animationMillis(base:Long):Long {
        if(!PortalMotion.enabled(activity)) return 0L
        val key=if(nativeWindow) Settings.Global.WINDOW_ANIMATION_SCALE else Settings.Global.ANIMATOR_DURATION_SCALE
        val scale=runCatching {Settings.Global.getFloat(activity.contentResolver,key,1f)}.getOrDefault(1f)
        return (base*(if(scale.isFinite()) scale.coerceIn(0f,10f) else 1f)).toLong()
    }

    private fun onBlurChanged(enabled:Boolean) {
        if(dialog.isShowing) applyBlur(enabled)
    }

    private fun onDialogDismissed() {
        if(!dialog.isShowing) {
            stopListening()
            if(nativeWindow) {visible=false;dismissed()}
        }
    }

    fun show(width:Int,height:Int,radius:Int,animated:Boolean,onShown:()->Unit) {
        blurRadius=radius.coerceIn(1,150)
        visible=true
        nativeWindow=Build.VERSION.SDK_INT>=31 && runCatching {manager.isCrossWindowBlurEnabled}.getOrDefault(false)
        if(!nativeWindow) {showFallback(width,height,animated,onShown);return}
        removeFallback()
        if(content.parent==null) dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawable(tint)
            setWindowAnimations(if(animated) R.style.PortalQuickMenuAnimation else 0)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setGravity(Gravity.TOP or Gravity.END)
            if(Build.VERSION.SDK_INT>=28) attributes=attributes.apply {
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            attributes=attributes.apply {screenBrightness=activity.window.attributes.screenBrightness;dimAmount=0f}
        }
        // The blur-capable window begins with its translucent surface to avoid an opaque flash.
        applyBlur(true)
        if(!dialog.isShowing) dialog.show()
        dialog.window?.apply {
            decorView.setPadding(0,0,0,0)
            setLayout(width,height)
            WindowCompat.setDecorFitsSystemWindows(this,false)
            WindowCompat.getInsetsController(this,decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        if(Build.VERSION.SDK_INT>=31) {
            applyBlur(runCatching{manager.isCrossWindowBlurEnabled}.getOrDefault(false))
            if(!listening) {
                listening=runCatching {
                    manager.addCrossWindowBlurEnabledListener(activity.mainExecutor,blurListener)
                    true
                }.getOrDefault(false)
            }
        }
        onShown()
    }

    fun resize(width:Int,height:Int,radius:Int) {
        blurRadius=radius.coerceIn(1,150)
        if(!nativeWindow && visible) {
            val changed=fallbackWidth!=width || fallbackHeight!=height
            fallbackWidth=width;fallbackHeight=height
            fallback?.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.END)
            if(changed) updateFallbackSnapshot()
            return
        }
        if(!dialog.isShowing) return
        dialog.window?.setLayout(width,height)
        if(Build.VERSION.SDK_INT>=31) applyBlur(runCatching{manager.isCrossWindowBlurEnabled}.getOrDefault(false))
    }

    private fun applyBlur(enabled:Boolean) {
        val applied=Build.VERSION.SDK_INT>=31 && enabled && runCatching {
            dialog.window?.setBackgroundBlurRadius(blurRadius)
            dialog.window!=null
        }.getOrDefault(false)
        if(!applied && Build.VERSION.SDK_INT>=31) runCatching{dialog.window?.setBackgroundBlurRadius(0)}
        tint.colors=if(applied) intArrayOf(
            0x783e3544,0x87342b3d.toInt(),0x9c25222e.toInt())
            // If the system blur is disabled while the dialog is showing,
            // protect readability over sharp gameplay until it can re-open
            // with the software-blur fallback.
            else intArrayOf(0xb83f3948.toInt(),0xc1322c3b.toInt(),0xce26232f.toInt())
    }

    fun setBrightness(value:Float) {
        if(nativeWindow) dialog.window?.let { window ->window.attributes=window.attributes.apply{screenBrightness=value} }
    }

    fun dismiss(animated:Boolean) {
        visible=false;snapshotVersion++;capture.cancel()
        if(!nativeWindow) {
            val host=fallback ?: return
            host.animate().cancel()
            if(animated && PortalMotion.enabled(activity)) host.animate().translationX(host.width.toFloat()).alpha(0f)
                .setDuration(300L).withEndAction {if(!visible) {removeFallback();dismissed()}}.start()
            else {removeFallback();dismissed()}
            return
        }
        if(!animated) dialog.window?.setWindowAnimations(0)
        if(dialog.isShowing) dialog.dismiss() else stopListening()
    }

    private fun showFallback(width:Int,height:Int,animated:Boolean,onShown:()->Unit) {
        fallbackWidth=width;fallbackHeight=height
        val host=fallback ?: FrameLayout(activity).apply {
            isClickable=true;isSoundEffectsEnabled=false
            (content.parent as? ViewGroup)?.removeView(content)
            addView(content,FrameLayout.LayoutParams(-1,-1))
        }.also {fallback=it;overlay.addView(it,FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.END))}
        host.animate().cancel()
        host.layoutParams=FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.END)
        host.background=FallbackBackdrop()
        if(animated) {
            host.translationX=width.toFloat();host.alpha=0f
            host.animate().translationX(0f).alpha(1f).setDuration(300L)
                .setInterpolator(android.view.animation.PathInterpolator(.20f,0f,.10f,1f)).start()
        } else {host.translationX=0f;host.alpha=1f}
        updateFallbackSnapshot()
        onShown()
    }

    private fun updateFallbackSnapshot() {
        if(nativeWindow || !visible) return
        val version=++snapshotVersion
        capture.capture(blurRadius) {bitmap ->
            if(version!=snapshotVersion || !visible || nativeWindow) {bitmap?.recycle();return@capture}
            val old=snapshot;snapshot=bitmap
            fallback?.background?.invalidateSelf()
            old?.recycle()
        }
    }

    /** Refresh the software-frosted scene gently as the game changes. Native
     * Android background blur follows the scene automatically. */
    fun refreshBackdrop() {
        if(visible && !nativeWindow && fallback!=null) updateFallbackSnapshot()
    }

    private fun removeFallback() {
        capture.cancel();snapshotVersion++
        fallback?.let {it.animate().cancel();it.removeView(content);overlay.removeView(it)}
        fallback=null
        snapshot?.recycle();snapshot=null
    }

    private inner class FallbackBackdrop:Drawable() {
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        override fun draw(canvas:Canvas) {
            val image=snapshot
            if(image!=null && !image.isRecycled) {
                val parentWidth=overlay.width.coerceAtLeast(fallbackWidth)
                val left=(image.width*(1f-fallbackWidth.toFloat()/parentWidth)).toInt().coerceIn(0,image.width-1)
                canvas.drawBitmap(image,Rect(left,0,image.width,image.height),bounds,paint)
            }
            val colors=if(image!=null) intArrayOf(
                0x70453a4b,0x81362d3f.toInt(),0x9b282430.toInt())
                else intArrayOf(
                    0xb5423a4b.toInt(),0xc2362f3d.toInt(),0xce28252f.toInt())
            paint.shader=LinearGradient(0f,0f,0f,bounds.height().toFloat().coerceAtLeast(1f),colors,null,Shader.TileMode.CLAMP)
            canvas.drawRect(bounds,paint);paint.shader=null
        }
        override fun setAlpha(alpha:Int) {paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?) {paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    private fun stopListening() {
        if(Build.VERSION.SDK_INT>=31 && listening) runCatching{manager.removeCrossWindowBlurEnabledListener(blurListener)}
        listening=false
    }
}
