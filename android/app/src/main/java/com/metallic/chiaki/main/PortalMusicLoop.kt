// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * Feeds a prepared stereo PCM loop to one continuous AudioTrack. File boundaries are
 * filled inside the same output buffer: no decoder seek, player restart or silence is
 * inserted at the wrap. The loop asset itself must have an acoustically matched join.
 */
internal class PortalMusicLoop(
    private val context: Context,
    private val asset: String,
    private val onError: (PortalMusicLoop, Exception) -> Unit
) : Closeable {
    private val gate = java.lang.Object()
    @Volatile private var output: AudioTrack? = null
    @Volatile private var closed = false
    private var requested = false
    private var started = false
    private var volume = .55f
    private val worker = Thread(::pump, "RelayMenuMusic")

    fun play() = synchronized(gate) {
        if(closed) return@synchronized
        requested = true
        if(!started) { started = true; worker.start() }
        gate.notifyAll()
    }

    fun pause() = synchronized(gate) {
        requested = false
        // Pause retains queued samples and the cursor for a seamless menu resume.
        runCatching { output?.pause() }
        gate.notifyAll()
    }

    fun setVolume(value: Float) = synchronized(gate) {
        volume = value.coerceIn(0f, 1f)
        runCatching { output?.setVolume(volume) }
    }

    private fun awaitPlayback(track: AudioTrack): Boolean = synchronized(gate) {
        while(!requested && !closed) gate.wait()
        if(closed) return@synchronized false
        if(track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
        true
    }

    private fun pump() {
        var source: InputStream? = null
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT)
            if(minimum <= 0) throw IOException("No stereo PCM output is available")
            val active = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum * 4, RATE * FRAME_BYTES / 3)).build()
            track = active
            if(active.state != AudioTrack.STATE_INITIALIZED) throw IOException("Menu audio output did not initialize")
            synchronized(gate) {
                if(closed) return
                output = active
                active.setVolume(volume)
            }
            source = context.assets.open(asset)
            val buffer = ByteArray(16_384) // A multiple of the four-byte stereo frame.
            var cycleBytes = 0L
            var firstCycleBytes = 0L
            var loops = 0L
            while(!closed) {
                if(!awaitPlayback(active)) break
                var filled = 0
                while(filled < buffer.size && !closed) {
                    val count = source!!.read(buffer, filled, buffer.size - filled)
                    if(count < 0) {
                        if(cycleBytes == 0L || cycleBytes % FRAME_BYTES != 0L)
                            throw IOException("Menu loop has an invalid PCM frame boundary")
                        if(firstCycleBytes == 0L) firstCycleBytes = cycleBytes
                        if(cycleBytes != firstCycleBytes) throw IOException("Menu loop changed while playing")
                        source.close()
                        source = context.assets.open(asset)
                        cycleBytes = 0L
                        loops++
                        Log.d("PortalMenuAudio", "Continuous PCM loop $loops queued; underruns=${active.underrunCount}")
                    } else if(count == 0) {
                        throw IOException("Menu loop stopped supplying PCM samples")
                    } else {
                        filled += count
                        cycleBytes += count
                    }
                }
                var written = 0
                while(written < filled && !closed) {
                    if(!awaitPlayback(active)) break
                    val count = active.write(buffer, written, filled - written, AudioTrack.WRITE_BLOCKING)
                    if(count < 0) throw OutputException(count)
                    if(count == 0) synchronized(gate) { if(!closed) gate.wait(10L) }
                    else written += count
                }
            }
        } catch(interrupted: InterruptedException) {
            if(!closed) onError(this, IOException("Menu audio thread was interrupted", interrupted))
        } catch(error: Exception) {
            if(!closed) onError(this, error)
        } finally {
            synchronized(gate) { output = null }
            runCatching { source?.close() }
            runCatching { track?.pause() }
            track?.release()
        }
    }

    override fun close() {
        synchronized(gate) {
            closed = true
            requested = false
            runCatching { output?.pause(); output?.flush() }
            gate.notifyAll()
        }
        worker.interrupt()
    }

    class OutputException(val code: Int) : IOException("Menu audio output failed ($code)")

    companion object {
        private const val RATE = 48_000
        private const val FRAME_BYTES = 4
    }
}
