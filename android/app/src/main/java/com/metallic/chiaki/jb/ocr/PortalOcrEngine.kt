// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** OCR inputs are owned, decoded-game-video crops. Caller keeps Bitmap alive until Task completes. */
internal interface PortalOcrEngine : AutoCloseable {
    val id: String
    fun recognize(gameVideoCrop: Bitmap): Task<PortalOcrFrame>
}

internal enum class PortalOcrScript(val id: String) {
    LATIN("latin"), JAPANESE("ja"), CHINESE("zh"), KOREAN("ko"), DEVANAGARI("hi")
}

/** All bounds are relative to the cropped OCR bitmap, not the phone/TV screen. */
internal data class PortalOcrTextRegion(val text: String, val bounds: PortalPixelCrop)
internal data class PortalOcrFrame(
    val engineId: String,
    val script: PortalOcrScript,
    val text: String,
    val regions: List<PortalOcrTextRegion>,
)

/** Pure, deterministic script scoring so recognition selection can be unit-tested. */
internal object PortalOcrScriptScore {
    fun score(script: PortalOcrScript, text: String): Int {
        val matches = when (script) {
            PortalOcrScript.LATIN -> text.count { it in 'A'..'Z' || it in 'a'..'z' || it in '\u00c0'..'\u024f' }
            PortalOcrScript.JAPANESE -> text.count { it in '\u3040'..'\u30ff' } * 4 + text.count { it in '\u4e00'..'\u9fff' }
            PortalOcrScript.CHINESE -> text.count { it in '\u4e00'..'\u9fff' } * 4
            PortalOcrScript.KOREAN -> text.count { it in '\uac00'..'\ud7af' } * 4
            PortalOcrScript.DEVANAGARI -> text.count { it in '\u0900'..'\u097f' } * 4
        }
        return matches
    }
}

/**
 * Existing ML Kit readers exposed behind a single replaceable engine contract.
 * Automatic mode tries all five script readers in sequence and chooses the highest
 * source-script evidence, then returns positioned, per-line regions. This keeps
 * OCR computation confined to the caller-supplied game bitmap.
 *
 * A future Paddle/MNN implementation can implement [PortalOcrEngine] without
 * changing crop and translation consumers. No camera/window/screen capture API.
 */
internal class PortalMlKitOcrEngine private constructor(
    private val readers: List<Pair<PortalOcrScript, TextRecognizer>>,
) : PortalOcrEngine {
    private val closed = AtomicBoolean(false)
    override val id: String = if (readers.size == 1) "mlkit-${readers.first().first.id}" else "mlkit-auto"

    override fun recognize(gameVideoCrop: Bitmap): Task<PortalOcrFrame> {
        val promise = TaskCompletionSource<PortalOcrFrame>()
        if (closed.get() || gameVideoCrop.isRecycled) {
            promise.setException(IllegalStateException("OCR engine is closed or bitmap was recycled"))
            return promise.task
        }
        val readings = mutableListOf<PortalOcrFrame>()
        var firstError: Exception? = null

        fun read(index: Int) {
            if (closed.get()) {
                promise.trySetException(IllegalStateException("OCR engine closed during recognition"))
                return
            }
            if (index == readers.size) {
                if (readings.isEmpty() && firstError != null) {
                    promise.trySetException(firstError!!)
                } else {
                    promise.trySetResult(
                        readings.maxWithOrNull(compareBy<PortalOcrFrame>(
                            { PortalOcrScriptScore.score(it.script, it.text) }, { it.text.length },
                        )) ?: PortalOcrFrame(id, readers.first().first, "", emptyList()),
                    )
                }
                return
            }
            val (script, recognizer) = readers[index]
            try {
                recognizer.process(InputImage.fromBitmap(gameVideoCrop, 0)).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        readings.add(toFrame(script, task.result))
                    } else if (firstError == null) {
                        firstError = task.exception ?: IllegalStateException("ML Kit recognition failed")
                    }
                    read(index + 1)
                }
            } catch (error: Exception) {
                if (firstError == null) firstError = error
                read(index + 1)
            }
        }
        read(0)
        return promise.task
    }

    private fun toFrame(script: PortalOcrScript, result: Text): PortalOcrFrame {
        val regions = result.textBlocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .mapNotNull { line ->
                val value = PortalOcrTextNormalizer.cleanLine(line.text)
                val bounds = line.boundingBox
                if (value.isBlank() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) null
                else PortalOcrTextRegion(value, PortalPixelCrop(bounds.left, bounds.top, bounds.width(), bounds.height()))
            }
            .take(MAX_REGIONS)
            .toList()
        val text = if (regions.isNotEmpty()) regions.joinToString("\n") { it.text }
            else result.text.lineSequence().map(PortalOcrTextNormalizer::cleanLine)
                .filter(String::isNotBlank).take(MAX_REGIONS).joinToString("\n")
        return PortalOcrFrame(id, script, text.take(MAX_TEXT_CHARS), regions)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) readers.forEach { it.second.close() }
    }

    companion object {
        private const val MAX_REGIONS = 40
        private const val MAX_TEXT_CHARS = 1024

        fun single(script: PortalOcrScript): PortalMlKitOcrEngine =
            PortalMlKitOcrEngine(listOf(script to recognizer(script)))

        fun automatic(): PortalMlKitOcrEngine = PortalMlKitOcrEngine(
            PortalOcrScript.entries.map { it to recognizer(it) },
        )

        private fun recognizer(script: PortalOcrScript): TextRecognizer = when (script) {
            PortalOcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            PortalOcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            PortalOcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            PortalOcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            PortalOcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
    }
}
