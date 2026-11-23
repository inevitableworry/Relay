// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.session

import android.view.KeyEvent
import com.metallic.chiaki.lib.ControllerState
import org.junit.Assert.*
import org.junit.Test

class GamepadButtonStateTest {
    @Test fun releasingOneAliasKeepsTheOtherHeld() {
        val input = GamepadButtonState()
        input.update(96, GamepadButton.CROSS, true)
        input.update(23, GamepadButton.CROSS, true)
        input.release(96)
        assertEquals(ControllerState.BUTTON_CROSS, input.state().buttons)
        input.release(23)
        assertEquals(0U, input.state().buttons)
    }

    @Test fun repeatedEventsCannotChangeAHeldMapping() {
        val input = GamepadButtonState()
        input.update(96, GamepadButton.CROSS, true)
        input.update(96, GamepadButton.CIRCLE, true)
        assertEquals(ControllerState.BUTTON_CROSS, input.state().buttons)
        input.release(96)
        assertEquals(0U, input.state().buttons)
    }

    @Test fun arbitraryHidButtonsCanMapToTriggersAndTouchpad() {
        val input = GamepadButtonState()
        input.update(188, GamepadButton.L2, true)
        input.update(189, GamepadButton.TOUCHPAD, true)
        assertEquals(255, input.state().l2State.toInt())
        assertEquals(ControllerState.BUTTON_TOUCHPAD, input.state().buttons)
        input.release(188)
        assertEquals(0, input.state().l2State.toInt())
        assertEquals(ControllerState.BUTTON_TOUCHPAD, input.state().buttons)
    }

    @Test fun androidPhysicalPositionsAreUsedWithoutVendorGuesses() {
        assertEquals(GamepadButton.CROSS, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_A, false, true))
        assertEquals(GamepadButton.CIRCLE, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_B, false, true))
        assertEquals(GamepadButton.SQUARE, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_X, false, true))
        assertEquals(GamepadButton.TRIANGLE, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_Y, false, true))
        assertEquals(GamepadButton.CIRCLE, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_A, true, true))
        assertEquals(GamepadButton.CROSS, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_B, true, true))
        assertEquals(GamepadButton.SQUARE, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_X, true, true))
        assertEquals(GamepadButton.TRIANGLE, GamepadButton.automatic(KeyEvent.KEYCODE_BUTTON_Y, true, true))
        assertNull(GamepadButton.automatic(KeyEvent.KEYCODE_BACK, false, false))
        assertEquals(GamepadButton.SHARE, GamepadButton.automatic(KeyEvent.KEYCODE_BACK, false, true))
    }

    @Test fun simultaneousButtonsKeepUnreleasedControlsHeld() {
        val input = GamepadButtonState()
        input.update(KeyEvent.KEYCODE_BUTTON_A, GamepadButton.CROSS, true)
        input.update(KeyEvent.KEYCODE_BUTTON_R1, GamepadButton.R1, true)
        input.update(KeyEvent.KEYCODE_BUTTON_L2, GamepadButton.L2, true)
        input.release(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(ControllerState.BUTTON_R1, input.state().buttons)
        assertEquals(255, input.state().l2State.toInt())
        input.release(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(0U, input.state().buttons)
        assertEquals(255, input.state().l2State.toInt())
    }

    @Test fun remappingASystemKeyDoesNotStillOpenTheMenu() {
        assertTrue(GamepadProfile().opensQuickMenu(KeyEvent.KEYCODE_BUTTON_MODE))
        assertFalse(GamepadProfile(mapOf(KeyEvent.KEYCODE_BUTTON_MODE to GamepadButton.OPTIONS))
            .opensQuickMenu(KeyEvent.KEYCODE_BUTTON_MODE))
        assertFalse(GamepadProfile(mapOf(KeyEvent.KEYCODE_BUTTON_C to GamepadButton.NONE))
            .opensQuickMenu(KeyEvent.KEYCODE_BUTTON_C))
        assertFalse(GamepadProfile().opensQuickMenu(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test fun analogTriggerDeduplicationPreservesAnExplicitButtonMapping() {
        val input = GamepadButtonState()
        input.update(KeyEvent.KEYCODE_BUTTON_L2, GamepadButton.L2, true)
        assertEquals(255, input.state().l2State.toInt())
        assertEquals(0, input.state(analogL2 = true).l2State.toInt())
        input.update(KeyEvent.KEYCODE_BUTTON_A, GamepadButton.L2, true)
        assertEquals(255, input.state(analogL2 = true).l2State.toInt())
        input.release(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, input.state(analogL2 = true).l2State.toInt())
    }
}

