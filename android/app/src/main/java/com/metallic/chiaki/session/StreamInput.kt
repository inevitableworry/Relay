package com.metallic.chiaki.session

import android.content.Context
import android.hardware.*
import android.hardware.input.InputManager
import android.view.*
import android.os.Build
import android.os.VibrationEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	private var inputSuspended = false
	private val profiles = GamepadProfiles(context)

	val controllerState: ControllerState get()
	{
		if(inputSuspended) return ControllerState()
		var physical = ControllerState()
		for(state in gamepadStates.values)
		{
			// Prefer this device's analog value even at zero, without suppressing
			// another controller, a custom button mapping, or on-screen controls.
			val keys = state.keys.state(state.analogL2, state.analogR2)
			physical = physical or keys or state.axes
		}
		val controllerState = sensorControllerState or physical
		val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
		@Suppress("DEPRECATION")
		when(windowManager.defaultDisplay.rotation)
		{
			Surface.ROTATION_90 -> {
				controllerState.accelX *= -1.0f
				controllerState.accelZ *= -1.0f
				controllerState.gyroX *= -1.0f
				controllerState.gyroZ *= -1.0f
				controllerState.orientX *= -1.0f
				controllerState.orientZ *= -1.0f
			}
			else -> {}
		}
		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private class GamepadState(val profile: GamepadProfile)
	{
		val keys = GamepadButtonState()
		val axes = ControllerState()
		var analogL2 = false
		var analogR2 = false
	}
	private val gamepadStates = mutableMapOf<Int, GamepadState>()
	private fun gamepadState(deviceId: Int, device: InputDevice?) =
		gamepadStates.getOrPut(deviceId) { GamepadState(profiles.load(device)) }

	private fun clearGamepad(deviceId: Int)
	{
		if(gamepadStates.remove(deviceId) != null)
			controllerStateUpdated()
	}

	/** Release held controls when a menu opens, focus is lost, or a session pauses. */
	fun releaseAllInputs()
	{
		gamepadStates.clear()
		sensorControllerState.gyroX = 0f
		sensorControllerState.gyroY = 0f
		sensorControllerState.gyroZ = 0f
		touchControllerState = ControllerState()
	}

	/** Consume controller input without forwarding it while Android menus own focus. */
	fun setInputSuspended(suspended: Boolean)
	{
		if(inputSuspended == suspended) return
		inputSuspended = suspended
		releaseAllInputs()
	}

	private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
	private var sensorsRegistered = false
	private val inputDeviceListener = object: InputManager.InputDeviceListener {
		override fun onInputDeviceAdded(deviceId: Int) {}
		override fun onInputDeviceRemoved(deviceId: Int) = clearGamepad(deviceId)
		override fun onInputDeviceChanged(deviceId: Int) = clearGamepad(deviceId)
	}
	var touchControllerState = ControllerState()
		set(value)
		{
			field = if(inputSuspended) ControllerState() else value
			controllerStateUpdated()
		}

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
					sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
				}
				Sensor.TYPE_GYROSCOPE -> {
					sensorControllerState.gyroX = event.values[1]
					sensorControllerState.gyroY = event.values[2]
					sensorControllerState.gyroZ = event.values[0]
				}
				Sensor.TYPE_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}

	private val inputLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			inputManager.registerInputDeviceListener(inputDeviceListener, null)
			if(!preferences.motionEnabled)
				return
			val samplingPeriodUs = 4000
			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			listOfNotNull(
				sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
			).forEach {
				sensorManager.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
			sensorsRegistered = true
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
			inputManager.unregisterInputDeviceListener(inputDeviceListener)
			if(sensorsRegistered)
			{
				val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
				sensorManager.unregisterListener(sensorEventListener)
				sensorsRegistered = false
			}
			releaseAllInputs()
			stopRumble()
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		lifecycleOwner.lifecycle.addObserver(inputLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun opensQuickMenu(event:KeyEvent):Boolean {
        if(event.keyCode!=KeyEvent.KEYCODE_BUTTON_MODE && event.keyCode!=KeyEvent.KEYCODE_BUTTON_C) return false
        return (gamepadStates[event.deviceId]?.profile ?: profiles.load(event.device)).opensQuickMenu(event.keyCode)
    }

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
		val gamepadSource = GamepadProfiles.isController(event.device) ||
			(event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
			(event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
		val existing = gamepadStates[event.deviceId]
		if(event.action == KeyEvent.ACTION_UP && existing?.keys?.release(event.keyCode) == true)
		{
			controllerStateUpdated()
			return true
		}
		val profile = existing?.profile ?: profiles.load(event.device)
		val target = profile.buttons[event.keyCode]
			?: GamepadButton.automatic(event.keyCode, preferences.swapCrossMoon, gamepadSource)
			?: return false
		if(inputSuspended) return true
		gamepadState(event.deviceId, event.device).keys.update(
			event.keyCode, target, event.action == KeyEvent.ACTION_DOWN && !event.isCanceled)
		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		if(event.actionMasked == MotionEvent.ACTION_CANCEL)
		{
			clearGamepad(event.deviceId)
			return true
		}
		if(event.actionMasked != MotionEvent.ACTION_MOVE) return false
		if(inputSuspended) return true

		val state = gamepadState(event.deviceId, event.device)
		val profile = state.profile
		val axes = state.axes
		val device = event.device
		fun range(axis: Int) = device?.getMotionRange(axis, event.source) ?: device?.getMotionRange(axis)
		fun hasAxis(axis: Int) = range(axis) != null
		val hasRxRy = hasAxis(MotionEvent.AXIS_RX) && hasAxis(MotionEvent.AXIS_RY)
		val hasDedicatedTriggers = hasAxis(MotionEvent.AXIS_LTRIGGER) || hasAxis(MotionEvent.AXIS_RTRIGGER) ||
			hasAxis(MotionEvent.AXIS_BRAKE) || hasAxis(MotionEvent.AXIS_GAS)
		// A positive-only Z/RZ pair alongside RX/RY can safely be recognized as
		// triggers. Centered ambiguous axes need the explicit saved profile.
		val positiveZTriggers = hasRxRy && !hasDedicatedTriggers &&
			(range(MotionEvent.AXIS_Z)?.min ?: -1f) >= 0f &&
			(range(MotionEvent.AXIS_RZ)?.min ?: -1f) >= 0f
		val useRxRy = hasRxRy && (positiveZTriggers ||
			!hasAxis(MotionEvent.AXIS_Z) || !hasAxis(MotionEvent.AXIS_RZ))

		fun stick(target: GamepadAxis, automaticAxis: Int): Short
		{
			val binding = profile.axes[target] ?: GamepadAxisBinding(automaticAxis)
			val bounds = range(binding.axis) ?: return 0
			return GamepadMath.stick(event.getAxisValue(binding.axis), bounds.min, bounds.max,
				bounds.flat, profile.deadZone, binding.inverted)
		}
		fun trigger(binding: GamepadAxisBinding): UByte
		{
			val bounds = range(binding.axis) ?: return 0U
			return GamepadMath.trigger(event.getAxisValue(binding.axis), bounds.min, bounds.max,
				bounds.flat, binding.inverted)
		}
		fun triggerBindings(target: GamepadAxis, standard: Int, alias: Int, fallback: Int): List<GamepadAxisBinding>
		{
			profile.axes[target]?.let { return if(hasAxis(it.axis)) listOf(it) else emptyList() }
			val candidates = mutableListOf(standard, alias)
			if(positiveZTriggers) candidates.add(fallback)
			return candidates.filter { hasAxis(it) }.map { GamepadAxisBinding(it) }
		}
		axes.leftX = stick(GamepadAxis.LEFT_X, MotionEvent.AXIS_X)
		axes.leftY = stick(GamepadAxis.LEFT_Y, MotionEvent.AXIS_Y)
		axes.rightX = stick(GamepadAxis.RIGHT_X, if(useRxRy) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z)
		axes.rightY = stick(GamepadAxis.RIGHT_Y, if(useRxRy) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ)
		val left = triggerBindings(GamepadAxis.L2, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_Z)
		val right = triggerBindings(GamepadAxis.R2, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS, MotionEvent.AXIS_RZ)
		state.analogL2 = left.isNotEmpty()
		state.analogR2 = right.isNotEmpty()
		axes.l2State = left.maxOfOrNull { trigger(it) } ?: 0U
		axes.r2State = right.maxOfOrNull { trigger(it) } ?: 0U
		val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
		val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
		axes.buttons = (if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
			(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
			(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
			(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
		controllerStateUpdated()
		return true
	}

	/** Android exposes ordinary rumble on some controllers; advanced trigger effects
	 * need a supported HID transport and are not synthesized here. */
	@Suppress("DEPRECATION")
	fun rumble(left: Int, right: Int): Boolean
	{
		val amplitude = ((left.coerceIn(0, 255) + right.coerceIn(0, 255)) / 2)
		var delivered = false
		for(device in GamepadProfiles.connected())
		{
			val vibrator = device.vibrator
			if(!vibrator.hasVibrator()) continue
			delivered = true
			vibrator.cancel()
			if(amplitude == 0) continue
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				vibrator.vibrate(VibrationEffect.createOneShot(1000,
					if(vibrator.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE))
			else vibrator.vibrate(1000)
		}
		return delivered
	}

	@Suppress("DEPRECATION")
	fun stopRumble()
	{
		GamepadProfiles.connected().forEach { it.vibrator.cancel() }
	}
}
