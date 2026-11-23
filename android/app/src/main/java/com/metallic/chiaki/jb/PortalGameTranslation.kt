// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.speech.tts.TextToSpeech
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.jb.translate.PortalCloudTranslator
import com.metallic.chiaki.jb.ocr.PortalVideoCrop
import com.metallic.chiaki.jb.ocr.PortalMlKitOcrEngine
import com.metallic.chiaki.jb.ocr.PortalOcrEngine
import com.metallic.chiaki.jb.ocr.PortalOcrScript
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors

/** Scoped game-video text translation: only PixelCopy from the decoded
 * SurfaceView, never from a Window or screen recorder. This excludes the
 * Relay app's own settings, menus, notifications and lock screens.
 *
 * Translation runs on-device using Wi-Fi-downloaded models. Capture regions,
 * text overlays and optional speech apply only to the decoded game video.
 */
class PortalGameTranslation(
    private val activity:AppCompatActivity,
    private val video:SurfaceView,
    private val parent:FrameLayout,
    private val canSample:()->Boolean
) {
    data class Mode(val title:String,val source:String,val target:String,val japanese:Boolean)
    companion object {
        data class VideoCrop(val x:Int,val y:Int,val width:Int,val height:Int)
        val modes=listOf(
            Mode("Japanese → English",TranslateLanguage.JAPANESE,TranslateLanguage.ENGLISH,true),
            Mode("Spanish → English",TranslateLanguage.SPANISH,TranslateLanguage.ENGLISH,false),
            Mode("English → Spanish",TranslateLanguage.ENGLISH,TranslateLanguage.SPANISH,false),
            Mode("English → French",TranslateLanguage.ENGLISH,TranslateLanguage.FRENCH,false),
            Mode("German → English",TranslateLanguage.GERMAN,TranslateLanguage.ENGLISH,false))
        /** Video-only crop. 0.60..1.00 of the decoded bitmap, never the app window. */
        fun subtitleRegion(width:Int,height:Int):VideoCrop {
            require(width>0 && height>0)
            val y=(height*.60f).toInt()
            return VideoCrop(0,y,width,height-y)
        }
    }
    private val handler=Handler(Looper.getMainLooper())
    private var recognizer:TextRecognizer?=null
    private var translator:Translator?=null
    private var autoMode=false
    private var ocrEngine:PortalOcrEngine?=null
    private var identifier:LanguageIdentifier?=null
    private val autoTranslators=mutableMapOf<String,Translator>()
    private val readyPairs=mutableSetOf<String>()
    private val downloadingPairs=mutableSetOf<String>()
    private var targetLanguage=TranslateLanguage.ENGLISH
    private val options by lazy { PortalPreferences(activity) }
    private val cloud by lazy { PortalCloudTranslator(activity) }
    private val overlay=FrameLayout(activity).apply {
        isClickable=false;isFocusable=false
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        clipChildren=false
    }
    private val activeBoxes=mutableMapOf<String,TextView>()
    private data class NaturalLine(
        val key:String,val source:Rect,val frameWidth:Int,val frameHeight:Int,
        val erased:ImageView,val translated:TextView,var bitmap:Bitmap?=null)
    private data class NaturalSource(val key:String,val region:Rect,val width:Int,val height:Int)
    private data class NaturalResult(val key:String,val patch:PortalNaturalTextInpaint.Patch?)
    private val naturalLines=linkedMapOf<String,NaturalLine>()
    private val naturalWorker=Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable,"RelayNaturalText").apply {isDaemon=true}
    }
    /** Only accessed on naturalWorker; holds recent decoded game frames for clean plate recovery. */
    private val naturalHistory=ArrayDeque<PortalNaturalTextInpaint.Frame>()
    private var naturalCapturing=false
    private val translationCache=object:LinkedHashMap<String,String>(96,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>96
    }
    private val history by lazy {PortalTranslationHistory(activity)}
    private val priorDialogue=ArrayDeque<Pair<String,String>>()
    private var lastSaved=""
    private var voice:TextToSpeech?=null
    private var lastSpoken=""
    private var blankReads=0
    private var generation=0
    private var scanRevision=0
    private var active=false
    private var busy=false
    private var lastText=""
    private var sourceLanguage=""
    /** Read-only OCR result for an explicit user-triggered dictionary screen. */
    val currentGameText:String get()=if(active) lastText else ""
    val detectedSourceLanguage:String get()=sourceLanguage
    private var lastTranslation=""
    val isEnabled get()=active
    /** User-triggered scan while an existing game-only translation session is active. */
    fun scanNow():Boolean {
        if(!active || busy || !canSample()) return false
        sample()
        return true
    }
    private val caption=TextView(activity).apply {
        gravity=Gravity.CENTER
        textSize=19f
        setTextColor(Color.WHITE)
        setPadding(20,14,20,14)
        background=GradientDrawable().apply {
            setColor(0xcc1c1a24.toInt())
            cornerRadius=16f
            setStroke(1,0x667d739f)
        }
        visibility=View.GONE
        contentDescription="Translated game subtitles"
        isClickable=false
        isFocusable=false
    }
    private val tick=object:Runnable {
        override fun run() {
            if(!active) return
            if(canSample() && !busy) sample()
            handler.postDelayed(this,options.translationScanIntervalMs.toLong())
        }
    }
    private val naturalTick=object:Runnable {
        override fun run() {
            if(!active || !options.translationNaturalInpaint) return
            refreshNaturalBackground()
            handler.postDelayed(this,if(naturalLines.isEmpty()) 1050L else 550L)
        }
    }
    private fun captionBackground():GradientDrawable = GradientDrawable().apply {
        val opacity=(options.translationBackgroundOpacity*255/100).coerceIn(0,255)
        setColor((opacity shl 24) or 0x101429)
        cornerRadius=16f*activity.resources.displayMetrics.density
        setStroke(1,0x667d739f)
    }
    private fun applyCaptionStyle() {
        val natural=options.translationNaturalInpaint
        caption.textSize=options.translationTextSizeSp.toFloat()
        caption.background=if(natural) null else captionBackground()
        caption.setShadowLayer(if(natural) 3f else 0f,0f,1f,Color.BLACK)
        if(natural) caption.setPadding(0,0,0,0)
        else caption.setPadding(20,14,20,14)
        activeBoxes.values.forEach {box ->
            box.textSize=options.translationTextSizeSp.toFloat()
            box.background=if(natural) null else captionBackground()
        }
    }
    init {
        val dp=activity.resources.displayMetrics.density
        parent.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        parent.addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin=(dp*48).toInt();rightMargin=(dp*48).toInt()
            bottomMargin=(dp*76).toInt()
        })
    }
    fun enable(mode:Mode,onState:(String)->Unit) {
        stop()
        applyCaptionStyle()
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O) {
            onState("Game translation requires Android 8 or newer")
            return
        }
        recognizer=if(mode.japanese)
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        else TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val options=TranslatorOptions.Builder()
            .setSourceLanguage(mode.source)
            .setTargetLanguage(mode.target)
            .build()
        val client=Translation.getClient(options)
        translator=client
        onState("Preparing offline translation · ${mode.title}")
        client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener {
                if(translator!==client) return@addOnSuccessListener
                active=true
                lastText=""
                lastTranslation=""
                caption.text=""
                caption.visibility=View.GONE
                handler.post(tick)
                if(this.options.translationNaturalInpaint) handler.post(naturalTick)
                onState("Game translation enabled · ${mode.title}")
            }.addOnFailureListener {error->
                if(translator===client) {
                    stop()
                    onState("Could not prepare offline translator: ${error.localizedMessage ?: "model unavailable"}")
                }
            }
    }
    /** Auto mode is armed by user preferences, never outside a real stream.
     * All OCR input comes from the decoded game SurfaceView (no MediaProjection).
     */
    fun enableAuto(target:String,onState:(String)->Unit) {
        stop()
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O) {
            onState("Translation requires Android 8 or newer")
            return
        }
        if(target !in TranslateLanguage.getAllLanguages()) {
            onState("Unsupported translation language")
            return
        }
        targetLanguage=target
        applyCaptionStyle()
        if(options.translationSpeech) voice=TextToSpeech(activity) {result ->
            if(result==TextToSpeech.SUCCESS) voice?.let { tts ->
                val speechLanguage=if(targetLanguage=="en") options.translationSpeechLanguage
                    else targetLanguage
                tts.language=Locale.forLanguageTag(speechLanguage)
                tts.setSpeechRate(options.translationSpeechRate)
            }
        }
        autoMode=true
        identifier=LanguageIdentification.getClient()
        val script=runCatching {PortalOcrScript.valueOf(options.translationOcrScript)}.getOrNull()
        ocrEngine=if(script==null) PortalMlKitOcrEngine.automatic()
            else PortalMlKitOcrEngine.single(script)
        active=true
        handler.post(tick)
        if(options.translationNaturalInpaint) handler.post(naturalTick)
        onState("Automatic game translation enabled; language detection starts with gameplay")
    }
    private fun sample() {
        if(!active || !video.holder.surface.isValid) return
        val w=video.width;val h=video.height
        if(w<160 || h<100) return
        busy=true
        // Auto mode scans the whole game image, at a capped resolution for TV hardware.
        val sampledW=w.coerceAtMost(if(autoMode) 720 else 960)
        val sampledH=(h.toFloat()*sampledW/w).toInt().coerceAtLeast(90)
        val image=Bitmap.createBitmap(sampledW,sampledH,Bitmap.Config.ARGB_8888)
        PixelCopy.request(video,image,{status->
            if(status!=PixelCopy.SUCCESS || !active) {
                image.recycle();busy=false
                return@request
            }
            val chosen=if(autoMode && options.translationRegion=="CUSTOM") {
                val p=options.translationCustomCrop.pixels(sampledW,sampledH)
                PortalTranslationLayout.Crop(p.x,p.y,p.width,p.height)
            } else if(autoMode) PortalTranslationLayout.crop(sampledW,sampledH,options.translationRegion)
                else PortalTranslationLayout.crop(sampledW,sampledH,"SUBTITLES")
            val crop=VideoCrop(chosen.x,chosen.y,chosen.width,chosen.height)
            val sub=Bitmap.createBitmap(image,crop.x,crop.y,crop.width,crop.height)
            image.recycle()
            if(autoMode) {
                sampleAuto(sub,crop,sampledW,sampledH)
                return@request
            }
            val reader=recognizer
            if(reader==null) {sub.recycle();busy=false;return@request}
            reader.process(InputImage.fromBitmap(sub,0))
                .addOnSuccessListener {result->
                    val text=result.text.lineSequence().map{it.trim()}
                        .filter {it.length>=3}.take(4).joinToString(" ")
                        .take(220)
                    if(text.isNotBlank() && text!=lastText && active) {
                        lastText=text
                        val lines=if(options.translationNaturalInpaint) result.textBlocks
                            .asSequence().flatMap {it.lines.asSequence()}.mapNotNull {line ->
                                val bounds=line.boundingBox
                                val value=line.text.trim()
                                if(value.length<3 || bounds==null || bounds.isEmpty) null
                                else value.take(220) to Rect(bounds)
                            }.take(4).toList() else emptyList()
                        if(lines.isNotEmpty()) {
                            val token=generation
                            caption.visibility=View.GONE
                            clearBoxes()
                            lines.forEach {(source,bounds) ->
                                translator?.translate(source)?.addOnSuccessListener {translated ->
                                    if(active && token==generation && lastText==text && translated.isNotBlank()) {
                                        lastTranslation=translated.take(260)
                                        renderNaturalText(lastTranslation,source,bounds,crop,sampledW,sampledH)
                                    }
                                }
                            }
                        } else {
                            translator?.translate(text)?.addOnSuccessListener {translated->
                                if(active && lastText==text && translated.isNotBlank()) {
                                    lastTranslation=translated.take(260)
                                    caption.text=lastTranslation
                                    caption.visibility=View.VISIBLE
                                }
                            }?.addOnFailureListener {
                                if(active) caption.visibility=View.GONE
                            }
                        }
                    }
                }.addOnCompleteListener {
                    sub.recycle()
                    busy=false
                }
        },handler)
    }
    private fun sampleAuto(bitmap:Bitmap,crop:VideoCrop,frameW:Int,frameH:Int) {
        val token=generation
        val engine=ocrEngine
        if(engine==null) {bitmap.recycle();busy=false;return}
        engine.recognize(bitmap).addOnSuccessListener {result ->
            if(!active || token!=generation) return@addOnSuccessListener
            val text=PortalTranslationLayout.normalized(result.text)
            if(text.length<3) {
                if(++blankReads>=2) {
                    scanRevision++;clearBoxes();caption.visibility=View.GONE;lastText=""
                }
                return@addOnSuccessListener
            }
            blankReads=0
            if(!PortalTranslationLayout.changed(lastText,text)) return@addOnSuccessListener
            lastText=text
            val revision=++scanRevision
            caption.visibility=View.GONE
            identifier?.identifyLanguage(text)?.addOnSuccessListener languageListener@ {detected ->
                if(!active || token!=generation || revision!=scanRevision || text!=lastText)
                    return@languageListener
                val script=result.script.id
                val source=when {
                    script in listOf("ja","ko","hi") -> script
                    script=="zh" && text.any{it in '\u4e00'..'\u9fff'} -> "zh"
                    detected!="und" -> detected.substringBefore('-')
                    else -> ""
                }
                if(source==targetLanguage || source !in TranslateLanguage.getAllLanguages()) {
                    clearBoxes();caption.visibility=View.GONE
                    return@languageListener
                }
                sourceLanguage=source
                val lines=result.regions.filter {
                    PortalTranslationLayout.normalized(it.text).length>=3
                }.take(4)
                clearBoxes()
                if(lines.isEmpty() || (!options.translationNaturalInpaint &&
                        options.translationPlacement=="SUBTITLE"))
                    translateAuto(text,source,token,revision,null,crop,frameW,frameH)
                else lines.forEach {line ->
                    val bounds=line.bounds
                    val rect=Rect(bounds.x,bounds.y,bounds.x+bounds.width,bounds.y+bounds.height)
                    translateAuto(PortalTranslationLayout.normalized(line.text),source,
                        token,revision,rect,crop,frameW,frameH)
                }
            }?.addOnFailureListener {
                if(token==generation && revision==scanRevision) caption.visibility=View.GONE
            }
        }.addOnFailureListener {
            if(active && token==generation) {
                blankReads++
                if(blankReads>=2) {
                    scanRevision++;clearBoxes();caption.visibility=View.GONE
                }
            }
        }.addOnCompleteListener {
            bitmap.recycle()
            busy=false
        }
    }    private fun translateAuto(text:String,source:String,token:Int,revision:Int,region:Rect?,crop:VideoCrop,frameW:Int,frameH:Int) {
        val pair="$source:$targetLanguage"
        val cacheKey="$pair|$text|${if(cloud.enabled) cloud.endpoint+"/"+cloud.model else "offline"}"
        if(cloud.enabled) {
            translationCache[cacheKey]?.let {cached ->
                renderText(cached,text,region,crop,frameW,frameH,token,revision)
                return
            }
            activity.lifecycleScope.launch {
                val context=if(options.cloudDialogueContext) priorDialogue.toList() else emptyList()
                val translated=runCatching {cloud.translate(text,source,targetLanguage,context)}.getOrNull()
                if(active && token==generation && revision==scanRevision && !translated.isNullOrBlank()) {
                    val value=translated.take(300)
                    translationCache[cacheKey]=value
                    renderText(value,text,region,crop,frameW,frameH,token,revision)
                }
            }
            return
        }
        val client=autoTranslators.getOrPut(pair) {
            Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(source).setTargetLanguage(targetLanguage).build())
        }
        if(pair !in readyPairs) {
            if(!downloadingPairs.add(pair)) return
            client.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
                .addOnSuccessListener {
                    downloadingPairs.remove(pair)
                    if(token==generation && active) {
                        readyPairs.add(pair)
                        lastText="" // retry detection when model is installed
                    }
                }.addOnFailureListener {
                    downloadingPairs.remove(pair)
                    if(token==generation && active) {
                        lastText="" // retry once network/model is available
                        caption.visibility=View.GONE
                    }
                }
            return
        }
        val cached=translationCache[cacheKey]
        if(cached!=null) {renderText(cached,text,region,crop,frameW,frameH,token,revision);return}
        client.translate(text).addOnSuccessListener { translated ->
            if(active && token==generation && revision==scanRevision && translated.isNotBlank()) {
                translationCache[cacheKey]=translated.take(300)
                renderText(translated.take(300),text,region,crop,frameW,frameH,token,revision)
            }
        }.addOnFailureListener {if(token==generation && region==null) caption.visibility=View.GONE}
    }
    private fun renderText(translated:String,original:String,region:Rect?,crop:VideoCrop,
        frameW:Int,frameH:Int,token:Int,revision:Int) {
        if(token!=generation || revision!=scanRevision || !active || !canSample()) return
        if(options.translationNaturalInpaint && region!=null) {
            renderNaturalText(translated,original,region,crop,frameW,frameH)
        } else if(region==null || options.translationPlacement=="SUBTITLE") {
            caption.text=translated
            caption.visibility=View.VISIBLE
        } else {
            val videoXY=IntArray(2);val rootXY=IntArray(2)
            video.getLocationOnScreen(videoXY);parent.getLocationOnScreen(rootXY)
            val placement=PortalTranslationLayout.placement(region.left,region.top,
                region.width(),frameW,frameH,
                PortalTranslationLayout.Crop(crop.x,crop.y,crop.width,crop.height),
                video.width.coerceAtLeast(1),video.height.coerceAtLeast(1))
            val dp=activity.resources.displayMetrics.density
            val key=original
            val view=activeBoxes.getOrPut(key) {TextView(activity).apply {
                textSize=options.translationTextSizeSp.toFloat();gravity=Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding((8*dp).toInt(),(5*dp).toInt(),(8*dp).toInt(),(5*dp).toInt())
                background=captionBackground()
                isClickable=false;isFocusable=false
                this@PortalGameTranslation.overlay.addView(this)
            }}
            view.text=translated
            val width=(placement.width+(40*dp).toInt()).coerceAtLeast((110*dp).toInt())
                .coerceAtMost((video.width*.78f).toInt().coerceAtLeast(1))
            view.layoutParams=FrameLayout.LayoutParams(width,-2,Gravity.TOP or Gravity.START).apply {
                leftMargin=(placement.left+videoXY[0]-rootXY[0])
                    .coerceIn(0,(parent.width-width).coerceAtLeast(0))
                topMargin=(placement.top+videoXY[1]-rootXY[1])
                    .coerceIn(0,(parent.height-(60*dp).toInt()).coerceAtLeast(0))
            }
        }
        if(options.translationSpeech && translated!=lastSpoken) {
            lastSpoken=translated
            voice?.speak(translated,TextToSpeech.QUEUE_FLUSH,null,"relay_game_translation")
        }
        val savedKey="$original|$translated"
        if(options.translationHistoryEnabled && savedKey!=lastSaved) {
            lastSaved=savedKey
            history.add(original,translated,targetLanguage)
        }
        if(priorDialogue.lastOrNull()?.first!=original) {
            priorDialogue.addLast(original.take(260) to translated.take(300))
            while(priorDialogue.size>3) priorDialogue.removeFirst()
        }
    }
    private fun renderNaturalText(translated:String,original:String,region:Rect,crop:VideoCrop,
        frameW:Int,frameH:Int) {
        val source=Rect(region).apply {offset(crop.x,crop.y)}
        val key="$original@${source.left}:${source.top}"
        val line=naturalLines[key] ?: run {
            val erased=ImageView(activity).apply {
                scaleType=ImageView.ScaleType.FIT_XY
                isClickable=false;isFocusable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility=View.INVISIBLE
            }
            val label=TextView(activity).apply {
                gravity=Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                textSize=options.translationTextSizeSp.toFloat()
                setPadding(0,0,0,0)
                setShadowLayer(2f,0f,1f,Color.BLACK)
                background=null
                isClickable=false;isFocusable=false
                visibility=View.INVISIBLE
            }
            overlay.addView(erased,FrameLayout.LayoutParams(1,1))
            overlay.addView(label,FrameLayout.LayoutParams(1,1))
            NaturalLine(key,source,frameW,frameH,erased,label).also {
                naturalLines[key]=it
            }
        }
        line.translated.text=translated
        line.translated.textSize=options.translationTextSizeSp.toFloat()
        val videoXY=IntArray(2);val rootXY=IntArray(2)
        video.getLocationOnScreen(videoXY);parent.getLocationOnScreen(rootXY)
        val position=PortalTranslationLayout.placement(source.left,source.top,source.width(),
            frameW,frameH,PortalTranslationLayout.Crop(0,0,frameW,frameH),
            video.width.coerceAtLeast(1),video.height.coerceAtLeast(1))
        val dp=activity.resources.displayMetrics.density
        val maxWidth=(video.width*.88f).toInt().coerceAtLeast(1)
        val width=(position.width*1.6f).toInt().coerceAtLeast((100*dp).toInt())
            .coerceAtMost(maxWidth)
        line.translated.layoutParams=FrameLayout.LayoutParams(width,-2,Gravity.TOP or Gravity.START).apply {
            leftMargin=(position.left+videoXY[0]-rootXY[0])
                .coerceIn(0,(parent.width-width).coerceAtLeast(0))
            topMargin=(position.top+videoXY[1]-rootXY[1])
                .coerceIn(0,(parent.height-(30*dp).toInt()).coerceAtLeast(0))
        }
    }

    /** Refresh game pixels independently of the expensive OCR/translation interval. */
    private fun refreshNaturalBackground() {
        if(naturalCapturing || !canSample() || !video.holder.surface.isValid) return
        val width=video.width.coerceAtMost(720)
        if(width<160 || video.height<100) return
        val height=(video.height.toFloat()*width/video.width).toInt().coerceAtLeast(90)
        val frame=runCatching {Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)}
            .getOrNull() ?: return
        val token=generation
        val revision=scanRevision
        naturalCapturing=true
        PixelCopy.request(video,frame,{status ->
            if(status!=PixelCopy.SUCCESS || !active || token!=generation ||
                revision!=scanRevision || !options.translationNaturalInpaint) {
                frame.recycle();naturalCapturing=false
                return@request
            }
            val sources=naturalLines.values.map {
                NaturalSource(it.key,Rect(it.source),it.frameWidth,it.frameHeight)
            }
            try {
                naturalWorker.execute {
                    val patches=try {
                        val snapshot=PortalNaturalTextInpaint.Frame.capture(frame)
                        sources.map {source ->
                            val bounds=Rect(
                                source.region.left*width/source.width,
                                source.region.top*height/source.height,
                                source.region.right*width/source.width,
                                source.region.bottom*height/source.height)
                            NaturalResult(source.key,runCatching {
                                PortalNaturalTextInpaint.restore(snapshot,bounds,naturalHistory.toList())
                            }.getOrNull())
                        }.also {
                            naturalHistory.addLast(snapshot)
                            while(naturalHistory.size>8) naturalHistory.removeFirst()
                        }
                    } finally {frame.recycle()}
                    handler.post {
                        naturalCapturing=false
                        val usable=active && token==generation && revision==scanRevision &&
                            options.translationNaturalInpaint && canSample()
                        val videoXY=IntArray(2);val rootXY=IntArray(2)
                        if(usable) {
                            video.getLocationOnScreen(videoXY)
                            parent.getLocationOnScreen(rootXY)
                        }
                        patches.forEach {result ->
                            val patch=result.patch ?: run {
                                if(usable) naturalLines[result.key]?.translated?.visibility=View.VISIBLE
                                return@forEach
                            }
                            val line=if(usable) naturalLines[result.key] else null
                            if(line==null) {
                                patch.image.recycle()
                                return@forEach
                            }
                            val previous=line.bitmap
                            line.erased.setImageBitmap(patch.image)
                            line.bitmap=patch.image
                            line.erased.visibility=View.VISIBLE
                            val bw=(patch.bounds.width()*video.width/width).coerceAtLeast(1)
                            val bh=(patch.bounds.height()*video.height/height).coerceAtLeast(1)
                            line.erased.layoutParams=FrameLayout.LayoutParams(bw,bh,Gravity.TOP or Gravity.START).apply {
                                leftMargin=(patch.bounds.left*video.width/width+videoXY[0]-rootXY[0])
                                    .coerceIn(0,(parent.width-bw).coerceAtLeast(0))
                                topMargin=(patch.bounds.top*video.height/height+videoXY[1]-rootXY[1])
                                    .coerceIn(0,(parent.height-bh).coerceAtLeast(0))
                            }
                            line.translated.setTextColor(if(patch.darkLettering) Color.rgb(24,24,32) else Color.WHITE)
                            line.translated.setShadowLayer(1.3f,0f,.7f,
                                if(patch.darkLettering) Color.WHITE else Color.BLACK)
                            line.translated.visibility=View.VISIBLE
                            previous?.recycle()
                        }
                    }
                }
            } catch(_:java.util.concurrent.RejectedExecutionException) {
                frame.recycle();naturalCapturing=false
            }
        },handler)
    }
    private fun clearBoxes() {
        naturalLines.values.forEach {
            it.erased.setImageDrawable(null)
            it.bitmap?.recycle()
            it.bitmap=null
        }
        naturalLines.clear()
        activeBoxes.clear()
        overlay.removeAllViews()
    }
    fun stop() {
        generation++
        scanRevision++
        active=false
        translationCache.clear()
        priorDialogue.clear()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(naturalTick)
        naturalWorker.execute {naturalHistory.clear()}
        caption.visibility=View.GONE
        clearBoxes()
        voice?.stop();voice?.shutdown();voice=null
        lastSpoken="";blankReads=0
        lastSaved=""
        recognizer?.close();recognizer=null
        translator?.close();translator=null
        ocrEngine?.close();ocrEngine=null
        identifier?.close();identifier=null
        autoTranslators.values.forEach {it.close()};autoTranslators.clear()
        readyPairs.clear();downloadingPairs.clear()
        autoMode=false
        lastText="";lastTranslation=""
        sourceLanguage=""
    }
    fun dispose() {
        stop()
        naturalWorker.shutdownNow()
        parent.removeView(caption);parent.removeView(overlay)
    }
}
