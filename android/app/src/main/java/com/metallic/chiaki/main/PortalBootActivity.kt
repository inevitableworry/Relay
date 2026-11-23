// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.metallic.chiaki.R
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Portal's black PS-logo boot, then its separate PS-button unlock screen.
 * The hardware boot delay is shortened; the unlock remains an actual user action.
 * Source frames and measurements are documented in docs/research/portal-motion.md.
 */
class PortalBootActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var glow: LockGlow
    private lateinit var logo: ImageView
    private lateinit var unlockButton: ImageView
    private lateinit var prompt: TextView
    private lateinit var status: LinearLayout
    private lateinit var wifi: ImageView
    private lateinit var battery: ImageView
    private lateinit var charge: TextView
    private lateinit var time: TextView
    private lateinit var sliders:ImageButton
    private lateinit var lockMenu:PortalQuickMenu
    private val wifiSymbol=WifiSymbol()
    private val batterySymbol=BatterySymbol()
    private val handler = Handler(Looper.getMainLooper())
    private var locked = false
    private var opening = false
    private var mainLaunched=false
    private var active = false
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private var layoutWidth = 0
    private var layoutHeight = 0
    private val revealLock = Runnable { showLock(true) }
    private val updateStatus = object : Runnable {
        override fun run() {
            if(!active) return
            time.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val connected=runCatching {cm.allNetworks.any {cm.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}}.getOrNull()
            @Suppress("DEPRECATION")
            val signal=if(connected==true) runCatching {
                val rssi=(applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi
                if(rssi in -126..-1) WifiManager.calculateSignalLevel(rssi,4) else null
            }.getOrNull() else null
            wifiSymbol.connected=connected;wifiSymbol.level=signal;wifiSymbol.invalidateSelf()
            wifi.contentDescription=when(connected) {true->"Wi-Fi connected";false->"Wi-Fi disconnected";null->"Wi-Fi status unavailable"}
            val state = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent=if(level>=0 && scale>0) (level*100/scale).coerceIn(0,100) else null
            val charging=(state?.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) ?: 0)!=0
            batterySymbol.percent=percent;batterySymbol.charging=charging;batterySymbol.invalidateSelf()
            charge.text=percent?.let {"$it%"}.orEmpty()
            battery.contentDescription = percent?.let {"Battery $it percent"+(if(charging) ", connected to power" else "")}
                ?: "Battery level unavailable"
            handler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launcher tap should return to an existing task, not put another lock over it.
        if(!isTaskRoot && intent.action == Intent.ACTION_MAIN &&
            (intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
                intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))) { finish(); return }
        PortalUi.fullscreen(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PortalMenuAudio.suppressMusic(this, true)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // This full-screen tap target is not a visible focus control.
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setDefaultFocusHighlightEnabled(false)
        }
        glow = LockGlow(this).apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(glow, FrameLayout.LayoutParams(-1, -1))
        logo = ImageView(this).apply {
            setImageResource(R.drawable.portal_ps_symbol)
            setColorFilter(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(logo, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        unlockButton = ImageView(this).apply {
            setImageResource(R.drawable.portal_ps_symbol)
            setColorFilter(0xff151344.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = StateListDrawable().apply {
                fun disc(focused: Boolean) = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    if(focused) setStroke(PortalUi.dp(this@PortalBootActivity, 2), Color.WHITE)
                }
                addState(intArrayOf(android.R.attr.state_pressed), disc(true))
                addState(intArrayOf(android.R.attr.state_focused), disc(true))
                addState(intArrayOf(), disc(false))
            }
            isFocusable = true
            isClickable = true
            isSoundEffectsEnabled = false
            contentDescription = "Unlock Relay. Press the PS button, confirm button, or tap."
            visibility = View.INVISIBLE
            setOnClickListener { unlock() }
        }
        root.addView(unlockButton, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        prompt = PortalUi.text(this, "To unlock, press the PS button.").apply {
            gravity = Gravity.CENTER
            alpha = 0f
            setOnClickListener { unlock() }
            isSoundEffectsEnabled = false
        }
        root.addView(prompt, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f
        }
        wifi = ImageView(this).apply {setImageDrawable(wifiSymbol);contentDescription="Wi-Fi"}
        battery = ImageView(this).apply {setImageDrawable(batterySymbol);contentDescription="Battery"}
        charge=PortalUi.text(this,"",14f).apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        time = PortalUi.text(this, "", 14f)
        sliders=ImageButton(this).apply {
            setImageResource(R.drawable.portal_status_sliders);background=null
            contentDescription="Quick menu";isSoundEffectsEnabled=false;isFocusable=true
            setOnClickListener {if(locked && !opening) lockMenu.show(true)}
        }
        status.addView(wifi, LinearLayout.LayoutParams(1, 1))
        status.addView(charge,LinearLayout.LayoutParams(-2,-2))
        status.addView(battery, LinearLayout.LayoutParams(1, 1))
        status.addView(time, LinearLayout.LayoutParams(-2, -2))
        status.addView(sliders,LinearLayout.LayoutParams(1,1))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        lockMenu=PortalQuickMenu(this,root) {}
        lockMenu.brightness()
        fun settings(page:String) {lockMenu.onClosed {
            startActivity(Intent(this,PortalSettingsActivity::class.java).putExtra(PortalSettingsActivity.EXTRA_PAGE,page))
        }}
        lockMenu.row("Audio Devices",icon=PortalUi.Icon.AUDIO) {settings("audio")}
        lockMenu.row("Network",icon=PortalUi.Icon.NETWORK) {settings("network")}
        lockMenu.row("Settings",icon=PortalUi.Icon.SYSTEM) {settings("home")}
        lockMenu.row("Close",icon=PortalUi.Icon.CLOSE) {lockMenu.back()}
        root.setOnClickListener { unlock() }
        root.isSoundEffectsEnabled = false
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutScreen() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if(safeLeft != safe.left || safeTop != safe.top || safeRight != safe.right || safeBottom != safe.bottom) {
                safeLeft = safe.left; safeTop = safe.top; safeRight = safe.right; safeBottom = safe.bottom
                layoutWidth = 0
                layoutScreen()
            }
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        if(Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        if(savedInstanceState?.getBoolean("portal_locked") == true) showLock(false)
    }

    private fun layoutScreen() {
        if(root.width == 0 || root.height == 0 ||
            (root.width == layoutWidth && root.height == layoutHeight)) return
        layoutWidth = root.width; layoutHeight = root.height
        val width = root.width - safeLeft - safeRight
        val height = root.height - safeTop - safeBottom
        val scale = min(width / 1920f, height / 1080f)
        val density = resources.displayMetrics.density
        fun size(px: Float, minimumDp: Float = 0f) = max(px * scale, minimumDp * density).roundToInt()
        fun center(view: View, w: Int, h: Int, centerY: Float = safeTop + height * .5f) {
            val p = view.layoutParams as FrameLayout.LayoutParams
            val offsetX = (safeLeft - safeRight) / 2
            val offsetY = (centerY - root.height * .5f).roundToInt()
            if(p.width != w || p.height != h || p.leftMargin != offsetX || p.topMargin != offsetY) {
                p.width = w; p.height = h; p.leftMargin = offsetX; p.topMargin = offsetY
                view.layoutParams = p
            }
        }
        center(logo, size(240f, 72f), size(187f, 56f))
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_PX,
            max(36f * scale * resources.configuration.fontScale, 14f * resources.displayMetrics.scaledDensity))
        val promptInset = size(64f, 16f)
        prompt.minimumHeight = size(64f, 48f)
        prompt.measure(View.MeasureSpec.makeMeasureSpec((width - promptInset * 2).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        val promptHeight = prompt.measuredHeight
        val diameter = min(size(150f, 56f), (height - promptHeight - size(40f, 24f)).coerceAtLeast(size(0f, 40f)))
        val bottomGap = min(size(64f, 32f), ((height - promptHeight - diameter - size(24f, 16f)) / 3).coerceAtLeast(0))
        val discCenterY = min(safeTop + height * .5f,
            (safeTop + height - bottomGap - promptHeight - size(24f, 16f) - diameter * .5f))
            .coerceAtLeast(safeTop + diameter * .5f + size(12f, 8f))
        center(unlockButton, diameter, diameter, discCenterY)
        val pad = (diameter * .12f).roundToInt()
        unlockButton.setPadding(pad, pad, pad, pad)
        prompt.layoutParams = (prompt.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = safeLeft + promptInset; rightMargin = safeRight + promptInset
            bottomMargin = safeBottom + bottomGap; this.height = promptHeight
        }
        status.visibility = if(height < size(0f, 220f)) View.INVISIBLE else View.VISIBLE
        status.layoutParams = (status.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = safeTop + size(100f, 16f); rightMargin = safeRight + size(106f, 16f)
        }
        wifi.layoutParams = LinearLayout.LayoutParams(size(32f, 18f), size(32f, 18f))
        charge.visibility=if(width/density<360f) View.GONE else View.VISIBLE
        charge.layoutParams=LinearLayout.LayoutParams(-2,-2).apply {marginStart=size(36f,12f)}
        battery.layoutParams = LinearLayout.LayoutParams(size(42f, 22f), size(34f, 18f)).apply {
            marginStart = if(charge.visibility==View.VISIBLE) size(8f,4f) else size(36f,12f)
        }
        time.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = size(32f, 12f) }
        charge.setTextSize(TypedValue.COMPLEX_UNIT_PX,max(34f*scale,12f*resources.displayMetrics.scaledDensity))
        time.setTextSize(TypedValue.COMPLEX_UNIT_PX,
            max(36f * scale, 12f * resources.displayMetrics.scaledDensity))
        val menuSize=size(64f,48f)
        sliders.layoutParams=LinearLayout.LayoutParams(menuSize,menuSize).apply {marginStart=size(24f,8f)}
        val menuPadding=((menuSize-size(38f,22f))/2).coerceAtLeast(0)
        sliders.setPadding(menuPadding,menuPadding,menuPadding,menuPadding)
        glow.centerX = safeLeft + width * .5f
        glow.centerY = discCenterY
        glow.radius = min(width, height) * .52f
        glow.invalidate()
    }

    private fun showLock(animate: Boolean) {
        if(opening || !::root.isInitialized) return
        locked = true
        handler.removeCallbacks(revealLock)
        unlockButton.visibility = View.VISIBLE
        val motion = animate && PortalMotion.enabled(this)
        listOf(logo, glow, unlockButton, prompt, status).forEach {it.animate().cancel();it.animate().setStartDelay(0)}
        if(motion) {
            logo.animate().alpha(0f).setDuration(220L).start()
            glow.animate().alpha(1f).setDuration(380L).start()
            unlockButton.alpha = 0f; unlockButton.scaleX = .65f; unlockButton.scaleY = .65f
            unlockButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(130L).setDuration(260L).start()
            prompt.animate().alpha(1f).setStartDelay(350L).setDuration(220L).start()
            status.animate().alpha(1f).setDuration(220L).start()
        } else {
            logo.alpha = 0f; glow.alpha = 1f; unlockButton.alpha = 1f
            unlockButton.scaleX = 1f; unlockButton.scaleY = 1f; prompt.alpha = 1f; status.alpha = 1f
        }
        glow.setRunning(active && PortalMotion.enabled(this))
        PortalMenuAudio.suppressMusic(this, false)
    }

    private fun unlock() {
        if(!locked || opening || !active || lockMenu.isShowing) return
        opening = true
        PortalMenuAudio.play(this, PortalMenuAudio.Cue.CONFIRM)
        if(!PortalMotion.enabled(this)) {launchMain();return}
        // 20-fps source: full disc through ~05:31.45, small glow ~31.60, gone ~31.65.
        // The shrink/fade lasts about 200 ms; the easing curve is reconstructed.
        unlockButton.animate().cancel();prompt.animate().cancel()
        prompt.animate().setStartDelay(0).alpha(0f).setDuration(180L).start()
        unlockButton.animate().setStartDelay(0).scaleX(.04f).scaleY(.04f).alpha(0f)
            .setDuration(200L).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {launchMain()}.start()
    }

    private fun launchMain() {
        if(!opening || !active || isFinishing || isDestroyed) return
        mainLaunched=true
        // Real new installs see setup; upgrades retain their prior Relay
        // configuration and go directly to the familiar Portal home.
        val onboarding=getSharedPreferences("portal_first_setup",MODE_PRIVATE)
        if(!onboarding.contains("completed")) {
            val existing=getSharedPreferences("relay_portal",MODE_PRIVATE).all.isNotEmpty()
            onboarding.edit().putBoolean("completed",existing).apply()
        }
        val target=if(onboarding.getBoolean("completed",false))
            PortalMainActivity::class.java else PortalSetupActivity::class.java
        startActivity(Intent(this,target).putExtra(EXTRA_UNLOCK_ENTRANCE, true))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(::lockMenu.isInitialized && lockMenu.isShowing) {
            if(event.keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_MODE,KeyEvent.KEYCODE_BUTTON_C,KeyEvent.KEYCODE_BACK)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) lockMenu.back()
                return true
            }
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_A) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) currentFocus?.performClick()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if(event.keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_MODE,KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) unlock()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if(!::root.isInitialized) return
        active = true
        if(opening && !mainLaunched) opening=false
        handler.post(updateStatus)
        if(locked) showLock(false) else handler.postDelayed(revealLock, if(PortalMotion.enabled(this)) 850L else 0L)
    }
    override fun onPause() {
        active = false
        handler.removeCallbacks(updateStatus); handler.removeCallbacks(revealLock)
        if(::glow.isInitialized) {
            glow.setRunning(false)
            listOf(logo, glow, unlockButton, prompt, status).forEach { it.animate().cancel() }
        }
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("portal_locked", locked)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if(::glow.isInitialized) glow.setRunning(false)
        super.onDestroy()
    }

    private class LockGlow(context: Context) : View(context) {
        var centerX = 0f
        var centerY = 0f
        var radius = 1f
        private var phase = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f,
                intArrayOf(0xff3434de.toInt(), 0xd32b28af.toInt(), 0x65201867, Color.TRANSPARENT),
                floatArrayOf(0f, .28f, .62f, 1f), Shader.TileMode.CLAMP)
        }
        private val pulse = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 6000L; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
        }
        fun setRunning(running: Boolean) {
            if(running && !pulse.isStarted) pulse.start()
            else if(!running) pulse.cancel()
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xff060610.toInt())
            canvas.save()
            canvas.translate(centerX, centerY)
            val r = radius * (1f + .025f * sin(phase))
            canvas.scale(r, r)
            paint.alpha = (217f + 18f * sin(phase)).roundToInt()
            canvas.drawCircle(0f, 0f, 1f, paint)
            canvas.restore()
        }
        override fun onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow() }
    }

    private class WifiSymbol:Drawable() {
        var connected:Boolean?=null
        var level:Int?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;strokeCap=Paint.Cap.ROUND}
        override fun draw(canvas:Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/32f,bounds.height()/32f)
            paint.style=Paint.Style.FILL;paint.alpha=if(connected==true) 255 else 90
            canvas.drawRect(2f,24f,9f,31f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=3.2f
            for(index in 1..3) {
                val radius=8f+index*6f
                paint.alpha=if(connected==true && level!=null && index<=level!!) 255 else 65
                canvas.drawArc(5f-radius,27f-radius,5f+radius,27f+radius,270f,90f,false,paint)
            }
            if(connected==false) {paint.alpha=220;canvas.drawLine(5f,6f,27f,28f,paint)}
            canvas.restore()
        }
        override fun setAlpha(alpha:Int) {paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?) {paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    private class BatterySymbol:Drawable() {
        var percent:Int?=null
        var charging=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/48f,bounds.height()/32f)
            paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f
            canvas.drawRect(8f,6f,44f,26f,paint)
            paint.style=Paint.Style.FILL;canvas.drawRect(3f,12f,7f,20f,paint)
            percent?.let {amount ->
                val fill=amount/100f*29f
                for(index in 0..3) {val start=index*8f
                    if(fill>start) canvas.drawRect(12f+start,10f,12f+min(start+5f,fill),22f,paint)}
            }
            if(charging) {
                paint.color=0xff181729.toInt()
                canvas.drawPath(Path().apply {moveTo(27f,7f);lineTo(19f,18f);lineTo(25f,18f)
                    lineTo(23f,26f);lineTo(33f,13f);lineTo(27f,13f);close()},paint)
            }
            canvas.restore()
        }
        override fun setAlpha(alpha:Int) {paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?) {paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    companion object {
        const val EXTRA_UNLOCK_ENTRANCE = "com.portalstyle.remoteplay.UNLOCK_ENTRANCE"
    }
}

