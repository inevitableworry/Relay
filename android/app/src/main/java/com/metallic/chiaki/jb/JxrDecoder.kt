package com.metallic.chiaki.jb

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.Keep

/** Bounded, on-device JPEG XR previews. Downloaded capture files remain unchanged. */
@Keep
object JxrDecoder {
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024
    private val available: Boolean by lazy {
        try {
            System.loadLibrary("relay-jxr")
            true
        } catch (error: LinkageError) {
            Log.e("RelayJxr", "JPEG XR preview library could not be loaded", error)
            false
        }
    }

    /** Call on a worker thread. Invalid, oversized, or unsupported captures return null. */
    @JvmStatic
    @Synchronized
    fun decode(bytes: ByteArray, maxEdge: Int = 2048): Bitmap? {
        if (bytes.size !in 16..MAX_INPUT_BYTES || maxEdge < 1 || !available) return null
        return try {
            decodeNative(bytes, maxEdge.coerceAtMost(2048))
        } catch (error: OutOfMemoryError) {
            Log.w("RelayJxr", "Insufficient memory for a JPEG XR preview")
            null
        } catch (error: LinkageError) {
            Log.e("RelayJxr", "JPEG XR native entry point is unavailable", error)
            null
        }
    }

    private external fun decodeNative(bytes: ByteArray, maxEdge: Int): Bitmap?
}
