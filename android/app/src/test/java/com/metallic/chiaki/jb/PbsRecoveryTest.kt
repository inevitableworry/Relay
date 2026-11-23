// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLHandshakeException
import okhttp3.internal.http2.StreamResetException
import okhttp3.internal.http2.ConnectionShutdownException
import okhttp3.internal.http2.ErrorCode

class PbsRecoveryTest {
    @Test fun http2ResetAndShutdownAtNetworkReadAreRecoverableButMalformedFramingIsNot() {
        for(error in listOf(StreamResetException(ErrorCode.CANCEL), ConnectionShutdownException())) {
            val source = object: InputStream() { override fun read(): Int = throw error }
            val stream = PbsTransportInputStream(source, PbsFailureStage.VIDEO)
            val failure = assertThrows(PbsConnectionException::class.java) { stream.read() }
            assertEquals(PbsFailureStage.VIDEO, failure.stage)
        }
        val invalidHeader = ByteBuffer.allocate(8).putInt(0).putInt(0).array()
        val malformed = assertThrows(IOException::class.java) {
            PbsProtocol.readPacket(PbsTransportInputStream(ByteArrayInputStream(invalidHeader), PbsFailureStage.VIDEO), 1024)
        }
        assertNull(PbsFailures.connection(malformed, PbsFailureStage.VIDEO))
        val tls = SSLHandshakeException("certificate rejected")
        assertSame(tls, PbsFailures.atIoBoundary(tls, PbsFailureStage.CONNECT))
        assertNull(PbsFailures.connection(tls, PbsFailureStage.CONNECT))
    }
    @Test fun cancelledIntermediateRestartStillWaitsForOldestCleanup() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val secondWaiting = CompletableDeferred<Unit>()
        var thirdAcquired = false
        val first = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() } }
        }
        yield(); first.cancel(); cleanupStarted.await()
        val second = launch { secondWaiting.complete(Unit); awaitPreviousPbsRun(first); fail("Cancelled restart acquired ownership") }
        secondWaiting.await(); yield(); second.cancel()
        val third = launch { awaitPreviousPbsRun(second); thirdAcquired = true }
        try {
            yield()
            assertFalse(thirdAcquired)
            assertFalse(second.isCompleted)
        } finally { releaseCleanup.complete(Unit) }
        withTimeout(2000) { third.join() }
        assertTrue(first.isCompleted); assertTrue(second.isCompleted); assertTrue(thirdAcquired)
    }
    @Test fun interruptionHasFourRetriesAndOneDeadline() {
        val policy = PbsRecoveryPolicy()
        val retries = (0..3).map { policy.next(1000L + it * 1000)!! }
        assertEquals(listOf(500L, 1500L, 3000L, 6000L), retries.map { it.delayMs })
        assertEquals(listOf(1, 2, 3, 4), retries.map { it.attempt })
        assertTrue(retries.all { it.deadlineMs == 46000L })
        assertEquals(46000L, policy.deadlineMs())
        assertNull(policy.next(6000))
        policy.reset()
        assertEquals(Long.MAX_VALUE, policy.deadlineMs())
    }
    @Test fun elapsedRecoveryDeadlinePreventsAnewAttempt() {
        val policy = PbsRecoveryPolicy()
        policy.next(1000)
        assertNull(policy.next(45000))
    }
    @Test fun briefFirstFrameDoesNotGrantAnUnlimitedRetryBudget() {
        val policy = PbsRecoveryPolicy()
        repeat(4) { assertNotNull(policy.next(1000L + it * 1000, playedForMs = 500)) }
        assertNull(policy.next(6000, playedForMs = 1000))
        val recovered = policy.next(40000, playedForMs = 30000)!!
        assertEquals(1, recovered.attempt)
        assertEquals(85000L, recovered.deadlineMs)
    }
    @Test fun onlyConcreteTransportFailuresAreRetryable() {
        assertEquals("timeout", PbsFailures.connection(SocketTimeoutException(), PbsFailureStage.VIDEO)?.reason)
        assertEquals("stream_eof", PbsFailures.connection(EOFException(), PbsFailureStage.AUDIO)?.reason)
        assertNotNull(PbsFailures.connection(PbsHttpException(503, "unavailable"), PbsFailureStage.CONNECT))
        for(status in listOf(400, 401, 403, 409, 422, 501))
            assertNull(PbsFailures.connection(PbsHttpException(status, "rejected"), PbsFailureStage.CONTROLLER))
        assertNull(PbsFailures.connection(PbsOwnershipException("ambiguous acquisition", SocketTimeoutException()), PbsFailureStage.CONTROLLER))
        assertNull(PbsFailures.connection(PbsProfileException("decoder failed"), PbsFailureStage.VIDEO))
        assertNull(PbsFailures.connection(IOException("invalid packet"), PbsFailureStage.VIDEO))
    }
}
