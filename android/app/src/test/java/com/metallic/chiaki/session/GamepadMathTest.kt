// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.session

import org.junit.Assert.assertEquals
import org.junit.Test

class GamepadMathTest {
    @Test fun stickSuppressesRestNoiseAndScalesRemainingTravel() {
        assertEquals(0, GamepadMath.stick(0.1f, -1f, 1f, 0f, 0.12f).toInt())
        assertEquals(16384, GamepadMath.stick(0.56f, -1f, 1f, 0f, 0.12f).toInt())
        assertEquals(-16384, GamepadMath.stick(-0.56f, -1f, 1f, 0f, 0.12f).toInt())
        assertEquals(0, GamepadMath.stick(0.3f, -1f, 1f, 0.4f, 0.12f).toInt())
    }

    @Test fun sticksSupportUnsignedHidRangesAndFullInvertedTravel() {
        assertEquals(0, GamepadMath.stick(127.5f, 0f, 255f, 0f, 0.12f).toInt())
        assertEquals(32767, GamepadMath.stick(255f, 0f, 255f, 0f, 0.12f).toInt())
        assertEquals(-32768, GamepadMath.stick(0f, 0f, 255f, 0f, 0.12f).toInt())
        assertEquals(-32768, GamepadMath.stick(1f, -1f, 1f, 0f, 0.12f, true).toInt())
        assertEquals(32767, GamepadMath.stick(-1f, -1f, 1f, 0f, 0.12f, true).toInt())
    }

    @Test fun invalidOrNonFiniteRangesAreNeutral() {
        assertEquals(0, GamepadMath.stick(Float.NaN, -1f, 1f, 0f, 0.12f).toInt())
        assertEquals(0, GamepadMath.stick(1f, 1f, 1f, 0f, 0.12f).toInt())
        assertEquals(0, GamepadMath.stick(1f, 2f, 1f, 0f, 0.12f).toInt())
        assertEquals(0, GamepadMath.stick(1f, -1f, Float.POSITIVE_INFINITY, 0f, 0.12f).toInt())
        assertEquals(0, GamepadMath.trigger(Float.NaN, 0f, 1f, 0f).toInt())
        assertEquals(0, GamepadMath.trigger(0f, 0f, 0f, 0f).toInt())
    }

    @Test fun triggersNormalizeSignedAndUnsignedRangesWithoutHalfPressAtRest() {
        assertEquals(0, GamepadMath.trigger(-1f, -1f, 1f, 0f).toInt())
        assertEquals(128, GamepadMath.trigger(0f, -1f, 1f, 0f).toInt())
        assertEquals(255, GamepadMath.trigger(1f, -1f, 1f, 0f).toInt())
        assertEquals(128, GamepadMath.trigger(512f, 0f, 1023f, 0f).toInt())
        assertEquals(0, GamepadMath.trigger(0.02f, 0f, 1f, 0.05f).toInt())
        assertEquals(0, GamepadMath.trigger(1f, 0f, 1f, 0f, true).toInt())
        assertEquals(255, GamepadMath.trigger(0f, 0f, 1f, 0f, true).toInt())
    }
}

