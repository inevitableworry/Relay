// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.session

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import com.metallic.chiaki.lib.ControllerState
import org.json.JSONObject
import java.security.MessageDigest

/** Android normalizes physical button positions for recognized HID controllers. */
enum class GamepadButton(val title: String, val mask: UInt = 0U) {
    CROSS("Cross", ControllerState.BUTTON_CROSS),
    CIRCLE("Circle", ControllerState.BUTTON_MOON),
    SQUARE("Square", ControllerState.BUTTON_BOX),
    TRIANGLE("Triangle", ControllerState.BUTTON_PYRAMID),
    UP("D-pad up", ControllerState.BUTTON_DPAD_UP),
    DOWN("D-pad down", ControllerState.BUTTON_DPAD_DOWN),
    LEFT("D-pad left", ControllerState.BUTTON_DPAD_LEFT),
    RIGHT("D-pad right", ControllerState.BUTTON_DPAD_RIGHT),
    L1("L1", ControllerState.BUTTON_L1), R1("R1", ControllerState.BUTTON_R1),
    L2("L2"), R2("R2"),
    L3("L3", ControllerState.BUTTON_L3), R3("R3", ControllerState.BUTTON_R3),
    OPTIONS("Options", ControllerState.BUTTON_OPTIONS),
    SHARE("Create / Share", ControllerState.BUTTON_SHARE),
    TOUCHPAD("Touchpad click", ControllerState.BUTTON_TOUCHPAD),
    PS("PS", ControllerState.BUTTON_PS),
    NONE("Unassigned");

    companion object {
        fun automatic(keyCode: Int, swapFaceButtons: Boolean, controllerSource: Boolean): GamepadButton? =
            when(keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> UP
                KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
                KeyEvent.KEYCODE_DPAD_CENTER -> if(controllerSource) CROSS else null
                KeyEvent.KEYCODE_BUTTON_A -> if(swapFaceButtons) CIRCLE else CROSS
                KeyEvent.KEYCODE_BUTTON_B -> if(swapFaceButtons) CROSS else CIRCLE
                KeyEvent.KEYCODE_BUTTON_X -> SQUARE
                KeyEvent.KEYCODE_BUTTON_Y -> TRIANGLE
                KeyEvent.KEYCODE_BUTTON_L1 -> L1
                KeyEvent.KEYCODE_BUTTON_R1 -> R1
                KeyEvent.KEYCODE_BUTTON_L2 -> L2
                KeyEvent.KEYCODE_BUTTON_R2 -> R2
                KeyEvent.KEYCODE_BUTTON_THUMBL -> L3
                KeyEvent.KEYCODE_BUTTON_THUMBR -> R3
                KeyEvent.KEYCODE_BUTTON_SELECT -> SHARE
                KeyEvent.KEYCODE_BUTTON_START -> OPTIONS
                KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_MODE -> PS
                KeyEvent.KEYCODE_BACK -> if(controllerSource) SHARE else null
                KeyEvent.KEYCODE_MENU -> if(controllerSource) OPTIONS else null
                else -> null
            }
    }
}

enum class GamepadAxis(val title: String) {
    LEFT_X("Left stick horizontal"), LEFT_Y("Left stick vertical"),
    RIGHT_X("Right stick horizontal"), RIGHT_Y("Right stick vertical"),
    L2("L2 analog trigger"), R2("R2 analog trigger")
}

data class GamepadAxisBinding(val axis: Int, val inverted: Boolean = false)

data class GamepadProfile(
    val buttons: Map<Int, GamepadButton> = emptyMap(),
    val axes: Map<GamepadAxis, GamepadAxisBinding> = emptyMap(),
    val deadZone: Float = 0.12f
) {
    fun opensQuickMenu(keyCode:Int):Boolean =
        (keyCode==KeyEvent.KEYCODE_BUTTON_MODE || keyCode==KeyEvent.KEYCODE_BUTTON_C) &&
            (buttons[keyCode] ?: GamepadButton.PS)==GamepadButton.PS
}

