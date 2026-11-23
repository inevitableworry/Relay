// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.common

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.jb.JbBootstrap
import com.metallic.chiaki.jb.PbsCaptureQuarantine
import com.metallic.chiaki.jb.PbsClient
import com.metallic.chiaki.jb.PbsConfig
import com.metallic.chiaki.jb.PbsHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-configured HTTPS gateway; local/VPN traffic keeps the original console address. */
class PortalRemoteAccess(context: Context) {
    private val app = context.applicationContext
    private val store = app.getSharedPreferences("relay_remote_access", Context.MODE_PRIVATE)

    init { importPrivateProvisioning() }

    var enabled: Boolean
        get() = store.getBoolean("enabled", false)
        set(value) {
            if(value) require(validEndpoint(endpoint) && token.isNotBlank()) { "Save a tunnel address and access key first." }
            store.edit().putBoolean("enabled", value).apply()
        }
    var endpoint: String
        get() = store.getString("endpoint", "").orEmpty()
        set(value) {
            val normalized = normalizeEndpoint(value)
            store.edit().putString("endpoint", normalized).apply()
        }
    val token: String
        get() {
            val encoded = store.getString("access_key", null) ?: return ""
            return runCatching {
                val parts = encoded.split(':', limit=2)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }.getOrDefault("")
        }

    fun saveToken(value: String) {
        val clean = value.trim()
        require(clean.length in 24..512 && clean.all { it.code in 33..126 }) { "Enter a valid tunnel access key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.doFinal(clean.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        store.edit().putString("access_key", "$iv:$encrypted").apply()
    }

    fun clearKey() { store.edit().remove("access_key").putBoolean("enabled", false).apply() }

    /** Associate only the user's current LAN address and saved tunnel for this console. */
    fun captureQuarantine(prefs: PortalPreferences, remoteOrigin: String? = if(enabled) endpoint else null): PbsCaptureQuarantine.Endpoint {
        val lan = PbsCaptureQuarantine.lanOrigin(prefs.jbHost, prefs.jbPort)
        val savedTunnel = endpoint.takeIf(::validEndpoint)
        return PortalCaptureQuarantine(app).endpoint(remoteOrigin ?: lan, listOfNotNull(lan, savedTunnel))
    }

    fun createClient(prefs: PortalPreferences): PbsClient =
        if(enabled) remoteClient(prefs) else attachGuard(PbsClient(prefs.jbHost, prefs.jbPort), captureQuarantine(prefs, null))

    private fun remoteClient(prefs: PortalPreferences, origin: String = endpoint, savedKey: String = token): PbsClient {
        if(!validEndpoint(origin) || savedKey.isBlank())
            throw IOException("Set the Relay tunnel address and access key in Remote Access.")
        return attachGuard(PbsClient(prefs.jbHost, prefs.jbPort, origin, savedKey), captureQuarantine(prefs, origin))
    }

    private fun attachGuard(client: PbsClient, guard: PbsCaptureQuarantine.Endpoint): PbsClient = client.apply {
        captureGuard = guard
        captureAttemptId = guard.currentAttemptId()
    }

    suspend fun testTunnel(prefs: PortalPreferences): PbsConfig =
        remoteClient(prefs).use { it.config() }

    suspend fun ensureStreamService(prefs: PortalPreferences, progress: (String) -> Unit): PbsClient {
        if(!enabled) return JbBootstrap.ensureService(app, prefs, progress, captureQuarantine(prefs, null))
        // A suspended attempt must not switch consoles or credentials when Settings changes.
        val origin = endpoint
        val savedKey = token
        val client = remoteClient(prefs, origin, savedKey)
        val guard = requireNotNull(client.captureGuard)
        try {
            progress("Connecting through your Relay tunnel")
            try {
                client.config()
                return client
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: PbsHttpException) {
                // Only a gateway's explicit unavailable-service response can request loading.
                if(error.statusCode != 503 || !prefs.autoPayload) throw error
            }
            guard.requirePayloadLoadAllowed()
            currentCoroutineContext().ensureActive()
            progress("Starting the PS5 streaming service through your Relay tunnel")
            val attempt = guard.beforePayloadLoad()
            client.captureAttemptId = attempt
            gatewayStart(origin, savedKey, guard, attempt)
            repeat(24) {
                try { client.config(); return client }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) { if(it == 23) throw error }
                delay(500L)
            }
            throw IOException("The PS5 streaming service did not become ready.")
        } catch(error: Exception) { client.close(); throw error }
    }

    private suspend fun gatewayStart(origin: String, savedKey: String, guard: PbsCaptureQuarantine.Endpoint,
        attempt: String) = withContext(Dispatchers.IO) {
        val c = URL(origin + "/relay/start").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.instanceFollowRedirects = false
        c.useCaches = false
        c.connectTimeout = 5000
        // Allow the gateway's three refusal probes, pinned upload and readiness check to finish.
        c.readTimeout = 45000
        c.setRequestProperty("Authorization", "Bearer " + savedKey)
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "text/plain; charset=us-ascii")
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        try {
            currentCoroutineContext().ensureActive()
            guard.requirePayloadAttempt(attempt)
            c.outputStream.use { it.flush() }
            val status = c.responseCode
            val stream = if(status in 200..299) c.inputStream else c.errorStream
            val text = stream?.use {
                val bytes = ByteArray(8193)
                var used = 0
                while(used < bytes.size) {
                    val count = it.read(bytes, used, bytes.size - used)
                    if(count < 0) break
                    used += count
                }
                if(used > 8192) throw IOException("The tunnel returned an oversized response.")
                String(bytes, 0, used, Charsets.UTF_8)
            }.orEmpty()
            if(status !in 200..299) {
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IOException(reason?.takeIf { it.isNotBlank() } ?: "The Relay tunnel rejected startup (HTTP $status).")
            }
        } finally { c.disconnect() }
    }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    /** ADB may privately provision this debug build without opening UI on the user's Fold. */
    private fun importPrivateProvisioning() {
        if(!BuildConfig.DEBUG) return
        val file = File(app.filesDir, "relay-remote-access.json")
        if(!file.isFile) return
        try {
            require(file.length() in 2..8192) { "Invalid private configuration size." }
            val settings = JSONObject(file.readText())
            val url = normalizeEndpoint(settings.getString("endpoint"))
            val accessKey = settings.getString("accessKey")
            saveToken(accessKey)
            store.edit().putString("endpoint", url)
                .putBoolean("enabled", settings.optBoolean("enabled", false)).commit()
        } catch(error: Exception) {
            android.util.Log.w("PortalRemoteAccess", "Private tunnel provisioning could not be applied")
        } finally {
            // Keep provisioning out of Android backups and normal app exports.
            file.delete()
        }
    }

    companion object {
        private const val KEY_ALIAS = "relay_https_gateway_key"
        fun normalizeEndpoint(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) {
                "Enter an HTTPS address without a path, query or password."
            }
            return URI("https", null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
        }
        fun validEndpoint(value: String): Boolean = runCatching { normalizeEndpoint(value) }.isSuccess
    }
}

