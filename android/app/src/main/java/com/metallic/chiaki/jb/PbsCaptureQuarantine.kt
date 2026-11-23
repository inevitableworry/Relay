// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class PbsCaptureQuarantinedException: IOException(
    "The previous PS5 stream stopped before its state could be confirmed. " +
        "Relay can reconnect to a service that is still running, but automatic loading is paused. " +
        "After restarting your PS5 and re-enabling its jailbreak, choose PS5 restarted in Settings > Remote Play JB > Capture Recovery.")

/** Durable evidence of an unfinished capture operation, scoped to explicitly associated origins. */
class PbsCaptureQuarantine(
    private val store: Store,
    private val now: () -> Long = System::currentTimeMillis,
    private val nextId: () -> String = { UUID.randomUUID().toString() }
) {
    interface Store {
        fun read(key: String): String?
        /** All changes must reach durable storage together before returning true. */
        fun commit(changes: Map<String, String?>): Boolean
    }

    data class Record(val attemptId: String, val height: Int, val startedAt: Long, val reason: String) {
        fun encode(): String = "1|$attemptId|$height|$startedAt|$reason"
    }

    class Snapshot internal constructor(internal val entries: Map<String, String>) {
        val blocked: Boolean get() = entries.isNotEmpty()
        val records: List<Record> get() = entries.values.mapNotNull(::decodeRecord)
        val requestedHeights: List<Int> get() = records.map { it.height }.filter { it > 0 }.distinct()
        val hasUnreadableRecord: Boolean get() = records.size != entries.size
        internal fun commonAttemptId(): String? = if(hasUnreadableRecord) null
            else records.map { it.attemptId }.distinct().singleOrNull()
    }

    fun endpoint(primary: String, related: List<String> = emptyList()): Endpoint =
        Endpoint((listOf(primary) + related).map(::normalizeOrigin).distinct().also {
            require(it.size in 1..4) { "Too many associated capture endpoints" }
        })

    inner class Endpoint internal constructor(val origins: List<String>) {
        private val keys = origins.map(::preferenceKey)
        // Only the same in-memory startup may progress into its initial profile selection.
        // Loading a journal from an older client/process does not grant this permission.
        private var ownedStartupAttempt: String? = null

        fun snapshot(): Snapshot = synchronized(LOCK) { snapshotLocked() }
        fun currentAttemptId(): String? = snapshot().commonAttemptId()
        fun requiresFrameVerification(): Boolean = synchronized(LOCK) {
            val pending = snapshotLocked()
            pending.blocked && !(ownedStartupAttempt != null && pending.commonAttemptId() == ownedStartupAttempt &&
                pending.records.all { it.reason == "payload_load" })
        }

        fun requirePayloadLoadAllowed() = synchronized(LOCK) {
            if(snapshotLocked().blocked) throw PbsCaptureQuarantinedException()
        }

        fun beforePayloadLoad(): String = synchronized(LOCK) {
            if(snapshotLocked().blocked) throw PbsCaptureQuarantinedException()
            writeLocked(0, "payload_load").also { ownedStartupAttempt = it }
        }

        /** Recheck immediately before writing a payload or requesting gateway startup. */
        fun requirePayloadAttempt(attemptId: String) = synchronized(LOCK) {
            val pending = snapshotLocked()
            if(attemptId != ownedStartupAttempt || pending.commonAttemptId() != attemptId ||
                pending.records.any { it.reason != "payload_load" }) throw PbsCaptureQuarantinedException()
        }

        /** Cancel only our own new load reservation, before any ELF bytes were sent.
         * A running service discovered during the final preflight needs no upload.
         * Never use this after connecting to the loader or on an uncertain write.
         */
        fun abandonPayloadBeforeTransfer(attemptId: String) = synchronized(LOCK) {
            val pending = snapshotLocked()
            if(attemptId != ownedStartupAttempt || pending.commonAttemptId() != attemptId ||
                pending.records.isEmpty() || pending.records.any { it.reason != "payload_load" })
                throw PbsCaptureQuarantinedException()
            clearLocked(pending)
            ownedStartupAttempt = null
        }

        fun beforeProfileMutation(height: Int): String = synchronized(LOCK) {
            require(height in HEIGHTS) { "Invalid capture profile" }
            val pending = snapshotLocked()
            val ownStartup = pending.blocked && ownedStartupAttempt != null &&
                pending.commonAttemptId() == ownedStartupAttempt && pending.records.all { it.reason == "payload_load" }
            if(pending.blocked && !ownStartup) throw PbsCaptureQuarantinedException()
            // A refused first profile must not erase the still-unverified payload startup.
            writeLocked(height, if(ownStartup) "payload_profile" else "profile_mutation").also {
                ownedStartupAttempt = null
            }
        }

        /** Call only from a real MediaCodec output frame, never from config/status or a UI choice. */
        fun confirmedDecodedFrame(attemptId: String?, width: Int, height: Int): Boolean = synchronized(LOCK) {
            if(attemptId == null || height !in HEIGHTS || width != height * 16 / 9) return@synchronized false
            val pending = snapshotLocked()
            if(!pending.blocked || pending.commonAttemptId() != attemptId ||
                pending.records.any { it.height != 0 && it.height != height }) return@synchronized false
            clearLocked(pending)
            ownedStartupAttempt = null
            true
        }

        /** A documented refusal before native mutation may clear only this new profile request. */
        fun rejectedBeforeMutation(attemptId: String): Boolean = synchronized(LOCK) {
            val pending = snapshotLocked()
            if(pending.commonAttemptId() != attemptId || pending.records.isEmpty() ||
                pending.records.any { it.reason != "profile_mutation" }) return@synchronized false
            clearLocked(pending)
            true
        }

        /** Generic connection/cleanup uncertainty does not claim that a native encoder failed. */
        fun markStreamStateUnverified(): String? = synchronized(LOCK) {
            val pending = snapshotLocked()
            if(pending.blocked) return@synchronized pending.commonAttemptId()
            writeLocked(0, "stream_unverified")
        }

        /** Explicit local acknowledgment only; this does not reboot, probe or load the PS5. */
        fun acknowledgeConsoleReset(observed: Snapshot) = synchronized(LOCK) {
            if(snapshotLocked().entries != observed.entries)
                throw IOException("The stream state changed. Open Capture Recovery again before confirming the PS5 restart.")
            clearLocked(observed)
            ownedStartupAttempt = null
        }

        private fun snapshotLocked() = Snapshot(keys.mapNotNull { key -> store.read(key)?.let { key to it } }.toMap())

        private fun writeLocked(height: Int, reason: String): String {
            val id = nextId()
            require(id.matches(ID)) { "Invalid capture attempt identifier" }
            val record = Record(id, height, now().coerceAtLeast(0), reason).encode()
            if(!store.commit(keys.associateWith { record }))
                throw IOException("Relay could not save the capture recovery state. No new capture operation was started.")
            return id
        }

        private fun clearLocked(pending: Snapshot) {
            if(pending.entries.isNotEmpty() && !store.commit(pending.entries.keys.associateWith { null }))
                throw IOException("Relay could not save the capture recovery state. Automatic loading remains paused.")
        }
    }

    companion object {
        const val PREFERENCES_NAME = "relay_capture_quarantine"
        private val LOCK = Any()
        private val HEIGHTS = setOf(720, 1080, 1440, 2160)
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")
        private val REASONS = setOf("payload_load", "payload_profile", "profile_mutation", "stream_unverified", "native_open_failed")

        fun normalizeOrigin(value: String): String {
            val uri = URI(value.trim())
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.orEmpty() in listOf("", "/") && (uri.port == -1 || uri.port in 1..65535)) {
                "Invalid capture endpoint"
            }
            val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
            require(host.isNotBlank()) { "Invalid capture endpoint" }
            val defaultPort = if(scheme == "https") 443 else 80
            val port = if(uri.port == defaultPort) -1 else uri.port
            return URI(scheme, null, host, port, null, null, null).toASCIIString()
        }

        fun lanOrigin(host: String, port: Int): String {
            require(port in 1..65535)
            return normalizeOrigin(URI("http", null, host.trim(), port, null, null, null).toASCIIString())
        }

        fun preferenceKey(origin: String): String = "endpoint_" + MessageDigest.getInstance("SHA-256")
            .digest(normalizeOrigin(origin).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun decodeRecord(value: String): Record? = runCatching {
            val fields = value.split('|')
            require(fields.size == 5 && fields[0] == "1" && fields[1].matches(ID))
            val height = fields[2].toInt()
            val timestamp = fields[3].toLong()
            require((height == 0 || height in HEIGHTS) && timestamp >= 0 && fields[4] in REASONS)
            Record(fields[1], height, timestamp, fields[4])
        }.getOrNull()

        /** A timeout or an arbitrary IOException is not evidence that no service is running. */
        fun explicitlyRefused(error: Throwable): Boolean {
            var current: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            repeat(8) {
                val cause = current ?: return false
                if(!seen.add(cause)) return false
                if(cause is ConnectException && (cause.message.orEmpty().contains("ECONNREFUSED", true) ||
                    cause.message.orEmpty().contains("connection refused", true))) return true
                current = cause.cause
            }
            return false
        }
    }
}
