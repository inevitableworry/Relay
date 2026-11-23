// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityPortalHomeBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.jb.JbStreamActivity
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rebuilds Sony's 2025 Portal home navigation around libchiaki's real console list. */
class PortalMainActivity : AppCompatActivity() {
    companion object { const val EXTRA_START_TAB="portal_start_tab" }
    private enum class Tab { REMOTE, CLOUD, SEARCH }
    private lateinit var binding: ActivityPortalHomeBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var quickMenu: PortalQuickMenu
    private var thorSecondScreen: PortalThorSecondScreen? = null
    private val portal by lazy { PortalPreferences(this) }
    private val wifiSymbol = PortalQuickMenu.WifiGlyph()
    private val batterySymbol = PortalQuickMenu.BatteryGlyph()
    private var tab = Tab.REMOTE
    private var hosts: List<DisplayHost> = emptyList()
    private var selectedHostKey: String? = null
    private var lastHatMove = 0L
    private var lastLayoutWidth = 0
    private var lastLayoutHeight = 0
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private var keyboardBottom = 0
    private var unlockEntranceRunning=false
    private var entranceAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private var entranceBlocker:View?=null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val refreshClock = object : Runnable {
        override fun run() {
            if(!isFinishing && !isDestroyed) {
                binding.timeText.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                refreshStatus()
                clockHandler.postDelayed(this, 15000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        binding = ActivityPortalHomeBinding.inflate(layoutInflater)
        binding.wifiIndicator.setImageDrawable(wifiSymbol)
        binding.batteryIndicator.setImageDrawable(batterySymbol)
        // Keeping keyboard focus on the scene must not tint the entire background.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) binding.root.setDefaultFocusHighlightEnabled(false)
        setContentView(binding.root)
        binding.root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            applyHomeLayout(right - left, bottom - top)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if(safeLeft != safe.left || safeTop != safe.top || safeRight != safe.right ||
                safeBottom != safe.bottom || keyboardBottom != ime) {
                safeLeft = safe.left
                safeTop = safe.top
                safeRight = safe.right
                safeBottom = safe.bottom
                keyboardBottom = ime
                lastLayoutWidth = 0
                applyHomeLayout(binding.root.width, binding.root.height)
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        selectedHostKey = savedInstanceState?.getString("portal_selected_host")
        viewModel = ViewModelProvider(this, viewModelFactory {
            MainViewModel(getDatabase(this), Preferences(this))
        })[MainViewModel::class.java]
        quickMenu = PortalQuickMenu(this,binding.root) { shown ->
            binding.portalBackdrop.setDefocused(tab != Tab.REMOTE)
            if(shown) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(binding.portalSearch.windowToken,0)
                binding.portalSearch.clearFocus()
            } else if(!unlockEntranceRunning) binding.quickMenuButton.requestFocus()
        }

        binding.remoteTab.setOnClickListener { selectTab(Tab.REMOTE) }
        binding.cloudTab.setOnClickListener { selectTab(Tab.CLOUD) }
        binding.searchTab.setOnClickListener { selectTab(Tab.SEARCH) }
        binding.portalConsoleTitle.setOnClickListener { chooseConsole() }
        binding.portalConnect.setOnClickListener { connectSelected() }
        binding.quickMenuButton.setOnClickListener { toggleQuickMenu(true) }
        PortalUi.focus(binding.portalConnect)
        PortalUi.focus(binding.quickMenuButton)
        PortalUi.focus(binding.portalConsoleTitle)
        binding.portalSearch.addTextChangedListener { updateSearchResults() }
        // Use the same Relay keyboard across all Android device flavors rather
        // than a different OS keyboard for each phone, TV or handheld.
        binding.portalSearch.showSoftInputOnFocus = false
        binding.portalSearch.setOnClickListener {
            PortalOnScreenKeyboard.show(this, binding.portalSearch.text.toString()) { value ->
                if(binding.portalSearch.text.toString() != value) binding.portalSearch.setText(value)
            }
        }
        viewModel.displayHosts.observe(this) { refreshed ->
            hosts = refreshed.distinctBy { it.host }
                .sortedWith(compareByDescending<DisplayHost> { it.isRegistered }
                    .thenBy { it.name ?: it.host })
            if(selectedHostKey == null || hosts.none { it.host == selectedHostKey })
                selectedHostKey = hosts.firstOrNull()?.host
            updateRemoteScreen()
            updateSearchResults()
        }
        onBackPressedDispatcher.addCallback(this) {
            when {
                quickMenu.isShowing -> quickMenu.back()
                tab != Tab.REMOTE -> selectTab(Tab.REMOTE)
                else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        }
        selectTab(if(intent.getStringExtra(EXTRA_START_TAB)=="cloud") Tab.CLOUD else Tab.REMOTE)
        if(BuildConfig.FLAVOR == "aynThor" || BuildConfig.FLAVOR == "dualScreen") {
            thorSecondScreen = PortalThorSecondScreen(
                this,
                onRemote = { binding.remoteTab.performClick() },
                onCloud = { binding.cloudTab.performClick() },
                onSearch = { binding.searchTab.performClick() },
                onQuickMenu = { binding.quickMenuButton.performClick() },
                onSettings = { startActivity(Intent(this, PortalSettingsActivity::class.java)) },
                onConnect = { binding.portalConnect.performClick() },
                onSearchText = { value -> binding.portalSearch.setText(value) }
            )
        }
        startUnlockEntrance()
    }

    private fun startUnlockEntrance() {
        val enter=intent.getBooleanExtra(PortalBootActivity.EXTRA_UNLOCK_ENTRANCE,false)
        intent.removeExtra(PortalBootActivity.EXTRA_UNLOCK_ENTRANCE)
        if(!enter || !PortalMotion.enabled(this)) return
        unlockEntranceRunning=true
        entranceAccessibility=binding.portalLayout.importantForAccessibility
        binding.portalLayout.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        entranceBlocker=View(this).apply {
            isClickable=true;isFocusableInTouchMode=true;isSoundEffectsEnabled=false
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setDefaultFocusHighlightEnabled(false)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also {
            binding.root.addView(it,FrameLayout.LayoutParams(-1,-1));it.requestFocus()
        }
        // Current 2026 footage at 20 fps: arc 05:32.667, navigation 05:35.667,
        // body 05:35.867. The fresh backdrop's first arc starts at about 100 ms.
        // These stage offsets are observed timing; the Android fade curve is an approximation.
        val header=listOf(binding.portalTabSwitcher,binding.statusBar)
        val body=listOf(binding.remoteContent,binding.cloudContent,binding.searchContent,binding.portalConnect,binding.bottomHint)
        (header+body).forEach {it.alpha=0f}
        header.forEach {it.animate().alpha(1f).setStartDelay(3100L).setDuration(180L).start()}
        body.forEach {view ->
            val animation=view.animate().alpha(1f).setStartDelay(3300L).setDuration(200L)
            if(view===binding.portalConnect) animation.withEndAction {finishUnlockEntrance(true)}
            animation.start()
        }
    }

    private fun finishUnlockEntrance(requestFocus:Boolean) {
        if(!unlockEntranceRunning) return
        unlockEntranceRunning=false
        listOf(binding.portalTabSwitcher,binding.statusBar,binding.remoteContent,binding.cloudContent,
            binding.searchContent,binding.portalConnect,binding.bottomHint).forEach {
            it.animate().cancel();it.animate().setStartDelay(0);it.alpha=1f
        }
        entranceBlocker?.let {binding.root.removeView(it)};entranceBlocker=null
        binding.portalLayout.importantForAccessibility=entranceAccessibility
        if(requestFocus && !quickMenu.isShowing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            binding.portalConsoleTitle.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,null)
    }

    /** Sony's 1920x1080 composition, with native minimum targets and reflow for small windows. */
    private fun applyHomeLayout(width: Int, height: Int) {
        if(width <= 0 || height <= 0 ||
            (width == lastLayoutWidth && height == lastLayoutHeight)) return
        lastLayoutWidth = width
        lastLayoutHeight = height
        val metrics = resources.displayMetrics
        val availableWidth = width - safeLeft - safeRight
        val availableHeight = height - safeTop - safeBottom
        val designScale = min(availableWidth / 1920f, availableHeight / 1080f)
        fun size(referencePx: Float, minimumDp: Int = 0) =
            max(referencePx * designScale, minimumDp * metrics.density).roundToInt()
        fun text(view: TextView, referencePx: Float, minimumSp: Float) {
            val fontScale = resources.configuration.fontScale
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                max(referencePx * designScale * fontScale, minimumSp * metrics.scaledDensity))
        }
        fun dimensions(view: View, w: Int, h: Int) {
            view.layoutParams = view.layoutParams.apply { this.width = w; this.height = h }
        }
        fun leadingMargin(view: View, value: Int) {
            view.layoutParams = (view.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = value
            }
        }
        val horizontal = size(84f, 16)
        val top = size(80f, 16)
        val bottom = size(40f, 16)
        val contentWidth = (availableWidth - horizontal * 2).coerceAtLeast(1)
        binding.portalLayout.setPadding(safeLeft + horizontal, safeTop + top,
            safeRight + horizontal, max(safeBottom, keyboardBottom) + bottom)

        val navHeight = size(97f, 48)
        val navWidth = (navHeight * 340f / 97f).roundToInt().coerceAtMost(contentWidth)
        dimensions(binding.portalTabSwitcher, navWidth, navHeight)
        // Sony's capsule begins 7 reference pixels left of the aligned title.
        leadingMargin(binding.portalTabSwitcher, -size(7f))
        dimensions(binding.statusBar, ViewGroup.LayoutParams.WRAP_CONTENT, navHeight)
        dimensions(binding.wifiIndicator, size(32f, 18), size(32f, 18))
        dimensions(binding.batteryIndicator, size(42f, 22), size(34f, 18))
        leadingMargin(binding.batteryIndicator, size(36f, 10))
        leadingMargin(binding.timeText, size(32f, 10))
        leadingMargin(binding.quickMenuButton, size(24f, 8))
        text(binding.timeText, 36f, 12f)
        val menuSize = size(80f, 48)
        dimensions(binding.quickMenuButton, menuSize, menuSize)
        val menuPadding = ((menuSize - size(38f, 22)) / 2).coerceAtLeast(0)
        binding.quickMenuButton.setPadding(menuPadding, menuPadding, menuPadding, menuPadding)
        val clockWidth = binding.timeText.paint.measureText(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())).roundToInt()
        val statusWidth = size(32f, 18) + size(42f, 22) + size(36f, 10) +
            size(32f, 10) + clockWidth + size(24f, 8) + menuSize
        val stackedHeader = navWidth + statusWidth + size(24f, 12) > contentWidth
        val headerHeight = if(stackedHeader) navHeight * 2 + 8.dp else navHeight
        val compact = availableWidth / metrics.density < 600f ||
            availableHeight / metrics.density < 420f
        val titleOffset = max(headerHeight + size(54f, 24),
            (availableHeight * .234f).roundToInt() - top)
        ConstraintSet().apply {
            clone(binding.portalLayout)
            clear(R.id.statusBar, ConstraintSet.TOP)
            connect(R.id.statusBar, ConstraintSet.TOP,
                if(stackedHeader) R.id.portalTabSwitcher else ConstraintSet.PARENT_ID,
                if(stackedHeader) ConstraintSet.BOTTOM else ConstraintSet.TOP)
            setMargin(R.id.statusBar, ConstraintSet.TOP, if(stackedHeader) 8.dp else 0)
            setGuidelineBegin(R.id.titleGuide, titleOffset)
            applyTo(binding.portalLayout)
        }
        text(binding.portalConsoleTitle, 46f, 22f)
        binding.portalConsoleTitle.maxLines = if(compact) 2 else 1
        text(binding.portalConsoleMeta, 24f, 12f)
        text(binding.cloudHeading, 34f, 20f)
        text(binding.cloudMessage, 27f, 15f)
        text(binding.searchHeading, 30f, 18f)
        text(binding.portalSearch, 28f, 16f)
        val searchWidth = min(contentWidth, size(620f, 280))
        dimensions(binding.portalSearch, searchWidth, size(70f, 50))
        binding.searchContent.layoutParams =
            (binding.searchContent.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = if(stackedHeader) navHeight + 20.dp else size(56f, 20)
            }
        dimensions(binding.searchResultsScroll, ViewGroup.LayoutParams.MATCH_PARENT,
            min(size(310f, 208), max(120.dp, availableHeight - headerHeight - top - bottom - 100.dp)))

        text(binding.portalConnect, 30f, 16f)
        text(binding.bottomHint, 28f, 12f)
        updateControllerHint()
        val buttonHeight = size(82f, 48)
        val buttonWidth = min(contentWidth, size(316f, 160))
        binding.portalConnect.insetTop = 0
        binding.portalConnect.insetBottom = 0
        binding.portalConnect.cornerRadius = buttonHeight / 2
        dimensions(binding.portalConnect, buttonWidth, buttonHeight)
        val hintWidth = Layout.getDesiredWidth(binding.bottomHint.text, binding.bottomHint.paint)
        val stackedFooter = hintWidth + buttonWidth + 20.dp > contentWidth
        binding.bottomHint.layoutParams =
            (binding.bottomHint.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = if(stackedFooter) buttonHeight + 12.dp else
                    ((buttonHeight - binding.bottomHint.textSize * 1.3f) / 2).roundToInt().coerceAtLeast(0)
            }
    }

    private fun updateControllerHint() {
        val hint = SpannableString("L1 / R1 Switch Tabs")
        val height = (binding.bottomHint.textSize * 25f / 28f).roundToInt().coerceAtLeast(1)
        val width = (height * 30f / 25f).roundToInt().coerceAtLeast(1)
        listOf(R.drawable.portal_hint_l1 to 0, R.drawable.portal_hint_r1 to 5).forEach { (resource, start) ->
            ContextCompat.getDrawable(this, resource)?.let { drawable ->
                drawable.setBounds(0, 0, width, height)
                hint.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BASELINE), start, start + 2,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        binding.bottomHint.text = hint
        // Keep the actual button names available to screen readers and copy/text tooling.
        binding.bottomHint.contentDescription = "L1 or R1: Switch Tabs"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("portal_selected_host", selectedHostKey)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        if(portal.mode==PortalPreferences.Mode.TRADITIONAL) viewModel.discoveryManager.resume()
    }
    override fun onResume() {
        super.onResume()
        thorSecondScreen?.start()
        PortalUi.fullscreen(this)
        binding.portalBackdrop.reduceMotion=portal.reduceMotion
        if(portal.mode==PortalPreferences.Mode.JB) viewModel.discoveryManager.pause()
        else {
            viewModel.discoveryManager.active=Preferences(this).discoveryEnabled
            viewModel.discoveryManager.resume()
        }
        updateRemoteScreen()
        buildQuickMenu()
        clockHandler.removeCallbacks(refreshClock)
        clockHandler.post(refreshClock)
    }
    override fun onPause() {
        thorSecondScreen?.stop()
        finishUnlockEntrance(false)
        clockHandler.removeCallbacks(refreshClock)
        super.onPause()
    }
    override fun onStop() { viewModel.discoveryManager.pause(); super.onStop() }
    override fun onDestroy() {finishUnlockEntrance(false);super.onDestroy()}

    @Suppress("DEPRECATION")
    private fun refreshStatus() {
        val network = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected = runCatching {
            network.allNetworks.any { network.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrNull()
        val signal = if(connected == true) runCatching {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val rssi = manager.connectionInfo.rssi
            if(rssi in -126..-1) WifiManager.calculateSignalLevel(rssi, 4) else null
        }.getOrNull() else null
        wifiSymbol.connected = connected
        wifiSymbol.level = signal
        wifiSymbol.invalidateSelf()
        binding.wifiIndicator.contentDescription = when {
            connected == false -> "Wi-Fi not connected"
            connected == null -> "Wi-Fi status unavailable"
            signal == null -> "Wi-Fi connected, signal strength unavailable"
            else -> "Wi-Fi connected, signal strength $signal of 3"
        }
        val battery = runCatching { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if(level >= 0 && scale > 0) (level * 100f / scale).roundToInt().coerceIn(0, 100) else null
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        batterySymbol.percent = percent
        batterySymbol.charging = charging
        batterySymbol.invalidateSelf()
        binding.batteryIndicator.contentDescription = if(percent == null) "Battery level unavailable"
            else "Battery $percent percent" + if(charging) ", connected to power" else ""
    }

    private fun selectTab(next: Tab) {
        val previous=tab
        tab = next
        if(previous != next) PortalMenuAudio.play(this, PortalMenuAudio.Cue.FOCUS)
        binding.portalTabArtwork.setImageResource(when(next) {
            Tab.REMOTE -> R.drawable.portal_nav_remote
            Tab.CLOUD -> R.drawable.portal_nav_cloud
            Tab.SEARCH -> R.drawable.portal_nav_search
        })
        binding.remoteContent.visibility = if(next == Tab.REMOTE) View.VISIBLE else View.GONE
        binding.cloudContent.visibility = if(next == Tab.CLOUD) View.VISIBLE else View.GONE
        binding.searchContent.visibility = if(next == Tab.SEARCH) View.VISIBLE else View.GONE
        binding.portalConnect.visibility = if(next == Tab.REMOTE) View.VISIBLE else View.GONE
        binding.portalTabSwitcher.contentDescription = next.name + " tab selected"
        binding.remoteTab.isSelected=next==Tab.REMOTE
        binding.cloudTab.isSelected=next==Tab.CLOUD
        binding.searchTab.isSelected=next==Tab.SEARCH
        binding.portalBackdrop.setDefocused(next!=Tab.REMOTE)
        if(previous!=next && !portal.reduceMotion) {
            PortalMotion.crossfade(when(next) {
                Tab.REMOTE->binding.remoteContent
                Tab.CLOUD->binding.cloudContent
                Tab.SEARCH->binding.searchContent
            },if(next.ordinal>previous.ordinal) 1 else -1)
            PortalMotion.crossfade(binding.portalTabArtwork)
        }
        if(next == Tab.SEARCH) updateSearchResults()
        else {
            val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.hideSoftInputFromWindow(binding.portalSearch.windowToken, 0)
            binding.portalSearch.clearFocus()
        }
    }

    private fun selectedHost(): DisplayHost? =
        hosts.firstOrNull { it.host == selectedHostKey } ?: hosts.firstOrNull()

    private fun updateRemoteScreen() {
        if(portal.mode==PortalPreferences.Mode.JB) {
            binding.portalConsoleTitle.text="Connect to your PS5"
            binding.portalConsoleMeta.text=if(PortalRemoteAccess(this).enabled) "Remote Play JB · Relay Tunnel"
                else "Remote Play JB  ·  "+portal.jbHost
            binding.portalConsoleMeta.visibility=View.VISIBLE
            binding.portalConnect.text="Connect"
            binding.portalConsoleTitle.contentDescription="Remote Play JB settings"
            return
        }
        binding.portalConsoleMeta.visibility=View.GONE
        val host = selectedHost()
        val nickname = host?.name ?: if(host?.isPS5 == false) "PS4" else "PS5"
        binding.portalConsoleTitle.text =
            if(host == null) "Connect to your PS5" else "Connect to " + '"' + nickname + '"'
        binding.portalConnect.text = if(host == null) "Add Console" else "Connect"
        binding.portalConsoleTitle.contentDescription =
            binding.portalConsoleTitle.text.toString() +
                if(hosts.size > 1) ", select a different console" else ""
    }

    private fun chooseConsole() {
        PortalMenuAudio.play(this, PortalMenuAudio.Cue.CONFIRM)
        if(portal.mode==PortalPreferences.Mode.JB) {openSettings("remote");return}
        if(hosts.isEmpty()) { showRegistration(); return }
        val labels = hosts.map {
            (it.name ?: it.host) + " • " + if(it.isRegistered) "Paired" else "Pair required"
        }.toTypedArray()
        val selected = hosts.indexOfFirst { it.host == selectedHostKey }.coerceAtLeast(0)
        PortalChoiceDialog.choice(this,"Choose a Console",
            labels.toList()+"Add Console",selected) {index->
            if(index==hosts.size) addManualConsole()
            else {selectedHostKey=hosts[index].host;updateRemoteScreen()}
        }
    }

    private fun cycleHost(direction: Int) {
        if(portal.mode==PortalPreferences.Mode.JB) return
        if(hosts.size < 2) return
        val index = hosts.indexOfFirst { it.host == selectedHostKey }.coerceAtLeast(0)
        selectedHostKey = hosts[(index + direction + hosts.size) % hosts.size].host
        updateRemoteScreen()
    }

    private fun updateSearchResults() {
        if(!::binding.isInitialized) return
        binding.portalSearchResults.removeAllViews()
        val text = binding.portalSearch.text?.toString()?.trim().orEmpty()
        val matches = hosts.filter {
            it.name.orEmpty().contains(text, true) || it.host.contains(text, true) ||
                (it as? DiscoveredDisplayHost)?.discoveredHost?.runningAppName.orEmpty()
                    .contains(text, true)
        }
        if(matches.isEmpty()) {
            binding.portalSearchResults.addView(TextView(this).apply {
                this.text = "No discovered consoles match your search."
                setTextColor(0xffc3bfd5.toInt())
                textSize = 15f
                setPadding(14.dp, 16.dp, 14.dp, 16.dp)
            })
        }
        for(host in matches) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(
                    this@PortalMainActivity, R.drawable.portal_search_field)
                setPadding(10.dp, 12.dp, 10.dp, 12.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedHostKey = host.host
                    updateRemoteScreen()
                    selectTab(Tab.REMOTE)
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(if(host.isPS5) R.drawable.ic_console_ps5 else R.drawable.ic_console)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = if(host.isPS5) "PS5" else "PS4"
            }, LinearLayout.LayoutParams(102.dp, 124.dp))
            row.addView(TextView(this).apply {
                this.text = host.name ?: host.host
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xffffffff.toInt())
            }, LinearLayout.LayoutParams(-1, 36.dp))
            binding.portalSearchResults.addView(row, LinearLayout.LayoutParams(148.dp, 186.dp).apply {
                marginEnd = 12.dp
            })
        }
    }
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun openSettings(page:String="home") {
        toggleQuickMenu(false)
        startActivity(Intent(this,PortalSettingsActivity::class.java).putExtra(PortalSettingsActivity.EXTRA_PAGE,page))
    }
    private fun buildQuickMenu() {
        quickMenu.reset()
        quickMenu.row(if(portal.mode==PortalPreferences.Mode.JB) "Remote Play JB" else "Remote Play",
            "Connect",PortalUi.Icon.REMOTE) {toggleQuickMenu(false);connectSelected()}
        if(portal.mode==PortalPreferences.Mode.JB) {
            quickMenu.players { player ->
                PortalChoiceDialog.message(this,"Player $player",
                    "Player numbers are assigned automatically in connection order. Connect to Remote Play JB, then select a PS5 account or Guest on the console. Nobody is preassigned.",
                    listOf("Connect" to {toggleQuickMenu(false);connectSelected()},"Close" to {}))
            }
            for(player in 1..4) {
                quickMenu.updatePlayer(player,
                    "Player $player","Waiting for connection",null)
            }
        }
        quickMenu.brightness()
        quickMenu.row("Audio Devices",icon=PortalUi.Icon.AUDIO) {openSettings("audio")}
        quickMenu.row("Network",icon=PortalUi.Icon.NETWORK) {openSettings("network")}
        quickMenu.row("Network Status",icon=PortalUi.Icon.NETWORK) {
            quickMenu.onClosed {PortalNetworkStatusDialog(this).show()}
        }
        quickMenu.row("Settings",icon=PortalUi.Icon.SYSTEM) {openSettings()}
        quickMenu.row("Close",icon=PortalUi.Icon.CLOSE) {toggleQuickMenu(false)}
    }
    private fun toggleQuickMenu(show: Boolean) {
        quickMenu.show(show)
    }

    private fun connectSelected() {
        PortalMenuAudio.play(this, PortalMenuAudio.Cue.CONFIRM)
        if(portal.mode==PortalPreferences.Mode.JB) {
            startActivity(Intent(this,JbStreamActivity::class.java))
            return
        }
        val host = selectedHost()
        if(host == null) { showRegistration(); return }
        val registered = host.registeredHost
        if(registered == null) {
            startActivity(Intent(this, RegistActivity::class.java).apply {
                putExtra(RegistActivity.EXTRA_HOST, host.host)
                putExtra(RegistActivity.EXTRA_BROADCAST, false)
                if(host is ManualDisplayHost)
                    putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
            })
            return
        }
        val connect = {
            val info = ConnectInfo(host.isPS5, host.host,
                registered.rpRegistKey, registered.rpKey, Preferences(this).videoProfile)
            startActivity(Intent(this, StreamActivity::class.java).apply {
                putExtra(StreamActivity.EXTRA_CONNECT_INFO, info)
            })
        }
        if(host is DiscoveredDisplayHost &&
            host.discoveredHost.state == DiscoveryHost.State.STANDBY) {
            PortalChoiceDialog.message(this,"Console in Rest Mode",
                getString(R.string.alert_message_standby_wakeup),
                listOf(getString(R.string.action_wakeup) to {
                    viewModel.discoveryManager.sendWakeup(
                        host.host, registered.rpRegistKey, registered.target.isPS5)
                },getString(R.string.action_connect_immediately) to {connect()},
                    getString(R.string.action_connect_cancel_connect) to {}))
        } else connect()
    }

    private fun showRegistration() {
        if(portal.mode==PortalPreferences.Mode.JB) openSettings("remote")
        else startActivity(Intent(this, RegistActivity::class.java))
    }
    private fun addManualConsole() =
        startActivity(Intent(this, EditManualConsoleActivity::class.java))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(unlockEntranceRunning && (KeyEvent.isGamepadButton(event.keyCode) || event.keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_SPACE,KeyEvent.KEYCODE_TAB))) return true
        if(event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)
            return super.dispatchKeyEvent(event)
        if(quickMenu.isShowing) {
            if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B) { quickMenu.back(); return true }
            if(event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {currentFocus?.performClick();return true}
            return super.dispatchKeyEvent(event)
        }
        when(event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> { selectTab(Tab.entries[(tab.ordinal + 2) % 3]); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { selectTab(Tab.entries[(tab.ordinal + 1) % 3]); return true }
            KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_MODE -> { toggleQuickMenu(true); return true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { chooseConsole(); return true }
            KeyEvent.KEYCODE_BUTTON_X -> { showRegistration(); return true }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                startActivity(Intent(this, PortalSettingsActivity::class.java)); return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if(tab != Tab.REMOTE) selectTab(Tab.REMOTE)
                else onBackPressedDispatcher.onBackPressed()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if(tab == Tab.REMOTE) { cycleHost(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if(tab == Tab.REMOTE) { cycleHost(1); return true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                val focused=currentFocus
                if(focused!=null && focused.isClickable) focused.performClick()
                else if(tab == Tab.REMOTE) connectSelected()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if(unlockEntranceRunning && event.source and InputDevice.SOURCE_CLASS_JOYSTICK!=0) return true
        if(event.actionMasked == MotionEvent.ACTION_MOVE &&
            event.source and InputDevice.SOURCE_CLASS_JOYSTICK != 0 &&
            tab == Tab.REMOTE && !quickMenu.isShowing) {
            val hat = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val x = if(abs(hat) > 0.55f) hat else event.getAxisValue(MotionEvent.AXIS_X)
            val now = SystemClock.uptimeMillis()
            if(abs(x) > 0.75f && now - lastHatMove > 360L) {
                cycleHost(if(x < 0) -1 else 1)
                lastHatMove = now
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}
