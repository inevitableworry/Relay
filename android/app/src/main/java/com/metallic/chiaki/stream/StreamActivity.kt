// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Matrix
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.EditText
import android.widget.SeekBar
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.main.PortalSettingsActivity
import com.metallic.chiaki.main.PortalWordLearningActivity
import com.metallic.chiaki.main.PortalQuickMenu
import com.metallic.chiaki.main.PortalBackdropView
import com.metallic.chiaki.main.PortalConnectionLayout
import com.metallic.chiaki.main.PortalUi
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.jb.PortalGameTranslation
import com.metallic.chiaki.jb.PortalTranslationHotkey
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat
        private lateinit var portalMenu: PortalQuickMenu
        private lateinit var portalMenuButton: ImageButton
        private lateinit var connectingSurface: PortalConnectionLayout
        private lateinit var connectingBackdrop: PortalBackdropView
        private var gameplayInputReady=false
        private var gameTranslation: PortalGameTranslation?=null
        private var audioCaptions: com.metallic.chiaki.main.PortalAudioCaptionsController?=null
        private val translationHotkey=PortalTranslationHotkey()

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)
            supportFragmentManager.fragments.filterIsInstance<TouchControlsFragment>().forEach(::bindTouchControls)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
        gameTranslation=PortalGameTranslation(this,binding.surfaceView,binding.root) {
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                ::viewModel.isInitialized &&
                viewModel.session.state.value==StreamStateConnected &&
                ::portalMenu.isInitialized && !portalMenu.isShowing
        }
        audioCaptions=com.metallic.chiaki.main.PortalAudioCaptionsController(this,binding.root)
		binding.streamQuickMenuButton.setOnClickListener { showPortalStreamMenu(true) }
            binding.streamQuickMenuButton.isSoundEffectsEnabled=false
            installPortalSurfaces()

		WindowCompat.setDecorFitsSystemWindows(window, false)
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		//viewModel.session.attachToTextureView(textureView)
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
                    if(viewModel.input.rumble(it.left.toInt(), it.right.toInt())) return@Observer
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

        private val controlsJobs = mutableListOf<Job>()
        private val touchStates = mutableMapOf<TouchControlsFragment, ControllerState>()
        private val boundTouchControls = mutableSetOf<TouchControlsFragment>()

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
            if(fragment is TouchControlsFragment && ::viewModel.isInitialized) bindTouchControls(fragment)
	}

        private fun bindTouchControls(fragment:TouchControlsFragment) {
            if(!boundTouchControls.add(fragment)) return
                controlsJobs += fragment.controllerState
                    .onEach {
                        touchStates[fragment] = it
                        val active = touchStates.filterKeys { control ->
                            if(control is TouchpadOnlyFragment) viewModel.touchpadOnlyEnabled.value == true
                            else viewModel.onScreenControlsEnabled.value == true
                        }
                        viewModel.input.touchControllerState = active.values.fold(ControllerState()) { a,b -> a or b }
                    }.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
        }

	override fun onResume()
	{
		super.onResume()
            if(!::viewModel.isInitialized || !::binding.isInitialized) return
            PortalUi.fullscreen(this)
            val settings = Preferences(this)
            viewModel.setOnScreenControlsEnabled(settings.onScreenControlsEnabled)
            viewModel.setTouchpadOnlyEnabled(settings.touchpadOnlyEnabled)
            binding.displayModeToggle.check(when(PortalPreferences(this).fitMode) {
                "ZOOM" -> R.id.display_mode_zoom_button
                "STRETCH" -> R.id.display_mode_stretch_button
                else -> R.id.display_mode_normal_button
            })
		hideSystemUI()
		viewModel.session.resume()
            if(viewModel.session.state.value==StreamStateConnected) startAutoGameTranslation()
            updateInputSuspension()
	}

	override fun onPause()
	{
		super.onPause()
            audioCaptions?.stop()
            translationHotkey.reset()
            gameTranslation?.stop()
            if(::viewModel.isInitialized) {
                setGameplayInputReady(false)
                touchStates.keys.forEach { it.releaseControls() }
                viewModel.session.pause()
            }
	}

	override fun onDestroy()
	{
		super.onDestroy()
            audioCaptions?.dispose()
            gameTranslation?.dispose();gameTranslation=null
            controlsJobs.forEach { it.cancel() }
            uiVisibilityHandler.removeCallbacksAndMessages(null)
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

        private fun installPortalSurfaces() {
            val dp = { value:Int -> PortalUi.dp(this,value) }
            connectingSurface = PortalConnectionLayout(this)
            connectingBackdrop = PortalBackdropView(this).apply {
                reduceMotion=PortalPreferences(context).reduceMotion
                setConnecting(true)
            }
            connectingSurface.addView(connectingBackdrop,FrameLayout.LayoutParams(-1,-1))
            connectingSurface.addView(PortalUi.text(this,"Connecting to your console",24f).apply {
                gravity=Gravity.CENTER
            },FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply {topMargin=dp(75)})
            connectingSurface.addView(PortalUi.primary(this,"Cancel") {finish()},
                FrameLayout.LayoutParams(dp(170),-2,Gravity.BOTTOM or Gravity.END).apply {
                    bottomMargin=dp(28);marginEnd=dp(32)
                })
            binding.root.addView(connectingSurface,FrameLayout.LayoutParams(-1,-1))
            portalMenuButton=ImageButton(this).apply {
                setImageDrawable(PortalUi.Glyph(PortalUi.Icon.SYSTEM))
                contentDescription="Quick menu";alpha=.52f
                background=GradientDrawable().apply {setColor(0x77000000);cornerRadius=dp(32).toFloat()}
                setPadding(dp(10),dp(10),dp(10),dp(10))
                PortalUi.focus(this)
                setOnClickListener {showPortalStreamMenu(!portalMenu.isShowing)}
            }
            binding.root.addView(portalMenuButton,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.TOP or Gravity.END).apply {
                topMargin=dp(18);marginEnd=dp(18)
            })
            TouchControlsFragment.positionOverlayActions(binding.root,portalMenuButton)
            portalMenu=PortalQuickMenu(this,binding.root) {shown ->
                updateInputSuspension()
                touchStates.keys.forEach { it.releaseControls() }
                touchStates.clear()
                supportFragmentManager.fragments.filterIsInstance<TouchControlsFragment>().forEach {
                    val enabled=if(it is TouchpadOnlyFragment) viewModel.touchpadOnlyEnabled.value==true
                        else viewModel.onScreenControlsEnabled.value==true
                    it.view?.visibility=if(!shown && enabled) View.VISIBLE else View.GONE
                }
            }
            portalMenu.row("Console","Disconnect",PortalUi.Icon.REMOTE) {finish()}
            portalMenu.row("PS5 Control Center",icon=PortalUi.Icon.CONTROL_CENTER) {
                portalMenu.onClosed {viewModel.session.pulseButton(ControllerState.BUTTON_PS,120)}
            }
            portalMenu.row("PS5 Home",icon=PortalUi.Icon.HOME) {
                portalMenu.onClosed {viewModel.session.pulseButton(ControllerState.BUTTON_PS,1000)}
            }
            portalMenu.maxResolution({Preferences(this).resolution.value}) {showMaxResolution()}
            portalMenu.brightness()
            portalMenu.row("Audio Devices",icon=PortalUi.Icon.AUDIO) {openPortalSettings("audio")}
            portalMenu.row("On-Screen Controls",icon=PortalUi.Icon.CONTROLLER) {
                viewModel.setOnScreenControlsEnabled(!(viewModel.onScreenControlsEnabled.value ?: true))
                showPortalStreamMenu(false)
            }
            portalMenu.row("Translate Game Text",
                if(PortalPreferences(this).autoTranslateGame) "Auto on" else "Off",
                PortalUi.Icon.GALLERY) {
                val settings=PortalPreferences(this)
                settings.autoTranslateGame=!settings.autoTranslateGame
                if(settings.autoTranslateGame) startAutoGameTranslation() else gameTranslation?.stop()
                portalMenu.updateRowSubtitle("Translate Game Text",if(settings.autoTranslateGame) "Auto on" else "Off")
                showPortalStreamMenu(false)
            }
            portalMenu.row("Live Spoken Captions", "On-device speech translation from game audio",
                PortalUi.Icon.AUDIO) {
                portalMenu.onClosed {
                    if(viewModel.session.state.value==StreamStateConnected) audioCaptions?.toggle()
                    else android.widget.Toast.makeText(this,"Connect Remote Play before starting audio captions",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            portalMenu.row("Translate Current Frame", "Scan decoded game video only",PortalUi.Icon.GALLERY) {
                portalMenu.onClosed {
                    if(viewModel.session.state.value!=StreamStateConnected) return@onClosed
                    if(gameTranslation?.isEnabled!=true) {
                        val settings=PortalPreferences(this)
                        settings.autoTranslateGame=true
                        startAutoGameTranslation()
                    }
                    gameTranslation?.scanNow()
                }
            }
            portalMenu.row("Words and Readings", "Local dictionary and Furigana/Pinyin",PortalUi.Icon.GALLERY) {
                portalMenu.onClosed {
                    startActivity(Intent(this,PortalWordLearningActivity::class.java)
                        .putExtra(PortalWordLearningActivity.EXTRA_TEXT,gameTranslation?.currentGameText.orEmpty())
                        .putExtra(PortalWordLearningActivity.EXTRA_LANGUAGE,
                            gameTranslation?.detectedSourceLanguage.orEmpty().ifBlank {"ja"}))
                }
            }
            portalMenu.row("Settings",icon=PortalUi.Icon.SYSTEM) {openPortalSettings("home")}
            portalMenu.row("Close",icon=PortalUi.Icon.CLOSE) {showPortalStreamMenu(false)}
        }
        private fun showMaxResolution() {
            val preferences=Preferences(this)
            val profiles=Preferences.resolutionAll
            var selected=profiles.indexOf(preferences.resolution).coerceAtLeast(0)
            val heading=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(PortalUi.dp(context,24),PortalUi.dp(context,20),PortalUi.dp(context,24),PortalUi.dp(context,8))
                addView(PortalUi.text(this@StreamActivity,"Max Resolution",22f))
                addView(PortalUi.text(this@StreamActivity,"Return home, then reconnect to use the selected resolution.",14f,true).apply {
                    setPadding(0,PortalUi.dp(context,8),0,0)
                })
            }
            MaterialAlertDialogBuilder(this).setCustomTitle(heading)
                .setSingleChoiceItems(profiles.map{getString(it.title)}.toTypedArray(),selected) {_,which ->selected=which}
                .setPositiveButton("Save and return home") {_,_ ->
                    preferences.resolution=profiles[selected]
                    viewModel.input.releaseAllInputs()
                    finish()
                }.setNegativeButton("Cancel",null).show()
        }
        private fun openPortalSettings(page:String) {
            startActivity(Intent(this,PortalSettingsActivity::class.java).putExtra(PortalSettingsActivity.EXTRA_PAGE,page))
        }

        private fun setGameplayInputReady(ready:Boolean) {
            gameplayInputReady=ready
            updateInputSuspension()
        }
        private fun startAutoGameTranslation() {
            val settings=PortalPreferences(this)
            if(!settings.autoTranslateGame || gameTranslation?.isEnabled==true ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                viewModel.session.state.value!=StreamStateConnected) return
            gameTranslation?.enableAuto(settings.translationTarget) { status ->
                if(!isFinishing && !isDestroyed)
                    android.widget.Toast.makeText(this,status,android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        private fun updateInputSuspension() {
            if(!::viewModel.isInitialized) return
            val blocked=!gameplayInputReady || !hasWindowFocus() || (::portalMenu.isInitialized && portalMenu.isShowing)
            viewModel.input.setInputSuspended(blocked)
            if(blocked) supportFragmentManager.fragments.filterIsInstance<TouchControlsFragment>().forEach {it.releaseControls()}
        }

	private fun showPortalStreamMenu(show: Boolean)
	{
            portalMenu.show(show)
            binding.overlay.visibility=View.GONE
	}

	private fun showOverlay()
	{
            binding.overlay.isGone=true
            if(::portalMenuButton.isInitialized) portalMenuButton.animate().alpha(.65f).setDuration(180).start()
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
            binding.overlay.isGone=true
            if(::portalMenuButton.isInitialized) portalMenuButton.animate().alpha(.35f).setDuration(180).start()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
            if(!::viewModel.isInitialized) return
            updateInputSuspension()
            if(hasFocus && ::insetsController.isInitialized)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
            if(::insetsController.isInitialized) insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		        if(state==StreamStateConnected) startAutoGameTranslation()
		        else {
                    gameTranslation?.stop()
                    if(com.metallic.chiaki.main.PortalAudioCaptionBus.state.running) audioCaptions?.stop()
                }
	            setGameplayInputReady(false)
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
            if(state==StreamStateConnected) {
                // Chiaki exposes a native connection event; unlike JB it has no JNI
                // first-decoded-frame callback. The aperture always shows the real surface.
                connectingSurface.reveal {
                    if(viewModel.session.state.value==StreamStateConnected) setGameplayInputReady(true)
                }
            } else connectingSurface.showConnecting()
            connectingBackdrop.setConnecting(state==StreamStateConnecting)

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
            if(!::portalMenu.isInitialized) return super.dispatchKeyEvent(event)
            val settings=PortalPreferences(this)
            if(settings.translationHotkey && !portalMenu.isShowing &&
                viewModel.session.state.value==StreamStateConnected &&
                translationHotkey.onKey(event.keyCode,event.action==KeyEvent.ACTION_DOWN)) {
                settings.autoTranslateGame=!settings.autoTranslateGame
                if(settings.autoTranslateGame) startAutoGameTranslation() else gameTranslation?.stop()
                portalMenu.updateRowSubtitle("Translate Game Text",if(settings.autoTranslateGame) "Auto on" else "Off")
                return true
            }
		// The Portal's PS button opens quick controls. Android may reserve the
		// physical home button, so the stream toolbar provides a touch fallback.
		if(viewModel.input.opensQuickMenu(event)) {
			if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                    showPortalStreamMenu(!portalMenu.isShowing)
			return true
		}
            if(portalMenu.isShowing) {
			if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
				if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount==0) portalMenu.back()
				return true
			}
                if(event.keyCode==KeyEvent.KEYCODE_BUTTON_A) {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) currentFocus?.performClick()
                    return true
                }
			return super.dispatchKeyEvent(event)
		}
		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}
        override fun onGenericMotionEvent(event: MotionEvent):Boolean {
            if(!::portalMenu.isInitialized || portalMenu.isShowing) return super.onGenericMotionEvent(event)
            return viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
        }
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
