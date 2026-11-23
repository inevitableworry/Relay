// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.metallic.chiaki.common.PortalPreferences
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 13+ offline speech input with scoped playback audio, based on the
 * AudioRecord/MediaProjection lifecycle pattern studied in InstantCaption.
 * The recognition backend is Android's on-device SpeechRecognizer, using an
 * external PCM pipe (API 33) rather than InstantCaption's native sherpa model.
 * The ML Kit offline translator is shared with Relay's existing OCR feature.
 */
class PortalAudioCaptionService : Service() {
    companion object {
        const val START = "com.metallic.chiaki.START_AUDIO_CAPTIONS"
        const val STOP = "com.metallic.chiaki.STOP_AUDIO_CAPTIONS"
        const val EXTRA_CONSENT = "projection_data"
        const val EXTRA_LANGUAGE = "spoken_language"
        private const val CHANNEL = "relay_audio_captions"
        private const val NOTIFICATION_ID = 18732
        private const val SAMPLE_RATE = 16000
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var audioThread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var language = "ja-JP"
    private var target = "en"
    private var latest = ""
    private var translationReady = false
    private var translationPending = false
    private var session = 0
    private var input: ParcelFileDescriptor? = null
    @Volatile private var output: OutputStream? = null
    private var failure = ""
    private var listeningReady = false
    private var consecutiveEngineErrors = 0
    private var displayedSource = ""
    private var displayedTranslation = ""
    private var lastCaptionAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Relay spoken game captions", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START || running.get()) return START_NOT_STICKY
        try {
            require(Build.VERSION.SDK_INT >= 33) { "Android 13 or newer required" }
            require(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Audio recording permission required"
            }
            require(SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                "No on-device speech recognition service is installed"
            }
            language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "ja-JP"
            target = PortalPreferences(this).translationTarget
            // Foreground must be established before calling getMediaProjection
            // on Android 14+. This is an audio-only capture: no VirtualDisplay.
            val open = PendingIntent.getActivity(this, 0, Intent(this, PortalMainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1,
                Intent(this, PortalAudioCaptionService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notice = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Relay · Spoken Game Captions")
                .setContentText("Listening to this app's game playback audio")
                .setContentIntent(open)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
            startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val data = intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
                ?: throw IllegalArgumentException("Screen-sharing audio permission expired")
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(Activity.RESULT_OK, data)
                ?: throw IllegalStateException("Android refused playback audio capture")
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (running.get()) {
                        failure = "Android ended playback capture permission"
                        stopSelf()
                    }
                }
            }, handler)
            val sampleFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                // Critical: exclude every other application, notification and
                // phone call even though MediaProjection consent is global.
                .addMatchingUid(applicationInfo.uid)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "16 kHz playback PCM not supported" }
            recorder = AudioRecord.Builder()
                .setAudioFormat(sampleFormat)
                .setAudioPlaybackCaptureConfig(capture)
                .setBufferSizeInBytes(maxOf(min * 4, SAMPLE_RATE * 2))
                .build()
            require(recorder!!.state == AudioRecord.STATE_INITIALIZED) { "Could not initialize playback capture" }
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
            running.set(true)
            recorder!!.startRecording()
            require(recorder!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android did not start audio recording"
            }
            publish("Preparing offline speech recognition")
            if (language.substringBefore('-') != target) prepareTranslator()
            startSegment()
            audioThread = Thread({ pumpAudio() }, "relay-caption-pcm").also { it.start() }
        } catch (error: Exception) {
            failure = "Live audio captions unavailable: ${error.localizedMessage ?: error.javaClass.simpleName}"
            PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(status = failure))
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun prepareTranslator() {
        val source = language.substringBefore('-')
        if (source !in TranslateLanguage.getAllLanguages() ||
            target !in TranslateLanguage.getAllLanguages()) {
            failure = "This speech language cannot be translated by the installed ML Kit language set"
            return
        }
        translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(source).setTargetLanguage(target).build())
        translationPending = true
        translator!!.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener {
                if (running.get()) { translationReady = true; translateLatest() }
            }
            .addOnFailureListener {
                if (running.get()) publish("Offline translation model unavailable: ${it.localizedMessage ?: "connect to Wi-Fi"}")
            }
            .addOnCompleteListener { translationPending = false }
    }

    private fun startSegment() {
        if (!running.get()) return
        try {
            val pair = ParcelFileDescriptor.createPipe()
            input = pair[0]
            output = ParcelFileDescriptor.AutoCloseOutputStream(pair[1])
            listeningReady = false
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, input)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            }
            recognizer?.startListening(intent)
        } catch (error: Exception) {
            failure = "Could not feed offline speech recognizer: ${error.localizedMessage}"
            stopSelf()
        }
    }

    private fun pumpAudio() {
        val pcm = ByteArray(SAMPLE_RATE / 2) // 250 ms, 16-bit mono
        while (running.get()) {
            val n = try { recorder?.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING) ?: -1 }
                catch (_: Exception) { -1 }
            if (n <= 0) {
                if (running.get()) handler.post {
                    failure = "Playback capture ended (audio unavailable or protected)"
                    stopSelf()
                }
                break
            }
            val writer = output
            if (writer != null) try { writer.write(pcm, 0, n) } catch (_: Exception) {
                // Speech recognizer has closed this segment; next segment starts on the main thread.
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listeningReady = true
            publish("Ready for speech")
        }
        override fun onBeginningOfSpeech() { publish("Recognizing speech") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { publish("Transcribing…") }
        override fun onError(code: Int) {
            if (!running.get()) return
            closePipe()
            if (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                failure = "On-device speech pack missing for $language. Install it in Android voice services."
                stopSelf()
                return
            }
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                failure = "Speech recognition permission denied"
                stopSelf()
                return
            }
            if (code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                failure = "Android's on-device speech service disconnected"
                stopSelf()
                return
            }
            if (code == SpeechRecognizer.ERROR_CLIENT || code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                publish("Speech recognizer busy; retrying")
            }
            if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                ++consecutiveEngineErrors > 5) {
                failure = "On-device recognizer failed repeatedly (error $code)"
                stopSelf()
                return
            }
            handler.postDelayed({ startSegment() }, 400)
        }
        override fun onResults(results: Bundle?) {
            consecutiveEngineErrors = 0
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (words.isNotBlank()) received(words, final = true)
            closePipe()
            handler.postDelayed({ startSegment() }, 350)
        }
        override fun onPartialResults(results: Bundle?) {
            val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (words.isNotBlank()) received(words, final = false)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun received(words: String, final: Boolean) {
        if (!running.get()) return
        val safe = words.take(350)
        if (final) latest = safe
        publish(if (final) "Translating recognized speech" else "Live speech draft",
            safe, "")
        if (!final) return
        if (language.substringBefore('-') == target) {
            publish("Original-language caption", safe, safe)
        } else translateLatest()
    }

    private fun translateLatest() {
        if (!running.get() || !translationReady || latest.isBlank()) return
        val original = latest
        val token = ++session
        translator?.translate(original)
            ?.addOnSuccessListener { translated ->
                if (running.get() && token == session && original == latest) {
                    publish("Offline speech translated", original, translated.take(500))
                }
            }?.addOnFailureListener {
                if (running.get() && token == session)
                    publish("Could not translate speech: ${it.localizedMessage}", original)
            }
    }

    private fun publish(status: String, original: String? = null, translated: String? = null) {
        if (original != null) {
            displayedSource = original
            lastCaptionAt = SystemClock.elapsedRealtime()
        }
        if (translated != null) {
            displayedTranslation = translated
            lastCaptionAt = SystemClock.elapsedRealtime()
        }
        // Preserve the final words across SpeechRecognizer segment restarts.
        // InstantCaption uses bounded visible history for the same reason.
        val recent = SystemClock.elapsedRealtime() - lastCaptionAt < 10000L
        PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(
            status = status,
            original = if (recent) displayedSource else "",
            translated = if (recent) displayedTranslation else "",
            running = running.get(), ready = listeningReady))
    }

    private fun closePipe() {
        val old = output
        output = null
        runCatching { old?.close() }
        runCatching { input?.close() }
        input = null
    }

    override fun onDestroy() {
        running.set(false)
        handler.removeCallbacksAndMessages(null)
        closePipe()
        runCatching { recorder?.stop() }
        runCatching { audioThread?.interrupt() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        translator?.close()
        translator = null
        runCatching { projection?.stop() }
        projection = null
        PortalAudioCaptionBus.update(PortalAudioCaptionBus.Snapshot(
            status = failure.ifBlank { "Spoken captions stopped" }))
        super.onDestroy()
    }
}
