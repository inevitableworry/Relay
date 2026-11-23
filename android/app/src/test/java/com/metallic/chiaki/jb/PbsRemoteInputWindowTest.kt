// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class PbsRemoteInputWindowTest {
    @Test fun windowBoundsUnacknowledgedStatesRatherThanTcpBytes() {
        val window = PbsRemoteInputWindow()
        repeat(32) { window.sent(it.toLong(), it * 32L) }
        assertFalse(window.hasCapacity())
        assertThrows(IllegalArgumentException::class.java) { window.sent(32, 1024) }
        assertTrue(window.acknowledge(0))
        assertTrue(window.hasCapacity())
    }
    @Test fun wrongOrDuplicateAckCannotMaskAnOutstandingStall() {
        val window = PbsRemoteInputWindow()
        window.sent(10, 1000); window.sent(11, 1032)
        assertFalse(window.acknowledge(11))
        assertFalse(window.acknowledge(999))
        assertTrue(window.expired(7000))
        assertTrue(window.acknowledge(10))
        assertFalse(window.acknowledge(10))
        assertFalse(window.expired(7000))
        assertTrue(window.expired(7032))
    }
    @Test fun transientTwoSecondJitterDoesNotTerminateTheWindow() {
        val window = PbsRemoteInputWindow()
        window.sent(1, 0)
        assertFalse(window.expired(2100))
        assertFalse(window.expired(5999))
        assertTrue(window.expired(6000))
    }
    @Test fun sequenceWrapAndLifecycleClearCannotReplayOldInput() {
        val window = PbsRemoteInputWindow()
        window.sent(0xffffffffL, 0); window.sent(0, 1)
        assertTrue(window.acknowledge(0xffffffffL)); assertTrue(window.acknowledge(0))
        assertFalse(window.expired(99999))
        window.sent(1, 10); window.clear()
        assertFalse(window.acknowledge(1)); assertTrue(window.hasCapacity())
        assertFalse(window.expired(99999))
    }
}
