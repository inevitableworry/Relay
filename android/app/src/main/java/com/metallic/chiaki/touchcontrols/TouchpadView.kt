// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.ControllerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

class TouchpadView @JvmOverloads constructor(
	context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr)
{
	companion object
	{
		private const val BUTTON_PRESS_MAX_MOVE_DIST_DP = 32.0f
		private const val SHORT_BUTTON_PRESS_DURATION_MS = 200L
		private const val BUTTON_HOLD_DELAY_MS = 500L
	}

	private val haptics = ButtonHaptics(context)

	private val drawableIdle: Drawable?
	private val drawablePressed: Drawable?

	private val state: ControllerState = ControllerState()

	inner class Touch(
		val stateId: UByte,
		private val startX: Float,
		private val startY: Float)
	{
        var currentX:Float=startX
            private set
        var currentY:Float=startY
            private set
		var lifted = false // will be true but touch still in list when only relevant for short touch
		private var maxDist: Float = 0.0f
		val moveInsignificant: Boolean get() = maxDist < BUTTON_PRESS_MAX_MOVE_DIST_DP

		fun onMove(x: Float, y: Float)
		{
            currentX=x
            currentY=y
			val d = (Vector(x, y) - Vector(startX, startY)).length / resources.displayMetrics.density
			maxDist = max(d, maxDist)
		}

		val startButtonHoldRunnable = Runnable {
			if(!moveInsignificant || buttonHeld)
				return@Runnable
			haptics.trigger(true)
			state.buttons = state.buttons or ControllerState.BUTTON_TOUCHPAD
			buttonHeld = true
			triggerStateChanged()
		}
	}
	private val pointerTouches = mutableMapOf<Int, Touch>()
    private val indicator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE
        strokeCap=Paint.Cap.ROUND
    }

	private fun snapshot() = state.copy(touches=state.touches.map{it.copy()}.toTypedArray())
	private val _controllerState = MutableStateFlow(snapshot())
	val controllerState: StateFlow<ControllerState> get() = _controllerState.asStateFlow()

	private var shortPressingTouches = listOf<Touch>()
	private val shortButtonPressLiftRunnable = Runnable {
		state.buttons = state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
		shortPressingTouches.forEach {
			state.stopTouch(it.stateId)
		}
		shortPressingTouches = listOf()
		triggerStateChanged()
	}

	private var buttonHeld = false

    fun releaseTouches() {
        (pointerTouches.values + shortPressingTouches).forEach { removeCallbacks(it.startButtonHoldRunnable) }
        removeCallbacks(shortButtonPressLiftRunnable)
        pointerTouches.clear();shortPressingTouches=emptyList();buttonHeld=false
        state.buttons=state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
        state.touches.forEach {it.id=-1}
        triggerStateChanged()
    }

    override fun onDetachedFromWindow() {
        releaseTouches()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView:View,visibility:Int) {
        super.onVisibilityChanged(changedView,visibility)
        if(visibility!=View.VISIBLE && isAttachedToWindow) releaseTouches()
    }

	init
	{
		context.theme.obtainStyledAttributes(attrs, R.styleable.TouchpadView, 0, 0).apply {
			drawableIdle = getDrawable(R.styleable.TouchpadView_drawableIdle)
			drawablePressed = getDrawable(R.styleable.TouchpadView_drawablePressed)
			recycle()
		}
		isClickable = true
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
        val touching=pointerTouches.values.filter { !it.lifted }
        if(touching.isEmpty() && state.buttons and ControllerState.BUTTON_TOUCHPAD == 0U)
			return
		val drawable = if(state.buttons and ControllerState.BUTTON_TOUCHPAD != 0U) drawablePressed else drawableIdle
		drawable?.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
		drawable?.draw(canvas)
        // Portal 3.0 added visible feedback for touchpad interactions.
        // Draw soft concentric touch rings on the controller's actual finger
        // coordinates, not an ornamental hotspot fixed to the screen.
        val density=resources.displayMetrics.density
        for(touch in touching) {
            indicator.strokeWidth=2f*density
            indicator.color=0xe9ffffff.toInt()
            canvas.drawCircle(touch.currentX,touch.currentY,13f*density,indicator)
            indicator.strokeWidth=density
            indicator.color=0x87d3c6ff.toInt()
            canvas.drawCircle(touch.currentX,touch.currentY,22f*density,indicator)
        }
	}

	private fun touchX(event: MotionEvent, index: Int): UShort =
		maxOf(0U.toUShort(), minOf((ControllerState.TOUCHPAD_WIDTH - 1u).toUShort(),
			(ControllerState.TOUCHPAD_WIDTH.toFloat() * event.getX(index) / width.toFloat()).toUInt().toUShort()))

	private fun touchY(event: MotionEvent, index: Int): UShort =
		maxOf(0U.toUShort(), minOf((ControllerState.TOUCHPAD_HEIGHT - 1u).toUShort(),
			(ControllerState.TOUCHPAD_HEIGHT.toFloat() * event.getY(index) / height.toFloat()).toUInt().toUShort()))

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_CANCEL -> releaseTouches()
			MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
				state.startTouch(touchX(event, event.actionIndex), touchY(event, event.actionIndex))?.let {
					haptics.trigger()
					val touch = Touch(it, event.getX(event.actionIndex), event.getY(event.actionIndex))
					pointerTouches[event.getPointerId(event.actionIndex)] = touch
					if(!buttonHeld)
						postDelayed(touch.startButtonHoldRunnable, BUTTON_HOLD_DELAY_MS)
					triggerStateChanged()
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
				pointerTouches.remove(event.getPointerId(event.actionIndex))?.let {
					removeCallbacks(it.startButtonHoldRunnable)
					when
					{
						buttonHeld ->
						{
							buttonHeld = false
							state.buttons = state.buttons and ControllerState.BUTTON_TOUCHPAD.inv()
							state.stopTouch(it.stateId)
						}
						it.moveInsignificant -> triggerShortButtonPress(it)
						else -> state.stopTouch(it.stateId)
					}
					triggerStateChanged()
				}
			}
			MotionEvent.ACTION_MOVE -> {
				val changed = pointerTouches.entries.fold(false) { acc, it ->
					val index = event.findPointerIndex(it.key)
					if(index < 0)
						acc
					else
					{
						it.value.onMove(event.getX(index), event.getY(index))
						val moved = state.setTouchPos(it.value.stateId, touchX(event, index), touchY(event, index))
						acc || moved
					}
				}
				if(changed)
					triggerStateChanged()
			}
		}
		return true
	}

	private fun triggerShortButtonPress(touch: Touch)
	{
		shortPressingTouches = shortPressingTouches + listOf(touch)
		removeCallbacks(shortButtonPressLiftRunnable)
		state.buttons = state.buttons or ControllerState.BUTTON_TOUCHPAD
		postDelayed(shortButtonPressLiftRunnable, SHORT_BUTTON_PRESS_DURATION_MS)
	}

	private fun triggerStateChanged()
	{
		invalidate()
		_controllerState.value = snapshot()
	}
}
