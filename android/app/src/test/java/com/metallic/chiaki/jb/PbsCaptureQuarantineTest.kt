// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class PbsCaptureQuarantineTest {
    private class MemoryStore: PbsCaptureQuarantine.Store {
        val values = mutableMapOf<String, String>()
        var writesSucceed = true
        override fun read(key: String) = values[key]
        override fun commit(changes: Map<String, String?>): Boolean {
            if(!writesSucceed) return false
            changes.forEach { (key, value) -> if(value == null) values.remove(key) else values[key] = value }
            return true
        }
    }
    private var next = 0
    private fun journal(store: MemoryStore) = PbsCaptureQuarantine(store, { 1000L }, { "attempt_${++next}" })
    private val lan = "http://192.168.20.10:9999"
    private val remote = "https://relay.example.test"

    @Test fun firstLoadIsAllowedButItsUnfinishedStateSurvivesANewClient() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan)
        endpoint.requirePayloadLoadAllowed()
        val id = endpoint.beforePayloadLoad()
        endpoint.requirePayloadAttempt(id)
        assertFalse(endpoint.requiresFrameVerification())
        val restored = journal(store).endpoint(lan)
        assertEquals(id, restored.currentAttemptId())
        assertTrue(restored.requiresFrameVerification())
        assertThrows(PbsCaptureQuarantinedException::class.java) { restored.requirePayloadLoadAllowed() }
        assertThrows(PbsCaptureQuarantinedException::class.java) { restored.beforeProfileMutation(1080) }
        assertThrows(PbsCaptureQuarantinedException::class.java) { restored.requirePayloadAttempt(id) }
    }

    @Test fun anAlreadyRunningServiceCanCancelOnlyAnUnsentLoadReservation() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan)
        val ownAttempt = endpoint.beforePayloadLoad()
        endpoint.requirePayloadAttempt(ownAttempt)
        endpoint.abandonPayloadBeforeTransfer(ownAttempt)
        assertFalse(endpoint.snapshot().blocked)
        assertThrows(PbsCaptureQuarantinedException::class.java) {
            endpoint.abandonPayloadBeforeTransfer(ownAttempt)
        }
        endpoint.requirePayloadLoadAllowed()
        val nextAttempt = endpoint.beforePayloadLoad()
        assertNotEquals(ownAttempt, nextAttempt)
    }

    @Test fun aProfileMutationOrAnOlderClientCannotAbandonAnUnverifiedPayloadLoad() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan)
        val attempt = endpoint.beforePayloadLoad()
        val restored = journal(store).endpoint(lan)
        assertThrows(PbsCaptureQuarantinedException::class.java) {
            restored.abandonPayloadBeforeTransfer(attempt)
        }
        endpoint.beforeProfileMutation(1080)
        assertThrows(PbsCaptureQuarantinedException::class.java) {
            endpoint.abandonPayloadBeforeTransfer(attempt)
        }
        assertTrue(endpoint.snapshot().blocked)
    }

    @Test fun onlyCurrentStartupMayPrepareItsFirstProfileAndItStillNeedsDecodedFrames() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan)
        endpoint.beforePayloadLoad()
        val profile = endpoint.beforeProfileMutation(1080)
        assertTrue(endpoint.requiresFrameVerification())
        assertFalse(endpoint.rejectedBeforeMutation(profile))
        assertFalse(endpoint.confirmedDecodedFrame(profile, 0, 1080))
        assertTrue(endpoint.confirmedDecodedFrame(profile, 1920, 1080))
        assertFalse(endpoint.requiresFrameVerification())
        endpoint.requirePayloadLoadAllowed()
    }

    @Test fun associatedRoutesBlockTogetherWhileOtherConsolesRemainIndependent() {
        val store = MemoryStore()
        val journal = journal(store)
        val endpoint = journal.endpoint(lan, listOf(remote))
        endpoint.beforeProfileMutation(2160)
        assertThrows(PbsCaptureQuarantinedException::class.java) { journal.endpoint(remote).requirePayloadLoadAllowed() }
        assertThrows(PbsCaptureQuarantinedException::class.java) { journal.endpoint(lan).requirePayloadLoadAllowed() }
        journal.endpoint("http://192.168.20.11:9999").requirePayloadLoadAllowed()
        assertEquals(2, store.values.size)
    }

    @Test fun staleOrWrongResolutionFramesCannotEraseAProfileRisk() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan, listOf(remote))
        val id = endpoint.beforeProfileMutation(2160)
        assertFalse(endpoint.confirmedDecodedFrame("older_attempt", 3840, 2160))
        assertFalse(endpoint.confirmedDecodedFrame(id, 1280, 720))
        assertFalse(endpoint.confirmedDecodedFrame(id, 1920, 2160))
        assertThrows(PbsCaptureQuarantinedException::class.java) { endpoint.beforeProfileMutation(1080) }
        assertTrue(endpoint.confirmedDecodedFrame(id, 3840, 2160))
        assertTrue(store.values.isEmpty())
    }

    @Test fun genericUnverifiedStreamCanRecoverWithRealFramesButKeepsSpecificFailureEvidence() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan)
        val lost = endpoint.markStreamStateUnverified()
        assertFalse(endpoint.confirmedDecodedFrame(null, 1920, 1080))
        assertTrue(endpoint.confirmedDecodedFrame(lost, 1920, 1080))
        val profile = endpoint.beforeProfileMutation(2160)
        assertEquals(profile, endpoint.markStreamStateUnverified())
        assertEquals(listOf(2160), endpoint.snapshot().requestedHeights)
    }

    @Test fun explicitNoMutationRefusalOnlyClearsTheMatchingNewProfile() {
        val endpoint = journal(MemoryStore()).endpoint(lan)
        val id = endpoint.beforeProfileMutation(720)
        assertFalse(endpoint.rejectedBeforeMutation("old"))
        assertTrue(endpoint.rejectedBeforeMutation(id))
        assertFalse(endpoint.snapshot().blocked)
    }

    @Test fun durableWriteFailurePreventsStartingAnOperationAndClearFailureKeepsTheGuard() {
        val store = MemoryStore()
        val endpoint = journal(store).endpoint(lan)
        store.writesSucceed = false
        assertThrows(IOException::class.java) { endpoint.beforeProfileMutation(1080) }
        assertTrue(store.values.isEmpty())
        store.writesSucceed = true
        val id = endpoint.beforeProfileMutation(1080)
        store.writesSucceed = false
        assertThrows(IOException::class.java) { endpoint.confirmedDecodedFrame(id, 1920, 1080) }
        assertTrue(endpoint.snapshot().blocked)
    }

    @Test fun resetAcknowledgmentCannotEraseANewerOperation() {
        val endpoint = journal(MemoryStore()).endpoint(lan)
        val first = endpoint.beforeProfileMutation(720)
        val oldDialog = endpoint.snapshot()
        assertTrue(endpoint.confirmedDecodedFrame(first, 1280, 720))
        endpoint.beforeProfileMutation(2160)
        assertThrows(IOException::class.java) { endpoint.acknowledgeConsoleReset(oldDialog) }
        endpoint.acknowledgeConsoleReset(endpoint.snapshot())
        endpoint.requirePayloadLoadAllowed()
    }

    @Test fun malformedJournalFailsClosedUntilAnExplicitResetAcknowledgment() {
        val store = MemoryStore()
        store.values[PbsCaptureQuarantine.preferenceKey(lan)] = "unexpected data"
        val endpoint = journal(store).endpoint(lan)
        assertTrue(endpoint.snapshot().hasUnreadableRecord)
        assertNull(endpoint.currentAttemptId())
        assertFalse(endpoint.confirmedDecodedFrame("attempt_1", 1920, 1080))
        assertThrows(PbsCaptureQuarantinedException::class.java) { endpoint.requirePayloadLoadAllowed() }
        endpoint.acknowledgeConsoleReset(endpoint.snapshot())
        endpoint.requirePayloadLoadAllowed()
    }

    @Test fun originNormalizationDoesNotPermitCredentialPathOrDefaultPortBypasses() {
        assertEquals(remote, PbsCaptureQuarantine.normalizeOrigin("HTTPS://RELAY.EXAMPLE.TEST.:443/"))
        assertEquals(PbsCaptureQuarantine.preferenceKey(remote), PbsCaptureQuarantine.preferenceKey("https://RELAY.example.test:443/"))
        for(origin in listOf("https://user:secret@relay.example.test", "$remote/frames", "$remote?token=secret", "$remote#fragment", "file:///tmp/capture"))
            assertThrows(IllegalArgumentException::class.java) { PbsCaptureQuarantine.normalizeOrigin(origin) }
    }

    @Test fun bootstrapRecognizesWrappedExplicitRefusalWithoutTreatingTimeoutAsAbsence() {
        assertTrue(PbsCaptureQuarantine.explicitlyRefused(IOException("wrapper", ConnectException("ECONNREFUSED (Connection refused)"))))
        assertFalse(PbsCaptureQuarantine.explicitlyRefused(ConnectException("Connection timed out")))
        assertFalse(PbsCaptureQuarantine.explicitlyRefused(SocketTimeoutException("timeout")))
        assertFalse(PbsCaptureQuarantine.explicitlyRefused(IOException("connection refused")))
    }
}
