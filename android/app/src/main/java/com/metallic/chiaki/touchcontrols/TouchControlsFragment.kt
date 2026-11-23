// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.Gravity
import android.widget.FrameLayout
import android.os.SystemClock
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.metallic.chiaki.databinding.FragmentControlsBinding
import com.metallic.chiaki.lib.ControllerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

abstract class TouchControlsFragment : Fragment()
{
    companion object {
        /** Leave the shoulder buttons free. The touchpad reserves the matching center strip. */
        fun positionOverlayActions(container:FrameLayout,vararg actions:View) {
            val density=container.resources.displayMetrics.density
            fun dp(value:Int)=(value*density+.5f).toInt()
            fun update(width:Int,height:Int) {
                if(width<=0 || height<=0) return
                val landscape=width>height
                actions.forEachIndexed {index,action ->
                    val params=action.layoutParams as FrameLayout.LayoutParams
                    val gravity=Gravity.TOP or if(landscape) Gravity.CENTER_HORIZONTAL else Gravity.END
                    val end=if(landscape) 0 else dp(18+index*60)
                    if(params.gravity!=gravity || params.marginEnd!=end || params.topMargin!=dp(18)) {
                        params.gravity=gravity;params.marginEnd=end;params.topMargin=dp(18)
                        action.layoutParams=params
                    }
                    action.translationX=if(landscape) (actions.lastIndex/2f-index)*dp(60) else 0f
                }
            }
            container.addOnLayoutChangeListener {_,left,top,right,bottom,_,_,_,_ ->update(right-left,bottom-top)}
            update(container.width,container.height)
        }
    }
	protected var ownControllerState = ControllerState()
		set(value)
		{
			val diff = field != value
			field = value
			if(diff)
				_ownControllerStateFlow.value = ownControllerState
		}

	protected val _ownControllerStateFlow = MutableStateFlow(ControllerState())

	// Proxy to delay attaching to the touchpadView until it's available.
	// Starts emitting ownControllerState, then switches to combined flow when touchpad is ready.
	protected val _controllerStateSource = MutableStateFlow<Flow<ControllerState>>(_ownControllerStateFlow)

	@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
	val controllerState: Flow<ControllerState> get() =
		_controllerStateSource.flatMapLatest { it }

	var onScreenControlsEnabled: LiveData<Boolean>? = null
    fun releaseControls() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        view?.dispatchTouchEvent(cancel)
        // A short touchpad press can outlive its pointer; cancel its pending timer too.
        fun releaseTouchpads(control:View) {
            if(control is TouchpadView) control.releaseTouches()
            if(control is ViewGroup) for(index in 0 until control.childCount) releaseTouchpads(control.getChildAt(index))
        }
        view?.let(::releaseTouchpads)
        cancel.recycle()
        ownControllerState = ControllerState()
    }
    override fun onStop() {
        releaseControls()
        super.onStop()
    }
}

class DefaultTouchControlsFragment : TouchControlsFragment()
{
	private var _binding: FragmentControlsBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentControlsBinding.inflate(inflater, container, false).let {
			_binding = it
			_controllerStateSource.value =
				combine(_ownControllerStateFlow, binding.touchpadView.controllerState) { a, b -> a or b }
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
        fun reserveActionArea(width:Int,height:Int) {
            if(width<=0 || height<=0) return
            val density=resources.displayMetrics.density
            fun dp(value:Int)=(value*density+.5f).toInt()
            val landscape=width>height
            val top=if(landscape) 0 else dp(72)
            if(view.paddingTop!=top) view.setPadding(view.paddingLeft,top,view.paddingRight,view.paddingBottom)
            val params=binding.touchpadView.layoutParams as ConstraintLayout.LayoutParams
            val touchpadTop=dp(if(landscape) 80 else 32)
            if(params.topMargin!=touchpadTop) {
                params.topMargin=touchpadTop;binding.touchpadView.layoutParams=params
            }
        }
        view.addOnLayoutChangeListener {_,left,top,right,bottom,_,_,_,_ ->reserveActionArea(right-left,bottom-top)}
        reserveActionArea(view.width,view.height)
		binding.dpadView.stateChangeCallback = this::dpadStateChanged
		binding.crossButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_CROSS)
		binding.moonButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_MOON)
		binding.pyramidButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PYRAMID)
		binding.boxButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_BOX)
		binding.l1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L1)
		binding.r1ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R1)
		binding.l3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_L3)
		binding.r3ButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_R3)
		binding.optionsButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_OPTIONS)
		binding.shareButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_SHARE)
		binding.psButtonView.buttonPressedCallback = buttonStateChanged(ControllerState.BUTTON_PS)

		binding.l2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { l2State = if(it) 255U else 0U } }
		binding.r2ButtonView.buttonPressedCallback = { ownControllerState = ownControllerState.copy().apply { r2State = if(it) 255U else 0U } }

		val quantizeStick = { f: Float ->
			(Short.MAX_VALUE * f).toInt().toShort()
		}

		binding.leftAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			leftX = quantizeStick(it.x)
			leftY = quantizeStick(it.y)
		}}

		binding.rightAnalogStickView.stateChangedCallback = { ownControllerState = ownControllerState.copy().apply {
			rightX = quantizeStick(it.x)
			rightY = quantizeStick(it.y)
		}}

		onScreenControlsEnabled?.observe(viewLifecycleOwner, Observer {
			if(!it) releaseControls()
			view.visibility = if(it) View.VISIBLE else View.GONE
		})
	}

	private fun dpadStateChanged(direction: DPadView.Direction?)
	{
		ownControllerState = ownControllerState.copy().apply {
			buttons = ((buttons
						and ControllerState.BUTTON_DPAD_LEFT.inv()
						and ControllerState.BUTTON_DPAD_RIGHT.inv()
						and ControllerState.BUTTON_DPAD_UP.inv()
						and ControllerState.BUTTON_DPAD_DOWN.inv())
					or when(direction)
					{
						DPadView.Direction.UP -> ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.DOWN -> ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.LEFT -> ControllerState.BUTTON_DPAD_LEFT
						DPadView.Direction.RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
						DPadView.Direction.LEFT_UP -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.LEFT_DOWN -> ControllerState.BUTTON_DPAD_LEFT or ControllerState.BUTTON_DPAD_DOWN
						DPadView.Direction.RIGHT_UP -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_UP
						DPadView.Direction.RIGHT_DOWN -> ControllerState.BUTTON_DPAD_RIGHT or ControllerState.BUTTON_DPAD_DOWN
						null -> 0U
					})
		}
	}

	private fun buttonStateChanged(buttonMask: UInt) = { pressed: Boolean ->
		ownControllerState = ownControllerState.copy().apply {
			buttons =
				if(pressed)
					buttons or buttonMask
				else
					buttons and buttonMask.inv()

		}
	}
}
