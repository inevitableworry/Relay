// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps controller updates ordered without one cellular round trip per update.
 * The authenticated gateway acknowledges each native acceptance and enforces
 * minimum digital hold time before it forwards a subsequent state.
 */
class PbsRemoteController(
    private val baseUrl: String,
    private val bearerToken: String,
    private val controllerToken: String
) : Closeable {
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null
    private val outstanding = PbsRemoteInputWindow()
    private val acknowledgements = Channel<Unit>(Channel.CONFLATED)

    suspend fun open() {
        check(socket == null && !closed)
        require(baseUrl.startsWith("https://"))
        require(controllerToken.matches(Regex("[0-9a-fA-F]{32}")))
        val request = Request.Builder()
            .url(baseUrl.replaceFirst("https://", "wss://") + "/relay/input")
            .header("Authorization", "Bearer " + bearerToken)
            .header("X-PBS-Controller", controllerToken)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if(closed) { webSocket.cancel(); return }
                ready.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val sequence = if(text.startsWith("ok,") && text.length <= 32) text.substring(3).toLongOrNull() else null
                if(sequence != null && outstanding.acknowledge(sequence)) acknowledgements.trySend(Unit)
                else fail(IOException("The Relay gateway returned an invalid controller acknowledgement."))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if(!closed) fail(if(response?.code == 401 || response?.code == 403)
                    PbsHttpException(response.code, "The tunnel access key was rejected.")
                else PbsFailures.atIoBoundary(t as? IOException ?: IOException(t), PbsFailureStage.CONTROLLER))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if(!closed) fail(PbsConnectionException(PbsFailureStage.CONTROLLER, "websocket_closed", "The tunnel controller connection closed."))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if(!closed) fail(PbsConnectionException(PbsFailureStage.CONTROLLER, "websocket_closed", "The tunnel controller connection closed."))
            }
        })
        try { withTimeout(7000L) { ready.await() } }
        catch(error: TimeoutCancellationException) {
            close()
            throw PbsConnectionException(PbsFailureStage.CONTROLLER, "websocket_timeout", "The tunnel controller connection timed out.", error)
        }
        catch(error: Exception) { close(); throw error }
    }

    private fun fail(error: IOException) {
        if(failure != null || closed) return
        failure = error
        ready.completeExceptionally(error)
        acknowledgements.trySend(Unit)
        socket?.cancel()
    }

    /** Wait before sampling current input, so backpressure never saves an old press for later. */
    suspend fun awaitWritable() {
        while(true) {
            checkProgress()
            if(outstanding.hasCapacity()) return
            withTimeoutOrNull(100L) { acknowledgements.receive() }
        }
    }

    private fun checkProgress() {
        if(closed) throw IOException("The tunnel controller connection is closed.")
        failure?.let { throw it }
        if(outstanding.expired(SystemClock.elapsedRealtime())) {
            val error = PbsConnectionException(PbsFailureStage.CONTROLLER, "ack_timeout", "Controller updates stopped reaching the PS5.")
            fail(error); throw error
        }
    }

    fun send(body: String) {
        checkProgress()
        require(body.length in 15..256)
        val current = socket ?: throw IOException("The tunnel controller connection is not ready.")
        val now = SystemClock.elapsedRealtime()
        // The 32-state acknowledgement window bounds both TCP backlog and native work.
        if(!outstanding.hasCapacity() || current.queueSize() > 8192L) {
            val error = PbsConnectionException(PbsFailureStage.CONTROLLER, "input_backpressure", "The controller connection is congested.")
            fail(error)
            throw error
        }
        val sequence = body.substringBefore(',').toLongOrNull() ?: throw IOException("Invalid controller sequence")
        outstanding.sent(sequence, now)
        if(!current.send(body)) {
            val error = PbsConnectionException(PbsFailureStage.CONTROLLER, "websocket_send_failed", "The tunnel could not send a controller update.")
            fail(error)
            throw error
        }
    }

    override fun close() {
        if(closed) return
        closed = true
        ready.cancel()
        outstanding.clear()
        acknowledgements.trySend(Unit)
        // Discard queued presses immediately; the gateway neutralizes this owner on socket loss.
        socket?.cancel()
        socket = null
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

