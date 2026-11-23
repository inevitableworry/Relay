// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections

data class PbsAudioFormat(val codec: String, val channels: Int, val rate: Int, val bits: Int) {
    val bitrateBps: Long get() = channels.toLong() * rate * bits
}
data class PbsRateControl(val minBps: Int, val maxBps: Int, val minQp: Int, val maxQp: Int,
                          val bitrateBps: Int, val requestedBitrateBps: Int,
                          val activeMinQp: Int, val requestedMinQp: Int)
data class PbsProfileRequest(val height: Int, val videoBitrateBps: Int? = null, val minQp: Int? = null)
data class PbsConfig(val platform: String, val width: Int, val height: Int, val fps: Int,
                     val codec: String, val sps: String, val pps: String, val audio: PbsAudioFormat?,
                     val resolutions: List<Int>, val requestedHeight: Int, val profileChanging: Boolean,
                     val profileError: Boolean, val profileErrorHeight: Int,
                     val rateControl: PbsRateControl? = null,
                     val recoveryVerificationOnly: Boolean = false) {
    val ready: Boolean get() = width > 0 && height > 0 && sps.isNotEmpty() && pps.isNotEmpty() && !profileChanging
    // The upstream interface has no motion, touch-coordinate, feedback or native-gallery API.
    val supportsNativeRecording: Boolean get() = false
    val supportsNativeGallery: Boolean get() = false
    val supportsMotion: Boolean get() = false
    val supportsTouchCoordinates: Boolean get() = false
    val supportsFeedback: Boolean get() = false
}
data class PbsStatus(val width: Int, val height: Int, val frames: Long, val bytes: Long,
                     val fps: Double, val kbps: Double, val recording: Boolean,
                     val recordingError: Boolean, val audioBytes: Long)
data class PbsControllerStatus(val available: Boolean, val connected: Boolean, val cleanupRequired: Boolean,
                               val lastRc: Int, val raw: String,
                               val allowedButtons: Int = PbsProtocol.LEGACY_BUTTON_MASK and PbsProtocol.NATIVE_BUTTON_MASK)
data class PbsPlayerSlot(val player: Int, val connected: Boolean, val cleanupRequired: Boolean,
                         val promptSent: Boolean, val accountName: String? = null,
                         val avatarUserId: Int? = null)
data class PbsPartyStatus(val slots: List<PbsPlayerSlot>) {
    val connectedCount: Int get() = slots.count { it.connected }
}
data class PbsConsoleUser(val userId: Int, val name: String, val hasAvatar: Boolean)
class PbsHttpException(val statusCode: Int, message: String): IOException(message)

