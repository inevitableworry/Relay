// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.common

import android.content.Context
import android.net.Uri
import com.metallic.chiaki.jb.ocr.PortalVideoCrop

/** Mode-specific configuration never overwrites libchiaki's paired-console settings. */
class PortalPreferences(context: Context) {
    enum class Mode { TRADITIONAL, JB }
    private val store = context.getSharedPreferences("relay_portal", Context.MODE_PRIVATE)
    init {
        // Earlier development builds stored fixed player/account assignments.
        // Party Mode now allocates by connection order and defers identity to PS5.
        if(store.contains("jb_player_slot") || store.contains("jb_requested_account"))
            store.edit().remove("jb_player_slot").remove("jb_requested_account").apply()
        // The console previously rejected 2160p native capture. Only for that
        // durable failure, lower the old Auto ceiling once so the first post-
        // restart session cannot repeat the failed 4K request silently.
        if(!store.getBoolean("jb_2160_recovery_migrated",false)) {
            val failure=context.getSharedPreferences("relay_capture_quarantine",Context.MODE_PRIVATE)
                .all.values.any { value ->
                    val fields=(value as? String)?.split('|')
                    fields?.size==5 && fields[2]=="2160" && fields[4]=="native_open_failed"
                }
            if(failure) {
                store.edit().putInt("jb_height",1080)
                    .putInt("jb_auto_max_height",1080)
                    .putBoolean("jb_2160_recovery_migrated",true).apply()
            }
        }
    }
    var mode: Mode
        get() = runCatching { Mode.valueOf(store.getString("mode", "TRADITIONAL")!!) }
            .getOrDefault(Mode.TRADITIONAL)
        set(value) { store.edit().putString("mode", value.name).apply() }
    /** Translation is opt-in and runs only while a decoded Remote Play session is active. */
    var autoTranslateGame: Boolean
        get() = store.getBoolean("auto_translate_game", false)
        set(value) { store.edit().putBoolean("auto_translate_game", value).apply() }
    var translationTarget: String
        get() = store.getString("translation_target", java.util.Locale.getDefault().language
            .takeIf { it in com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages() }
            ?: "en") ?: "en"
        set(value) {
            require(value in com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages())
            store.edit().putString("translation_target", value).apply()
        }
    /** ONCE_PER_VIDEO_FRAME is intentionally not offered: OCR must not starve decoding. */
    var translationRegion: String
        get() = store.getString("translation_region", "FULL").orEmpty().takeIf {
            it in listOf("FULL","SUBTITLES","CENTER","TOP","CUSTOM")
        } ?: "FULL"
        set(value) {
            require(value in listOf("FULL","SUBTITLES","CENTER","TOP","CUSTOM"))
            store.edit().putString("translation_region",value).apply()
        }
    internal var translationCustomCrop:PortalVideoCrop
        get() = PortalVideoCrop.decode(store.getString("translation_custom_crop",null))
            ?: PortalVideoCrop.preset("SUBTITLES")
        set(value) {store.edit().putString("translation_custom_crop",value.encode()).apply()}
    var translationPlacement: String
        get() = store.getString("translation_placement","SOURCE").orEmpty().takeIf {
            it in listOf("SOURCE","SUBTITLE")
        } ?: "SOURCE"
        set(value) {
            require(value in listOf("SOURCE","SUBTITLE"))
            store.edit().putString("translation_placement",value).apply()
        }
    /** Experimental: restore OCR text strokes from nearby video pixels, no caption panels. */
    var translationNaturalInpaint:Boolean
        get()=store.getBoolean("translation_natural_inpaint",false)
        set(value){store.edit().putBoolean("translation_natural_inpaint",value).apply()}
    var translationSpeech: Boolean
        get() = store.getBoolean("translation_speech",false)
        set(value) {store.edit().putBoolean("translation_speech",value).apply()}
    /** English voice for translated dialogue and Japanese translation lab read-aloud tests. */
    var translationSpeechLanguage: String
        get() = store.getString("translation_speech_language","en-US").orEmpty()
            .takeIf { it in listOf("en-US","en-GB","en-AU","en-CA","en-IN") } ?: "en-US"
        set(value) {
            require(value in listOf("en-US","en-GB","en-AU","en-CA","en-IN"))
            store.edit().putString("translation_speech_language",value).apply()
        }
    /** Normal Android TTS speech rate is 1.0f; both game and test speech can use this. */
    var translationSpeechRate: Float
        get() = store.getFloat("translation_speech_rate",1f).coerceIn(.75f,1.5f)
        set(value) {
            require(value.isFinite() && value in .75f..1.5f)
            store.edit().putFloat("translation_speech_rate",value).apply()
        }
    var translationHotkey: Boolean
        get() = store.getBoolean("translation_hotkey",false)
        set(value) {store.edit().putBoolean("translation_hotkey",value).apply()}
    /** The on-device speech recognizer interprets this language, independent of OCR. */
    var captionSpokenLanguage: String
        get() = store.getString("caption_spoken_language","ja-JP") ?: "ja-JP"
        set(value) {
            require(value in listOf("ja-JP","en-US","es-ES","fr-FR","de-DE",
                "it-IT","pt-BR","ko-KR","zh-CN","ru-RU","ar-SA","hi-IN"))
            store.edit().putString("caption_spoken_language",value).apply()
        }
    var captionShowOriginal: Boolean
        get() = store.getBoolean("caption_show_original",true)
        set(value) {store.edit().putBoolean("caption_show_original",value).apply()}
    var translationHistoryEnabled: Boolean
        get() = store.getBoolean("translation_history_enabled",false)
        set(value) {store.edit().putBoolean("translation_history_enabled",value).apply()}
    var translationTextSizeSp:Int
        get() = store.getInt("translation_text_size_sp",19).coerceIn(12,32)
        set(value) { require(value in 12..32);store.edit().putInt("translation_text_size_sp",value).apply() }
    var translationBackgroundOpacity:Int
        get() = store.getInt("translation_background_opacity",85).coerceIn(0,100)
        set(value) { require(value in 0..100);store.edit().putInt("translation_background_opacity",value).apply() }
    var translationScanIntervalMs:Int
        get() = store.getInt("translation_scan_interval_ms",3400).coerceIn(800,6000)
        set(value) {require(value in 800..6000);store.edit().putInt("translation_scan_interval_ms",value).apply()}
    var translationOcrScript:String
        get()=store.getString("translation_ocr_script","AUTO").orEmpty().takeIf {
            it in listOf("AUTO","LATIN","JAPANESE","CHINESE","KOREAN","DEVANAGARI")
        } ?: "AUTO"
        set(value) {
            require(value in listOf("AUTO","LATIN","JAPANESE","CHINESE","KOREAN","DEVANAGARI"))
            store.edit().putString("translation_ocr_script",value).apply()
        }
    var cloudDialogueContext:Boolean
        get()=store.getBoolean("cloud_dialogue_context",false)
        set(value){store.edit().putBoolean("cloud_dialogue_context",value).apply()}
    var jbHost: String
        get() = store.getString("jb_host", "192.0.2.10")!!
        set(value) { require(validHost(value)); store.edit().putString("jb_host", value.trim()).apply() }
    var jbPort: Int
        get() = store.getInt("jb_port", 9999)
        set(value) { require(value in 1..65535); store.edit().putInt("jb_port", value).apply() }
    var loaderPort: Int
        get() = store.getInt("loader_port", 9021)
        set(value) { require(value in 1..65535); store.edit().putInt("loader_port", value).apply() }
    var ftpPort: Int
        get() = store.getInt("ftp_port", 2121)
        set(value) { require(value in 1..65535); store.edit().putInt("ftp_port", value).apply() }
    var jbHeight: Int
        get() = store.getInt("jb_height", 1080)
        set(value) { require(value in listOf(720,1080,1440,2160)); store.edit().putInt("jb_height", value).apply() }
    var jbAutoQuality: Boolean
        get() = store.getBoolean("jb_auto_quality", true)
        set(value) { store.edit().putBoolean("jb_auto_quality", value).apply() }
    var jbAutoMaxHeight: Int
        get() = store.getInt("jb_auto_max_height", 1080)
        set(value) { require(value in listOf(720,1080,1440,2160)); store.edit().putInt("jb_auto_max_height", value).apply() }
    /** Zero lets Auto choose the encoder target; an explicit limit includes PCM and transport allowance. */
    var jbMaxBandwidthMbps: Int
        get() = store.getInt("jb_max_bandwidth_mbps", 0)
        set(value) { require(value == 0 || value in 6..120); store.edit().putInt("jb_max_bandwidth_mbps", value).apply() }
    var jbVideoBitrateMbps: Int
        get() = store.getInt("jb_video_bitrate_mbps", 0)
        set(value) { require(value in 0..100); store.edit().putInt("jb_video_bitrate_mbps", value).apply() }
    var jbMinQp: Int
        get() = store.getInt("jb_min_qp", 18)
        set(value) { require(value in 18..35); store.edit().putInt("jb_min_qp", value).apply() }
    var jbAudioBufferMs: Int
        get() = store.getInt("jb_audio_buffer_ms", 50)
        set(value) { require(value in listOf(20,50,100)); store.edit().putInt("jb_audio_buffer_ms", value).apply() }
    var jbAdvanced: Boolean
        get() = store.getBoolean("jb_advanced", false)
        set(value) { store.edit().putBoolean("jb_advanced", value).apply() }
    var jbAudio: Boolean
        get() = store.getBoolean("jb_audio", true)
        set(value) { store.edit().putBoolean("jb_audio", value).apply() }
    var autoPayload: Boolean
        get() = store.getBoolean("auto_payload", true)
        set(value) { store.edit().putBoolean("auto_payload", value).apply() }
    var galleryRoot: String
        get() = store.getString("gallery_root", "/user/av_contents")!!
        set(value) {
            require(value.startsWith("/") && !value.contains("..") &&
                !value.contains('\n') && !value.contains('\r'))
            store.edit().putString("gallery_root", value.trimEnd('/')).apply()
        }
    var galleryTree: Uri?
        get() = store.getString("gallery_tree", null)?.let(Uri::parse)
        set(value) { store.edit().putString("gallery_tree", value?.toString()).apply() }
    var brightness: Float
        get() = store.getFloat("brightness", -1f)
        set(value) { store.edit().putFloat("brightness", if(value < 0f) -1f else value.coerceIn(.03f,1f)).apply() }
    var reduceMotion: Boolean
        get() = store.getBoolean("reduce_motion", false)
        set(value) { store.edit().putBoolean("reduce_motion", value).apply() }
    var fitMode: String
        get() = store.getString("fit_mode", "FIT")!!
        set(value) { require(value in listOf("FIT","ZOOM","STRETCH")); store.edit().putString("fit_mode",value).apply() }

    companion object {
        fun validHost(host: String): Boolean {
            val value = host.trim()
            if(value.isBlank() || value.length > 253 || value.any { it.isWhitespace() || it in "/?#@\\" })
                return false
            return runCatching {
                val parsed = java.net.URI("http", null, value, 9999, "/", null, null)
                parsed.host != null
            }.getOrDefault(false)
        }
    }
}
