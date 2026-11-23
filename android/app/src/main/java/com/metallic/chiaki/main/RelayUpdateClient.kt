// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Read and verify a versioned, Ed25519-signed Relay Android update manifest.
 * The public key is pinned inside the client, never downloaded. Installation
 * is explicitly handed to Android's system Package Installer for approval. */
internal object RelayUpdateClient {
    private val HOST = BuildConfig.RELAY_UPDATE_HOST
    private const val MAX_MANIFEST_BYTES = 32 * 1024
    private const val MAX_APK_BYTES = 1024L * 1024L * 1024L
    private const val KEY_ID = "dc3d875aa030d831"

    data class Release(
        val channel: String, val target: String,
        val versionName: String, val versionCode: Long,
        val packageName: String, val fileName: String,
        val downloadUrl: String, val byteSize: Long,
        val sha256: String, val notes: String
    )

    private fun identifiers(flavor: String): Pair<String, String> = when(flavor) {
        "mobile" -> "mobile" to "android-mobile"
        "tv" -> "tv" to "android-tv"
        "shield" -> "shield" to "android-shield"
        "googleTv" -> "googleTv" to "android-google-tv"
        "retroid" -> "retroid" to "android-retroid"
        "razerEdge" -> "razerEdge" to "android-razer-edge"
        "aynThor" -> "aynThor" to "android-ayn-thor"
        "metaQuest" -> "metaQuest" to "meta-quest"
        else -> throw IllegalArgumentException("Unknown update flavor: " + flavor)
    }

    private fun canonical(value: Any?): String = when(value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported manifest member")
    }

    private fun connection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        require(url.protocol == "https" && url.host == HOST && url.port == -1) {
            "Updates require a trusted HTTPS host"
        }
        return (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Relay-Updates/1")
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while(true) {
            val read = input.read(chunk)
            if(read < 0) break
            require(out.size() + read <= MAX_MANIFEST_BYTES) { "Manifest too large" }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    private fun verify(context: Context, envelope: JSONObject): JSONObject {
        val signature = envelope.getJSONObject("signature")
        require(signature.getString("algorithm") == "Ed25519" &&
            signature.getString("key_id") == KEY_ID) { "Unknown update key" }
        val pem = context.assets.open("relay_update_public_key.pem").bufferedReader().use {
            it.readText()
        }
        val der = Base64.decode(pem.replace(Regex("-----[^-]+-----"), "")
            .replace(Regex("\\s+"), ""), Base64.DEFAULT)
        require(der.size >= 32)
        val raw = der.copyOfRange(der.size - 32, der.size)
        val actualKeyId = MessageDigest.getInstance("SHA-256").digest(raw).hex().take(16)
        require(actualKeyId == KEY_ID) { "Pinned verification key mismatch" }
        val body = envelope.getJSONObject("payload")
        val check = Signature.getInstance("Ed25519")
        check.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der)))
        check.update(canonical(body).toByteArray(StandardCharsets.UTF_8))
        require(check.verify(Base64.decode(signature.getString("value"), Base64.DEFAULT))) {
            "Update manifest signature invalid"
        }
        return body
    }

    fun check(context: Context, flavor: String, packageName: String): Release? {
        val (alias, target) = identifiers(flavor)
        val channel = "stable"
        val connection = connection("https://$HOST/api/v1/updates/android/$alias/$channel")
        try {
            if(connection.responseCode == 404 || connection.responseCode == 503) return null
            require(connection.responseCode == 200) {
                "Update service HTTP " + connection.responseCode
            }
            val body = connection.inputStream.use { readBounded(it) }
            val payload = verify(context, JSONObject(String(body, StandardCharsets.UTF_8)))
            require(payload.getString("schema") == "relay-update-v1" &&
                payload.getString("app") == "relay" &&
                payload.getString("target") == target &&
                payload.getString("channel") == channel)
            val install = payload.getJSONObject("install")
            require(install.getString("method") == "android-package-installer" &&
                install.getString("package_id") == packageName) { "Wrong application ID" }
            val artifact = payload.getJSONObject("artifact")
            val name = artifact.getString("name")
            val size = artifact.getLong("size")
            val sha = artifact.getString("sha256")
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}").matches(name) &&
                name.endsWith(".apk") && size in 1..MAX_APK_BYTES &&
                Regex("[0-9a-f]{64}").matches(sha))
            val expected = "https://$HOST/v1/$target/$channel/artifacts/$name"
            require(artifact.getString("url") == expected) { "Untrusted artifact URL" }
            return Release(channel, target, payload.getString("version"),
                payload.getLong("version_code"), packageName,
                name, expected, size, sha, payload.optString("notes", ""))
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        val folder = File(context.cacheDir, "relay_updates")
        require(folder.isDirectory || folder.mkdirs())
        val output = File(folder, release.sha256 + ".apk")
        if(output.isFile && output.length() == release.byteSize &&
            hash(output) == release.sha256) { progress(100); return output }
        val partial = File(folder, release.sha256 + ".partial")
        val connection = connection(release.downloadUrl)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            require(connection.responseCode == 200) {
                "Download HTTP " + connection.responseCode
            }
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { stream ->
                    val buf = ByteArray(65536)
                    while(true) {
                        val n = input.read(buf)
                        if(n < 0) break
                        total += n
                        require(total <= release.byteSize && total <= MAX_APK_BYTES) {
                            "Download exceeded signed file size"
                        }
                        stream.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        progress((total * 100 / release.byteSize).toInt().coerceIn(0,100))
                    }
                    stream.fd.sync()
                }
            }
            require(total == release.byteSize && digest.digest().hex() == release.sha256) {
                "Downloaded APK failed size/SHA-256 verification"
            }
            if(output.exists() && !output.delete()) error("Cannot replace cached APK")
            require(partial.renameTo(output)) { "Cannot finalize update file" }
            return output
        } finally {
            connection.disconnect()
            if(partial.exists()) partial.delete()
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hash(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while(true) {
                val n = input.read(buffer)
                if(n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return sha.digest().hex()
    }

    @Suppress("DEPRECATION")
    fun openInstaller(context: Context, release: Release, file: File) {
        require(file.isFile && file.length() == release.byteSize &&
            hash(file) == release.sha256) { "Unverified APK" }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("APK package metadata unreadable")
        require(info.packageName == context.packageName) { "APK package ID changed" }
        val version = if(Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else info.versionCode.toLong()
        require(version == release.versionCode) { "APK version mismatch" }
        val uri = FileProvider.getUriForFile(context,
            context.packageName + ".fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
