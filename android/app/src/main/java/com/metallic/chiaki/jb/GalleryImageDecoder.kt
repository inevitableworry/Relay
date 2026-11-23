// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException

/** Bounded, on-device decoding shared by gallery cards and full image previews. */
internal object GalleryImageDecoder {
    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap {
        require(maxEdge in 1..2048) { "Invalid preview dimensions." }
        if(bytes.size !in 4..(32 * 1024 * 1024) || !GalleryFtpProtocol.imageSignature(bytes))
            throw IOException("This capture is not a supported preview image.")
        if(GalleryFtpProtocol.isJxr(bytes)) return JxrDecoder.decode(bytes, maxEdge)
            ?: throw IOException("This JPEG XR image could not be previewed. You can still transfer the original.")
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if(width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 32L * 1024 * 1024)
                throw IOException("This image is invalid or exceeds the preview size limit.")
            var sample = 1
            while((width + sample - 1) / sample > maxEdge || (height + sample - 1) / sample > maxEdge)
                sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IOException("This image could not be decoded.")
        } catch(error: OutOfMemoryError) {
            throw IOException("There is not enough memory for this preview.")
        }
    }
}
