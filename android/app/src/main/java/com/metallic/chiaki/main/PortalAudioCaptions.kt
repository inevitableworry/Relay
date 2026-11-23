// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.metallic.chiaki.common.PortalPreferences
import java.util.Locale

/**
 * Spoken dialogue travels through its own opt-in playback-audio capture path.
 * The OCR/Play Translate video pipeline remains scoped to decoded game frames.
 * No screen image, microphone audio or audio from another application's UID
 * is requested. MediaProjection consent is always obtained from Android.
 */
object PortalAudioCaptionBus {
    data class Snapshot(
        val status: String = "",
        val original: String = "",
        val translated: String = "",
        val running: Boolean = false,
        val ready: Boolean = false
    )
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableSetOf<(Snapshot) -> Unit>()
    private var current = Snapshot()
    val state: Snapshot get() = current

    fun listen(observer: (Snapshot) -> Unit) {
        handler.post { observers.add(observer); observer(current) }
    }
    fun remove(observer: (Snapshot) -> Unit) {
        handler.post { observers.remove(observer) }
    }
    fun update(snapshot: Snapshot) {
        handler.post {
            current = snapshot
            observers.toList().forEach { it(snapshot) }
        }
    }
}

/**
 * ActivityResult registration occurs during Activity.onCreate, so Android can
 * restore permission callbacks correctly across activity recreation.
 */
class PortalAudioCaptionsController(
    private val activity: AppCompatActivity,
    private val parent: FrameLayout,
    private val onReady: (() -> Unit)? = null,
    private val onState: ((PortalAudioCaptionBus.Snapshot) -> Unit)? = null,
    private val spokenLanguageOverride: String? = null
) {
    private val text = TextView(activity).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = GradientDrawable().apply {
            setColor(0xe51a1937.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xff7898d2.toInt())
        }
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        contentDescription = "Live translated spoken game captions"
    }
    private val projection = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(status = "Audio capture permission declined"))
        } else {
            val intent = Intent(activity, PortalAudioCaptionService::class.java)
                .setAction(PortalAudioCaptionService.START)
                .putExtra(PortalAudioCaptionService.EXTRA_CONSENT, result.data)
                .putExtra(PortalAudioCaptionService.EXTRA_LANGUAGE,
                    spokenLanguageOverride ?: PortalPreferences(activity).captionSpokenLanguage)
            runCatching { ContextCompat.startForegroundService(activity, intent) }
                .onFailure {
                    PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(
                        status = "Could not start audio captions: ${it.localizedMessage ?: "service unavailable"}"))
                }
        }
    }
    private val recordPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection()
        else PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(status = "Audio recording permission declined"))
    }
    private var readySent = false
    private val observer: (PortalAudioCaptionBus.Snapshot) -> Unit = { state ->
        val options = PortalPreferences(activity)
        text.textSize = options.translationTextSizeSp.toFloat()
        text.background = GradientDrawable().apply {
            setColor(((options.translationBackgroundOpacity * 255 / 100) shl 24) or 0x101429)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xff7898d2.toInt())
        }
        val original = state.original.takeIf { options.captionShowOriginal && it.isNotBlank() }
        val caption = state.translated.ifBlank { state.original }
        text.text = listOfNotNull(original?.takeIf { it != caption }, caption.takeIf { it.isNotBlank() })
            .joinToString("\n")
        text.visibility = if (state.running && text.text.isNotBlank()) View.VISIBLE else View.GONE
        if (state.ready && !readySent) {
            readySent = true
            onReady?.invoke()
        }
        if (!state.running) readySent = false
        onState?.invoke(state)
    }

    init {
        parent.addView(text, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(48)
            rightMargin = dp(48)
            bottomMargin = dp(142)
        })
        PortalAudioCaptionBus.listen(observer)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    fun toggle() {
        if (PortalAudioCaptionBus.state.running) {
            stop()
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(
                status = "Spoken captions require Android 13 or newer for offline PCM speech input"))
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) recordPermission.launch(Manifest.permission.RECORD_AUDIO)
        else requestProjection()
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection.launch(manager.createScreenCaptureIntent())
        } catch (error: Exception) {
            PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(
                status = "Playback permission unavailable: ${error.localizedMessage ?: "unsupported device"}"))
        }
    }

    fun stop() {
        activity.stopService(Intent(activity, PortalAudioCaptionService::class.java))
        PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(status = "Spoken captions stopped"))
    }

    fun dispose() {
        PortalAudioCaptionBus.remove(observer)
        parent.removeView(text)
    }
}
