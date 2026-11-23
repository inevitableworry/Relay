// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A failure must never offer an automatic payload retry when capture state is unverified. */
internal object PbsRecoveryPrompt {
    fun needsConsoleRestartAcknowledgment(message:String):Boolean =
        message.contains("automatic loading is paused",ignoreCase=true) &&
        message.contains("Capture Recovery",ignoreCase=true)
}

enum class PbsFailureStage { CONNECT, VIDEO, AUDIO, CONTROLLER, STATUS, CLEANUP }

class PbsConnectionException(val stage: PbsFailureStage, val reason: String,
                             message: String, cause: Throwable? = null): IOException(message, cause)
class PbsOwnershipException(message: String, cause: Throwable? = null): IOException(message, cause)

/** A cancelled intermediate restart must still carry the oldest run's cleanup barrier. */
internal suspend fun awaitPreviousPbsRun(previous: Job?) {
    withContext(NonCancellable) { previous?.join() }
    currentCoroutineContext().ensureActive()
}

/** Only established transport failures may reacquire a controller. Never retry protocol/auth errors. */
internal object PbsFailures {
    /** Call only around socket/HTTP I/O, never around protocol parsing or decoder work. */
    fun atIoBoundary(error: IOException, stage: PbsFailureStage): IOException {
        if(error is SSLException || error is PbsHttpException || error is PbsOwnershipException) return error
        return connection(error, stage) ?: PbsConnectionException(stage, "transport_io",
            "The connection to your PS5 was interrupted.", error)
    }
    fun connection(error: Exception, stage: PbsFailureStage): PbsConnectionException? {
        if (error is PbsOwnershipException || error is PbsProfileException) return null
        if (error is PbsHttpException) return if (error.statusCode in setOf(408, 429, 500, 502, 503, 504))
            PbsConnectionException(stage, "http_${error.statusCode}", "The connection to your PS5 was interrupted.", error) else null
        if (error is PbsConnectionException) return error
        val reason = when (error) {
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns_unavailable"
            is EOFException -> "stream_eof"
            is SocketException -> "socket_lost"
            else -> return null
        }
        return PbsConnectionException(stage, reason, "The connection to your PS5 was interrupted.", error)
    }

    fun reason(error: Exception): String = when (error) {
        is PbsConnectionException -> error.reason
        is PbsHttpException -> "http_${error.statusCode}"
        is PbsOwnershipException -> "ownership_unverified"
        is PbsProfileException -> "profile_unavailable"
        else -> connection(error, PbsFailureStage.CONNECT)?.reason ?: "protocol_or_device_error"
    }
}

/** A short interruption has four retries; only 30 seconds of actual playback resets that budget. */
internal class PbsRecoveryPolicy {
    data class Retry(val attempt: Int, val delayMs: Long, val deadlineMs: Long)
    private var deadline = 0L
    private var attempts = 0
    fun reset() { deadline = 0; attempts = 0 }
    fun deadlineMs(): Long = if(deadline == 0L) Long.MAX_VALUE else deadline
    fun next(nowMs: Long, playedForMs: Long = 0): Retry? {
        if (playedForMs >= 30_000) reset()
        if (deadline == 0L) deadline = nowMs + 45_000
        val delay = DELAYS.getOrNull(attempts) ?: return null
        if (nowMs + delay >= deadline) return null
        return Retry(++attempts, delay, deadline)
    }
    companion object { private val DELAYS = longArrayOf(500, 1500, 3000, 6000) }
}
