// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.metallic.chiaki.common.PortalPreferences
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry follows Sony's March 2026 1920x1080 quick-menu image.
 * The sheet owns a separate window so native blur includes the complete underlying scene.
 */
class PortalQuickMenu(
    private val activity: AppCompatActivity,
    private val parent: FrameLayout,
    private val visibilityChanged: (Boolean) -> Unit
) {
    val overlay = FrameLayout(activity).apply {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isSoundEffectsEnabled = false
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val panel = ScrollView(activity).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isFillViewport = true; isClickable = true; isVerticalScrollBarEnabled = false
        isSoundEffectsEnabled = false
        clipToPadding = false
    }
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; isSoundEffectsEnabled = false
        // The 1.8% focus enlargement fits inside the existing side margins.
        // Keep its rounded outline visible instead of clipping at the row's layout bounds.
        clipChildren = false; clipToPadding = false
    }
    private data class MenuRow(
        val view: LinearLayout, val icon: ImageView, val title: TextView, val subtitle: TextView?,
        val resolution: (() -> String)? = null
    )
    private val rows = mutableListOf<MenuRow>()
    private data class PlayerCell(val root: LinearLayout, val avatar: ImageView,
                                  val name: TextView, val detail: TextView)
    private val playerCells = mutableMapOf<Int, PlayerCell>()
    private var playersPanel: LinearLayout? = null
    private var playersHeading: TextView? = null
    private var referenceScale = 1f
    private var lastWidth = 0
    private var lastHeight = 0
    private var panelWidth = 0
    private lateinit var menuWindow:PortalMenuWindow
    private var closing=false
    private var previousFocus:WeakReference<View>?=null
    private var afterClose:(()->Unit)?=null
    private var afterWindowFocus:(()->Unit)?=null
    private var focusObserver:ViewTreeObserver?=null
    private val windowFocusListener=ViewTreeObserver.OnWindowFocusChangeListener {focused ->
        if(focused) runClosedAction()
    }
    private lateinit var status: LinearLayout
    private lateinit var clock: TextClock
    private lateinit var wifiView: ImageView
    private lateinit var batteryView: ImageView
    private val wifi = WifiGlyph()
    private val battery = BatteryGlyph()
    private var brightnessRow: LinearLayout? = null
    private var brightnessIcon: ImageView? = null
    private var brightnessSlider: SeekBar? = null
    private var brightnessDivider: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val finishClose=Runnable {completeClose()}
    private val focusFirstRow=Runnable {
        if(isShowing && !closing) rows.firstOrNull()?.view?.requestFocusFromTouch()
    }
    private val updateStatus = object: Runnable {
        override fun run() {
            if(!isShowing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            refreshStatus()
            // Keep the software-frosted backdrop in sync with a moving game
            // without refreshing every frame or disturbing media input.
            menuWindow.refreshBackdrop()
            handler.postDelayed(this, 2500)
        }
    }
    var isShowing = false
        private set

    init {
        parent.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        panel.addView(content, FrameLayout.LayoutParams(-1, -2))
        menuWindow=PortalMenuWindow(activity,panel,overlay,{back()}) {
            if(isShowing && !closing && !menuWindow.isVisible) closeMenu(false)
        }
        overlay.setOnClickListener { back() }
        parent.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            applyGeometry(r - l, b - t)
        }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_START -> if(isShowing) startStatusUpdates()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    cancelClosedAction()
                    handler.removeCallbacks(updateStatus)
                    handler.removeCallbacks(focusFirstRow)
                    closeMenu(false)
                }
                else -> {}
            }
        })
        reset()
    }

    private fun dp(value: Int) = PortalUi.dp(activity, value)
    private fun px(value: Int) = (value * referenceScale).roundToInt()
    private fun textSp(referencePixels: Int, minimum: Float) =
        (referencePixels * referenceScale / activity.resources.displayMetrics.density).coerceAtLeast(minimum)
    private fun gap() = max(px(28), dp(12))
    private fun transitionMillis(base:Long):Long {
        return menuWindow.animationMillis(base)
    }

    private fun applyGeometry(width: Int, height: Int) {
        if(width <= 0 || height <= 0 || (lastWidth == width && lastHeight == height)) return
        lastWidth = width; lastHeight = height
        val proposed = if(width > height) (width * (704f / 1920f)).roundToInt()
            else (width * .88f).roundToInt()
        panelWidth = proposed.coerceAtLeast(min(width, dp(280))).coerceAtMost(width)
        if(panel.layoutParams.width != panelWidth)
            panel.layoutParams = panel.layoutParams.apply { this.width = panelWidth }
        referenceScale = min(panelWidth / 704f, height / 1080f).coerceAtLeast(.4f)
        content.setPadding(max(px(28), dp(12)), max(px(22), dp(10)),
            max(px(28), dp(12)), max(px(28), dp(12)))
        applyStatusGeometry()
        rows.forEach(::applyRowGeometry)
        applyPlayersGeometry()
        applyBrightnessGeometry()
        if(::menuWindow.isInitialized) menuWindow.resize(panelWidth,height,max(px(70),dp(32)))
    }

    fun reset() {
        content.removeAllViews(); rows.clear();playerCells.clear()
        playersPanel=null;playersHeading=null
        brightnessRow = null; brightnessIcon = null; brightnessSlider = null; brightnessDivider = null
        status = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        clock = TextClock(activity).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"
            setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding=false
            gravity = Gravity.CENTER_VERTICAL
        }
        status.addView(clock, LinearLayout.LayoutParams(0, -1, 1f))
        wifiView = ImageView(activity).apply {
            setImageDrawable(wifi); contentDescription = "Wi-Fi status unavailable"
        }
        batteryView = ImageView(activity).apply {
            setImageDrawable(battery); contentDescription = "Battery level unavailable"
        }
        status.addView(wifiView, LinearLayout.LayoutParams(dp(24), dp(24)))
        status.addView(batteryView, LinearLayout.LayoutParams(dp(30), dp(22)))
        content.addView(status, LinearLayout.LayoutParams(-1, -2))
        applyStatusGeometry()
        if(isShowing) refreshStatus()
    }

    private fun applyStatusGeometry() {
        if(!::status.isInitialized) return
        clock.textSize = textSp(36, 18f)
        status.layoutParams = LinearLayout.LayoutParams(-1, max(px(70), dp(36))).apply {
            bottomMargin = max(px(24), dp(12))
        }
        wifiView.layoutParams = LinearLayout.LayoutParams(max(px(38), dp(23)), max(px(38), dp(23)))
        batteryView.layoutParams = LinearLayout.LayoutParams(max(px(48), dp(28)), max(px(32), dp(20))).apply {
            marginStart = max(px(60), dp(24))
        }
    }

    fun row(title: String, subtitle: String = "", icon: PortalUi.Icon, action: () -> Unit): View =
        createRow(title, subtitle, MenuGlyph(icon, subtitle.equals("Disconnect", true)), null,
            if(icon==PortalUi.Icon.BACK || icon==PortalUi.Icon.CLOSE) PortalMenuAudio.Cue.BACK else PortalMenuAudio.Cue.CONFIRM, action)

    /** Refresh a single displayed setting without resetting or moving menu focus. */
    fun updateRowSubtitle(title:String,subtitle:String) {
        rows.firstOrNull {it.title.text.toString()==title}?.subtitle?.text=subtitle
    }

    fun maxResolution(value: () -> String, action: () -> Unit): View =
        createRow("Max Resolution", "", ResolutionGlyph(value), value, PortalMenuAudio.Cue.CONFIRM, action)

    /** Compact four-player summary uses the same glass surface as every quick-menu tile. */
    fun players(onSelect: (Int) -> Unit) {
        val panel=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false
            clipToPadding=false
            background=tileSurface()
        }
        playersPanel=panel
        playersHeading=PortalUi.text(activity,"Players").apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            includeFontPadding=false
        }.also {panel.addView(it)}
        val grid=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false
            clipToPadding=false
        }
        for(gridRow in 0..1) {
          val columns=LinearLayout(activity).apply {
              orientation=LinearLayout.HORIZONTAL
              gravity=Gravity.CENTER_VERTICAL
              clipChildren=false
              clipToPadding=false
          }
          for(player in (gridRow*2+1)..(gridRow*2+2)) {
            val tile=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                background=playerFocusSurface()
                isClickable=true
                isFocusable=true
                setOnClickListener {
                    PortalMenuAudio.play(context,PortalMenuAudio.Cue.CONFIRM)
                    onSelect(player)
                }
                PortalUi.focus(this)
            }
            val avatar=ImageView(activity).apply {
                setImageDrawable(PlayerAvatarGlyph(null,player))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val name=PortalUi.text(activity,"P$player").apply {
                gravity=Gravity.START;includeFontPadding=false
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END
            }
            val detail=PortalUi.text(activity,"Available",muted=true).apply {
                gravity=Gravity.START;includeFontPadding=false
                setSingleLine();ellipsize=android.text.TextUtils.TruncateAt.END
            }
            val labels=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL
                gravity=Gravity.CENTER_VERTICAL
                addView(name,LinearLayout.LayoutParams(-1,-2))
                addView(detail,LinearLayout.LayoutParams(-1,-2))
            }
            tile.addView(avatar)
            tile.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            columns.addView(tile,LinearLayout.LayoutParams(0,-2,1f))
            playerCells[player]=PlayerCell(tile,avatar,name,detail)
            tile.contentDescription="Player $player, available"
          }
          grid.addView(columns,LinearLayout.LayoutParams(-1,-2))
        }
        panel.addView(grid,LinearLayout.LayoutParams(-1,-2))
        content.addView(panel,LinearLayout.LayoutParams(-1,-2))
        applyPlayersGeometry()
    }

    fun updatePlayer(player:Int,name:String,detail:String,avatar:Bitmap?) {
        val cell=playerCells[player] ?: return
        // "P2 · This device" was visibly truncated on the original Fold.
        // Keep the full identity in accessibility text and compact the glyph label.
        cell.name.text=if(name.contains("This device",ignoreCase=true))
            "P$player · You" else name
        cell.detail.text=detail
        cell.avatar.setImageDrawable(PlayerAvatarGlyph(avatar,player))
        cell.root.contentDescription="Player $player, $name, $detail"
    }

    private fun playerFocusSurface(): Drawable {
        fun shape(focused:Boolean)=GradientDrawable().apply {
            cornerRadius=max(px(14),dp(7)).toFloat()
            setColor(if(focused) 0x28ffffff else Color.TRANSPARENT)
            if(focused) setStroke(max(px(2),dp(1)),0xddeeeafb.toInt())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),shape(true))
            addState(intArrayOf(android.R.attr.state_pressed),shape(true))
            addState(intArrayOf(),shape(false))
        }
    }

    private fun applyPlayersGeometry() {
        val panel=playersPanel ?: return
        panel.background=tileSurface()
        panel.setPadding(max(px(22),dp(12)),max(px(16),dp(10)),
            max(px(22),dp(12)),max(px(20),dp(12)))
        panel.layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=gap()}
        playersHeading?.apply {
            textSize=textSp(25,15f)
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=max(px(16),dp(8))}
        }
        playerCells.values.forEach {cell ->
            cell.root.minimumHeight=max(px(90),dp(51))
            cell.root.setPadding(max(px(6),dp(3)),max(px(8),dp(5)),
                max(px(6),dp(3)),max(px(8),dp(5)))
            cell.avatar.layoutParams=LinearLayout.LayoutParams(max(px(52),dp(27)),max(px(52),dp(27))).apply {
                marginEnd=max(px(9),dp(5))
            }
            cell.name.textSize=textSp(19,12f)
            cell.detail.textSize=textSp(16,10f)
            cell.name.layoutParams=LinearLayout.LayoutParams(-1,-2)
            cell.detail.layoutParams=LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=max(px(5),dp(2))
            }
        }
    }

    private fun createRow(
        title: String, subtitle: String, drawable: Drawable, resolution: (() -> String)?,
        cue: PortalMenuAudio.Cue, action: () -> Unit
    ): View {
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            PortalUi.focus(this)
            setOnClickListener {
                PortalMotion.shine(this)
                PortalMenuAudio.play(context,cue); action()
            }
        }
        val icon = ImageView(activity).apply {
            setImageDrawable(drawable); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleView = PortalUi.text(activity, title).apply {includeFontPadding=false}
        val subtitleView = subtitle.takeIf { it.isNotEmpty() }?.let {
            PortalUi.text(activity, it, muted = true).apply {includeFontPadding=false}
        }
        labels.addView(titleView)
        subtitleView?.let { labels.addView(it) }
        view.addView(icon, LinearLayout.LayoutParams(-2, -2))
        view.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val item = MenuRow(view, icon, titleView, subtitleView, resolution)
        rows.add(item)
        content.addView(view, LinearLayout.LayoutParams(-1, -2))
        applyRowGeometry(item)
        updateRowDescription(item)
        return view
    }

    private fun tileSurface(): Drawable {
        fun shape(fill:Int,focused:Boolean=false)=MenuTileDrawable(fill,focused,
            max(px(28),dp(10)).toFloat(),max(px(2),dp(1)).toFloat())
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),shape(0x43e6dae8,true))
            addState(intArrayOf(android.R.attr.state_selected),shape(0x43e6dae8,true))
            addState(intArrayOf(android.R.attr.state_pressed),shape(0x4ce6dae8,true))
            addState(intArrayOf(),shape(0x28e6d8e7))
        }
    }

    private fun applyRowGeometry(row: MenuRow) {
        row.view.background = tileSurface()
        row.view.minimumHeight = max(px(154), dp(74))
        row.view.setPadding(max(px(44), dp(18)), max(px(22), dp(12)),
            max(px(38), dp(16)), max(px(22), dp(12)))
        row.view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap() }
        row.icon.layoutParams = LinearLayout.LayoutParams(max(px(96), dp(42)), max(px(80), dp(38))).apply {
            marginEnd = max(px(14), dp(8))
        }
        row.title.textSize = textSp(28, 16f)
        row.subtitle?.apply {
            textSize = textSp(28, 15f); setTextColor(0xbaffffff.toInt())
            setPadding(0, max(px(10), dp(4)), 0, 0)
        }
    }

    private fun updateRowDescription(row: MenuRow) {
        row.view.contentDescription = listOfNotNull(
            row.title.text.toString(), row.subtitle?.text?.toString(), row.resolution?.invoke()
        ).joinToString(", ")
        row.icon.drawable?.invalidateSelf()
    }

    fun brightness() {
        val divider = View(activity).apply { setBackgroundColor(0x22ffffff) }
        brightnessDivider = divider
        content.addView(divider, LinearLayout.LayoutParams(-1, 1))
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        brightnessRow = row
        brightnessIcon = ImageView(activity).apply {
            setImageDrawable(MenuGlyph(PortalUi.Icon.DISPLAY))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { row.addView(it) }
        brightnessSlider = SeekBar(activity).apply {
            max = 100; contentDescription = "Brightness"; isFocusable = true; splitTrack = false
            PortalUi.focus(this)
            val focusCue=onFocusChangeListener
            onFocusChangeListener=View.OnFocusChangeListener {view,focused ->
                row.isSelected=focused
                focusCue?.onFocusChange(view,focused)
            }
            progress = brightnessValue()
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) {
                    if(fromUser) {
                        val brightness = value.coerceAtLeast(3) / 100f
                        PortalPreferences(activity).brightness = brightness
                        activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                        menuWindow.setBrightness(brightness)
                        view?.contentDescription = "Brightness, ${(brightness * 100).roundToInt()} percent"
                    }
                }
                override fun onStartTrackingTouch(view: SeekBar?) {}
                override fun onStopTrackingTouch(view: SeekBar?) {}
            })
        }.also { row.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        content.addView(row, LinearLayout.LayoutParams(-1, -2))
        applyBrightnessGeometry()
    }

    private fun brightnessValue(): Int {
        val stored = PortalPreferences(activity).brightness
        return if(stored >= 0) (stored * 100).roundToInt() else runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) * 100 / 255
        }.getOrDefault(50)
    }

    private fun applyBrightnessGeometry() {
        brightnessDivider?.layoutParams = LinearLayout.LayoutParams(-1, max(px(1), 1)).apply { bottomMargin = gap() }
        val row = brightnessRow ?: return
        row.background = tileSurface(); row.minimumHeight = max(px(154), dp(74))
        row.setPadding(max(px(44), dp(18)), max(px(22), dp(12)), max(px(44), dp(18)), max(px(22), dp(12)))
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap() }
        brightnessIcon?.layoutParams = LinearLayout.LayoutParams(max(px(96), dp(42)), max(px(80), dp(38))).apply {
            marginEnd = max(px(14), dp(8))
        }
        brightnessSlider?.apply {
            layoutParams = LinearLayout.LayoutParams(0, max(px(80), dp(48)), 1f)
            val height = max(px(7), dp(3))
            fun track(color: Int) = GradientDrawable().apply {
                setColor(color); cornerRadius = height.toFloat()
            }
            progressDrawable = LayerDrawable(arrayOf(
                track(0x887c7884.toInt()), ClipDrawable(track(Color.WHITE), Gravity.LEFT, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background); setId(1, android.R.id.progress)
                for(index in 0..1) {
                    setLayerHeight(index, height); setLayerGravity(index, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                }
            }
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.WHITE)
                val size = max(px(28), dp(16)); setSize(size, size)
            }
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(0, 0, 0, 0)
        }
    }

    fun back() {
        if(!isShowing || closing) return
        PortalMenuAudio.play(activity,PortalMenuAudio.Cue.BACK)
        show(false)
    }

    /** Console commands run only after the dialog exit and activity focus restoration. */
    fun onClosed(action:()->Unit) {
        if(activity.isFinishing || activity.isDestroyed) return
        if(isShowing) {
            afterClose=action
            closeMenu(PortalMotion.enabled(activity))
        } else awaitWindowFocus(action)
    }

    private fun awaitWindowFocus(action:()->Unit) {
        afterWindowFocus=action
        if(activity.hasWindowFocus()) runClosedAction()
        else if(focusObserver==null) activity.window.decorView.viewTreeObserver.takeIf{it.isAlive}?.let {
            focusObserver=it
            it.addOnWindowFocusChangeListener(windowFocusListener)
        }
    }

    private fun runClosedAction() {
        focusObserver?.takeIf{it.isAlive}?.removeOnWindowFocusChangeListener(windowFocusListener)
        focusObserver=null
        val action=afterWindowFocus;afterWindowFocus=null
        if(!isShowing && !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action?.invoke()
    }

    private fun cancelClosedAction() {
        afterClose=null;afterWindowFocus=null
        focusObserver?.takeIf{it.isAlive}?.removeOnWindowFocusChangeListener(windowFocusListener)
        focusObserver=null
    }

    fun show(show: Boolean) {
        if(!show) {closeMenu(PortalMotion.enabled(activity));return}
        if((isShowing && !closing) || activity.isFinishing || activity.isDestroyed) return
        cancelClosedAction()
        val wasShowing=isShowing
        handler.removeCallbacks(finishClose);handler.removeCallbacks(focusFirstRow)
        closing=false;isShowing=true
        if(!wasShowing) previousFocus=activity.currentFocus?.let {WeakReference(it)}
        rows.forEach(::updateRowDescription)
        brightnessSlider?.progress=brightnessValue()
        panel.scrollTo(0,0)
        applyGeometry(parent.width.takeIf{it>0} ?: activity.resources.displayMetrics.widthPixels,
            parent.height.takeIf{it>0} ?: activity.resources.displayMetrics.heightPixels)
        overlay.visibility=View.VISIBLE
        overlay.requestFocus()
        if(!wasShowing) visibilityChanged(true)
        val animated=PortalMotion.enabled(activity)
        PortalMenuAudio.play(activity,PortalMenuAudio.Cue.MENU_OPEN)
        menuWindow.show(panelWidth.coerceAtLeast(1),lastHeight.coerceAtLeast(1),max(px(70),dp(32)),animated) {
            handler.postDelayed(focusFirstRow,if(animated) transitionMillis(300L) else 0L)
        }
        startStatusUpdates()
    }

    private fun closeMenu(animated:Boolean) {
        if(!isShowing) return
        if(closing && animated) return
        if(!closing) PortalMenuAudio.play(activity,PortalMenuAudio.Cue.MENU_CLOSE)
        closing=true
        handler.removeCallbacks(updateStatus);handler.removeCallbacks(focusFirstRow)
        handler.removeCallbacks(finishClose)
        // The transparent activity blocker keeps input suspended during the exit animation.
        overlay.requestFocus()
        menuWindow.dismiss(animated)
        if(animated && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            handler.postDelayed(finishClose,transitionMillis(300L))
        else completeClose()
    }

    private fun completeClose() {
        if(!isShowing) return
        closing=false;isShowing=false
        overlay.visibility=View.GONE
        visibilityChanged(false)
        if(activity.currentFocus==null || activity.currentFocus===overlay)
            previousFocus?.get()?.takeIf{it.isAttachedToWindow && it.isShown}?.requestFocus()
        previousFocus=null
        val action=afterClose;afterClose=null
        action?.let(::awaitWindowFocus)
    }

    private fun startStatusUpdates() {
        handler.removeCallbacks(updateStatus)
        refreshStatus()
        handler.postDelayed(updateStatus, 2500)
    }

    @Suppress("DEPRECATION")
    private fun refreshStatus() {
        val network = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected = runCatching {
            network.allNetworks.any { network.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrNull()
        val level = if(connected == true) runCatching {
            val manager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = manager.connectionInfo.rssi
            if(rssi in -126..-1) WifiManager.calculateSignalLevel(rssi, 4) else null
        }.getOrNull() else null
        wifi.connected = connected; wifi.level = level; wifi.invalidateSelf()
        wifiView.contentDescription = when {
            connected == false -> "Wi-Fi not connected"
            connected == null -> "Wi-Fi status unavailable"
            level == null -> "Wi-Fi connected, signal strength unavailable"
            else -> "Wi-Fi connected, signal strength $level of 3"
        }
        val intent = runCatching { activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val current = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if(current >= 0 && scale > 0) (current * 100f / scale).roundToInt().coerceIn(0, 100) else null
        val charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        battery.percent = percent; battery.charging = charging; battery.invalidateSelf()
        batteryView.contentDescription = if(percent == null) "Battery level unavailable"
            else "Battery $percent percent" + if(charging) ", connected to power" else ""
    }

    fun refreshTitle(view: View, title: String) {
        rows.firstOrNull { it.view === view }?.let {
            it.title.text = title; updateRowDescription(it)
        } ?: run { view.contentDescription = title }
    }

    private class MenuTileDrawable(
        private val fill:Int,private val focused:Boolean,private val radius:Float,private val stroke:Float
    ):Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var drawableAlpha=255
        override fun draw(canvas:Canvas) {
            val rect=RectF(bounds)
            paint.style=Paint.Style.FILL;paint.alpha=drawableAlpha
            // The Portal card is warmer at its lower-right edge and preserves
            // the blurred scene under it instead of becoming a flat gray slab.
            val end=(0x00c5b0c5) or ((Color.alpha(fill)*.70f).roundToInt() shl 24)
            paint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,fill,end,Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect,radius,radius,paint)
            if(focused) {
                rect.inset(stroke/2,stroke/2)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke
                paint.shader=LinearGradient(rect.left,rect.bottom,rect.right,rect.top,
                    intArrayOf(0xff656171.toInt(),0xffb7b3bf.toInt(),0xfffaf9fc.toInt()),
                    floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect,radius,radius,paint)
            }
            paint.shader=null
        }
        override fun setAlpha(alpha:Int) {drawableAlpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?) {paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }

    internal abstract class Glyph: Drawable() {
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        protected fun scaled(canvas: Canvas, width: Float, height: Float, draw: () -> Unit) {
            canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / width, bounds.height() / height)
            draw(); canvas.restore()
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class MenuGlyph(private val icon: PortalUi.Icon, private val disconnect: Boolean = false): Glyph() {
        override fun draw(canvas: Canvas) = scaled(canvas, 96f, 80f) {
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            if(icon == PortalUi.Icon.REMOTE || icon == PortalUi.Icon.CONTROL_CENTER || icon == PortalUi.Icon.HOME) {
                canvas.drawCircle(8f, 40f, 3f, paint); canvas.drawCircle(20f, 40f, 3f, paint)
            }
            when(icon) {
                PortalUi.Icon.HOME -> canvas.drawPath(Path().apply {
                    moveTo(32f, 38f); lineTo(53f, 18f); lineTo(75f, 38f); lineTo(75f, 59f)
                    lineTo(61f, 59f); lineTo(61f, 45f); quadTo(54f, 37f, 47f, 45f)
                    lineTo(47f, 59f); lineTo(32f, 59f); close()
                }, paint)
                PortalUi.Icon.CONTROL_CENTER -> {
                    canvas.drawRoundRect(32f, 21f, 51f, 48f, 2f, 2f, paint)
                    canvas.drawRect(57f, 31f, 69f, 48f, paint); canvas.drawRect(75f, 31f, 80f, 48f, paint)
                    canvas.drawRect(32f, 58f, 80f, 61f, paint)
                }
                PortalUi.Icon.REMOTE -> {
                    canvas.drawPath(Path().apply {
                        moveTo(34f,10f);quadTo(38f,13f,42f,12f);lineTo(40f,63f)
                        quadTo(35f,62f,31f,65f);close()
                    }, paint)
                    canvas.drawPath(Path().apply {
                        moveTo(46f,12f);quadTo(50f,13f,54f,10f);lineTo(51f,65f)
                        quadTo(47f,62f,44f,63f);close()
                    },paint)
                    canvas.drawPath(Path().apply {
                        moveTo(57f, 54f); quadTo(50f, 54f, 50f, 67f); quadTo(52f, 74f, 57f, 65f)
                        lineTo(68f, 65f); quadTo(74f, 73f, 75f, 66f); quadTo(74f, 54f, 68f, 54f); close()
                    }, paint)
                    if(disconnect) {
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                        canvas.drawLine(57f, 8f, 74f, 25f, paint); canvas.drawLine(74f, 8f, 57f, 25f, paint)
                    }
                }
                PortalUi.Icon.DISPLAY -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f;paint.strokeCap=Paint.Cap.SQUARE
                    canvas.drawCircle(40f, 40f, 16f, paint)
                    for(index in 0..7) {
                        canvas.save(); canvas.rotate(index * 45f, 40f, 40f)
                        canvas.drawLine(40f, 12f, 40f, 16f, paint); canvas.restore()
                    }
                }
                PortalUi.Icon.AUDIO -> {
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=5f
                    canvas.drawArc(17f,14f,63f,61f,180f,180f,false,paint)
                    paint.style=Paint.Style.FILL
                    canvas.drawRoundRect(14f,36f,24f,62f,4f,4f,paint)
                    canvas.drawRoundRect(56f,36f,66f,62f,4f,4f,paint)
                }
                PortalUi.Icon.NETWORK -> {
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=4f
                    canvas.drawCircle(40f,40f,24f,paint)
                    canvas.drawOval(29f,16f,51f,64f,paint)
                    canvas.drawLine(16f,40f,64f,40f,paint)
                }
                PortalUi.Icon.SYSTEM -> {
                    val gear=Path().apply {fillType=Path.FillType.EVEN_ODD}
                    for(tooth in 0 until 8) {
                        val center=tooth*2*PI/8
                        listOf(-.35 to 21f,-.22 to 27f,.22 to 27f,.35 to 21f).forEachIndexed {index,pair ->
                            val angle=center+pair.first*2*PI/8
                            val x=40f+(cos(angle)*pair.second).toFloat()
                            val y=40f+(sin(angle)*pair.second).toFloat()
                            if(tooth==0 && index==0) gear.moveTo(x,y) else gear.lineTo(x,y)
                        }
                    }
                    gear.close();gear.addCircle(40f,40f,10f,Path.Direction.CW)
                    paint.style=Paint.Style.FILL;canvas.drawPath(gear,paint)
                }
                PortalUi.Icon.CLOSE -> {
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=4f;paint.strokeCap=Paint.Cap.ROUND
                    canvas.drawLine(22f,22f,58f,58f,paint);canvas.drawLine(58f,22f,22f,58f,paint)
                }
                else -> {
                    val glyph = PortalUi.Glyph(icon)
                    glyph.setBounds(16, 14, 72, 66); glyph.draw(canvas)
                }
            }
        }
    }
    private class PlayerAvatarGlyph(private val image:Bitmap?,private val player:Int):Glyph() {
        override fun draw(canvas:Canvas) = scaled(canvas,80f,80f) {
            paint.shader=null
            paint.style=Paint.Style.FILL
            paint.color=0xff716a7f.toInt()
            canvas.drawCircle(40f,40f,32f,paint)
            if(image!=null && !image.isRecycled) {
                val radius=32f
                val scale=max(64f/image.width,64f/image.height)
                val matrix=Matrix().apply {
                    setScale(scale,scale)
                    postTranslate(40f-image.width*scale/2f,40f-image.height*scale/2f)
                }
                paint.shader=BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(matrix)
                }
                canvas.drawCircle(40f,40f,radius,paint)
                paint.shader=null
            } else {
                paint.color=Color.WHITE
                paint.textAlign=Paint.Align.CENTER
                paint.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
                paint.textSize=33f
                canvas.drawText(player.toString(),40f,52f,paint)
            }
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f
            paint.color=0xaaffffff.toInt()
            canvas.drawCircle(40f,40f,31f,paint)
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        }
    }

    private class ResolutionGlyph(private val value: () -> String): Glyph() {
        override fun draw(canvas: Canvas) = scaled(canvas, 96f, 80f) {
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawRoundRect(10f, 18f, 72f, 53f, 2f, 2f, paint)
            canvas.drawRect(22f, 56f, 27f, 60f, paint); canvas.drawRect(55f, 56f, 60f, 60f, paint)
            paint.color = 0xff242329.toInt(); paint.textSize = 23f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD); paint.textAlign = Paint.Align.CENTER
            val text = value().removeSuffix("p").let { if(it == "2160") "4K" else it }
            canvas.drawText(text, 41f, 44f, paint)
        }
    }

    internal class WifiGlyph: Glyph() {
        var connected: Boolean? = null
        var level: Int? = null
        override fun draw(canvas: Canvas) = scaled(canvas, 32f, 32f) {
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            paint.alpha = if(connected == true) 255 else 100
            canvas.drawRect(2f, 24f, 9f, 31f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.2f
            for(index in 1..3) {
                val radius = 8f + index * 6f
                paint.alpha = if(connected == true && level != null && index <= level!!) 255 else 65
                canvas.drawArc(5f - radius, 27f - radius, 5f + radius, 27f + radius, 270f, 90f, false, paint)
            }
            if(connected == false) { paint.alpha = 220; canvas.drawLine(5f, 6f, 27f, 28f, paint) }
            paint.alpha = 255
        }
    }

    internal class BatteryGlyph: Glyph() {
        var percent: Int? = null
        var charging = false
        override fun draw(canvas: Canvas) = scaled(canvas, 48f, 32f) {
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
            canvas.drawRect(8f, 6f, 44f, 26f, paint)
            paint.style = Paint.Style.FILL; canvas.drawRect(3f, 12f, 7f, 20f, paint)
            val amount = percent
            if(amount != null) {
                val filled = (amount / 100f) * 29f
                for(index in 0..3) {
                    val start = index * 8f
                    if(filled > start) canvas.drawRect(12f + start, 10f, 12f + min(start + 5f, filled), 22f, paint)
                }
            } else {
                paint.textSize = 19f; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("?", 26f, 23f, paint)
            }
            if(charging) {
                paint.color = 0xff312e35.toInt()
                canvas.drawPath(Path().apply {
                    moveTo(27f, 7f); lineTo(19f, 18f); lineTo(25f, 18f)
                    lineTo(23f, 26f); lineTo(33f, 13f); lineTo(27f, 13f); close()
                }, paint)
            }
        }
    }
}