/** LAN HTTP transport; never follows redirects to another host or sends Traditional credentials. */
class PbsClient(val host: String, val port: Int = 9999,
                baseUrl: String? = null, private val bearerToken: String? = null): Closeable {
    var captureGuard: PbsCaptureQuarantine.Endpoint? = null
    var captureAttemptId: String? = null
    val captureRecoveryBlocked: Boolean get() = captureGuard?.snapshot()?.blocked == true
    private val requests = Collections.synchronizedSet(mutableSetOf<HttpURLConnection>())
    @Volatile private var closed = false
    @Volatile private var remoteController: PbsRemoteController? = null
    @Volatile private var ownedControllerToken: String? = null
    @Volatile private var acquisitionUncertain = false
    @Volatile var playerSlot = 0
        private set
    @Volatile private var partyOwner = false
    private val remoteOrigin = baseUrl?.let {
        val uri = runCatching { URI(it.trim()) }.getOrElse { throw IllegalArgumentException("Enter a valid HTTPS remote address") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in listOf("", "/") &&
            (uri.port == -1 || uri.port in 1..65535)) { "Remote streaming requires an HTTPS origin without a path or sign-in information" }
        require(!bearerToken.isNullOrBlank() && bearerToken.length <= 4096 &&
            bearerToken.all { ch -> ch.code in 33..126 }) { "Enter a valid remote access token" }
        uri.toASCIIString().trimEnd('/')
    }
    val isRemote: Boolean get() = remoteOrigin != null

    init {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/?#@\\" }) { "Enter a PS5 IP address or hostname" }
        require(port in 1..65535) { "Invalid PS5 streaming port" }
    }

    suspend fun config(): PbsConfig = withContext(Dispatchers.IO) {
        val j = json("GET", "/config.json")
        val platform = j.optString("platform")
        if (platform != "PS5") throw IOException("This endpoint is not a compatible PS5 PBS capture service")
        val width = j.optInt("width")
        val height = j.optInt("height")
        if (width !in 0..8192 || height !in 0..4320) throw IOException("Invalid PS5 video dimensions")
        val resolutionArray = j.optJSONArray("resolutions")
        val resolutions = (0 until (resolutionArray?.length() ?: 0)).map { resolutionArray!!.getInt(it) }
            .filter { it in listOf(720, 1080, 1440, 2160) }.distinct().sorted()
        val a = j.optJSONObject("audio")
        val audio = a?.let { PbsAudioFormat(it.optString("codec"), it.optInt("channels"), it.optInt("rate"), it.optInt("bits")) }
        val rate = j.optJSONObject("rate_control")?.takeIf { it.optInt("version") == 1 }?.let {
            PbsRateControl(it.optInt("min_bps"), it.optInt("max_bps"), it.optInt("min_qp"), it.optInt("max_qp"),
                it.optInt("bitrate_bps"), it.optInt("requested_bitrate_bps"), it.optInt("min_qp_value"), it.optInt("requested_min_qp"))
        }?.also {
            if (it.minBps !in 1_000_000..100_000_000 || it.maxBps !in it.minBps..100_000_000 ||
                it.minQp !in 18..35 || it.maxQp !in it.minQp..35 ||
                it.bitrateBps !in it.minBps..it.maxBps || it.requestedBitrateBps !in it.minBps..it.maxBps ||
                it.activeMinQp !in it.minQp..it.maxQp || it.requestedMinQp !in it.minQp..it.maxQp)
                throw IOException("The PS5 advertised invalid encoder controls")
        }
        PbsConfig(platform, width, height, j.optInt("fps", 60).coerceIn(1, 120), j.optString("codec"),
            j.optString("sps"), j.optString("pps"), audio, resolutions,
            j.optInt("requested_height", height), j.optBoolean("profile_changing"),
            j.optBoolean("profile_error"), j.optInt("profile_error_height"), rate)
    }

    suspend fun status(): PbsStatus = withContext(Dispatchers.IO) {
        val j = json("GET", "/status.json")
        if (j.optString("backend") != "h264") throw IOException("The PS5 is not serving a compatible H.264 stream")
        PbsStatus(j.optInt("width"), j.optInt("height"), j.optLong("frames"), j.optLong("bytes"),
            j.optDouble("fps", 0.0), j.optDouble("kbps", 0.0), j.optBoolean("recording"),
            j.optBoolean("recording_error"), j.optLong("audio_bytes"))
    }

    suspend fun requestProfile(height: Int) = requestProfile(PbsProfileRequest(height))

    suspend fun requestProfile(request: PbsProfileRequest) {
        val current = config()
        if (request.height !in current.resolutions) throw IOException("The PS5 payload does not advertise ${request.height}p capture")
        var path = "/capture/profile?height=${request.height}"
        if (request.videoBitrateBps != null || request.minQp != null) {
            val rate = current.rateControl ?: throw IOException("Maximum bandwidth and encoder tuning require the updated Remote Play JB service")
            val bitrate = request.videoBitrateBps ?: rate.bitrateBps
            val qp = request.minQp ?: rate.activeMinQp
            if (bitrate !in rate.minBps..rate.maxBps || qp !in rate.minQp..rate.maxQp)
                throw IOException("The requested encoder settings exceed the PS5 service limits")
            path += "&bitrate=$bitrate&minqp=$qp"
        }
        withContext(Dispatchers.IO) {
            captureAttemptId = captureGuard?.beforeProfileMutation(request.height) ?: captureAttemptId
            try { json("POST", path) }
            catch(error: PbsHttpException) {
                // Only these explicit routing/auth/syntax denials establish that mutation did not begin.
                // A 422/backend failure, timeout or disappearing service retains the quarantine.
                val attempt = captureAttemptId
                if(error.statusCode in listOf(400, 401, 403, 404, 405) && attempt != null &&
                    captureGuard?.rejectedBeforeMutation(attempt) == true) captureAttemptId = null
                throw error
            }
        }
    }

    suspend fun requestKeyframe() = withContext(Dispatchers.IO) { json("POST", "/video/keyframe"); Unit }

    suspend fun controllerStatus(): PbsControllerStatus = withContext(Dispatchers.IO) {
        val j = json("GET", "/input/status")
        var allowed = j.optInt("buttons_mask", PbsProtocol.LEGACY_BUTTON_MASK) and PbsProtocol.NATIVE_BUTTON_MASK
        // Old PBS rejects native Create. Require an explicit native mapping before sending it.
        if (j.optInt("create_mask", 0) != PbsProtocol.Button.CREATE.mask)
            allowed = allowed and PbsProtocol.Button.CREATE.mask.inv()
        PbsControllerStatus(j.optBoolean("supported", j.optBoolean("available")), j.optBoolean("connected", j.optBoolean("owned")),
            j.optBoolean("cleanup_required"), j.optInt("last_rc"), j.toString(), allowed)
    }

    /** Optional protocol extension; old payloads continue with their original single owner. */
    suspend fun partyStatus(): PbsPartyStatus? = withContext(Dispatchers.IO) {
        val j = try { json("GET", "/party/status") }
        catch(error: PbsHttpException) {if(error.statusCode in listOf(404,405,501)) return@withContext null else throw error}
        catch(error: IOException) {
            // Legacy PBS answers unknown paths with HTTP 200 + plain text.
            if(error.message?.startsWith("The PS5 returned an invalid response for /party/status")==true)
                return@withContext null
            throw error
        }
        if(j.optInt("version") != 1 || j.optInt("max_players") != 4) return@withContext null
        val values=j.optJSONArray("players") ?: return@withContext null
        if(values.length()!=4) return@withContext null
        val slots=(0..3).map { index ->
            val entry=values.optJSONObject(index) ?: throw IOException("The PS5 returned invalid player data")
            if(entry.optInt("player") != index+1) throw IOException("The PS5 returned invalid player numbers")
            PbsPlayerSlot(index+1,entry.optBoolean("connected"),entry.optBoolean("cleanup_required"),
                entry.optBoolean("prompt_sent"),entry.optString("name").takeIf { it.isNotBlank() },
                entry.optInt("user_id",-1).takeIf { it>0 })
        }
        PbsPartyStatus(slots)
    }

    /** The PS5's logged-in user list is visible only to a live controller token. */
    suspend fun consoleUsers(): List<PbsConsoleUser> = withContext(Dispatchers.IO) {
        val token=ownedControllerToken ?: return@withContext emptyList()
        if(isRemote) return@withContext emptyList()
        val response=try {json("GET","/party/users",headers=controlHeaders(token))}
        catch(error:PbsHttpException) {
            if(error.statusCode in listOf(404,405,501)) return@withContext emptyList()
            throw error
        }
        val values=response.optJSONArray("users") ?: return@withContext emptyList()
        (0 until minOf(values.length(),4)).mapNotNull {index ->
            val item=values.optJSONObject(index) ?: return@mapNotNull null
            val id=item.optInt("user_id",-1)
            val name=item.optString("name").trim().take(32)
            if(id<=0 || name.isBlank()) null else PbsConsoleUser(id,name,item.optBoolean("avatar"))
        }
    }

    suspend fun consoleAvatar(userId: Int): Bitmap? = withContext(Dispatchers.IO) {
        val token=ownedControllerToken ?: return@withContext null
        if(isRemote || userId<=0) return@withContext null
        val c=connection("GET","/party/avatar?user_id=$userId",3000)
        try {
            controlHeaders(token).forEach {(key,value)->c.setRequestProperty(key,value)}
            try {checkResponse(c,PbsFailureStage.STATUS)}
            catch(error:PbsHttpException) {if(error.statusCode in listOf(404,405,501)) return@withContext null else throw error}
            val mime=c.contentType.orEmpty().substringBefore(';')
            if(mime!="image/png" && mime!="image/jpeg") throw IOException("Unexpected PS5 profile image")
            val bytes=c.inputStream.use { input ->
                val buffer=java.io.ByteArrayOutputStream()
                val chunk=ByteArray(8192)
                while(true) {
                    val count=input.read(chunk)
                    if(count<0) break
                    if(buffer.size()+count>256*1024) throw IOException("PS5 profile image is too large")
                    buffer.write(chunk,0,count)
                }
                buffer.toByteArray()
            }
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return@withContext null
            val scale=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/256)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                BitmapFactory.Options().apply {inSampleSize=scale})
        } finally {requests.remove(c);c.disconnect()}
    }

    suspend fun openController(): String = withContext(Dispatchers.IO) {
        if (acquisitionUncertain) throw PbsOwnershipException("The previous controller connection could not be verified. Wait for the old session to be released before retrying.")
        if (ownedControllerToken != null) throw PbsOwnershipException("The previous controller session has not been released yet.")
        // One server-side admission gate assigns players in successful join order.
        // Legacy and tunnel transports keep their original single-owner endpoint.
        val usingPartyProtocol = !isRemote && partyStatus() != null
        // A failed reply can still mean native acquisition succeeded. Never resend this POST automatically.
        acquisitionUncertain = true
        val j = try {
            json("POST", if(usingPartyProtocol) "/party/connect" else "/input/connect",
                headers = mapOf("X-PBS-Input" to "1"))
        } catch (error: Exception) {
            if (error is PbsHttpException && error.statusCode in 400..499) {
                acquisitionUncertain = false
                throw error
            }
            markStreamStateUnverified()
            throw PbsOwnershipException("The PS5 controller connection was not confirmed. Retry after the previous session is released.", error)
        }
        val token = j.optString("token")
        if (!token.matches(Regex("[0-9a-fA-F]{32}"))) {
            markStreamStateUnverified()
            throw PbsOwnershipException("The PS5 returned an invalid controller session")
        }
        ownedControllerToken = token
        playerSlot = if(usingPartyProtocol) j.optInt("player",-1) else 1
        partyOwner = playerSlot>1
        if(playerSlot !in 1..4) {
            markStreamStateUnverified()
            throw PbsOwnershipException("The PS5 assigned an invalid player number")
        }
        acquisitionUncertain = false
        if (remoteOrigin != null) {
            val channel = PbsRemoteController(remoteOrigin, bearerToken!!, token)
            remoteController = channel
            try {
                channel.open()
                if (closed || remoteController !== channel) throw IOException("The remote controller was closed while connecting")
            } catch (error: Throwable) {
                remoteController = null
                channel.close()
                // Keep the exact owner until the session's bounded cleanup is acknowledged.
                throw error
            }
        }
        token
    }

    suspend fun awaitControllerWritable() { remoteController?.awaitWritable() }

    fun markStreamStateUnverified() {
        captureAttemptId = captureGuard?.markStreamStateUnverified() ?: captureAttemptId
    }
    fun confirmDecodedCapture(width: Int, height: Int) {
        if(captureGuard?.confirmedDecodedFrame(captureAttemptId, width, height) == true) captureAttemptId = null
    }

    suspend fun sendController(token: String, sequence: Long, state: PbsProtocol.PadState) = withContext(Dispatchers.IO) {
        if (remoteOrigin != null) {
            val channel = remoteController ?: throw IOException("The remote controller channel is unavailable")
            channel.send(state.encode(sequence))
        } else json("POST", if(partyOwner) "/party/state" else "/input/state",
            state.encode(sequence), controlHeaders(token), 1000)
        Unit
    }

    /** Repeated cleanup uses only our retained token; it never acquires or clears another owner. */
    suspend fun releaseController() = withContext(Dispatchers.IO) {
        val channel = remoteController
        remoteController = null
        channel?.close()
        val token = ownedControllerToken ?: return@withContext
        try { json("POST", if(partyOwner) "/party/disconnect" else "/input/disconnect",
            headers = controlHeaders(token), timeout = if(isRemote) 10000 else 3000) }
        catch (error: PbsHttpException) {
            // The old native owner may already have been released after a lost response.
            if (error.statusCode != 403) throw error
            if(!partyOwner) {
                val status = controllerStatus()
                if (status.connected || status.cleanupRequired)
                    throw PbsOwnershipException("The previous controller release could not be verified. Another session or native cleanup is still active.", error)
            }
        }
        ownedControllerToken = null
        playerSlot=0
        partyOwner=false
        Unit
    }

    private fun controlHeaders(token: String): Map<String, String> {
        require(token.matches(Regex("[0-9a-fA-F]{32}")))
        return mapOf("X-PBS-Input" to "1", "X-PBS-Controller" to token)
    }

    internal class MediaStream(val input: InputStream, private val finish: () -> Unit): Closeable {
        override fun close() { try { input.close() } finally { finish() } }
    }

    internal fun openMedia(path: String): MediaStream {
        require(path == "/frames" || path == "/audio")
        val connection = connection("GET", path, 6000)
        try {
            val stage = if(path == "/frames") PbsFailureStage.VIDEO else PbsFailureStage.AUDIO
            checkResponse(connection, stage)
            if (!connection.contentType.orEmpty().startsWith("application/octet-stream"))
                throw IOException("The PS5 returned an unexpected media format")
            val input = transportIo(stage) { connection.inputStream }
            return MediaStream(PbsTransportInputStream(input, stage)) { requests.remove(connection); connection.disconnect() }
        } catch (e: Exception) {
            requests.remove(connection); connection.disconnect()
            throw PbsFailures.connection(e, if(path == "/frames") PbsFailureStage.VIDEO else PbsFailureStage.AUDIO) ?: e
        }
    }

    private fun connection(method: String, path: String, timeout: Int): HttpURLConnection {
        check(!closed) { "The PS5 client is closed" }
        val endpoint = remoteOrigin?.let { URL(it + path) } ?: URL("http", host, port, path)
        val c = endpoint.openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout.coerceAtMost(if(isRemote) 5000 else 3000)
        c.readTimeout = timeout
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.setRequestProperty("Accept", "application/json, application/octet-stream")
        if (remoteOrigin == null) c.setRequestProperty("Connection", "close")
        else c.setRequestProperty("Authorization", "Bearer $bearerToken")
        requests.add(c)
        if (closed) { requests.remove(c); c.disconnect(); throw IOException("The PS5 client is closed") }
        return c
    }

    private fun json(method: String, path: String, body: String = "", headers: Map<String, String> = emptyMap(), timeout: Int = if(isRemote) 6000 else 3000): JSONObject {
        val c = connection(method, path, timeout)
        val stage = when {
            path == "/input/disconnect" -> PbsFailureStage.CLEANUP
            path.startsWith("/input/") -> PbsFailureStage.CONTROLLER
            path == "/status.json" -> PbsFailureStage.STATUS
            else -> PbsFailureStage.CONNECT
        }
        try {
            headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
            if (method == "POST") {
                val bytes = body.toByteArray(Charsets.US_ASCII)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain; charset=us-ascii")
                c.setFixedLengthStreamingMode(bytes.size)
                transportIo(stage) { c.outputStream.use { it.write(bytes) } }
            }
            checkResponse(c, stage)
            val stream = transportIo(stage) { c.inputStream }
            val text = PbsTransportInputStream(stream, stage).use { boundedText(it) }
            try { return JSONObject(text) }
            catch (e: Exception) { throw IOException("The PS5 returned an invalid response for $path", e) }
        } catch (error: Exception) {
            // Keep HTTP status typed for authentication, ownership and capture-policy decisions.
            if(error is PbsHttpException) throw error
            throw PbsFailures.connection(error, stage) ?: error
        } finally { requests.remove(c); c.disconnect() }
    }

    private inline fun <T> transportIo(stage: PbsFailureStage, action: () -> T): T = try { action() }
        catch(error: IOException) { throw PbsFailures.atIoBoundary(error, stage) }

    private fun checkResponse(c: HttpURLConnection, stage: PbsFailureStage) {
        val code = transportIo(stage) { c.responseCode }
        if (code in 200..299) return
        // The known HTTP status wins over a failed optional error-body read, especially 401/403.
        val body = runCatching { c.errorStream?.use { boundedText(it) }.orEmpty() }.getOrDefault("")
        val detail = runCatching { val j = JSONObject(body); j.optString("error", j.optString("message")) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: body.take(240).trim().takeIf { it.isNotEmpty() }
        throw PbsHttpException(code, detail ?: "The PS5 rejected the request (HTTP $code)")
    }

    private fun boundedText(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > 65536) throw IOException("The PS5 response exceeded the protocol limit")
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }

    internal fun cancelRequests() {
        val channel = remoteController; remoteController = null
        channel?.close()
        val copy = synchronized(requests) { requests.toList() }
        copy.forEach { it.disconnect() }
    }

    override fun close() {
        closed = true
        val channel = remoteController; remoteController = null
        channel?.close()
        cancelRequests()
    }
}
