// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.jb.PortalNaturalTextInpaint
import com.metallic.chiaki.jb.ocr.PortalMlKitOcrEngine
import com.metallic.chiaki.jb.ocr.PortalOcrScript
import com.metallic.chiaki.jb.ocr.PortalOcrTextRegion
import java.util.Locale
import java.io.File
import java.io.OutputStream
import kotlin.math.min

/** Four opt-in Japanese translation tests with separate OCR, audio and speech paths.
 * The video OCR path reads actual decoded TextureView frames. Spoken recognition
 * captures only Relay's playback UID after explicit Android consent.
 * No expected text is substituted for actual OCR or speech recognition results.
 */
class PortalJapaneseTranslationTestActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_NATURAL_PREVIEW = "relay_natural_preview"
        const val JAPANESE_SPEECH_SAMPLE = "こんにちは。今日はいい天気です。"
        private const val VIDEO_ASSET = "playtranslate/japanese-subtitled-60s.mp4"
        private const val VIDEO_FRAME_INTERVAL_MS = 1200L
    }
    private var recognizer: PortalMlKitOcrEngine? = null
    private var translator: Translator? = null
    private var speech: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var audioController: PortalAudioCaptionsController? = null
    private var audioCaptureReady=false
    private var audioFileReady=false
    private var audioTestRequested=false
    private var audioCombinedImage=false
    private var audioPlayed=false
    private var ttsReady=false
    private var videoPlayer: MediaPlayer? = null
    private var videoReader: PortalMlKitOcrEngine? = null
    private var videoTranslator: Translator? = null
    private var videoPrepared = false
    private var videoRequested = false
    private var videoModeVisible = false
    private var videoPlaying = false
    private var videoAudioResolved = false
    private var videoCombinedAudio = false
    private var videoDubbed = false
    private var dubRecognizerReady = false
    private var dubFailed = false
    @Volatile private var dubActive = false
    @Volatile private var dubOutput: OutputStream? = null
    private var dubThread: Thread? = null
    private var dubRecognizer: SpeechRecognizer? = null
    private var modelDownloadRecognizer: SpeechRecognizer? = null
    private var dubTranslator: Translator? = null
    private var dubReadPipe: ParcelFileDescriptor? = null
    private var dubTranslationReady = false
    private var dubTranslationBusy = false
    private val dubTranslationQueue = ArrayDeque<String>()
    private var dubSession = 0
    private var dubRecognizedCount = 0
    private var dubTranslatedCount = 0
    private var dubSpokenCount = 0
    private var dubUtteranceCount = 0
    private var dubLastSource = ""
    private var dubLastTranslation = ""
    private var dubPendingSpeech: String? = null
    private lateinit var dubPermissionRequest: ActivityResultLauncher<String>
    private var simultaneousMode = false
    private var videoTranslationReady = false
    private var videoTranslationBusy = false
    private var videoOcrBusy = false
    private var videoSession = 0
    private var videoRecognitions = 0
    private var videoTranslations = 0
    private var videoLastSource = ""
    private var videoPendingTranslation = ""
    private var videoLastTranslated = ""
    private var videoModelError = ""
    private var videoSpokenRecognized = false
    private var videoSpokenTranslated = false
    private val videoHandler = Handler(Looper.getMainLooper())
    private val videoFrameTick = object : Runnable {
        override fun run() {
            if (!videoPlaying) return
            readVideoFrame()
            videoHandler.postDelayed(this, VIDEO_FRAME_INTERVAL_MS)
        }
    }
    private val audioFixture by lazy { File(filesDir,"relay-japanese-spoken-test.wav") }
    private var generation = 0
    private var running = false
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var detected: TextView
    private lateinit var launch: TextView
    private lateinit var audioStatus: TextView
    private lateinit var audioDetected: TextView
    private lateinit var englishTtsStatus: TextView
    private lateinit var englishTtsSource: TextView
    private var englishTtsVideoSource = false
    private var englishSpeech: TextToSpeech? = null
    private var englishTtsReady = false
    private var englishTtsUnavailable = ""
    private var englishTtsGeneration = 0
    private var englishTtsPendingText: String? = null
    private var englishTtsLastSource = ""
    private var englishTtsLastTranslation = ""
    private var englishTtsReader: PortalMlKitOcrEngine? = null
    private var englishTtsTranslator: Translator? = null
    private val englishTtsFrames = ArrayDeque<android.graphics.Bitmap>()
    private lateinit var visualContainer: FrameLayout
    private lateinit var art: ImageView
    private lateinit var video: TextureView
    private lateinit var videoLaunch: TextView
    private lateinit var videoStop: TextView
    private lateinit var dubStatus: TextView
    private lateinit var dubSubtitle: TextView
    private lateinit var dubLaunch: TextView
    private lateinit var naturalOverlay: FrameLayout
    private lateinit var naturalToggle: TextView
    private var originalOutputBackground: Drawable? = null
    private val naturalBitmaps = mutableListOf<android.graphics.Bitmap>()
    private val previousVideoFrames = ArrayDeque<PortalNaturalTextInpaint.Frame>()
    private var naturalFrame: PortalNaturalTextInpaint.Frame? = null
    private var naturalRegions: List<PortalOcrTextRegion> = emptyList()
    private val naturalLineTranslations = mutableMapOf<String,String>()
    private var naturalCaption = ""
    private var naturalIsVideo = false
    private var naturalVideoBounds: Rect? = null

    private fun clearNaturalPreview() {
        if(!::naturalOverlay.isInitialized) return
        naturalOverlay.removeAllViews()
        naturalBitmaps.forEach { if(!it.isRecycled) it.recycle() }
        naturalBitmaps.clear()
    }

    private fun resetNaturalPreview() {
        clearNaturalPreview()
        previousVideoFrames.clear()
        naturalFrame=null
        naturalRegions=emptyList()
        naturalLineTranslations.clear()
        naturalCaption=""
        naturalIsVideo=false
        naturalVideoBounds=null
    }

    private fun videoContentBounds(frameWidth:Int,frameHeight:Int):Rect {
        val sound=videoPlayer ?: return Rect(0,0,frameWidth,frameHeight)
        val width=sound.videoWidth
        val height=sound.videoHeight
        val displayWidth=video.width
        val displayHeight=video.height
        if(width<=0 || height<=0 || displayWidth<=0 || displayHeight<=0)
            return Rect(0,0,frameWidth,frameHeight)
        val fit=min(displayWidth.toFloat()/width,displayHeight.toFloat()/height)
        val renderedWidth=(fit*width*frameWidth/displayWidth).toInt()
        val renderedHeight=(fit*height*frameHeight/displayHeight).toInt()
        val left=(frameWidth-renderedWidth)/2
        val top=(frameHeight-renderedHeight)/2
        return Rect(left,top,left+renderedWidth,top+renderedHeight)
    }

    private fun captureNaturalFrame(bitmap:android.graphics.Bitmap,
        regions:List<PortalOcrTextRegion>,isVideo:Boolean) {
        naturalFrame=PortalNaturalTextInpaint.Frame.capture(bitmap)
        naturalVideoBounds=if(isVideo) videoContentBounds(bitmap.width,bitmap.height) else null
        val content=naturalVideoBounds ?: Rect(0,0,bitmap.width,bitmap.height)
        naturalRegions=regions.filter { region ->
            val b=region.bounds
            val bounds=Rect(b.x,b.y,b.x+b.width,b.y+b.height)
            b.width>=3 && b.height>=3 &&
                (!isVideo || (
                    content.contains(bounds) &&
                    b.width<=content.width()*.66f &&
                    b.height<=content.height()*.14f &&
                    b.width.toLong()*b.height < bitmap.width.toLong()*bitmap.height/55L &&
                    region.text.any {it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff'}
                ))
        }.take(if(isVideo) 2 else 4)
        naturalIsVideo=isVideo
        if(isVideo) {
            previousVideoFrames.addLast(naturalFrame!!)
            while(previousVideoFrames.size>8) previousVideoFrames.removeFirst()
        }
    }

    /** Renders genuine alpha-only repaired glyph pixels over the decoded picture.
     * Captions float in the original OCR area; app controls/audio remain outside. */
    private fun showNaturalPreview():Boolean {
        clearNaturalPreview()
        if(!PortalPreferences(this).translationNaturalInpaint || naturalCaption.isBlank()) return false
        output.background=null
        output.setShadowLayer(3f,0f,2f,Color.BLACK)
        val frame=naturalFrame ?: return false
        val regions=naturalRegions
        if(regions.isEmpty() || naturalOverlay.width<=0 || naturalOverlay.height<=0) return false
        val scale=if(naturalIsVideo) naturalOverlay.width.toFloat()/frame.width
            else min(naturalOverlay.width.toFloat()/frame.width,naturalOverlay.height.toFloat()/frame.height)
        val scaleY=if(naturalIsVideo) naturalOverlay.height.toFloat()/frame.height else scale
        val left=if(naturalIsVideo) 0f else (naturalOverlay.width-frame.width*scale)/2f
        val top=if(naturalIsVideo) 0f else (naturalOverlay.height-frame.height*scaleY)/2f
        var visible=false
        val history=if(naturalIsVideo) previousVideoFrames.dropLast(1) else emptyList()
        val content=if(naturalIsVideo) naturalVideoBounds ?: Rect(0,0,frame.width,frame.height)
            else Rect(0,0,frame.width,frame.height)
        val scaledContentLeft=left+content.left*scale
        val scaledContentRight=left+content.right*scale
        val scaledContentTop=top+content.top*scaleY
        val scaledContentBottom=top+content.bottom*scaleY
        val dp=resources.displayMetrics.density
        regions.forEachIndexed { index, region ->
            val b=region.bounds
            val rect=Rect(b.x,b.y,b.x+b.width,b.y+b.height)
            val patch=PortalNaturalTextInpaint.restore(frame,rect,history)
            if(patch!=null) {
                val restored=ImageView(this).apply {
                    scaleType=ImageView.ScaleType.FIT_XY
                    setImageBitmap(patch.image)
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val x=(left+patch.bounds.left*scale).toInt()
                val y=(top+patch.bounds.top*scaleY).toInt()
                val width=(patch.bounds.width()*scale).toInt().coerceAtLeast(1)
                val height=(patch.bounds.height()*scaleY).toInt().coerceAtLeast(1)
                if(x>=scaledContentLeft && y>=scaledContentTop &&
                    x+width<=scaledContentRight && y+height<=scaledContentBottom) {
                    naturalOverlay.addView(restored,FrameLayout.LayoutParams(width,height).apply {
                        leftMargin=x;topMargin=y
                    })
                    naturalBitmaps.add(patch.image)
                } else patch.image.recycle()
            }
            val translation=naturalLineTranslations[region.text]
                ?: if(index==0) naturalCaption else ""
            if(translation.isNotBlank()) {
                val label=TextView(this).apply {
                    text=translation
                    textSize=if(naturalIsVideo)
                        min(PortalPreferences(this@PortalJapaneseTranslationTestActivity)
                            .translationTextSizeSp.toFloat(),16f)
                        else PortalPreferences(this@PortalJapaneseTranslationTestActivity)
                            .translationTextSizeSp.toFloat()
                    setTextColor(if(patch?.darkLettering==true) Color.rgb(24,24,32) else Color.WHITE)
                    setShadowLayer(2f,0f,1f,if(patch?.darkLettering==true) Color.WHITE else Color.BLACK)
                    setPadding(0,0,0,0)
                    background=null
                    if(naturalIsVideo) maxLines=2
                    gravity=Gravity.TOP or Gravity.START
                    isClickable=false;isFocusable=false
                }
                val x=(left+rect.left*scale).toInt()
                    .coerceAtLeast(scaledContentLeft.toInt())
                val maxWidth=(scaledContentRight.toInt()-x).coerceAtLeast(0)
                val width=(rect.width()*scale*(if(naturalIsVideo) 1.22f else 1.9f)).toInt()
                    .coerceAtLeast(((if(naturalIsVideo) 40 else 90)*dp).toInt())
                    .coerceAtMost(maxWidth)
                val y=(top+rect.top*scaleY).toInt()
                    .coerceIn(scaledContentTop.toInt(),
                        (scaledContentBottom-(if(naturalIsVideo) 38 else 35)*dp).toInt()
                            .coerceAtLeast(scaledContentTop.toInt()))
                if(width>0 && x+width<=scaledContentRight) {
                    naturalOverlay.addView(label,FrameLayout.LayoutParams(width,-2).apply {
                        leftMargin=x;topMargin=y
                    })
                    visible=true
                }
            }
        }
        if(visible) output.visibility=View.GONE
        return visible
    }

    private fun translateNaturalLines(client:Translator,token:Int,isVideo:Boolean) {
        if(!PortalPreferences(this).translationNaturalInpaint) return
        naturalRegions.forEach { region ->
            client.translate(region.text).addOnSuccessListener { result ->
                if((if(isVideo) token==videoSession && videoModeVisible else token==generation && !isDestroyed) &&
                    result.isNotBlank() && PortalPreferences(this).translationNaturalInpaint) {
                    naturalLineTranslations[region.text]=result.take(260)
                    showNaturalPreview()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dubPermissionRequest=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if(videoDubbed && dubActive) {
                if(granted) initializeDubRecognizer()
                else failDub("Android audio recognition permission declined")
            }
        }
        if(intent.getBooleanExtra(EXTRA_NATURAL_PREVIEW,false))
            PortalPreferences(this).translationNaturalInpaint=true
        PortalUi.fullscreen(this)
        val dp=resources.displayMetrics.density
        val page=PortalPage(this,"Japanese Game Translation Test",::finish)
        setContentView(page.root)
        onBackPressedDispatcher.addCallback(this){finish()}
        page.note("Run five tests independently: Japanese image OCR, 60-second video OCR, " +
            "Japanese spoken WAV recognition, English speech from translated OCR, " +
            "or muted Japanese video with live English speech dubbing. " +
            "Offline models may require a first-time Wi-Fi download.")

        val image=FrameLayout(this).apply {
            setBackgroundColor(0xff091126.toInt())
        }
        visualContainer=image
        art=ImageView(this).apply {
            setImageResource(R.drawable.relay_japanese_ocr_test)
            scaleType=ImageView.ScaleType.FIT_CENTER
            contentDescription="Bundled image containing Japanese game dialogue for OCR testing"
        }
        image.addView(art,FrameLayout.LayoutParams(-1,-1))
        video=TextureView(this).apply {
            visibility=View.GONE
            isOpaque=true
            contentDescription="Playing Japanese-captioned video for live OCR"
            surfaceTextureListener=object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    if(videoRequested && videoPlayer==null) prepareVideoPlayer()
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    fitVideoTexture()
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    if(videoModeVisible) stopVideoTest("Video display surface was destroyed")
                    return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            }
        }
        image.addView(video,FrameLayout.LayoutParams(-1,-1))
        naturalOverlay=FrameLayout(this).apply {
            isClickable=false;isFocusable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipChildren=false
        }
        image.addView(naturalOverlay,FrameLayout.LayoutParams(-1,-1))
        dubSubtitle=TextView(this).apply {
            textSize=22f
            gravity=Gravity.CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(3f,1f,2f,Color.BLACK)
            visibility=View.GONE
            background=null
            isClickable=false;isFocusable=false
            contentDescription="Real English subtitles translated from Japanese video audio"
        }
        image.addView(dubSubtitle,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {
            leftMargin=(20*dp).toInt();rightMargin=(20*dp).toInt();bottomMargin=(23*dp).toInt()
        })
        output=TextView(this).apply {
            text=""
            textSize=19f
            gravity=Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding((18*dp).toInt(),(12*dp).toInt(),(18*dp).toInt(),(12*dp).toInt())
            background=GradientDrawable().apply {
                setColor(0xe5141938.toInt())
                cornerRadius=14*dp
                setStroke((1*dp).toInt(),0xff7998d5.toInt())
            }
            visibility=android.view.View.GONE
            isFocusable=false;isClickable=false
            contentDescription="Test translated text overlay"
        }
        originalOutputBackground=output.background
        image.addView(output,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {
            leftMargin=(20*dp).toInt();rightMargin=(20*dp).toInt();bottomMargin=(14*dp).toInt()
        })
        page.add(image)
        // Keep the entire 16:9 fixture visible on tablet landscape and leave space for controls.
        image.layoutParams=(image.layoutParams as LinearLayout.LayoutParams).apply {
            height=min((resources.displayMetrics.widthPixels*9f/16f).toInt(),
                (resources.displayMetrics.heightPixels*.34f).toInt())
                .coerceAtLeast((170*dp).toInt())
        }
        status=PortalUi.text(this,"IMAGE OCR · Ready",14f,true)
        page.add(status)
        detected=PortalUi.text(this,"IMAGE OCR · English output will be shown after detection",14f,true)
        page.add(detected)
        audioStatus=PortalUi.text(this,"SPOKEN AUDIO · Ready",14f,true)
        page.add(audioStatus)
        audioDetected=PortalUi.text(this,"Japanese WAV: recognition has not started",14f,true)
        page.add(audioDetected)
        englishTtsStatus=PortalUi.text(this,"ENGLISH SPEECH · Ready (real OCR translation required)",14f,true)
        page.add(englishTtsStatus)
        dubStatus=PortalUi.text(this,"VIDEO AUDIO DUB · Ready",14f,true)
        page.add(dubStatus)
        val controls=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        fun addTestRow(left:TextView,right:TextView) {
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            row.addView(left,LinearLayout.LayoutParams(0,-2,1f).apply {
                marginEnd=(5*dp).toInt()
            })
            row.addView(right,LinearLayout.LayoutParams(0,-2,1f).apply {
                marginStart=(5*dp).toInt()
            })
            controls.addView(row,LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=(8*dp).toInt()
            })
        }
        launch=PortalUi.primary(this,"1 · Japanese Image → English") {
            if(videoModeVisible) stopVideoTest(null)
            runTest()
        }.apply {textSize=14f;setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())}
        videoLaunch=PortalUi.primary(this,"2 · Japanese Video → English") {
            startVideoTest()
        }.apply {textSize=14f;setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())}
        addTestRow(launch,videoLaunch)
        val audioLaunch=PortalUi.primary(this,"3 · Japanese WAV → English") {
            if(videoModeVisible) {
                stopVideoTest(null)
                videoHandler.postDelayed({if(!isFinishing && !isDestroyed) runAudioTest()},500L)
            } else runAudioTest()
        }.apply {textSize=14f;setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())}
        val ttsLaunch=PortalUi.primary(this,"4 · Speak OCR English") {
            startEnglishTtsTest()
        }.apply {textSize=14f;setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())}
        addTestRow(audioLaunch,ttsLaunch)
        page.add(controls)
        dubLaunch=PortalUi.primary(this,"5 · Japanese Video Audio → English Subtitles + Voice") {
            startVideoTest(dubbed=true)
        }.apply {
            textSize=14f
            setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())
        }
        page.add(dubLaunch)
        page.add(PortalUi.primary(this,"Download Japanese Offline Speech Model") {
            requestJapaneseOfflineSpeechModel()
        }.apply {
            textSize=14f
            setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())
        })
        englishTtsSource=PortalUi.text(this,"English speech input: IMAGE OCR · Tap for VIDEO OCR",14f,true).apply {
            setPadding((14*dp).toInt(),(10*dp).toInt(),(14*dp).toInt(),(10*dp).toInt())
            background=GradientDrawable().apply {
                setColor(0xff192441.toInt())
                cornerRadius=12*dp
                setStroke((1*dp).toInt(),0xff6e86bd.toInt())
            }
            isClickable=true
            isFocusable=true
            setOnClickListener {
                englishTtsVideoSource=!englishTtsVideoSource
                text=if(englishTtsVideoSource)
                    "English speech input: VIDEO OCR · Tap for IMAGE OCR"
                    else "English speech input: IMAGE OCR · Tap for VIDEO OCR"
            }
        }
        page.add(englishTtsSource)
        val combinedToggle=PortalUi.text(this,"Combined image/video + spoken audio: OFF · Tap to opt in",14f,true).apply {
            setPadding((14*dp).toInt(),(10*dp).toInt(),(14*dp).toInt(),(10*dp).toInt())
            isClickable=true
            isFocusable=true
            setOnClickListener {
                simultaneousMode=!simultaneousMode
                text="Combined image/video + spoken audio: " +
                    (if(simultaneousMode) "ON · Tap for independent mode" else "OFF · Tap to opt in")
            }
        }
        page.add(combinedToggle)
        naturalToggle=PortalUi.primary(this,"Natural Subtitle Replace: " +
            if(PortalPreferences(this).translationNaturalInpaint) "ON" else "OFF") {
            val prefs=PortalPreferences(this)
            prefs.translationNaturalInpaint=!prefs.translationNaturalInpaint
            naturalToggle.text="Natural Subtitle Replace: " + if(prefs.translationNaturalInpaint) "ON" else "OFF"
            output.background=if(prefs.translationNaturalInpaint) null else originalOutputBackground
            if(prefs.translationNaturalInpaint) showNaturalPreview() else {
                clearNaturalPreview()
                if(naturalCaption.isNotBlank()) {
                    output.text=naturalCaption
                    output.visibility=View.VISIBLE
                }
            }
        }
        page.add(naturalToggle)
        naturalToggle.textSize=14f
        naturalToggle.setPadding((12*dp).toInt(),(10*dp).toInt(),(12*dp).toInt(),(10*dp).toInt())
        page.note("Natural mode replaces Japanese glyph areas with nearby scene pixels and " +
            "draws English directly over the picture. The two visual tests share this option.")
        videoStop=PortalUi.primary(this,"Stop Video and Return to Picture") {
            stopVideoTest("Video test stopped")
        }.apply { visibility=View.GONE }
        page.add(videoStop)
        page.add(PortalUi.primary(this,"Stop Spoken Audio") {
            audioController?.stop()
            audioTestRequested=false
            player?.release();player=null
            if(videoRequested && videoCombinedAudio) {
                videoAudioResolved=true
                audioCaptureReady=false
                audioDetected.text="Audio capture stopped manually; video OCR can continue without microphone input."
                maybeStartVideoPlayback()
            }
        })
        page.add(PortalUi.primary(this,"Close Test") {finish()})

        audioController=PortalAudioCaptionsController(this,page.root,
            onReady={
                audioCaptureReady=true
                if(videoRequested && videoCombinedAudio) {
                    videoAudioResolved=true
                    maybeStartVideoPlayback()
                } else if(audioTestRequested) maybePlayAudioFixture()
            },
            onState={ state ->
                if (::audioStatus.isInitialized && (audioTestRequested || videoCombinedAudio && videoModeVisible))
                    audioStatus.text="SPOKEN AUDIO · ${state.status}"
                if (::audioDetected.isInitialized && (audioTestRequested || videoCombinedAudio && videoModeVisible) &&
                    state.original.isNotBlank()) {
                    audioDetected.text="Recognized speech: ${state.original}\n" +
                        "Translated speech: ${state.translated.ifBlank { "Waiting for offline translation…" }}"
                    if(videoModeVisible) {
                        videoSpokenRecognized=true
                        if(state.translated.isNotBlank()) videoSpokenTranslated=true
                    }
                }
                if(videoRequested && videoCombinedAudio) {
                    if(state.running && state.ready) {
                        videoAudioResolved=true
                        audioCaptureReady=true
                        maybeStartVideoPlayback()
                    } else if(!state.running && state.status.isNotBlank() &&
                        state.status!="Spoken captions stopped") {
                        // The video OCR test remains usable when capture/model permission fails.
                        videoAudioResolved=true
                        if(videoPlaying && !videoSpokenRecognized) {
                            audioDetected.text="Audio capture unavailable: " + state.status +
                                ". Video OCR continues independently; no microphone fallback."
                        }
                        maybeStartVideoPlayback()
                    }
                }
            },spokenLanguageOverride="ja-JP")
        speech=TextToSpeech(this) { result ->
            if(result==TextToSpeech.SUCCESS) {
                val engine=speech
                if(engine != null && engine.isLanguageAvailable(Locale.JAPANESE) >= TextToSpeech.LANG_AVAILABLE &&
                    engine.setLanguage(Locale.JAPANESE) >= TextToSpeech.LANG_AVAILABLE) {
                    engine.setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    ttsReady=true
                    if(audioTestRequested) prepareAudioFixture()
                } else {
                    audioStatus.text="AUDIO · Japanese TTS voice missing. Install a Japanese voice in Android TTS settings."
                }
            } else audioStatus.text="AUDIO · Android text-to-speech engine initialization failed."
        }
        englishSpeech=TextToSpeech(this) { result ->
            val engine=englishSpeech
            if(result==TextToSpeech.SUCCESS && engine!=null &&
                engine.setLanguage(Locale.US)>=TextToSpeech.LANG_AVAILABLE) {
                englishTtsReady=true
                engine.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
                    override fun onStart(utteranceId:String?) {
                        val token=utteranceId?.removePrefix("relay-dub-")?.substringBefore('-')?.toIntOrNull()
                        runOnUiThread {
                            if(token==dubSession && videoDubbed && dubActive)
                                updateDubStatus("English voice playback started")
                        }
                    }
                    override fun onDone(utteranceId:String?) {
                        val token=utteranceId?.removePrefix("relay-english-ocr-")?.toIntOrNull()
                        val dubToken=utteranceId?.removePrefix("relay-dub-")?.substringBefore('-')?.toIntOrNull()
                        runOnUiThread {
                            if(dubToken==dubSession && videoDubbed && !isDestroyed) {
                                dubSpokenCount++
                                updateDubStatus("English voice playback completed")
                            }
                            if(token==englishTtsGeneration && !isDestroyed) {
                                englishTtsStatus.text=englishTtsResultText("Playback completed")
                                englishTtsPendingText=null
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId:String?) {
                        val token=utteranceId?.removePrefix("relay-english-ocr-")?.toIntOrNull()
                        val dubToken=utteranceId?.removePrefix("relay-dub-")?.substringBefore('-')?.toIntOrNull()
                        runOnUiThread {
                            if(dubToken==dubSession && videoDubbed && !isDestroyed)
                                updateDubStatus("English voice synthesis failed")
                            if(token==englishTtsGeneration && !isDestroyed)
                                englishTtsStatus.text="ENGLISH SPEECH · Android could not play the synthesized translation"
                        }
                    }
                })
                speakEnglishTranslation(englishTtsGeneration)
                dubPendingSpeech?.let { if(videoDubbed) speakDubEnglish(it,dubSession) }
            } else {
                englishTtsUnavailable="English text-to-speech voice unavailable in Android settings"
                if(::englishTtsStatus.isInitialized && englishTtsPendingText!=null)
                    englishTtsStatus.text="ENGLISH SPEECH · $englishTtsUnavailable"
            }
        }
    }

    private fun englishTtsResultText(stage:String):String =
        "ENGLISH SPEECH · $stage\n" +
            "Recognized Japanese OCR: ${englishTtsLastSource.take(300)}\n" +
            "Translated to English: ${englishTtsLastTranslation.take(400)}"

    private fun releaseEnglishOcrResources() {
        englishTtsReader?.close();englishTtsReader=null
        englishTtsTranslator?.close();englishTtsTranslator=null
        while(englishTtsFrames.isNotEmpty()) {
            val frame=englishTtsFrames.removeFirst()
            if(!frame.isRecycled) frame.recycle()
        }
    }

    private fun cancelEnglishTtsTest() {
        englishTtsGeneration++
        englishTtsPendingText=null
        englishTtsLastSource=""
        englishTtsLastTranslation=""
        englishSpeech?.stop()
        releaseEnglishOcrResources()
    }

    private fun startEnglishTtsTest() {
        // This test performs its own OCR and translation; it never borrows expected
        // fixture meanings or starts the audio recognizer.
        if(videoModeVisible) stopVideoTest(null)
        audioTestRequested=false
        audioController?.stop()
        player?.release();player=null
        cancelEnglishTtsTest()
        val token=englishTtsGeneration
        englishTtsStatus.text="ENGLISH SPEECH · Reading real Japanese " +
            (if(englishTtsVideoSource) "MP4 frames…" else "image pixels…")
        try {
            englishTtsReader=PortalMlKitOcrEngine.single(PortalOcrScript.JAPANESE)
            if(englishTtsVideoSource) extractEnglishTtsVideoFrames(token)
            else {
                val bitmap=BitmapFactory.decodeResource(resources,R.drawable.relay_japanese_ocr_test)
                if(bitmap==null) {
                    englishTtsStatus.text="ENGLISH SPEECH · Bundled Japanese image could not be decoded"
                    releaseEnglishOcrResources()
                } else recognizeEnglishTtsBitmap(bitmap,token,false)
            }
        } catch(error:Exception) {
            if(token==englishTtsGeneration) {
                englishTtsStatus.text="ENGLISH SPEECH · Could not start OCR: ${error.localizedMessage ?: "unknown error"}"
                releaseEnglishOcrResources()
            }
        }
    }

    /** Read actual video pixels without launching the visual video or audio tests.
     * Frame decoding is kept off the UI thread; OCR runs over a small set of
     * 60-second sample frames until the first real Japanese caption is detected.
     */
    private fun extractEnglishTtsVideoFrames(token:Int) {
        Thread {
            val frames=mutableListOf<android.graphics.Bitmap>()
            var errorMessage:String?=null
            val retriever=MediaMetadataRetriever()
            try {
                try {
                    assets.openFd(VIDEO_ASSET).use {
                        retriever.setDataSource(it.fileDescriptor,it.startOffset,it.length)
                    }
                } catch(compressed:java.io.FileNotFoundException) {
                    val local=File(cacheDir,"relay-japanese-captioned-tts.mp4")
                    assets.open(VIDEO_ASSET).use { source ->
                        local.outputStream().use { destination -> source.copyTo(destination) }
                    }
                    retriever.setDataSource(local.absolutePath)
                }
                for(second in listOf(5L,15L,25L,35L,45L,55L)) {
                    val frame=retriever.getFrameAtTime(second*1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                    if(frame.width>1100) {
                        val width=1100
                        val smaller=android.graphics.Bitmap.createScaledBitmap(
                            frame,width,(frame.height*width/frame.width).coerceAtLeast(1),true)
                        if(smaller!==frame) frame.recycle()
                        frames.add(smaller)
                    } else frames.add(frame)
                }
            } catch(error:Exception) {
                errorMessage=error.localizedMessage ?: error.javaClass.simpleName
            } finally {retriever.release()}
            runOnUiThread {
                if(token!=englishTtsGeneration || isDestroyed) {
                    frames.forEach {if(!it.isRecycled) it.recycle()}
                    return@runOnUiThread
                }
                if(frames.isEmpty()) {
                    englishTtsStatus.text="ENGLISH SPEECH · Could not decode Japanese video frames: " +
                        (errorMessage ?: "No readable video frames")
                    releaseEnglishOcrResources()
                } else {
                    englishTtsFrames.addAll(frames)
                    recognizeNextEnglishTtsFrame(token)
                }
            }
        }.start()
    }

    private fun recognizeNextEnglishTtsFrame(token:Int) {
        if(token!=englishTtsGeneration) return
        if(englishTtsFrames.isEmpty()) {
            englishTtsStatus.text="ENGLISH SPEECH · No Japanese characters detected in sampled MP4 frames"
            releaseEnglishOcrResources()
            return
        }
        recognizeEnglishTtsBitmap(englishTtsFrames.removeFirst(),token,true)
    }

    private fun recognizeEnglishTtsBitmap(bitmap:android.graphics.Bitmap,token:Int,isVideo:Boolean) {
        val reader=englishTtsReader ?: run {
            bitmap.recycle()
            return
        }
        try {
            reader.recognize(bitmap).addOnSuccessListener { result ->
                if(token!=englishTtsGeneration || isDestroyed) return@addOnSuccessListener
                val detectedText=result.text.trim().take(1000)
                if(detectedText.isNotBlank() && detectedText.any {
                        it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff'
                    }) {
                    // The English speech payload can only come from this OCR result.
                    translateEnglishTtsOcr(detectedText,token)
                } else if(isVideo) {
                    // Continue to another independently decoded video frame.
                } else {
                    englishTtsStatus.text="ENGLISH SPEECH · Image OCR found no Japanese characters"
                    releaseEnglishOcrResources()
                }
            }.addOnFailureListener {error ->
                if(token==englishTtsGeneration && !isDestroyed)
                    englishTtsStatus.text="ENGLISH SPEECH · OCR failed: ${error.localizedMessage ?: "unknown error"}"
            }.addOnCompleteListener {
                if(!bitmap.isRecycled) bitmap.recycle()
                if(isVideo && token==englishTtsGeneration &&
                    englishTtsLastSource.isBlank() && !isDestroyed)
                    recognizeNextEnglishTtsFrame(token)
            }
        } catch(error:Exception) {
            bitmap.recycle()
            if(token==englishTtsGeneration)
                englishTtsStatus.text="ENGLISH SPEECH · Could not read OCR bitmap: ${error.localizedMessage}"
        }
    }

    private fun translateEnglishTtsOcr(japanese:String,token:Int) {
        englishTtsLastSource=japanese
        englishTtsStatus.text="ENGLISH SPEECH · Real Japanese OCR detected. Loading English translation…"
        try {
            val client=Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH).build())
            englishTtsTranslator=client
            client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    if(token!=englishTtsGeneration || isDestroyed) return@addOnSuccessListener
                    englishTtsStatus.text="ENGLISH SPEECH · Translating actual OCR characters…"
                    client.translate(japanese).addOnSuccessListener { translated ->
                        if(token!=englishTtsGeneration || isDestroyed) return@addOnSuccessListener
                        val english=translated.trim().take(600)
                        if(english.isBlank()) {
                            englishTtsStatus.text="ENGLISH SPEECH · OCR translation returned no English text"
                        } else {
                            englishTtsLastTranslation=english
                            englishTtsPendingText=english
                            englishTtsStatus.text=englishTtsResultText("Preparing English voice")
                            releaseEnglishOcrResources()
                            speakEnglishTranslation(token)
                        }
                    }.addOnFailureListener {error ->
                        if(token==englishTtsGeneration)
                            englishTtsStatus.text="ENGLISH SPEECH · English translation failed: ${error.localizedMessage ?: "unknown error"}"
                    }
                }.addOnFailureListener {error ->
                    if(token==englishTtsGeneration)
                        englishTtsStatus.text="ENGLISH SPEECH · English model unavailable: ${error.localizedMessage ?: "Wi-Fi download required"}"
                }
        } catch(error:Exception) {
            if(token==englishTtsGeneration)
                englishTtsStatus.text="ENGLISH SPEECH · Translator failed: ${error.localizedMessage ?: "unknown error"}"
        }
    }

    private fun speakEnglishTranslation(token:Int) {
        if(token!=englishTtsGeneration || isDestroyed) return
        val actualTranslation=englishTtsPendingText ?: return
        if(!englishTtsReady) {
            englishTtsStatus.text=if(englishTtsUnavailable.isBlank())
                englishTtsResultText("Waiting for installed English voice…")
                else "ENGLISH SPEECH · $englishTtsUnavailable"
            return
        }
        val engine=englishSpeech ?: return
        val options=PortalPreferences(this)
        if(engine.setLanguage(Locale.forLanguageTag(options.translationSpeechLanguage))<
            TextToSpeech.LANG_AVAILABLE) {
            englishTtsStatus.text="ENGLISH SPEECH · Device lacks an English TTS voice"
            return
        }
        engine.setSpeechRate(options.translationSpeechRate)
        englishTtsStatus.text=englishTtsResultText("Synthesizing and speaking…")
        val code=engine.speak(actualTranslation,TextToSpeech.QUEUE_FLUSH,Bundle(),
            "relay-english-ocr-$token")
        if(code!=TextToSpeech.SUCCESS)
            englishTtsStatus.text="ENGLISH SPEECH · Android refused English synthesis"
    }

    private fun updateDubStatus(stage:String) {
        if(!::dubStatus.isInitialized || isDestroyed) return
        dubStatus.text="VIDEO AUDIO DUB · $stage\n" +
            "Recognized Japanese ($dubRecognizedCount): ${dubLastSource.take(220)}\n" +
            "Translated English ($dubTranslatedCount): ${dubLastTranslation.take(220)}\n" +
            "English TTS playback completed: $dubSpokenCount"
    }

    private fun failDub(reason:String) {
        if(!videoDubbed || isDestroyed) return
        android.util.Log.w("RelayVideoDub", "Unavailable: $reason")
        dubFailed=true
        dubActive=false
        updateDubStatus("Unavailable: $reason")
        closeDubPipe()
        // Keep the video visibly testable even if this device lacks offline ASR.
        maybeStartVideoPlayback()
    }

    /** Request the missing device speech model through Android's own recognition
     * service. The user may be asked to approve the download by the OS. */
    private fun requestJapaneseOfflineSpeechModel() {
        if(Build.VERSION.SDK_INT<33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            dubStatus.text="VIDEO AUDIO DUB · On-device speech model downloads are unavailable on this Android configuration."
            return
        }
        try {
            modelDownloadRecognizer?.destroy()
            modelDownloadRecognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(dubRecognitionListener)
            }
            val modelIntent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ja-JP")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
            }
            modelDownloadRecognizer?.triggerModelDownload(modelIntent)
            dubStatus.text="VIDEO AUDIO DUB · Japanese offline speech model requested. Approve any Android download prompt, then rerun Test 5 once the language pack is installed."
        } catch(error:Exception) {
            dubStatus.text="VIDEO AUDIO DUB · Android could not request Japanese speech model: " +
                (error.localizedMessage ?: "unknown error")
        }
    }

    private fun beginDubAudio() {
        dubSession++
        dubActive=true
        dubFailed=false
        dubRecognizerReady=false
        dubTranslationReady=false
        dubTranslationBusy=false
        dubTranslationQueue.clear()
        dubRecognizedCount=0
        dubTranslatedCount=0
        dubSpokenCount=0
        dubUtteranceCount=0
        dubLastSource=""
        dubLastTranslation=""
        dubPendingSpeech=null
        updateDubStatus("Preparing on-device Japanese speech recognition")
        if(Build.VERSION.SDK_INT<33) {
            failDub("Direct PCM recognition requires Android 13 or newer")
            return
        }
        try {
            val client=Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH).build())
            dubTranslator=client
            val token=dubSession
            client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    if(token==dubSession && videoDubbed && dubActive) {
                        dubTranslationReady=true
                        translateNextDubSegment(token)
                    }
                }.addOnFailureListener { error ->
                    if(token==dubSession && videoDubbed && dubActive)
                        updateDubStatus("English translation model unavailable: " +
                            (error.localizedMessage ?: "Wi-Fi download required"))
                }
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)
                ==PackageManager.PERMISSION_GRANTED) initializeDubRecognizer()
            else dubPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        } catch(error:Exception) {
            failDub("Cannot prepare Japanese speech translator: " +
                (error.localizedMessage ?: "unknown error"))
        }
    }

    private fun initializeDubRecognizer() {
        if(!videoDubbed || !dubActive) return
        try {
            if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                throw IllegalStateException("No offline Japanese speech recognition engine installed")
            dubRecognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply {
                setRecognitionListener(dubRecognitionListener)
            }
            startDubSegment()
        } catch(error:Exception) {
            failDub("Cannot start offline Japanese ASR: " +
                (error.localizedMessage ?: "unknown error"))
        }
    }

    private fun closeDubPipe() {
        val writer=dubOutput
        dubOutput=null
        runCatching {writer?.close()}
        runCatching {dubReadPipe?.close()}
        dubReadPipe=null
    }

    private fun startDubSegment() {
        if(!dubActive || !videoDubbed) return
        try {
            closeDubPipe()
            val pipes=ParcelFileDescriptor.createPipe()
            dubReadPipe=pipes[0]
            dubOutput=ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
            val recognize=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ja-JP")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,dubReadPipe)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000)
            }
            dubRecognizer?.startListening(recognize)
        } catch(error:Exception) {
            failDub("Cannot feed Japanese PCM to recognizer: " +
                (error.localizedMessage ?: "unknown error"))
        }
    }

    private val dubRecognitionListener=object:RecognitionListener {
        override fun onReadyForSpeech(params:Bundle?) {
            if(!dubActive) return
            dubRecognizerReady=true
            updateDubStatus("Japanese speech decoder ready")
            maybeStartVideoPlayback()
        }
        override fun onBeginningOfSpeech() {
            if(dubActive) updateDubStatus("Listening to decoded Japanese MP4 soundtrack")
        }
        override fun onRmsChanged(rmsdB:Float) {}
        override fun onBufferReceived(buffer:ByteArray?) {}
        override fun onEndOfSpeech() {
            if(dubActive) updateDubStatus("Transcribing decoded Japanese speech")
        }
        override fun onError(error:Int) {
            if(!dubActive) return
            android.util.Log.w("RelayVideoDub", "Japanese speech recognizer returned code $error")
            closeDubPipe()
            if(error==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                failDub("Japanese offline speech language pack unavailable")
                return
            }
            if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                error==SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                failDub("Japanese speech recognizer error $error")
                return
            }
            if(videoRequested || videoPlaying)
                videoHandler.postDelayed({if(dubActive) startDubSegment()},400L)
        }
        override fun onResults(results:Bundle?) {
            if(!dubActive) return
            val japanese=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty().take(350)
            if(japanese.isNotBlank() && japanese!=dubLastSource) {
                dubRecognizedCount++
                dubLastSource=japanese
                dubTranslationQueue.addLast(japanese)
                updateDubStatus("Recognized real Japanese soundtrack speech")
                translateNextDubSegment(dubSession)
            }
            closeDubPipe()
            if(videoRequested || videoPlaying)
                videoHandler.postDelayed({if(dubActive) startDubSegment()},350L)
        }
        override fun onPartialResults(results:Bundle?) {
            if(!dubActive) return
            val draft=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if(draft.isNotBlank()) updateDubStatus("Live Japanese ASR: ${draft.take(100)}")
        }
        override fun onEvent(eventType:Int,params:Bundle?) {}
    }

    private fun translateNextDubSegment(token:Int) {
        if(token!=dubSession || !dubActive || !dubTranslationReady ||
            dubTranslationBusy || dubTranslationQueue.isEmpty()) return
        val original=dubTranslationQueue.removeFirst()
        val client=dubTranslator ?: return
        dubTranslationBusy=true
        client.translate(original).addOnSuccessListener { english ->
            if(token==dubSession && dubActive && videoDubbed) {
                val translated=english.trim().take(400)
                if(translated.isBlank()) {
                    updateDubStatus("ASR returned Japanese; English translation was blank")
                } else {
                    dubTranslatedCount++
                    dubLastTranslation=translated
                    dubSubtitle.text=translated
                    dubSubtitle.visibility=View.VISIBLE
                    updateDubStatus("English subtitle translated from actual Japanese speech")
                    speakDubEnglish(translated,token)
                }
            }
        }.addOnFailureListener { error ->
            if(token==dubSession && dubActive)
                updateDubStatus("Japanese recognized; translation failed: " +
                    (error.localizedMessage ?: "unknown error"))
        }.addOnCompleteListener {
            if(token==dubSession) {
                dubTranslationBusy=false
                translateNextDubSegment(token)
            }
        }
    }

    private fun speakDubEnglish(text:String,token:Int) {
        if(token!=dubSession || !videoDubbed || !dubActive || text.isBlank()) return
        val engine=englishSpeech
        if(!englishTtsReady || engine==null) {
            dubPendingSpeech=text
            updateDubStatus(if(englishTtsUnavailable.isBlank())
                "Waiting for English speech engine" else englishTtsUnavailable)
            return
        }
        val settings=PortalPreferences(this)
        if(engine.setLanguage(Locale.forLanguageTag(settings.translationSpeechLanguage))<
            TextToSpeech.LANG_AVAILABLE) {
            updateDubStatus("English subtitles available; English TTS voice missing")
            return
        }
        engine.setSpeechRate(settings.translationSpeechRate)
        val utterance="relay-dub-$token-${++dubUtteranceCount}"
        val started=engine.speak(text,TextToSpeech.QUEUE_ADD,Bundle(),utterance)
        if(started!=TextToSpeech.SUCCESS)
            updateDubStatus("English subtitles available; speech synthesis refused")
        else {
            dubPendingSpeech=null
            updateDubStatus("English TTS queued from translated Japanese speech")
        }
    }

    private fun stopDubAudio(stopVoice:Boolean=true) {
        dubActive=false
        if(stopVoice) dubSession++
        closeDubPipe()
        dubThread?.interrupt();dubThread=null
        runCatching {dubRecognizer?.cancel()}
        runCatching {dubRecognizer?.destroy()}
        dubRecognizer=null
        dubTranslator?.close();dubTranslator=null
        dubTranslationQueue.clear()
        dubTranslationBusy=false
        if(stopVoice) dubPendingSpeech=null
        if(stopVoice) englishSpeech?.stop()
    }

    /** Decode original AAC audio directly into the speech recognizer's PCM pipe.
     * The MediaPlayer video output is muted separately. Never read silent output
     * back from a system playback capture, since media-player volume may mute it.
     */
    private fun decodeDubbedVideoAudio(token:Int) {
        if(token!=dubSession || !dubActive) return
        val worker=Thread({
            val extractor=MediaExtractor()
            var codec:MediaCodec?=null
            var errorMessage:String?=null
            var sampleCount=0L
            var inputPacketCount=0L
            var decodedByteCount=0L
            try {
                try {
                    assets.openFd(VIDEO_ASSET).use {
                        extractor.setDataSource(it.fileDescriptor,it.startOffset,it.length)
                    }
                } catch(compressed:java.io.FileNotFoundException) {
                    val local=File(cacheDir,"relay-japanese-audio-decode.mp4")
                    assets.open(VIDEO_ASSET).use { source ->
                        local.outputStream().use {destination -> source.copyTo(destination)}
                    }
                    extractor.setDataSource(local.absolutePath)
                }
                val audioTrack=(0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/")==true
                } ?: throw IllegalStateException("Bundled MP4 has no audio track")
                extractor.selectTrack(audioTrack)
                val format=extractor.getTrackFormat(audioTrack)
                val mime=format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("MP4 audio track has no codec")
                var sampleRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val decoder=MediaCodec.createDecoderByType(mime)
                codec=decoder
                decoder.configure(format,null,null,0)
                decoder.start()
                val info=MediaCodec.BufferInfo()
                val begun=SystemClock.elapsedRealtime()
                var inputEnded=false
                var outputEnded=false
                while(dubActive && token==dubSession && !Thread.currentThread().isInterrupted &&
                    !outputEnded) {
                    if(!inputEnded) {
                        val inputIndex=decoder.dequeueInputBuffer(10_000L)
                        if(inputIndex>=0) {
                            val buffer=decoder.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("AAC decoder input unavailable")
                            buffer.clear()
                            val size=extractor.readSampleData(buffer,0)
                            if(size<0) {
                                decoder.queueInputBuffer(inputIndex,0,0,0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded=true
                            } else {
                                inputPacketCount++
                                val whenUs=extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex,0,size,whenUs,0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex=decoder.dequeueOutputBuffer(info,10_000L)
                    when(outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val pcmFormat=decoder.outputFormat
                            sampleRate=pcmFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels=pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if(pcmFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                pcmFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)!=
                                AudioFormat.ENCODING_PCM_16BIT)
                                throw IllegalStateException("AAC decoder did not return PCM16 audio")
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if(outputIndex>=0) {
                            if(info.size>0) {
                                decodedByteCount+=info.size
                                val buffer=decoder.getOutputBuffer(outputIndex)
                                    ?: throw IllegalStateException("AAC decoder output unavailable")
                                buffer.position(info.offset)
                                buffer.limit(info.offset+info.size)
                                val decoded=ByteArray(info.size)
                                buffer.get(decoded)
                                val normalized=toMono16kPcm(decoded,sampleRate,channels)
                                val scheduled=begun+sampleCount*1000L/16000L
                                while(dubActive && token==dubSession) {
                                    val remaining=scheduled-SystemClock.elapsedRealtime()
                                    if(remaining<=0) break
                                    Thread.sleep(min(remaining,100L))
                                }
                                if(dubActive && token==dubSession) {
                                    try { dubOutput?.write(normalized) }
                                    catch (_:Exception) { /* Speech segment has ended; next pipe will be used. */ }
                                    sampleCount+=normalized.size/2L
                                }
                            }
                            outputEnded=(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0
                            decoder.releaseOutputBuffer(outputIndex,false)
                        }
                    }
                }
            } catch(interrupted:InterruptedException) {
                Thread.currentThread().interrupt()
            } catch(error:Exception) {
                errorMessage=error.localizedMessage ?: error.javaClass.simpleName
            } finally {
                runCatching {codec?.stop()}
                runCatching {codec?.release()}
                runCatching {extractor.release()}
            }
            runOnUiThread {
                if(token==dubSession && videoDubbed && !isDestroyed) {
                    android.util.Log.i("RelayVideoDub", "Decode ended: packets=$inputPacketCount decodedBytes=$decodedByteCount pcmSamples=$sampleCount active=$dubActive failed=$dubFailed error=$errorMessage")
                    if(!dubActive || dubFailed) {
                        // Keep the original recognition or permission failure visible.
                        return@runOnUiThread
                    }
                    if(errorMessage!=null) failDub("Could not decode Japanese MP4 audio: $errorMessage")
                    else if(sampleCount==0L) failDub("MP4 decoder provided no audio PCM samples (packets=$inputPacketCount, bytes=$decodedByteCount)")
                    else {
                        closeDubPipe()
                        updateDubStatus("Decoded ${sampleCount/16000}s of source Japanese PCM; awaiting final ASR")
                    }
                }
            }
        },"relay-japanese-video-aac-decode")
        dubThread=worker
        worker.start()
    }

    /** Downmix decoded PCM16 and resample to the Android offline ASR pipe format.
     * Frames are taken from the MP4 decoder, never synthesized from test text.
     */
    private fun toMono16kPcm(bytes:ByteArray,sampleRate:Int,channels:Int):ByteArray {
        require(sampleRate>0 && channels in 1..8) {"Unsupported MP4 audio sample format"}
        val sourceFrames=bytes.size/(2*channels)
        if(sourceFrames==0) return ByteArray(0)
        val outputFrames=(sourceFrames.toLong()*16000L/sampleRate).toInt().coerceAtLeast(1)
        val mono=ByteArray(outputFrames*2)
        for(index in 0 until outputFrames) {
            val original=(index.toLong()*sampleRate/16000L).toInt()
                .coerceAtMost(sourceFrames-1)
            var total=0
            for(channel in 0 until channels) {
                val offset=(original*channels+channel)*2
                val raw=(bytes[offset].toInt() and 255) or (bytes[offset+1].toInt() shl 8)
                total+=raw.toShort().toInt()
            }
            val sample=(total/channels).toShort().toInt()
            mono[index*2]=(sample and 255).toByte()
            mono[index*2+1]=((sample shr 8) and 255).toByte()
        }
        return mono
    }

    private fun runAudioTest() {
        cancelEnglishTtsTest()
        val active=PortalAudioCaptionBus.state
        audioCaptureReady=active.running && active.ready
        audioFileReady=false
        audioTestRequested=true
        audioCombinedImage=simultaneousMode
        audioPlayed=false
        player?.release();player=null
        audioDetected.text="Expected spoken phrase: $JAPANESE_SPEECH_SAMPLE\n" +
            "Recognition: waiting for captured playback (no expected text is prefilled)"
        if(ttsReady) prepareAudioFixture()
        else audioStatus.text="AUDIO · Waiting for installed Japanese speech synthesizer…"
        if(!active.running) audioController?.toggle()
        else if(active.ready) maybePlayAudioFixture()
        else audioStatus.text="SPOKEN AUDIO · Waiting for existing Japanese recognizer session…"
    }

    /** Generates a WAV with the device's real Japanese TTS voice, then plays it
     * through the game/media audio capture path. The emitted waveform is the
     * fixture; the UI never fakes recognized or translated output.
     */
    private fun prepareAudioFixture() {
        val engine=speech ?: return
        audioStatus.text="AUDIO · Synthesizing Japanese WAV sample…"
        engine.setOnUtteranceProgressListener(object:UtteranceProgressListener() {
            override fun onStart(utteranceId:String?) {}
            override fun onDone(utteranceId:String?) {
                runOnUiThread {
                    if(!audioTestRequested || isDestroyed) return@runOnUiThread
                    audioFileReady=audioFixture.isFile && audioFixture.length()>128
                    if(!audioFileReady) audioStatus.text="AUDIO · TTS did not create a usable audio fixture."
                    else maybePlayAudioFixture()
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId:String?) {
                runOnUiThread {audioStatus.text="AUDIO · Japanese speech synthesis failed"}
            }
        })
        val code=engine.synthesizeToFile(JAPANESE_SPEECH_SAMPLE,Bundle(),audioFixture,
            "relay-japanese-speech-fixture")
        if(code!=TextToSpeech.SUCCESS) audioStatus.text="AUDIO · Japanese TTS refused WAV synthesis"
    }

    private fun maybePlayAudioFixture() {
        if(!audioTestRequested || audioPlayed || !audioCaptureReady || !audioFileReady) return
        audioPlayed=true
        try {
            val sound=MediaPlayer()
            player=sound
            sound.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            sound.setDataSource(audioFixture.absolutePath)
            sound.setOnCompletionListener {
                audioStatus.text="AUDIO · Japanese sample played; checking actual speech recognition results…"
                it.release()
                if(player===it) player=null
            }
            sound.prepare()
            // Explicit opt-in is required to run visual OCR alongside spoken recognition.
            if(audioCombinedImage) runTest()
            sound.start()
            audioStatus.text="AUDIO · Playing actual Japanese WAV into live caption capture…"
        } catch(error:Exception) {
            audioStatus.text="AUDIO · Could not play WAV fixture: ${error.localizedMessage}"
            player?.release();player=null
        }
    }

    private fun startVideoTest(dubbed:Boolean=false) {
        if(videoModeVisible) stopVideoTest(null)
        else stopDubAudio()
        val target=TranslateLanguage.ENGLISH
        // Each video run owns its resources; playback capture is separately opted in.
        cancelEnglishTtsTest()
        generation++
        running=false
        launch.isEnabled=true
        recognizer?.close();recognizer=null
        translator?.close();translator=null
        audioTestRequested=false
        audioCombinedImage=false
        audioPlayed=false
        player?.release();player=null
        if(PortalAudioCaptionBus.state.running) audioController?.stop()
        videoSession++
        videoRequested=true
        videoDubbed=dubbed
        dubRecognizerReady=false
        dubFailed=false
        videoCombinedAudio=simultaneousMode && !dubbed
        videoModeVisible=true
        videoPrepared=false
        videoPlaying=false
        videoAudioResolved=!videoCombinedAudio
        audioCaptureReady=false
        videoOcrBusy=false
        videoTranslationBusy=false
        videoTranslationReady=target==TranslateLanguage.JAPANESE
        videoRecognitions=0
        videoTranslations=0
        videoLastSource=""
        videoLastTranslated=""
        videoPendingTranslation=""
        videoModelError=""
        videoSpokenRecognized=false
        videoSpokenTranslated=false
        resetNaturalPreview()
        art.visibility=View.GONE
        video.visibility=View.VISIBLE
        output.visibility=View.GONE
        videoLaunch.isEnabled=false
        dubLaunch.isEnabled=false
        dubSubtitle.visibility=View.GONE
        dubSubtitle.text=""
        videoStop.visibility=View.VISIBLE
        audioDetected.text=if(dubbed)
            "VIDEO DUB · Original Japanese media output muted; source decoded directly to PCM"
            else if(videoCombinedAudio)
            "VIDEO AUDIO · Waiting for separately consented playback recognition"
            else "VIDEO AUDIO · Disabled for independent video OCR test"
        audioStatus.text=if(dubbed)
            "VIDEO DUB · Offline Japanese ASR requested from decoded soundtrack"
            else if(videoCombinedAudio)
            "SPOKEN AUDIO · Waiting for Android playback capture consent…"
            else "SPOKEN AUDIO · Not requested"
        detected.text=if(dubbed) "VIDEO DUB · Actual Japanese audio recognition pending"
            else "VIDEO OCR · Waiting for Japanese subtitles in decoded video frames"
        setStatus(if(dubbed) "VIDEO DUB · Preparing muted MP4 and Japanese speech decoder…"
            else "VIDEO OCR · Preparing bundled Japanese MP4 and offline OCR…")
        try {
            if(!dubbed) videoReader=PortalMlKitOcrEngine.single(PortalOcrScript.JAPANESE)
            if(!dubbed && target!=TranslateLanguage.JAPANESE) {
                val client=Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(target).build())
                videoTranslator=client
                val token=videoSession
                client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                    .addOnSuccessListener {
                        if(token==videoSession && videoModeVisible) {
                            videoTranslationReady=true
                            translateLatestVideoCaption(token)
                        }
                    }.addOnFailureListener { error ->
                        if(token==videoSession && videoModeVisible) {
                            videoModelError="Offline video translation model unavailable: " +
                                (error.localizedMessage ?: "connect to Wi-Fi and retry")
                            setStatus("VIDEO OCR · " + videoModelError)
                        }
                    }
            }
        } catch(error:Exception) {
            stopVideoTest("Could not initialize Japanese OCR/translator: " + (error.localizedMessage ?: "unknown error"))
            return
        }
        if(video.isAvailable) prepareVideoPlayer()
        if(dubbed) beginDubAudio()
        else if(videoCombinedAudio) {
            // Optional paired mode uses Relay's own playback UID, never microphone input.
            val state=PortalAudioCaptionBus.state
            if(state.running) {
                if(state.ready) {
                    audioCaptureReady=true
                    videoAudioResolved=true
                    maybeStartVideoPlayback()
                } else audioStatus.text="SPOKEN AUDIO · Waiting for on-device speech recognizer…"
            } else audioController?.toggle()
        } else maybeStartVideoPlayback()
    }

    private fun prepareVideoPlayer() {
        if(!videoRequested || !video.isAvailable || videoPlayer!=null) return
        val texture=video.surfaceTexture ?: return
        val sound=MediaPlayer()
        videoPlayer=sound
        try {
            sound.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            if(videoDubbed) sound.setVolume(0f,0f)
            val surface=Surface(texture)
            try { sound.setSurface(surface) } finally { surface.release() }
            // AAPT usually stores MP4 assets uncompressed. Copy only when openFd
            // cannot address a compressed asset directly.
            try {
                assets.openFd(VIDEO_ASSET).use { source ->
                    sound.setDataSource(source.fileDescriptor,source.startOffset,source.length)
                }
            } catch(compressed:java.io.FileNotFoundException) {
                val local=File(cacheDir,"relay-japanese-captioned-test.mp4")
                assets.open(VIDEO_ASSET).use { source ->
                    local.outputStream().use { destination -> source.copyTo(destination) }
                }
                sound.setDataSource(local.absolutePath)
            }
            sound.setOnVideoSizeChangedListener { _,_,_ -> fitVideoTexture() }
            sound.setOnPreparedListener {
                if(videoPlayer===sound && videoRequested) {
                    videoPrepared=true
                    fitVideoTexture()
                    maybeStartVideoPlayback()
                }
            }
            sound.setOnCompletionListener {
                if(videoPlayer===sound && videoModeVisible) finishVideoPlayback()
            }
            sound.setOnErrorListener { _,what,extra ->
                stopVideoTest("Japanese MP4 playback failed (MediaPlayer " + what + "/" + extra + ")")
                true
            }
            sound.prepareAsync()
        } catch(error:Exception) {
            stopVideoTest("Cannot open bundled Japanese MP4: " + (error.localizedMessage ?: "unknown error"))
        }
    }

    private fun fitVideoTexture() {
        val sound=videoPlayer ?: return
        val nativeWidth=sound.videoWidth
        val nativeHeight=sound.videoHeight
        if(nativeWidth<=0 || nativeHeight<=0 || video.width<=0 || video.height<=0) return
        val fit=min(video.width.toFloat()/nativeWidth,video.height.toFloat()/nativeHeight)
        val x=fit*nativeWidth/video.width
        val y=fit*nativeHeight/video.height
        val matrix=Matrix()
        matrix.setScale(x,y,video.width/2f,video.height/2f)
        video.setTransform(matrix)
    }

    private fun maybeStartVideoPlayback() {
        if(!videoRequested || !videoPrepared || !videoAudioResolved || videoPlaying) return
        if(videoDubbed && !dubRecognizerReady && !dubFailed) return
        try {
            videoPlayer?.start()
            videoPlaying=true
            if(videoDubbed) {
                setStatus("VIDEO DUB · Muted Japanese video playing; original AAC decoded into ASR PCM")
                if(!dubFailed) decodeDubbedVideoAudio(dubSession)
                else updateDubStatus("Japanese soundtrack decoding unavailable; video remains muted")
            } else {
                setStatus("VIDEO OCR · Playing decoded Japanese frames; OCR sampled every 1.2 seconds")
            }
            if(!videoDubbed && videoCombinedAudio && !audioCaptureReady) {
                audioDetected.text="Audio unavailable: MediaProjection, on-device speech model or consent failed. " +
                    "Video OCR continues independently; microphone is never used."
            } else if(!videoDubbed && !videoCombinedAudio) {
                audioDetected.text="VIDEO AUDIO · Not requested; independent visual OCR only"
            }
            if(!videoDubbed) videoHandler.postDelayed(videoFrameTick,500L)
        } catch(error:Exception) {
            stopVideoTest("Could not start Japanese video playback: " + (error.localizedMessage ?: "unknown error"))
        }
    }

    private fun readVideoFrame() {
        if(!videoPlaying || videoOcrBusy || !video.isAvailable) return
        val reader=videoReader ?: return
        val width=video.width
        val height=video.height
        if(width<=0 || height<=0) return
        val captureWidth=min(1280,(width*2).coerceAtLeast(640))
        val captureHeight=(captureWidth.toLong()*height/width).toInt().coerceAtLeast(1)
        val bitmap=try { video.getBitmap(captureWidth,captureHeight) }
            catch(error:Exception) {
                setStatus("VIDEO OCR · Cannot read video texture: " + (error.localizedMessage ?: "frame unavailable"))
                null
            } ?: return
        videoOcrBusy=true
        val token=videoSession
        try {
            reader.recognize(bitmap).addOnSuccessListener { frame ->
                if(token!=videoSession || !videoModeVisible) return@addOnSuccessListener
                val source=frame.text.trim().take(1000)
                if(PortalPreferences(this).translationNaturalInpaint) {
                    captureNaturalFrame(bitmap,frame.regions,true)
                    if(source==videoLastSource && videoLastTranslated.isNotBlank()) {
                        naturalCaption=videoLastTranslated
                        showNaturalPreview()
                    }
                }
                if(source.isBlank()) {
                    clearNaturalPreview()
                    naturalCaption=""
                    naturalLineTranslations.clear()
                    videoLastSource=""
                    videoLastTranslated=""
                }
                if(source.isBlank() || source==videoLastSource ||
                    source.none { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }) return@addOnSuccessListener
                naturalLineTranslations.clear()
                naturalCaption=""
                clearNaturalPreview()
                videoLastSource=source
                videoPendingTranslation=source
                videoRecognitions++
                val second=(videoPlayer?.currentPosition ?: 0)/1000
                detected.text="VIDEO OCR · " + second + "s · Recognized Japanese:\n" + source
                if(PortalPreferences(this).translationTarget==TranslateLanguage.JAPANESE) {
                    videoLastTranslated=source
                    naturalCaption=source
                    if(!showNaturalPreview()) {
                        output.text=source
                        output.visibility=View.VISIBLE
                    }
                    setStatus("VIDEO OCR · Target is Japanese: displaying real recognized source text")
                } else if(videoTranslationReady) {
                    translateLatestVideoCaption(token)
                } else {
                    setStatus("VIDEO OCR · Captions detected; " +
                        videoModelError.ifBlank { "waiting for Japanese offline translation model…" })
                }
            }.addOnFailureListener { error ->
                if(token==videoSession && videoModeVisible)
                    setStatus("VIDEO OCR · ML Kit Japanese frame recognition failed: " +
                        (error.localizedMessage ?: "unknown error"))
            }.addOnCompleteListener {
                bitmap.recycle()
                if(token==videoSession) videoOcrBusy=false
            }
        } catch(error:Exception) {
            bitmap.recycle()
            videoOcrBusy=false
            setStatus("VIDEO OCR · Could not submit frame: " + (error.localizedMessage ?: "unknown error"))
        }
    }

    private fun translateLatestVideoCaption(token:Int) {
        if(token!=videoSession || !videoModeVisible || !videoTranslationReady || videoTranslationBusy) return
        val source=videoPendingTranslation.takeIf { it.isNotBlank() } ?: return
        val client=videoTranslator ?: return
        videoTranslationBusy=true
        client.translate(source).addOnSuccessListener { translated ->
            if(token==videoSession && videoModeVisible && source==videoLastSource) {
                videoLastTranslated=translated.take(700)
                naturalCaption=videoLastTranslated
                if(!showNaturalPreview()) {
                    output.text=videoLastTranslated
                    output.visibility=View.VISIBLE
                }
                videoTranslator?.let { translateNaturalLines(it,token,true) }
                videoTranslations++
                setStatus("VIDEO OCR · " + videoRecognitions + " captions recognized; " +
                    videoTranslations + " translated from live MP4 frames")
            }
        }.addOnFailureListener { error ->
            if(token==videoSession && videoModeVisible)
                setStatus("VIDEO OCR · Recognized Japanese; translation failed: " +
                    (error.localizedMessage ?: "offline model missing"))
        }.addOnCompleteListener {
            if(token==videoSession) {
                videoTranslationBusy=false
                if(videoPendingTranslation!=source) translateLatestVideoCaption(token)
            }
        }
    }

    private fun finishVideoPlayback() {
        if(!videoModeVisible) return
        videoRequested=false
        videoPlaying=false
        videoHandler.removeCallbacks(videoFrameTick)
        videoPlayer?.release();videoPlayer=null
        videoLaunch.isEnabled=true
        dubLaunch.isEnabled=true
        if(videoDubbed) {
            updateDubStatus("Muted MP4 playback ended; processing final Japanese ASR segment")
            setStatus("VIDEO DUB · Playback ended; ${dubRecognizedCount} Japanese speech segments recognized, " +
                "${dubTranslatedCount} translated, ${dubSpokenCount} English spoken completions")
            val token=videoSession
            videoHandler.postDelayed({
                if(token==videoSession && videoDubbed && !videoRequested) {
                    closeDubPipe()
                    runCatching {dubRecognizer?.stopListening()}
                }
            },2000L)
            videoHandler.postDelayed({
                if(token==videoSession && videoDubbed && !videoRequested) {
                    updateDubStatus("Completed video playback and speech analysis")
                    stopDubAudio(stopVoice=false)
                }
            },6500L)
            return
        }
        setStatus(videoOutcomeStatus())
        if(videoCombinedAudio) {
            // Let the last spoken segment complete only for explicitly combined mode.
            val token=videoSession
            videoHandler.postDelayed({
                if(token==videoSession && videoModeVisible && !videoRequested) {
                    setStatus(videoOutcomeStatus())
                    audioController?.stop()
                }
            },2500L)
        }
    }

    private fun videoOutcomeStatus():String {
        val translated=if(videoTranslations>0) videoTranslations.toString() + " translated"
            else "none translated"
        val speech=when {
            !videoCombinedAudio -> "spoken audio not requested"
            videoSpokenTranslated -> "speech translated"
            videoSpokenRecognized -> "speech recognized; translation pending or unavailable"
            else -> "no Japanese speech was recognized; check playback capture and offline speech model"
        }
        return "VIDEO OCR · Playback ended. " + videoRecognitions + " distinct OCR captions; " +
            translated + ". AUDIO · " + speech
    }

    private fun stopVideoTest(reason:String?) {
        videoSession++
        stopDubAudio()
        videoDubbed=false
        videoRequested=false
        videoModeVisible=false
        videoPlaying=false
        videoPrepared=false
        resetNaturalPreview()
        videoHandler.removeCallbacksAndMessages(null)
        videoPlayer?.release();videoPlayer=null
        videoReader?.close();videoReader=null
        videoTranslator?.close();videoTranslator=null
        if(::video.isInitialized) video.visibility=View.GONE
        if(::art.isInitialized) art.visibility=View.VISIBLE
        if(::videoLaunch.isInitialized) videoLaunch.isEnabled=true
        if(::dubLaunch.isInitialized) dubLaunch.isEnabled=true
        if(::videoStop.isInitialized) videoStop.visibility=View.GONE
        if(::dubSubtitle.isInitialized) dubSubtitle.visibility=View.GONE
        if(::output.isInitialized) output.visibility=View.GONE
        if(videoCombinedAudio) audioController?.stop()
        videoCombinedAudio=false
        if(reason!=null && ::status.isInitialized) setStatus(reason)
    }

    private fun setStatus(value:String) {
        if(!isFinishing && !isDestroyed) status.text=value
    }
    private fun complete(value:String) {
        running=false
        launch.isEnabled=true
        setStatus(value)
    }

    private fun runTest() {
        if(running) return
        val target=TranslateLanguage.ENGLISH
        ++generation
        val token=generation
        running=true
        resetNaturalPreview()
        launch.isEnabled=false
        output.visibility=android.view.View.GONE
        setStatus("Recognizing Japanese characters from the bundled test image…")
        val reader=PortalMlKitOcrEngine.single(PortalOcrScript.JAPANESE)
        recognizer=reader
        val bitmap=BitmapFactory.decodeResource(resources,R.drawable.relay_japanese_ocr_test)
        if(bitmap==null) {reader.close();recognizer=null;complete("Could not decode the bundled test image");return}
        reader.recognize(bitmap)
            .addOnSuccessListener ocrListener@ {frame ->
                if(token!=generation) return@ocrListener
                captureNaturalFrame(bitmap,frame.regions,false)
                val source=frame.text.trim()
                if(source.isBlank()) {
                    complete("No Japanese text detected. Check ML Kit Japanese OCR availability.")
                    return@ocrListener
                }
                val sample=source.take(1000)
                detected.text="Recognized Japanese: $sample"
                if(target==TranslateLanguage.JAPANESE) {
                    naturalCaption=source
                    if(!showNaturalPreview()) {
                        output.text=source
                        output.visibility=android.view.View.VISIBLE
                    }
                    complete("Target is Japanese; showing detected source text without translation")
                    return@ocrListener
                }
                setStatus("Japanese OCR succeeded. Preparing offline translation model…")
                val client=Translation.getClient(TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.JAPANESE)
                    .setTargetLanguage(target).build())
                translator?.close();translator=client
                client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                    .addOnSuccessListener modelListener@ {
                        if(token!=generation) return@modelListener
                        setStatus("Translating recognized Japanese into ${Locale.forLanguageTag(target).displayLanguage}…")
                        client.translate(sample).addOnSuccessListener translatedListener@ {result ->
                            if(token!=generation) return@translatedListener
                            naturalCaption=result.take(700)
                            if(!showNaturalPreview()) {
                                output.text=naturalCaption
                                output.visibility=android.view.View.VISIBLE
                            }
                            translateNaturalLines(client,token,false)
                            complete("Success — translated text is overlaid on the Japanese test image")
                        }.addOnFailureListener {err->
                            if(token==generation) complete("Translation failed: ${err.localizedMessage ?: "unknown error"}")
                        }
                    }.addOnFailureListener {err->
                        if(token==generation) complete("Could not download translation model over Wi-Fi: ${err.localizedMessage ?: "unknown error"}")
                    }
            }.addOnFailureListener {err->
                if(token==generation) complete("Japanese OCR failed: ${err.localizedMessage ?: "unknown error"}")
            }.addOnCompleteListener {
                bitmap.recycle()
                if(recognizer===reader) {recognizer=null;reader.close()}
            }
    }

    override fun onDestroy() {
        generation++
        audioTestRequested=false
        cancelEnglishTtsTest()
        stopVideoTest(null)
        modelDownloadRecognizer?.destroy();modelDownloadRecognizer=null
        audioController?.stop()
        audioController?.dispose()
        player?.release();player=null
        speech?.stop();speech?.shutdown();speech=null
        englishSpeech?.stop();englishSpeech?.shutdown();englishSpeech=null
        recognizer?.close();recognizer=null
        translator?.close();translator=null
        super.onDestroy()
    }
}
