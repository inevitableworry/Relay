// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.session

import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure range conversion shared by every HID profile; invalid readings are neutral. */
internal object GamepadMath {
    fun stick(value: Float, min: Float, max: Float, flat: Float, deadZone: Float, inverted: Boolean = false): Short {
        if(!value.isFinite() || !min.isFinite() || !max.isFinite() || max <= min) return 0
        val center = (min + max) * 0.5f
        if(!center.isFinite()) return 0
        val halfRange = if(value >= center) max - center else center - min
        if(halfRange <= 0f || !halfRange.isFinite()) return 0
        val normalized = ((value - center) / halfRange).coerceIn(-1f, 1f)
        val rangeFlat = if(flat.isFinite()) flat.coerceAtLeast(0f) / halfRange else 0f
        val requestedDeadZone = if(deadZone.isFinite()) deadZone.coerceIn(0f, 0.5f) else 0.12f
        val threshold = maxOf(requestedDeadZone, rangeFlat).coerceAtMost(0.95f)
        val magnitude = abs(normalized)
        if(magnitude <= threshold) return 0
        val rescaled = (magnitude - threshold) / (1f - threshold)
        val negative = (normalized < 0f) xor inverted
        return (rescaled * if(negative) Short.MIN_VALUE.toFloat() else Short.MAX_VALUE.toFloat())
            .roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun trigger(value: Float, min: Float, max: Float, flat: Float, inverted: Boolean = false): UByte {
        if(!value.isFinite() || !min.isFinite() || !max.isFinite() || max <= min) return 0U
        var normalized = ((value - min) / (max - min)).coerceIn(0f, 1f)
        if(inverted) normalized = 1f - normalized
        val threshold = if(flat.isFinite()) (flat.coerceAtLeast(0f) / (max - min)).coerceAtMost(0.95f) else 0f
        if(normalized <= threshold) return 0U
        return (normalized * 255f).roundToInt().coerceIn(0, 255).toUByte()
    }
}

