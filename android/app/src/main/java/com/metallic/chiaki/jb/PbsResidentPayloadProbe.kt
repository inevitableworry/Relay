// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only corroboration from PS5 Payload Manager. Its process-list endpoint
 * is optional, so a network error is never interpreted as "no payload".
 * The PBS service probe remains the primary test of protocol compatibility. */
internal object PbsResidentPayloadProbe {
    enum class Result { PRESENT, ABSENT, UNAVAILABLE }

    fun parse(json: String): Result {
        val processes = runCatching {
            JSONObject(json).optJSONArray("processes")
        }.getOrNull() ?: return Result.UNAVAILABLE
        for (index in 0 until processes.length()) {
            val process = processes.optJSONObject(index) ?: return Result.UNAVAILABLE
            val name = process.optString("name").trim().lowercase()
            if (name.isBlank()) return Result.UNAVAILABLE
            if (name == "payload.elf" || name == "pbs.elf" || name == "pbs-ps5.elf")
                return Result.PRESENT
        }
        return Result.ABSENT
    }

    suspend fun check(host: String): Result = withContext(Dispatchers.IO) {
        val request = try { URL("http", host, 8084, "/processes_list").openConnection() as HttpURLConnection }
        catch (_: Exception) { return@withContext Result.UNAVAILABLE }
        try {
            request.requestMethod = "GET"
            request.connectTimeout = 900
            request.readTimeout = 900
            request.instanceFollowRedirects = false
            request.useCaches = false
            if(request.responseCode != 200) return@withContext Result.UNAVAILABLE
            if(!request.contentType.orEmpty().contains("json",ignoreCase=true))
                return@withContext Result.UNAVAILABLE
            val body = request.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk=ByteArray(2048)
                while(true) {
                    val length=input.read(chunk)
                    if(length<0) break
                    if(out.size()+length>128*1024) return@withContext Result.UNAVAILABLE
                    out.write(chunk,0,length)
                }
                out.toString("UTF-8")
            }
            parse(body)
        } catch (_: Exception) { Result.UNAVAILABLE }
        finally { request.disconnect() }
    }
}
