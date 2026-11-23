// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import com.metallic.chiaki.lib.ControllerState
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire contract independently implemented from PBS Payload, pinned in jb-capabilities.md. */
object PbsProtocol {
    const val MAX_VIDEO_PACKET = 16 * 1024 * 1024
    const val MAX_AUDIO_PACKET = 65536
    const val LEGACY_BUTTON_MASK = 0x0013fffe
    const val NATIVE_BUTTON_MASK = 0x0011ffff
    data class Packet(val pts90k: Long, val data: ByteArray)

    fun readPacket(input: InputStream, maxBytes: Int): Packet {
        require(maxBytes in 1..MAX_VIDEO_PACKET)
        val header = ByteArray(8)
        readExactly(input, header)
        val fields = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val length = fields.int.toLong() and 0xffffffffL
        val pts = fields.int.toLong() and 0xffffffffL
        if (length == 0L || length > maxBytes) throw IOException("Invalid PBS packet length: $length")
        return Packet(pts, ByteArray(length.toInt()).also { readExactly(input, it) })
    }

    private fun readExactly(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw EOFException("The PS5 stream ended before a complete packet arrived")
            if (n == 0) {
                val value = input.read()
                if (value < 0) throw EOFException("The PS5 stream ended before a complete packet arrived")
                bytes[offset++] = value.toByte()
            } else offset += n
        }
    }

    fun nalFromHex(hex: String, expectedType: Int): ByteArray {
        if (hex.isEmpty() || hex.length > 4096 || hex.length % 2 != 0)
            throw IOException("The PS5 did not provide a valid H.264 decoder header")
        val result = ByteArray(4 + hex.length / 2)
        result[3] = 1
        for (i in hex.indices step 2) {
            val high = hex[i].digitToIntOrNull(16)
            val low = hex[i + 1].digitToIntOrNull(16)
            if (high == null || low == null) throw IOException("Invalid H.264 decoder header")
            result[4 + i / 2] = ((high shl 4) or low).toByte()
        }
        if (result[4].toInt() and 31 != expectedType) throw IOException("Unexpected H.264 decoder header type")
        return result
    }

    /** PBS's video clock is 90 kHz and wraps after approximately 13 hours. */
    class VideoClock {
        private var last = -1L
        private var epoch = 0L
        private var first = -1L
        fun timeUs(raw: Long): Long {
            require(raw in 0..0xffffffffL)
            if (last >= 0 && raw - last < -0x80000000L) epoch += 0x100000000L
            val ticks = epoch + raw
            if (first < 0) first = ticks
            last = raw
            return ((ticks - first).coerceAtLeast(0) * 1000L) / 90L
        }
    }

    enum class Button(val mask: Int) {
        L3(0x2), R3(0x4), OPTIONS(0x8), UP(0x10), RIGHT(0x20), DOWN(0x40), LEFT(0x80),
        L2(0x100), R2(0x200), L1(0x400), R1(0x800), TRIANGLE(0x1000), CIRCLE(0x2000),
        // scePadVirtualDeviceInsertData receives native ScePadData masks unchanged.
        // Upstream PBS web labels reversed system buttons; native Create is bit 0.
        CROSS(0x4000), SQUARE(0x8000), CREATE(0x1), PS(0x10000), TOUCHPAD(0x100000)
    }

    data class PadState(val buttons: Int = 0, val lx: Int = 128, val ly: Int = 128,
                        val rx: Int = 128, val ry: Int = 128, val l2: Int = 0, val r2: Int = 0) {
        fun encode(sequence: Long): String = "${sequence and 0xffffffffL},$buttons,$lx,$ly,$rx,$ry,$l2,$r2"
    }

    fun axis(value: Short): Int = ((value.toInt() + 32768L) * 255L + 32767L).div(65535L).toInt()

    fun controller(state: ControllerState): PadState {
        var mask = 0
        fun map(source: UInt, target: Button) { if (state.buttons and source != 0U) mask = mask or target.mask }
        map(ControllerState.BUTTON_CROSS, Button.CROSS)
        map(ControllerState.BUTTON_MOON, Button.CIRCLE)
        map(ControllerState.BUTTON_BOX, Button.SQUARE)
        map(ControllerState.BUTTON_PYRAMID, Button.TRIANGLE)
        map(ControllerState.BUTTON_DPAD_UP, Button.UP)
        map(ControllerState.BUTTON_DPAD_DOWN, Button.DOWN)
        map(ControllerState.BUTTON_DPAD_LEFT, Button.LEFT)
        map(ControllerState.BUTTON_DPAD_RIGHT, Button.RIGHT)
        map(ControllerState.BUTTON_L1, Button.L1)
        map(ControllerState.BUTTON_R1, Button.R1)
        map(ControllerState.BUTTON_L3, Button.L3)
        map(ControllerState.BUTTON_R3, Button.R3)
        map(ControllerState.BUTTON_OPTIONS, Button.OPTIONS)
        map(ControllerState.BUTTON_SHARE, Button.CREATE)
        map(ControllerState.BUTTON_PS, Button.PS)
        map(ControllerState.BUTTON_TOUCHPAD, Button.TOUCHPAD)
        if (state.l2State.toInt() >= 128) mask = mask or Button.L2.mask
        if (state.r2State.toInt() >= 128) mask = mask or Button.R2.mask
        return PadState(mask, axis(state.leftX), axis(state.leftY), axis(state.rightX), axis(state.rightY),
            state.l2State.toInt(), state.r2State.toInt())
    }
}
