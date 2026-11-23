package com.metallic.chiaki.jb

import com.metallic.chiaki.lib.ControllerState
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PbsProtocolTest {
    private fun packet(size: Int, pts: Int, bytes: ByteArray = ByteArray(0)) =
        ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.BIG_ENDIAN).putInt(size).putInt(pts).put(bytes).array()

    @Test fun fragmentedNetworkReadsPreservePacketAndUnsignedTimestamp() {
        val bytes = byteArrayOf(0, 0, 0, 1, 0x65, 0x22)
        val source = object: FilterInputStream(ByteArrayInputStream(packet(bytes.size, -1, bytes))) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        val result = PbsProtocol.readPacket(source, 1024)
        assertEquals(0xffffffffL, result.pts90k)
        assertArrayEquals(bytes, result.data)
    }

    @Test(expected = IOException::class) fun oversizedUnsignedLengthIsRejectedBeforeAllocation() {
        PbsProtocol.readPacket(ByteArrayInputStream(packet(-1, 0)), 1024)
    }
    @Test(expected = IOException::class) fun zeroLengthIsNotAcceptedAsVideo() {
        PbsProtocol.readPacket(ByteArrayInputStream(packet(0, 0)), 1024)
    }
    @Test(expected = EOFException::class) fun truncatedHeaderFails() {
        PbsProtocol.readPacket(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 1024)
    }
    @Test(expected = EOFException::class) fun truncatedPayloadFails() {
        PbsProtocol.readPacket(ByteArrayInputStream(packet(10, 0, byteArrayOf(1, 2))), 1024)
    }
    @Test fun decoderHeaderAddsAnnexBPrefix() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x28), PbsProtocol.nalFromHex("67640028", 7))
    }
    @Test(expected = IOException::class) fun malformedHeaderFails() { PbsProtocol.nalFromHex("67xx", 7) }
    @Test(expected = IOException::class) fun wrongNalTypeFails() { PbsProtocol.nalFromHex("6864", 7) }
    @Test fun clockConverts90kHzAndHandlesWrap() {
        val ordinary = PbsProtocol.VideoClock()
        assertEquals(0, ordinary.timeUs(1234))
        assertEquals(1000000, ordinary.timeUs(91234))
        val wrapping = PbsProtocol.VideoClock()
        assertEquals(0, wrapping.timeUs(0xfffffff0))
        assertEquals(533, wrapping.timeUs(0x20))
    }
    @Test fun sticksPreserveEndpointsAndNeutral() {
        assertEquals(0, PbsProtocol.axis(Short.MIN_VALUE))
        assertEquals(128, PbsProtocol.axis(0))
        assertEquals(255, PbsProtocol.axis(Short.MAX_VALUE))
    }
    @Test fun buttonsAreTranslatedFromChiakiToNativePadBits() {
        val state = ControllerState(buttons = ControllerState.BUTTON_CROSS or ControllerState.BUTTON_SHARE or
            ControllerState.BUTTON_PS or ControllerState.BUTTON_TOUCHPAD or ControllerState.BUTTON_DPAD_LEFT,
            l2State = 255U, r2State = 64U, leftX = Short.MIN_VALUE, rightY = Short.MAX_VALUE)
        val result = PbsProtocol.controller(state)
        assertEquals(0x4000 or 0x1 or 0x10000 or 0x100000 or 0x80 or 0x100, result.buttons)
        assertEquals(0, result.lx); assertEquals(255, result.ry)
        assertEquals(255, result.l2); assertEquals(64, result.r2)
    }
    @Test fun heartbeatSequenceWrapsWithoutSignedCsvValues() {
        assertEquals("4294967295,0,128,128,128,128,0,0", PbsProtocol.PadState().encode(-1))
        assertEquals("0,0,128,128,128,128,0,0", PbsProtocol.PadState().encode(0x100000000))
    }

    @Test fun nativePsAndCreateAreDistinctAndMatchScePadInsertData() {
        assertEquals(0x10000, PbsProtocol.controller(ControllerState(buttons = ControllerState.BUTTON_PS)).buttons)
        assertEquals(0x1, PbsProtocol.controller(ControllerState(buttons = ControllerState.BUTTON_SHARE)).buttons)
        assertEquals(0, PbsProtocol.LEGACY_BUTTON_MASK and PbsProtocol.Button.CREATE.mask)
        assertNotEquals(0, PbsProtocol.LEGACY_BUTTON_MASK and PbsProtocol.Button.PS.mask)
    }
}
