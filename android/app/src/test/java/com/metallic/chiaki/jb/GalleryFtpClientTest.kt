// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GalleryFtpClientTest {
    @Test fun pathsCannotEscapeTheCaptureRootOrInjectCommands() {
        assertEquals("/user/av_contents", GalleryFtpProtocol.root("/user/av_contents/"))
        assertTrue(GalleryFtpProtocol.inside("/user/av_contents", "/user/av_contents/photo/a.jpg"))
        assertFalse(GalleryFtpProtocol.inside("/user/av_contents", "/user/av_contents2/photo/a.jpg"))
        assertFalse(GalleryFtpProtocol.inside("/user/av_contents", "/user/av_contents/photo/../secret"))
        for(name in listOf("..", ".", "a/b.jpg", "a\\b.jpg", "x\r\nDELE file", "x\u0000.jpg")) {
            assertFalse(name, GalleryFtpProtocol.safeLeaf(name))
        }
        assertThrows(IllegalArgumentException::class.java) { GalleryFtpProtocol.root("/user/../etc") }
        assertThrows(IllegalArgumentException::class.java) { GalleryFtpProtocol.child("/user/av_contents", "../photo") }
    }

    @Test fun originalCapturesAreDistinguishedFromSonyPreviewSiblings() {
        assertEquals(CaptureKind.PHOTO, GalleryFtpProtocol.kind("capture.JPG"))
        assertEquals(CaptureKind.PHOTO, GalleryFtpProtocol.kind("capture.jpg.jxr"))
        assertEquals(CaptureKind.VIDEO, GalleryFtpProtocol.kind("clip.webm"))
        assertNull(GalleryFtpProtocol.kind("clip.mp4.jpeg"))
        assertNull(GalleryFtpProtocol.kind("capture.png.jpeg"))
        assertNull(GalleryFtpProtocol.kind("capture.jxr.jxr"))
        assertNull(GalleryFtpProtocol.kind("capture.jpg.meta"))
        assertNull(GalleryFtpProtocol.kind(".unfinished.mp4"))
    }

    @Test fun machineListingsHandleSpacesLargeFilesAndUnsafeEntries() {
        val entry = GalleryFtpProtocol.mlsd("type=file;size=5000000000;modify=20260102030405; a capture.mp4")!!
        assertEquals("a capture.mp4", entry.name)
        assertEquals(5000000000L, entry.size)
        assertFalse(entry.directory)
        assertNotNull(entry.modified)
        assertNull(GalleryFtpProtocol.mlsd("type=cdir; ."))
        assertNull(GalleryFtpProtocol.mlsd("type=pdir; .."))
        assertNull(GalleryFtpProtocol.mlsd("type=OS.unix=slink; link"))
        assertNull(GalleryFtpProtocol.mlsd("type=file;size=1; ../secret"))
    }

    @Test fun unixAndDosListingsPreserveFilenamesWithoutFollowingLinks() {
        val unix = GalleryFtpProtocol.listing("-rw-r--r-- 1 root wheel 5000000000 Nov 21 2026 My clip.mp4")!!
        assertEquals("My clip.mp4", unix.name)
        assertEquals(5000000000L, unix.size)
        assertTrue(GalleryFtpProtocol.listing("drwxr-xr-x 2 root wheel 512 Nov 21 12:45 PPSA12345")!!.directory)
        assertNull(GalleryFtpProtocol.listing("lrwxrwxrwx 1 root wheel 3 Nov 21 2026 link -> ../"))
        assertEquals("photo one.jpg",
            GalleryFtpProtocol.listing("11-21-26  12:45PM  1024 photo one.jpg")!!.name)
    }

    @Test fun passiveParsersRejectMalformedPortsAndNeverExposeTheAdvertisedHost() {
        assertEquals(49152, GalleryFtpProtocol.epsvPort("229 Entering Extended Passive Mode (|||49152|)"))
        assertEquals(49152, GalleryFtpProtocol.pasvPort("227 Entering Passive Mode (203,0,113,1,192,0)"))
        for(reply in listOf("229 (|||0|)", "229 (|||65536|)", "229 (||123|)")) {
            assertThrows(IllegalArgumentException::class.java) { GalleryFtpProtocol.epsvPort(reply) }
        }
        assertThrows(IllegalArgumentException::class.java) { GalleryFtpProtocol.pasvPort("227 (1,2,3,4,0,0)") }
        assertThrows(IllegalArgumentException::class.java) { GalleryFtpProtocol.pasvPort("227 (1,2,3,4,999,1)") }
    }

    @Test fun collisionNamesPreserveTheOriginalAndExtension() {
        assertEquals("clip.mp4", GalleryFtpProtocol.uniqueName("clip.mp4", emptySet()))
        assertEquals("clip (3).mp4",
            GalleryFtpProtocol.uniqueName("clip.mp4", setOf("clip.mp4", "clip (2).mp4")))
        assertEquals("capture (2)", GalleryFtpProtocol.uniqueName("capture", setOf("capture")))
    }

    @Test fun quotedWorkingDirectoryRepliesAreVerified() {
        assertEquals("/user/av_contents/photo",
            GalleryFtpProtocol.workingDirectory("257 \"/user/av_contents/photo\" is current"))
        assertEquals("/a \"quoted\" name",
            GalleryFtpProtocol.workingDirectory("257 \"/a \"\"quoted\"\" name\""))
        assertThrows(IllegalArgumentException::class.java) {
            GalleryFtpProtocol.workingDirectory("257 /user/av_contents/photo")
        }
    }

    @Test fun realSocketsListAndDownloadWithMachineListings() {
        FtpStandIn().use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                val result = client.scan()
                assertEquals(1, result.captures.size)
                assertTrue(result.warnings.isEmpty())
                val copied = ByteArrayOutputStream()
                val receipt = client.download(result.captures.single(), copied)
                assertEquals(DATA.size.toLong(), receipt.bytes)
                assertArrayEquals(DATA, copied.toByteArray())
            }
            assertTrue(server.commands.any { it == "MLSD" })
            assertTrue(server.commands.any { it.startsWith("RETR ") })
            assertTrue(server.commands.all {
                it.substringBefore(' ') in setOf("USER", "PASS", "TYPE", "OPTS", "CWD", "PWD", "EPSV", "PASV", "MLSD", "LIST", "SIZE", "RETR")
            })
            server.failure.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun pasvAndListFallbackStayOnTheControlPeer() {
        FtpStandIn(machine = false, extended = false).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                val capture = client.scan().captures.single()
                val copied = ByteArrayOutputStream()
                client.download(capture, copied)
                assertArrayEquals(DATA, copied.toByteArray())
            }
            // The server advertises 203.0.113.99, but the transfer succeeds on 127.0.0.1.
            assertTrue(server.commands.any { it == "PASV" })
            assertTrue(server.commands.any { it == "LIST" })
            server.failure.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun truncatedTransfersAreRejectedAfterTheCompletionReply() {
        FtpStandIn(reportedSize = DATA.size.toLong() + 3).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                val capture = client.scan().captures.single()
                val error = assertThrows(IOException::class.java) { client.download(capture, ByteArrayOutputStream()) }
                assertTrue(error.message.orEmpty().contains("Incomplete transfer"))
            }
        }
    }

    @Test fun closingTheClientInterruptsABlockedDownload() {
        FtpStandIn(blockRetr = true).use { server ->
            val client = GalleryFtpClient("127.0.0.1", server.port, ROOT, 5000)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try {
                    client.download(GalleryCapture("$ROOT/photo/capture.jpg", "capture.jpg",
                        DATA.size.toLong(), null, CaptureKind.PHOTO), ByteArrayOutputStream())
                } catch(error: Throwable) { failure.set(error) }
            }
            assertTrue(server.dataStarted.await(2, TimeUnit.SECONDS))
            client.close()
            worker.join(1500)
            assertFalse("Cancellation must close the blocked socket", worker.isAlive)
            assertTrue(failure.get() is IOException)
        }
    }

    @Test fun capturesThatGrowDuringTransferAreRejected() {
        FtpStandIn(sizeAfter = DATA.size.toLong() + 1).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                val error = assertThrows(IOException::class.java) {
                    client.download(GalleryCapture("$ROOT/photo/capture.jpg", "capture.jpg",
                        DATA.size.toLong(), null, CaptureKind.PHOTO), ByteArrayOutputStream())
                }
                assertTrue(error.message.orEmpty().contains("changed while"))
            }
        }
    }

    @Test fun videoThumbnailCandidatesCannotRetrieveTheOriginalClip() {
        val capture = GalleryCapture("$ROOT/video/NPXS40087/PPSA12345/abc/clip.webm",
            "clip.webm", 5000000000L, null, CaptureKind.VIDEO)
        val paths = GalleryFtpProtocol.thumbnailSources(ROOT, capture).map { it.path }
        assertEquals("$ROOT/thumbnails/video/NPXS40087/PPSA12345/abc/clip.webm.jxr", paths.first())
        assertFalse(capture.path in paths)
        assertFalse("$ROOT/thumbnails/video/NPXS40087/PPSA12345/abc/clip.webm" in paths)
        assertTrue(paths.all { GalleryFtpProtocol.inside(ROOT, it) && it.substringAfterLast('.') in setOf("jxr", "jpeg", "jpg", "png", "webp") })
        assertThrows(IllegalArgumentException::class.java) {
            GalleryFtpProtocol.thumbnailSources(ROOT, capture.copy(path = "$ROOT/video/../secret.webm"))
        }
    }

    @Test fun nativeVideoPosterIsReadWithoutAnyOriginalVideoRequest() {
        val original = "$ROOT/video/NPXS40087/PPSA12345/abc/clip.webm"
        val poster = "$ROOT/thumbnails/video/NPXS40087/PPSA12345/abc/clip.webm.jxr"
        FtpStandIn(files = mapOf(poster to JXR, original to DATA)).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                assertArrayEquals(JXR, client.thumbnail(GalleryCapture(original, "clip.webm", 118030336, null, CaptureKind.VIDEO)))
            }
            assertEquals(listOf("RETR $poster"), server.commands.filter { it.startsWith("RETR ") })
            assertFalse(server.commands.contains("SIZE $original"))
            server.failure.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun oversizedPosterIsRejectedBeforeRetrAndSmallJpegFallbackIsUsed() {
        val original = "$ROOT/video/clip.mp4"
        val oversized = "$ROOT/thumbnails/video/clip.mp4.jxr"
        val fallback = "$ROOT/thumbnails/video/clip.mp4.jpeg"
        FtpStandIn(files = mapOf(oversized to JXR, fallback to JPEG),
            fileSizes = mapOf(oversized to 3L * 1024 * 1024)).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                assertArrayEquals(JPEG, client.thumbnail(GalleryCapture(original, "clip.mp4", 100000000, null, CaptureKind.VIDEO)))
            }
            assertEquals(listOf("RETR $fallback"), server.commands.filter { it.startsWith("RETR ") })
            assertFalse(server.commands.contains("SIZE $original"))
            server.failure.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun nonImageSidecarIsRejectedAndNextRealImageIsUsed() {
        val original = "$ROOT/video/clip.webm"
        val wrong = "$ROOT/thumbnails/video/clip.webm.jxr"
        val fallback = "$ROOT/thumbnails/video/clip.webm.jpeg"
        FtpStandIn(files = mapOf(wrong to byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()), fallback to JPEG)).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                assertArrayEquals(JPEG, client.thumbnail(GalleryCapture(original, "clip.webm", 80000000, null, CaptureKind.VIDEO)))
            }
            assertEquals(listOf("RETR $wrong", "RETR $fallback"), server.commands.filter { it.startsWith("RETR ") })
            assertFalse(server.commands.contains("RETR $original"))
            server.failure.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun missingPostersNeverFallBackToDownloadingTheVideo() {
        val original = "$ROOT/video/clip.webm"
        FtpStandIn(files = mapOf(original to DATA)).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                assertThrows(IOException::class.java) {
                    client.thumbnail(GalleryCapture(original, "clip.webm", DATA.size.toLong(), null, CaptureKind.VIDEO))
                }
            }
            assertTrue(server.commands.none { it.startsWith("RETR ") })
            assertFalse(server.commands.contains("SIZE $original"))
            server.failure.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun undecodableNativePosterCanUseAnotherActualConsoleImage() {
        val original = "$ROOT/video/clip.webm"
        val native = "$ROOT/thumbnails/video/clip.webm.jxr"
        val fallback = "$ROOT/thumbnails/video/clip.webm.jpeg"
        FtpStandIn(files = mapOf(native to JXR, fallback to JPEG)).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                val result = client.thumbnailImage(GalleryCapture(original, "clip.webm", 80000000, null, CaptureKind.VIDEO)) { bytes ->
                    if(GalleryFtpProtocol.isJxr(bytes)) throw IOException("Unsupported fixture image encoding")
                    bytes
                }
                assertArrayEquals(JPEG, result)
            }
            assertEquals(listOf("RETR $native", "RETR $fallback"), server.commands.filter { it.startsWith("RETR ") })
            assertFalse(server.commands.contains("RETR $original"))
        }
    }

    @Test fun jpegXrScreenshotUsesTheVerifiedDoubleSuffixThumbnailFirst() {
        val original = "$ROOT/photo/NPXS40087/PPSA12345/abc/shot.jxr"
        val thumbnail = "$ROOT/thumbnails/photo/NPXS40087/PPSA12345/abc/shot.jxr.jxr"
        FtpStandIn(files = mapOf(thumbnail to JXR, original to DATA)).use { server ->
            GalleryFtpClient("127.0.0.1", server.port, ROOT, 2000).use { client ->
                assertArrayEquals(JXR, client.thumbnail(GalleryCapture(original, "shot.jxr", 30000000, null, CaptureKind.PHOTO)))
            }
            assertEquals(listOf("RETR $thumbnail"), server.commands.filter { it.startsWith("RETR ") })
            assertFalse(server.commands.contains("SIZE $original"))
        }
    }

    @Test fun imageSignatureAcceptsBothJxrByteOrdersAndRejectsVideoHeaders() {
        assertTrue(GalleryFtpProtocol.isJxr(JXR))
        assertTrue(GalleryFtpProtocol.isJxr(byteArrayOf(0x4d, 0x4d, 1, 0xbc.toByte())))
        assertTrue(GalleryFtpProtocol.imageSignature(JPEG))
        assertFalse(GalleryFtpProtocol.imageSignature(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertFalse(GalleryFtpProtocol.imageSignature(byteArrayOf(0x49, 0x49, 0xbc.toByte())))
    }

    private class FtpStandIn(
        private val machine: Boolean = true,
        private val extended: Boolean = true,
        private val reportedSize: Long = DATA.size.toLong(),
        private val blockRetr: Boolean = false,
        private val sizeAfter: Long? = null,
        private val files: Map<String, ByteArray>? = null,
        private val fileSizes: Map<String, Long> = emptyMap()
    ): Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = listener.localPort
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val failure = AtomicReference<Throwable?>()
        val dataStarted = CountDownLatch(1)
        @Volatile private var stopped = false
        @Volatile private var peer: Socket? = null
        @Volatile private var passive: ServerSocket? = null
        private val worker = thread(isDaemon = true, name = "gallery-ftp-test") {
            try {
                listener.accept().use { socket ->
                    peer = socket
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    val writer = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { writer.write(text + "\r\n"); writer.flush() }
                    fun send(bytes: ByteArray, block: Boolean = false) {
                        reply("150 Opening binary data")
                        passive!!.accept().use { data ->
                            if(block) { dataStarted.countDown(); data.getInputStream().read() }
                            else data.getOutputStream().write(bytes)
                        }
                        passive!!.close(); passive = null
                        reply("226 Transfer complete")
                    }
                    reply("220-Local protocol test")
                    reply("220 Ready")
                    var currentDirectory = "/"
                    var retrieved = false
                    while(!stopped) {
                        val line = reader.readLine() ?: break
                        commands.add(line)
                        val name = line.substringBefore(' ')
                        val argument = line.substringAfter(' ', "")
                        when(name) {
                            "USER" -> reply("331 Password required")
                            "PASS" -> reply("230 Logged in")
                            "TYPE", "OPTS" -> reply("200 OK")
                            "CWD" -> { currentDirectory = argument; reply("250 Directory changed") }
                            "PWD" -> reply("257 \"$currentDirectory\" is current")
                            "EPSV", "PASV" -> {
                                if(name == "EPSV" && !extended) reply("502 EPSV unavailable")
                                else {
                                    passive?.close()
                                    passive = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }
                                    val p = passive!!.localPort
                                    if(name == "EPSV") reply("229 Passive (|||$p|)")
                                    else reply("227 Passive (203,0,113,99,${p / 256},${p % 256})")
                                }
                            }
                            "MLSD" -> if(!machine) reply("502 MLSD unavailable") else send(
                                if(currentDirectory == "$ROOT/photo") "type=file;size=$reportedSize;modify=20260102030405; capture.jpg\r\n".toByteArray()
                                else byteArrayOf())
                            "LIST" -> send(if(currentDirectory == "$ROOT/photo")
                                "-rw-r--r-- 1 root wheel $reportedSize Jan 02 2026 capture.jpg\r\n".toByteArray()
                                else byteArrayOf())
                            "SIZE" -> if(files == null)
                                reply("213 " + if(retrieved) sizeAfter ?: reportedSize else reportedSize)
                                else if(files.containsKey(argument)) reply("213 ${fileSizes[argument] ?: files.getValue(argument).size.toLong()}")
                                else reply("550 No such image sidecar")
                            "RETR" -> if(files == null) { send(DATA, blockRetr); retrieved = true }
                                else files[argument]?.let { send(it, blockRetr); retrieved = true }
                                    ?: reply("550 No such image sidecar")
                            else -> reply("500 Unknown command")
                        }
                    }
                }
            } catch(error: Throwable) { if(!stopped) failure.set(error) }
        }

        override fun close() {
            stopped = true
            runCatching { peer?.close() }
            runCatching { passive?.close() }
            runCatching { listener.close() }
            worker.join(1000)
        }
    }

    companion object {
        private const val ROOT = "/user/av_contents"
        private val DATA = byteArrayOf(1, 2, 3, 4, 5, 6)
        // Transport fixtures contain signatures only; they do not claim native image decoding.
        private val JXR = byteArrayOf(0x49, 0x49, 0xbc.toByte(), 1, 0x20, 0, 0, 0, 0x24, 0xc3.toByte(), 0xdd.toByte(), 0x6f)
        private val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 0x10, 0x4a, 0x46)
    }
}