/** Profiles survive reconnects; no device ID is persisted because Android reassigns it. */
class GamepadProfiles(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("gamepad_profiles_v1", Context.MODE_PRIVATE)

    private fun key(device: InputDevice): String {
        val identity = device.descriptor.takeIf { it.isNotBlank() }
            ?: "${device.vendorId}:${device.productId}:${device.name}"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun load(device: InputDevice?): GamepadProfile {
        if(device == null) return GamepadProfile()
        return runCatching {
            val json = JSONObject(preferences.getString(key(device), "{}") ?: "{}")
            val buttons = mutableMapOf<Int, GamepadButton>()
            json.optJSONObject("buttons")?.let { values ->
                values.keys().forEach { key ->
                    val code = key.toIntOrNull()
                    val target = GamepadButton.values().firstOrNull { it.name == values.optString(key) }
                    if(code != null && target != null) buttons[code] = target
                }
            }
            val axes = mutableMapOf<GamepadAxis, GamepadAxisBinding>()
            json.optJSONObject("axes")?.let { values ->
                GamepadAxis.values().forEach { target ->
                    values.optJSONObject(target.name)?.let { value ->
                        if(value.has("axis")) axes[target] = GamepadAxisBinding(value.getInt("axis"), value.optBoolean("inverted"))
                    }
                }
            }
            val deadZone = json.optDouble("deadZone", 0.12).toFloat()
            GamepadProfile(buttons, axes, if(deadZone.isFinite()) deadZone.coerceIn(0f, 0.5f) else 0.12f)
        }.getOrDefault(GamepadProfile())
    }

    fun save(device: InputDevice, profile: GamepadProfile) {
        val buttons = JSONObject()
        profile.buttons.forEach { (key, value) -> buttons.put(key.toString(), value.name) }
        val axes = JSONObject()
        profile.axes.forEach { (key, value) ->
            axes.put(key.name, JSONObject().put("axis", value.axis).put("inverted", value.inverted))
        }
        val deadZone = if(profile.deadZone.isFinite()) profile.deadZone.coerceIn(0f, 0.5f) else 0.12f
        preferences.edit().putString(key(device), JSONObject().put("buttons", buttons)
            .put("axes", axes).put("deadZone", deadZone).toString()).apply()
    }

    fun reset(device: InputDevice) { preferences.edit().remove(key(device)).apply() }

    companion object {
        fun isController(device: InputDevice?): Boolean = device != null &&
            (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.supportsSource(InputDevice.SOURCE_JOYSTICK))

        fun connected(): List<InputDevice> =
            InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter { isController(it) }

        // Labels only: never gate input or swap mappings based on a vendor's name.
        fun family(device: InputDevice): String = when(device.vendorId) {
            0x054c -> if(device.name.contains("DualSense", true)) "DualSense" else "PlayStation controller"
            0x045e -> "Xbox controller"
            0x057e -> "Nintendo controller"
            0x2dc8 -> "8BitDo controller"
            else -> "Android gamepad"
        }
    }
}

/** Remember each physical key separately, including aliases mapped to the same target. */
internal class GamepadButtonState {
    private val pressed = mutableMapOf<Int, GamepadButton>()
    fun update(keyCode: Int, target: GamepadButton, down: Boolean) {
        if(down) pressed.putIfAbsent(keyCode, target) else pressed.remove(keyCode)
    }
    fun release(keyCode: Int) = pressed.remove(keyCode) != null
    fun state(analogL2: Boolean = false, analogR2: Boolean = false): ControllerState = ControllerState(
        buttons = pressed.values.fold(0U) { mask, button -> mask or button.mask },
        l2State = if(pressed.any { (key, button) ->
            button == GamepadButton.L2 && !(analogL2 && key == KeyEvent.KEYCODE_BUTTON_L2)
        }) UByte.MAX_VALUE else 0U,
        r2State = if(pressed.any { (key, button) ->
            button == GamepadButton.R2 && !(analogR2 && key == KeyEvent.KEYCODE_BUTTON_R2)
        }) UByte.MAX_VALUE else 0U
    )
}

