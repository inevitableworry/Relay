// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class CaptureKind { PHOTO, VIDEO }

data class GalleryCapture(
    val path: String,
    val name: String,
    val size: Long?,
    val modified: Long?,
    val kind: CaptureKind
) {
    val mimeType: String get() = when(name.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jxr" -> "image/vnd.ms-photo"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
    val game: String get() = path.split('/').lastOrNull {
        Regex("""[A-Z]{4}\d{5}""").matches(it)
    } ?: "PS5"
}

data class GalleryScan(val captures: List<GalleryCapture>, val warnings: List<String>, val limited: Boolean)
data class GalleryDownload(val bytes: Long, val expectedBytes: Long)

internal data class FtpEntry(val name: String, val directory: Boolean, val size: Long?, val modified: Long?)
internal data class FtpReply(val code: Int, val text: String)
internal class FtpReplyException(val code: Int, message: String): IOException(message)
internal data class GalleryImageSource(val path: String, val knownSize: Long? = null)

/** Pure protocol/path helpers, covered by host JVM tests. */
internal object GalleryFtpProtocol {
    private val photoExtensions = setOf("jpg", "jpeg", "png", "webp", "jxr")
    private val videoExtensions = setOf("mp4", "webm")

    fun safeLeaf(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.length <= 255 && name.none { it == '/' || it == '\\' || it.isISOControl() }

    fun root(path: String): String {
        val normalized = path.trim().trimEnd('/')
        require(normalized.startsWith("/") && normalized.length > 1 &&
            normalized.substring(1).split('/').all(::safeLeaf)) { "Invalid capture folder." }
        return normalized
    }

    fun child(parent: String, name: String): String {
        require(safeLeaf(name)) { "Unsafe filename in the console listing." }
        return parent.trimEnd('/') + "/" + name
    }

    fun inside(root: String, path: String): Boolean =
        path.startsWith("$root/") && path.removePrefix("$root/").split('/').all(::safeLeaf)

    /** Derived Sony image sidecars only. A video original is never a thumbnail candidate. */
    fun thumbnailSources(root: String, capture: GalleryCapture): List<GalleryImageSource> {
        require(inside(root, capture.path) &&
            (capture.path.startsWith("$root/photo/") || capture.path.startsWith("$root/video/"))) {
            "Invalid capture path."
        }
        val relative = capture.path.removePrefix("$root/")
        val mirrored = "$root/thumbnails/$relative"
        val sources = mutableListOf<GalleryImageSource>()
        fun add(path: String, size: Long? = null) { sources.add(GalleryImageSource(path, size)) }
        if(capture.kind == CaptureKind.PHOTO && capture.name.endsWith(".jxr", true)) {
            // Verified on this PS5: the small image keeps the original .jxr and adds another .jxr.
            add("$mirrored.jxr")
            add(mirrored)
        }
        // All three clips observed on this PS5 have mirrored .webm.jxr posters.
        // JPEG sidecars are also documented by the upstream PlayBridge source.
        for(extension in listOf("jxr", "jpeg", "jpg", "png", "webp")) {
            add("$mirrored.$extension")
            add("${capture.path}.$extension")
        }
        if(capture.kind == CaptureKind.PHOTO) {
            add(mirrored)
            if(capture.name.endsWith(".jxr", true))
                add(mirrored.substringBeforeLast('.') + ".jpeg")
            add(capture.path, capture.size)
        }
        return sources.distinctBy { it.path }
    }

    fun isJxr(bytes: ByteArray): Boolean = bytes.size >= 4 && (
        (bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0xbc.toByte() && bytes[3] == 1.toByte()) ||
        (bytes[0] == 0x4d.toByte() && bytes[1] == 0x4d.toByte() && bytes[2] == 1.toByte() && bytes[3] == 0xbc.toByte()))

    fun imageSignature(bytes: ByteArray): Boolean {
        if(isJxr(bytes)) return true
        if(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) return true
        if(bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() && bytes[4] == 13.toByte() &&
            bytes[5] == 10.toByte() && bytes[6] == 26.toByte() && bytes[7] == 10.toByte()) return true
        return bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(82, 73, 70, 70)) &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(87, 69, 66, 80))
    }

    fun kind(name: String): CaptureKind? {
        if(!safeLeaf(name) || name.startsWith('.')) return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if(extension == "jxr" && name.substringBeforeLast('.').endsWith(".jxr", true)) return null
        // Sony stores preview siblings such as clip.mp4.jpeg and shot.png.jpeg.
        if(extension in setOf("jpg", "jpeg", "png", "webp")) {
            val previous = name.substringBeforeLast('.', "").substringAfterLast('.', "").lowercase(Locale.ROOT)
            if(previous in photoExtensions || previous in videoExtensions) return null
        }
        return when(extension) {
            in photoExtensions -> CaptureKind.PHOTO
            in videoExtensions -> CaptureKind.VIDEO
            else -> null
        }
    }

    fun epsvPort(reply: String): Int {
        val token = reply.substringAfterLast('(', "").substringBefore(')', "")
        require(token.length >= 5) { "Malformed EPSV reply." }
        val separator = token[0]
        require(!separator.isDigit() && separator.code in 33..126) { "Malformed EPSV separator." }
        val fields = token.split(separator)
        require(fields.size == 5 && fields.take(3).all { it.isEmpty() } && fields.last().isEmpty()) {
            "Malformed EPSV address."
        }
        return fields[3].toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Invalid passive port.")
    }

    /** The advertised PASV host is deliberately never used. */
    fun pasvPort(reply: String): Int {
        val token = reply.substringAfterLast('(', "").substringBefore(')', "")
        val values = token.split(',').map { it.trim().toIntOrNull() ?: -1 }
        require(values.size == 6 && values.all { it in 0..255 }) { "Malformed PASV reply." }
        return (values[4] * 256 + values[5]).takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid passive port.")
    }

    fun workingDirectory(reply: String): String {
        val text = reply.substringAfter(' ').trimStart()
        require(text.startsWith('"')) { "The FTP server did not report a quoted working directory." }
        val result = StringBuilder()
        var index = 1
        while(index < text.length) {
            val character = text[index++]
            if(character == '"') {
                if(index < text.length && text[index] == '"') { result.append('"'); index++ }
                else return result.toString()
            } else result.append(character)
        }
        throw IllegalArgumentException("The FTP working-directory reply was truncated.")
    }

    fun mlsd(line: String): FtpEntry? {
        val boundary = line.indexOf(' ')
        if(boundary < 1) return null
        val facts = line.substring(0, boundary).split(';').mapNotNull {
            val equal = it.indexOf('=')
            if(equal <= 0) null else it.substring(0, equal).lowercase(Locale.ROOT) to it.substring(equal + 1)
        }.toMap()
        val name = line.substring(boundary + 1).trimEnd('\r')
        val type = facts["type"]?.lowercase(Locale.ROOT)
        if(!safeLeaf(name) || type !in setOf("file", "dir")) return null
        val size = facts["size"]?.toLongOrNull()?.takeIf { it >= 0 }
        val modified = facts["modify"]?.take(14)?.let { parseDate("yyyyMMddHHmmss", it, true) }
        return FtpEntry(name, type == "dir", size, modified)
    }

    private val unixList = Regex("""^([d-])[rwxStTs-]{9}[+@.]?\s+\d+\s+\S+\s+\S+\s+(\d+)\s+([A-Za-z]{3})\s+(\d{1,2})\s+(\d{2}:\d{2}|\d{4})\s(.+)$""")
    private val dosList = Regex("""^(\d{2}-\d{2}-\d{2,4})\s+(\d{2}:\d{2}[AP]M)\s+(<DIR>|\d+)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun listing(line: String, now: Long = System.currentTimeMillis()): FtpEntry? {
        unixList.matchEntire(line.trimEnd('\r'))?.let { match ->
            val groups = match.groupValues
            val name = groups[6]
            if(!safeLeaf(name)) return null
            val size = groups[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val clockOrYear = groups[5]
            val modified = if(clockOrYear.contains(':')) {
                val calendar = Calendar.getInstance().apply { timeInMillis = now }
                val value = parseDate("yyyy MMM dd HH:mm", "${calendar.get(Calendar.YEAR)} ${groups[3]} ${groups[4]} $clockOrYear")
                if(value != null && value > now + 2 * 86400000L) {
                    calendar.timeInMillis = value
                    calendar.add(Calendar.YEAR, -1)
                    calendar.timeInMillis
                } else value
            } else parseDate("yyyy MMM dd", "$clockOrYear ${groups[3]} ${groups[4]}")
            return FtpEntry(name, groups[1] == "d", size, modified)
        }
        dosList.matchEntire(line.trimEnd('\r'))?.let { match ->
            val groups = match.groupValues
            if(!safeLeaf(groups[4])) return null
            val directory = groups[3].equals("<DIR>", true)
            val pattern = if(groups[1].substringAfterLast('-').length == 4) "MM-dd-yyyy hh:mma" else "MM-dd-yy hh:mma"
            return FtpEntry(groups[4], directory, if(directory) null else groups[3].toLongOrNull(),
                parseDate(pattern, "${groups[1]} ${groups[2]}"))
        }
        return null // Symlinks and unknown formats are never followed.
    }

    private fun parseDate(pattern: String, text: String, utc: Boolean = false): Long? = runCatching {
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            if(utc) timeZone = TimeZone.getTimeZone("UTC")
        }.parse(text)?.time
    }.getOrNull()

    fun uniqueName(original: String, existing: Set<String>): String {
        require(safeLeaf(original)) { "Invalid capture filename." }
        if(original !in existing) return original
        val dot = original.lastIndexOf('.')
        val stem = if(dot > 0) original.substring(0, dot) else original
        val suffix = if(dot > 0) original.substring(dot) else ""
        for(index in 2..100000) {
            val counter = " ($index)"
            val candidate = stem.take((255 - suffix.length - counter.length).coerceAtLeast(1)) + counter + suffix
            if(candidate !in existing) return candidate
        }
        throw IOException("Too many files have the same name.")
    }
}

/**
 * Read-only FTP transport for etaHEN's local FTP server.
 * Every public network operation must run on an IO worker.
 * close() is safe from the UI thread and interrupts control/data socket reads.
 */
class GalleryFtpClient(
    private val host: String,
    private val port: Int,
    captureRoot: String,
    private val timeoutMs: Int = 12000
): Closeable {
    private val root = GalleryFtpProtocol.root(captureRoot)
    @Volatile private var stopped = false
    @Volatile private var control: Socket? = null
    @Volatile private var dataSocket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var mlsdSupported = true
    private var epsvSupported = true

    init {
        require(port in 1..65535) { "Invalid FTP port." }
        require(host.isNotBlank() && host.none { it.isWhitespace() || it in "/\\?#@" }) { "Invalid PS5 address." }
    }

    private fun checkActive() {
        if(stopped || Thread.currentThread().isInterrupted) throw IOException("Transfer cancelled.")
    }

    private fun connect() {
        checkActive()
        if(control?.isConnected == true && control?.isClosed == false) return
        val socket = Socket()
        control = socket
        try {
            checkActive() // close() may race between the first check and publishing the socket.
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            input = BufferedInputStream(socket.getInputStream())
            output = BufferedOutputStream(socket.getOutputStream())
            var greeting = reply()
            if(greeting.code == 120) greeting = reply()
            expect(greeting, 220)
            val login = command("USER anonymous")
            if(login.code == 331) expect(command("PASS relay@localhost"), 230) else expect(login, 230)
            expect(command("TYPE I"), 200)
            command("OPTS UTF8 ON") // Older servers may reject it; the reply is still consumed.
        } catch(error: Exception) {
            disconnect()
            if(error is IOException) throw error
            throw IOException(error.message ?: "FTP connection failed.", error)
        }
    }

    private fun command(value: String): FtpReply {
        checkActive()
        require(value.length <= 4096 && value.none { it.isISOControl() }) { "Invalid FTP command." }
        val target = output ?: throw IOException("FTP is disconnected.")
        target.write((value + "\r\n").toByteArray(Charsets.UTF_8))
        target.flush()
        return reply()
    }

    private fun reply(): FtpReply {
        val source = input ?: throw IOException("FTP is disconnected.")
        val first = readLine(source, 8192) ?: throw IOException("The PS5 closed the FTP connection.")
        val code = first.take(3).toIntOrNull() ?: throw IOException("Invalid FTP response.")
        val all = StringBuilder(first)
        if(first.getOrNull(3) == '-') {
            repeat(128) {
                val next = readLine(source, 8192) ?: throw IOException("Incomplete FTP response.")
                if(all.length + next.length > 65536) throw IOException("FTP response is too large.")
                all.append('\n').append(next)
                if(next.startsWith("$code ")) return FtpReply(code, all.toString())
            }
            throw IOException("Unterminated FTP response.")
        }
        if(first.length < 4 || first[3] != ' ') throw IOException("Invalid FTP response delimiter.")
        return FtpReply(code, all.toString())
    }

    private fun readLine(source: InputStream, limit: Int): String? {
        val bytes = ByteArrayOutputStream()
        while(true) {
            checkActive()
            val value = source.read()
            if(value == -1) return if(bytes.size() == 0) null else throw IOException("Truncated FTP response.")
            if(value == 10) return bytes.toByteArray().toString(Charsets.UTF_8).trimEnd('\r')
            if(bytes.size() >= limit) throw IOException("FTP line is too long.")
            bytes.write(value)
        }
    }

    private fun expect(response: FtpReply, vararg codes: Int) {
        if(response.code !in codes) throw FtpReplyException(response.code, response.text.take(300))
    }

    private fun passive(): Socket {
        connect()
        val passivePort = try {
            if(epsvSupported) {
                val response = command("EPSV")
                if(response.code == 229) GalleryFtpProtocol.epsvPort(response.text)
                else {
                    epsvSupported = false
                    val fallback = command("PASV")
                    expect(fallback, 227)
                    GalleryFtpProtocol.pasvPort(fallback.text)
                }
            } else {
                val response = command("PASV")
                expect(response, 227)
                GalleryFtpProtocol.pasvPort(response.text)
            }
        } catch(error: IllegalArgumentException) {
            throw IOException(error.message, error)
        }
        checkActive()
        val peer = control?.inetAddress ?: throw IOException("FTP is disconnected.")
        val socket = Socket()
        dataSocket = socket
        try {
            checkActive()
            // Pin the data socket to the established control peer. Never honor PASV's host field.
            socket.connect(InetSocketAddress(peer, passivePort), timeoutMs)
            socket.soTimeout = timeoutMs
            return socket
        } catch(error: Exception) {
            runCatching { socket.close() }
            dataSocket = null
            throw error
        }
    }

    private fun <T> receive(request: String, read: (InputStream) -> T): T {
        val socket = passive()
        var preliminary = false
        try {
            expect(command(request), 125, 150)
            preliminary = true
            val result = socket.getInputStream().buffered(65536).use(read)
            socket.close()
            dataSocket = null
            expect(reply(), 226, 250)
            return result
        } catch(error: Exception) {
            runCatching { socket.close() }
            dataSocket = null
            if(preliminary || error !is FtpReplyException) disconnect()
            throw error
        }
    }

    private fun listing(path: String): List<FtpEntry> {
        require(path == root || GalleryFtpProtocol.inside(root, path)) { "Folder is outside the capture root." }
        connect()
        expect(command("CWD $path"), 250)
        val current = command("PWD")
        expect(current, 257)
        val actual = try { GalleryFtpProtocol.workingDirectory(current.text) }
            catch(error: IllegalArgumentException) { throw IOException(error.message, error) }
        if(actual.trimEnd('/') != path.trimEnd('/'))
            throw IOException("The FTP server opened a different folder than requested.")
        fun read(request: String, machine: Boolean): List<FtpEntry> =
            receive(request) { stream ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while(true) {
                    checkActive()
                    val count = stream.read(buffer)
                    if(count < 0) break
                    if(bytes.size() + count > 8 * 1024 * 1024) throw IOException("A capture folder listing is too large.")
                    bytes.write(buffer, 0, count)
                }
                val lines = bytes.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.toList()
                val entries = lines.mapNotNull {
                    if(machine) GalleryFtpProtocol.mlsd(it) else GalleryFtpProtocol.listing(it)
                }
                if(entries.isEmpty() && lines.any {
                    if(machine) !it.contains("type=cdir;", true) && !it.contains("type=pdir;", true) &&
                        !it.contains("type=OS.unix=slink", true)
                    else !it.startsWith("total ") && !it.startsWith('l')
                }) throw IOException("The FTP server returned an unsupported directory listing.")
                entries
            }
        if(mlsdSupported) {
            // ftpsrv.elf v0.22 ignores an MLSD path argument. Change and verify
            // the directory first, then use the bare command on every server.
            try { return read("MLSD", true) }
            catch(error: FtpReplyException) {
                if(error.code !in setOf(500, 501, 502, 504)) throw error
                mlsdSupported = false
            }
        }
        return read("LIST", false)
    }

    private fun listWithRetry(path: String): List<FtpEntry> {
        try { return listing(path) }
        catch(error: IOException) {
            checkActive()
            if(error is FtpReplyException && error.code in 500..599) throw error
            disconnect()
            return listing(path)
        }
    }

    fun scan(progress: (Int) -> Unit = {}): GalleryScan {
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add("$root/photo" to 0)
        pending.add("$root/video" to 0)
        val found = mutableListOf<GalleryCapture>()
        val warnings = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        var successfulFolders = 0
        var limited = false
        while(pending.isNotEmpty()) {
            checkActive()
            val (path, depth) = pending.removeFirst()
            if(!visited.add(path)) continue
            if(visited.size > 4000 || found.size >= 10000) { limited = true; break }
            val entries = try { listWithRetry(path).also { successfulFolders++ } }
            catch(error: IOException) {
                checkActive()
                if(warnings.size < 20) warnings.add("$path: ${error.message ?: "Cannot read folder"}")
                continue
            }
            for(entry in entries) {
                val child = GalleryFtpProtocol.child(path, entry.name)
                if(entry.directory) {
                    if(!entry.name.startsWith('.')) {
                        if(depth < 12) pending.add(child to depth + 1) else limited = true
                    }
                } else {
                    val kind = GalleryFtpProtocol.kind(entry.name) ?: continue
                    if(entry.size == 0L) continue
                    found.add(GalleryCapture(child, entry.name, entry.size, entry.modified, kind))
                    if(found.size >= 10000) { limited = true; break }
                }
            }
            progress(found.size)
        }
        if(successfulFolders == 0 && warnings.isNotEmpty())
            throw IOException(warnings.joinToString("\n"))
        return GalleryScan(found.sortedWith(compareByDescending<GalleryCapture> { it.modified ?: 0 }
            .thenByDescending { it.name }), warnings, limited)
    }

    private fun size(path: String): Long? {
        connect()
        val response = command("SIZE $path")
        if(response.code == 213) {
            return response.text.substringAfter(' ').trim().toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("The console returned an invalid file size.")
        }
        if(response.code in setOf(500, 501, 502, 504, 550)) return null
        expect(response, 213)
        return null
    }

    private fun downloadPath(
        path: String, knownSize: Long?, target: OutputStream, maximum: Long,
        progress: (Long, Long) -> Unit
    ): GalleryDownload {
        require(GalleryFtpProtocol.inside(root, path)) { "File is outside the capture root." }
        val expected = size(path) ?: knownSize ?: throw IOException("The console did not report this file's size.")
        if(expected > maximum) throw IOException("The capture is too large for this preview.")
        var count = 0L
        receive("RETR $path") { stream ->
            val buffer = ByteArray(65536)
            while(true) {
                checkActive()
                val read = stream.read(buffer)
                if(read < 0) break
                if(count + read > expected || count + read > maximum)
                    throw IOException("The capture changed while it was being copied.")
                target.write(buffer, 0, read)
                count += read
                progress(count, expected)
            }
        }
        if(count != expected) throw IOException("Incomplete transfer: $count of $expected bytes.")
        val current = size(path)
        if(current != null && current != expected) throw IOException("The capture changed while it was being copied.")
        target.flush()
        return GalleryDownload(count, expected)
    }

    fun download(capture: GalleryCapture, target: OutputStream, progress: (Long, Long) -> Unit = { _, _ -> }): GalleryDownload {
        require(capture.path.startsWith("$root/photo/") || capture.path.startsWith("$root/video/")) {
            "Only original captures can be transferred."
        }
        return downloadPath(capture.path, capture.size, target, Long.MAX_VALUE, progress)
    }

    fun imagePreview(capture: GalleryCapture, maximum: Long = 32L * 1024 * 1024): ByteArray {
        require(GalleryFtpProtocol.inside(root, capture.path)) { "Invalid capture path." }
        val candidates = mutableListOf<Pair<String, Long?>>()
        if(capture.kind == CaptureKind.PHOTO && !capture.name.endsWith(".jxr", true) &&
            (capture.size == null || capture.size <= maximum))
            candidates.add(capture.path to capture.size)
        val relative = capture.path.removePrefix("$root/")
        if(capture.name.endsWith(".jxr", true)) {
            candidates.add("$root/thumbnails/$relative.jxr" to null)
            candidates.add("$root/thumbnails/$relative" to null)
            candidates.add(capture.path to capture.size)
        }
        candidates.add("$root/thumbnails/$relative.jpeg" to null)
        candidates.add("$root/thumbnails/$relative" to null)
        candidates.add("${capture.path}.jpeg" to null)
        if(capture.name.endsWith(".jxr", true)) {
            candidates.add("$root/thumbnails/${relative.removeSuffix(".jxr")}.jpeg" to null)
        }
        var lastError: IOException? = null
        for((path, knownSize) in candidates.distinct()) {
            checkActive()
            try {
                val bytes = ByteArrayOutputStream()
                downloadPath(path, knownSize, bytes, maximum) { _, _ -> }
                return bytes.toByteArray()
            } catch(error: IOException) { checkActive(); lastError = error }
        }
        throw IOException("No readable image preview is available. You can still transfer the original.", lastError)
    }

    /** Small, actual console image for a gallery card; never downloads a video original. */
    fun thumbnail(capture: GalleryCapture, maximum: Long = 2L * 1024 * 1024): ByteArray =
        thumbnailImage(capture, maximum) { it }

    /** An undecodable image can fall through to the next real sidecar, without substituting artwork. */
    internal fun <T> thumbnailImage(capture: GalleryCapture, maximum: Long = 2L * 1024 * 1024,
                                    decode: (ByteArray) -> T): T {
        require(maximum in 1..(4L * 1024 * 1024)) { "Invalid thumbnail byte limit." }
        var lastError: IOException? = null
        for(source in GalleryFtpProtocol.thumbnailSources(root, capture)) {
            checkActive()
            if(source.knownSize != null && source.knownSize > maximum) continue
            try {
                val bytes = ByteArrayOutputStream()
                downloadPath(source.path, source.knownSize, bytes, maximum) { _, _ -> }
                val result = bytes.toByteArray()
                if(!GalleryFtpProtocol.imageSignature(result))
                    throw IOException("The console sidecar is not a supported image.")
                return decode(result)
            } catch(error: IOException) {
                checkActive()
                if(control == null || control?.isClosed != false) throw error
                lastError = error
            }
        }
        throw IOException("No readable thumbnail is available for this capture.", lastError)
    }

    private fun disconnect() {
        runCatching { dataSocket?.close() }
        runCatching { control?.close() }
        dataSocket = null; control = null; input = null; output = null
    }

    override fun close() { stopped = true; disconnect() }
}

