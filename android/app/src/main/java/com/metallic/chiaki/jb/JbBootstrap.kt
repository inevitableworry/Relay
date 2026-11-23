// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.content.Context
import com.metallic.chiaki.common.PortalCaptureQuarantine
import com.metallic.chiaki.common.PortalPreferences
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Launches only the pinned, locally bundled service, then verifies its actual HTTP protocol. */
object JbBootstrap {
    suspend fun ensureService(context:Context,prefs:PortalPreferences,progress:(String)->Unit,
        captureGuard:PbsCaptureQuarantine.Endpoint?=null):PbsClient =
        PbsPayloadSingleFlight.withExclusiveStart {
            ensureServiceExclusive(context,prefs,progress,captureGuard)
        }

    private suspend fun ensureServiceExclusive(context:Context,prefs:PortalPreferences,progress:(String)->Unit,
        captureGuard:PbsCaptureQuarantine.Endpoint?):PbsClient {
        // One attempt always targets the same console, even if settings change while suspended.
        val host=prefs.jbHost
        val capturePort=prefs.jbPort
        val loaderPort=prefs.loaderPort
        val automatic=prefs.autoPayload
        val guard=captureGuard ?: PortalCaptureQuarantine(context).endpoint(PbsCaptureQuarantine.lanOrigin(host,capturePort))
        val client=PbsClient(host,capturePort).apply {
            this.captureGuard=guard
            captureAttemptId=guard.currentAttemptId()
        }
        try {
            client.config()
            progress("PS5 streaming payload is already running; connecting")
            return client
        } catch(error:Exception) {
            if(error is CancellationException) {client.close();throw error}
            // An answering HTTP server is not permission to inject another payload over it.
            // A timeout may be an existing capture owner. Never inject a second copy on that evidence.
            if(guard.snapshot().blocked) {
                client.close()
                throw PbsCaptureQuarantinedException()
            }
            val absent=PbsCaptureQuarantine.explicitlyRefused(error)
            if(!automatic) {
                client.close()
                throw IOException("Remote Play JB cannot reach the PS5 streaming service at " +
                    "$host:$capturePort. Automatic payload loading is disabled. " +
                    "Verify that the PS5 and payload loader are running, then enable " +
                    "Send Payload Automatically in Settings once Capture Recovery is clear.",error)
            }
            if(!absent) {
                client.close()
                throw IOException("The PS5 streaming service at $host:$capturePort did not answer. " +
                    "Relay cannot safely reload another payload without a definite " +
                    "connection-refused response. Check the PS5 service and network.",error)
            }
        }
        try {
            guard.requirePayloadLoadAllowed()
            progress("Starting the PS5 streaming service")
            val bytes=withContext(Dispatchers.IO) {
                val manifest=context.assets.open("jb/payload.json").bufferedReader().use {JSONObject(it.readText())}
                require(capturePort==manifest.getInt("defaultCapturePort")) {
                    "The bundled payload listens on port ${manifest.getInt("defaultCapturePort")}. Use that streaming port for automatic loading, or start your custom service separately."
                }
                val data=context.assets.open("jb/pbs-ps5.elf").use{it.readBytes()}
                require(data.size in 64..(8*1024*1024)) {"Bundled payload has an invalid size"}
                require(data[0]==0x7f.toByte() && data[1]=='E'.code.toByte() &&
                    data[2]=='L'.code.toByte() && data[3]=='F'.code.toByte()) {"Bundled payload is not an ELF"}
                val sha=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {"%02x".format(it)}
                require(sha.equals(manifest.getString("sha256"),true)) {"Bundled payload integrity check failed"}
                data
            }
            // Re-check *after* reading/hashing the ELF. Another client may have
            // started the same PS5 service during that work. A timeout, unknown
            // server or HTTP error does not authorize an upload.
            progress("Checking whether the PS5 payload has already started")
            if (existingServiceOrConfirmedRefusal(client)) {
                progress("PS5 streaming payload is already running; connecting")
                return client
            }
            requireNoResidentPayload(host)
            currentCoroutineContext().ensureActive()
            val attempt=guard.beforePayloadLoad()
            client.captureAttemptId=attempt
            guard.requirePayloadAttempt(attempt)
            // One last fresh HTTP probe after the durable journal, but BEFORE
            // opening the loader socket. If service appeared, release only
            // this own, unsent reservation and reuse the running payload.
            val alreadyRunning = try {
                val running = existingServiceOrConfirmedRefusal(client)
                if (!running) requireNoResidentPayload(host)
                running
            } catch(error:Exception) {
                // This final probe was before opening the loader socket, so
                // no ELF bytes were sent. Leave no stale load reservation if
                // it was cancelled or the PS5 status became ambiguous.
                guard.abandonPayloadBeforeTransfer(attempt)
                client.captureAttemptId=null
                throw error
            }
            if (alreadyRunning) {
                guard.abandonPayloadBeforeTransfer(attempt)
                client.captureAttemptId=null
                progress("PS5 streaming payload is already running; connecting")
                return client
            }
            progress("Loading PS5 streaming payload once")
            send(bytes,host,loaderPort,guard,attempt)
            progress("Waiting for video, audio and controller services")
            var last:Exception?=null
            repeat(20) {
                currentCoroutineContext().ensureActive()
                delay(500)
                try {client.config();return client}
                catch(e:CancellationException) {throw e}
                catch(e:Exception) {last=e}
            }
            throw IOException("The payload was sent, but the PS5 did not start a compatible streaming service. Check the console's firmware support and loader. "+(last?.message ?: ""))
        } catch(error:Exception) {client.close();throw error}
    }

    /** True only for a compatible service. False only for an explicit refusal. */
    private suspend fun existingServiceOrConfirmedRefusal(client:PbsClient):Boolean {
        try {
            client.config()
            return true
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:Exception) {
            if(PbsCaptureQuarantine.explicitlyRefused(error)) return false
            throw IOException("The PS5 streaming service state is uncertain. " +
                "Relay will not send a second payload without a confirmed refusal.", error)
        }
    }
    private suspend fun requireNoResidentPayload(host:String) {
        if(PbsResidentPayloadProbe.check(host) == PbsResidentPayloadProbe.Result.PRESENT)
            throw IOException("The PS5 still has a payload.elf process running, but its PBS " +
                "streaming service is unavailable. Relay will not upload a second ELF " +
                "over the resident process. Restart the PS5 or verify its native cleanup.")
    }
    private suspend fun send(data:ByteArray,host:String,port:Int,
        guard:PbsCaptureQuarantine.Endpoint,attempt:String)=coroutineScope {
        val socket=Socket()
        val transfer=async(Dispatchers.IO) {
            socket.connect(InetSocketAddress(host,port),3500)
            socket.tcpNoDelay=true
            // The connect may finish after cancellation or a changed recovery acknowledgment.
            currentCoroutineContext().ensureActive()
            guard.requirePayloadAttempt(attempt)
            socket.getOutputStream().write(data)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
        }
        try {withTimeout(10000) {transfer.await()}}
        catch(error:TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("The PS5 loader did not finish receiving the payload within ten seconds",error)
        }
        finally {
            // Closing here unblocks connect/write on cancellation as well as on the deadline.
            runCatching {socket.close()}
            transfer.cancel()
        }
    }
}
