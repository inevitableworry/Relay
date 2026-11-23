// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.metallic.chiaki.R
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.common.*
import com.metallic.chiaki.jb.GalleryActivity
import com.metallic.chiaki.jb.PortalTranslationHistory
import com.metallic.chiaki.jb.PortalAnkiExport
import com.metallic.chiaki.jb.translate.PortalCloudTranslator
import com.metallic.chiaki.jb.PbsClient
import com.metallic.chiaki.jb.PbsConfig
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.session.ControllerProfileEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Locale

/** All Android source settings, presented inside one adaptive Portal settings hierarchy. */
class PortalSettingsActivity : AppCompatActivity() {
    companion object { const val EXTRA_PAGE="page" }
    private lateinit var page: PortalPage
    private lateinit var prefs: Preferences
    private lateinit var portal: PortalPreferences
    private var section="home"
    private var registered=emptyList<RegisteredHost>()
    private val history=ArrayDeque<String>()
    private var columns=2
    private var layoutWidth=0
    private var jbCapabilities:PbsConfig?=null
    private var jbCapabilityKey=""
    private var jbCapabilityCheckedAt=0L
    private var jbCapabilityLoading=false
    private var jbCapabilityMessage="Checking PS5 capabilities…"
    private val importSettings=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) importSettingsFromUri(this,uri,lifecycleScope)
    }
    private val ankiPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->
        Toast.makeText(this,if(granted) "AnkiDroid access granted. Select the entry again to export."
            else "AnkiDroid access was not granted.",Toast.LENGTH_LONG).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs=Preferences(this);portal=PortalPreferences(this)
        PortalUi.fullscreen(this)
        section=savedInstanceState?.getString("section") ?: intent.getStringExtra(EXTRA_PAGE) ?: "home"
        page=PortalPage(this,"Settings",::back)
        setContentView(page.root)
        onBackPressedDispatcher.addCallback(this) {back()}
        page.root.addOnLayoutChangeListener {_,l,_,r,_,_,_,_,_ ->
            val width=(r-l)/resources.displayMetrics.density
            val next=when {width>=700f -> 3; width>=350f -> 2; else -> 1}
            if(columns!=next || layoutWidth!=r-l) {
                columns=next;layoutWidth=r-l
                if(section=="home") render()
            }
        }
        lifecycleScope.launch {
            getDatabase(this@PortalSettingsActivity).registeredHostDao().getAll().collect {
                registered=it
                if(section=="consoles") render()
            }
        }
        render()
    }
    override fun onResume() {super.onResume();if(::page.isInitialized) {PortalUi.fullscreen(this);render()}}
    override fun onSaveInstanceState(state:Bundle) {state.putString("section",section);super.onSaveInstanceState(state)}
    override fun onConfigurationChanged(config:Configuration) {super.onConfigurationChanged(config);render()}
    private fun open(next:String) {history.addLast(section);section=next;render();PortalMotion.crossfade(page.body,1)}
    private fun back() {
        PortalMenuAudio.play(this,PortalMenuAudio.Cue.BACK)
        if(history.isNotEmpty()) section=history.removeLast()
        else if(section!="home") section="home"
        else {finish();return}
        render();PortalMotion.crossfade(page.body,-1)
    }
    private fun row(title:String,summary:String="",value:String="",icon:PortalUi.Icon?=null,action:()->Unit) =
        page.add(PortalUi.row(this,title,summary,value,icon,action))
    private fun toggle(title:String,summary:String="",checked:Boolean,change:(Boolean)->Unit) =
        page.add(PortalUi.toggle(this,title,summary,checked,change))
    private fun render() {
        val titles=mapOf("home" to "Settings","network" to "Network","system" to "System",
            "display" to "Display and Brightness","controller" to "Controller","audio" to "PS Link Device",
            "cloud" to "Cloud Streaming",
            "remote" to if(portal.mode==PortalPreferences.Mode.JB) "Remote Play JB" else "Remote Play",
            "consoles" to "Registered Consoles","logs" to "Session Logs",
            "translation" to "Game Translation", "translation-history" to "Translation History")
        // Translation settings are available for both Traditional and JB modes.
        page.clear(titles[section] ?: "Settings")
        page.body.setPadding(0,0,0,0)
        if(section=="remote") modeSelector()
        when(section) {
            "home" -> home()
            "network" -> network()
            "system" -> system()
            "display" -> display()
            "controller" -> controller()
            "audio" -> audio()
            "cloud" -> cloud()
            "remote" -> if(portal.mode==PortalPreferences.Mode.JB) jb() else traditional()
            "translation" -> translationSettings()
            "translation-history" -> translationHistory()
            "consoles" -> consoles()
            "logs" -> logs()
        }
    }
    private fun modeSelector() {
        page.note("Remote Play Mode")
        val modes=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        for(mode in PortalPreferences.Mode.values()) {
            val selected=portal.mode==mode
            val button=PortalUi.primary(this,if(mode==PortalPreferences.Mode.JB) "Remote Play JB" else "Traditional") {
                if(portal.mode!=mode) {portal.mode=mode;render()}
            }.apply {
                if(!selected) {background=PortalUi.surface(context,50);setTextColor(Color.WHITE)}
                isSelected=selected
                contentDescription=text.toString()+if(selected) ", selected" else ""
            }
            modes.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply {
                marginEnd=page.dp(6);marginStart=page.dp(6)
            })
        }
        page.add(modes)
    }
    private fun home() {
        // The current Portal grid spans about 75% of the landscape screen width.
        // Compact windows use the available space and wrap the same categories.
        val inset=if(columns==3) (layoutWidth*.075f).toInt() else 0
        page.body.setPadding(inset,0,inset,0)
        val windowHeight=page.root.height/resources.displayMetrics.density
        // Current mapped tiles measure about 246 px on a 1080 px-high screen.
        val tileHeight=if(columns==3) (windowHeight*(246f/1080f)).toInt().coerceIn(112,252) else 132
        val iconSize=if(columns==3) (windowHeight*.054f).toInt().coerceIn(36,64) else 42
        val labelSize=if(columns==3) (windowHeight*.026f).coerceIn(16f,29f) else 17f
        // Current source: ~28 px card gaps at 1920×1080. Keep the existing outside grid bounds.
        val gridGap=if(columns==3) maxOf(page.dp(10),(28f*minOf(layoutWidth/1920f,page.root.height/1080f)).toInt())
            else page.dp(10)
        val entries=listOf(
            Triple("Network","network",PortalUi.Icon.NETWORK),
            Triple("System","system",PortalUi.Icon.SYSTEM),
            Triple("Display and Brightness","display",PortalUi.Icon.SCREEN),
            Triple("Controller","controller",PortalUi.Icon.CONTROLLER),
            Triple("PS Link Device","audio",PortalUi.Icon.PS_LINK),
            Triple("Cloud Streaming","cloud",PortalUi.Icon.CLOUD)
        )
        for((rowIndex,group) in entries.chunked(columns).withIndex()) {
            // Keep the mapped height as a minimum. Wrapping labels must be measured
            // before the horizontal row gives all tiles its tallest child's height.
            val line=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                minimumHeight=page.dp(tileHeight)
            }
            for((columnIndex,entry) in group.withIndex()) {
                val (title,target,icon)=entry
                val tile=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
                    minimumHeight=page.dp(tileHeight)
                    background=PortalUi.tileSurface(context);isClickable=true;PortalUi.focus(this)
                    setPadding(page.dp(10),page.dp(20),page.dp(10),page.dp(20))
                    setOnClickListener {
                        PortalMotion.shine(this)
                        PortalMenuAudio.play(context,PortalMenuAudio.Cue.CONFIRM);open(target)
                    }
                    addView(ImageView(context).apply {setImageDrawable(PortalUi.SettingsGlyph(icon))
                        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},
                        LinearLayout.LayoutParams(page.dp(iconSize),page.dp(iconSize)))
                    addView(PortalUi.text(context,title,labelSize).apply {
                        typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
                        gravity=Gravity.CENTER;setPadding(0,page.dp(12),0,0)
                    },LinearLayout.LayoutParams(-1,-2))
                }
                line.addView(tile,LinearLayout.LayoutParams(0,-1,1f).apply {
                    marginStart=page.dp(5)
                    marginEnd=if(columnIndex==group.lastIndex) page.dp(5) else gridGap-page.dp(5)
                })
            }
            page.add(line)
            if(rowIndex>0) line.layoutParams=(line.layoutParams as LinearLayout.LayoutParams).apply {topMargin=gridGap}
        }
        row("Remote Play Settings","Console pairing, streaming quality and connection mode",icon=PortalUi.Icon.REMOTE) {open("remote")}
        row("Game Translation","Automatic, video-only subtitles while playing",icon=PortalUi.Icon.GALLERY) {open("translation")}
        if(BuildConfig.FLAVOR in setOf("retroid","razerEdge"))
            row("Handheld Compatibility","Display, gamepad and secondary-screen capabilities",icon=PortalUi.Icon.CONTROLLER) {
                startActivity(Intent(this,PortalHandheldCompatibilityActivity::class.java))
            }
        if(portal.mode==PortalPreferences.Mode.JB)
            row("Media Gallery","Screenshots and video clips saved on your PS5",icon=PortalUi.Icon.GALLERY) {openGallery()}
    }
    private fun translationSettings() {
        row("Japanese Translation Lab · Four Independent Tests",
            "Japanese image OCR, 60-second video OCR, spoken audio recognition, and English read-aloud of actual translated text") {
            startActivity(Intent(this,PortalJapaneseTranslationTestActivity::class.java))
        }
        val spokenCodes=listOf("ja-JP","en-US","es-ES","fr-FR","de-DE","it-IT",
            "pt-BR","ko-KR","zh-CN","ru-RU","ar-SA","hi-IN")
        row("Spoken Game Language", "Language of PS5 voice dialogue; used only for live audio captions",
            value=Locale.forLanguageTag(portal.captionSpokenLanguage).displayLanguage) {
            PortalChoiceDialog.choice(this,"Spoken Game Language",
                spokenCodes.map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault())+" ($it)" },
                spokenCodes.indexOf(portal.captionSpokenLanguage).coerceAtLeast(0)) { index ->
                portal.captionSpokenLanguage=spokenCodes[index];render()
            }
        }
        toggle("Show Original Spoken Words",
            "Display recognized speech above translated speech during Remote Play",
            portal.captionShowOriginal) { portal.captionShowOriginal=it;render() }
        page.note("Live Spoken Captions starts from the Remote Play quick menu and asks Android for playback audio consent. Android 13+ and an installed offline speech pack are required. Spoken-audio and video OCR captions operate simultaneously; models and playback capture depend on the device.")
        toggle("Automatically Translate Game Text",
            "Only during a connected Traditional or JB Remote Play session; off outside gameplay",
            portal.autoTranslateGame) {enabled -> portal.autoTranslateGame=enabled;render()}
        val code=portal.translationTarget
        row("Translate To", "Your preferred language for game subtitles",
            value=Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())) {
            val languages=TranslateLanguage.getAllLanguages()
                .sortedBy {Locale.forLanguageTag(it).getDisplayName(Locale.getDefault())}
            PortalChoiceDialog.choice(this,"Translation Language",
                languages.map {Locale.forLanguageTag(it).getDisplayName(Locale.getDefault())+" ($it)"},
                languages.indexOf(code).coerceAtLeast(0)) {i ->
                portal.translationTarget=languages[i];render()
            }
        }
        val cloud=PortalCloudTranslator(this)
        toggle("Cloud Translation (Optional)",
            "Sends recognized game dialogue text to your chosen HTTPS provider; never sends images",
            cloud.enabled) {enabled ->
            if(enabled && (!PortalCloudTranslator.validEndpoint(cloud.endpoint) ||
                    cloud.model.isBlank() || !cloud.hasKey)) {
                PortalChoiceDialog.message(this,"Cloud Translation",
                    "Set an HTTPS OpenAI-compatible chat/completions endpoint, model and API key first. " +
                    "The recognized game dialogue will leave your device only when you enable this option.")
            } else {cloud.enabled=enabled;render()}
        }
        toggle("Cloud Dialogue Context", "When cloud is enabled, send up to three prior game lines " +
            "with the current line to improve translation; off by default",portal.cloudDialogueContext) {
            portal.cloudDialogueContext=it;render()
        }
        row("Cloud HTTPS Endpoint", "Use an OpenAI-compatible /v1/chat/completions URL",
            value=cloud.endpoint.takeIf{it.isNotBlank()}?.let{"Configured"} ?: "Not set") {
            edit("Cloud Translation Endpoint",cloud.endpoint,hint="https://provider.example/v1/chat/completions") {value ->
                if(!PortalCloudTranslator.validEndpoint(value)) "Enter an HTTPS /v1/chat/completions URL" else {
                    cloud.enabled=false;cloud.endpoint=value;null
                }
            }
        }
        row("Cloud Model", "Your provider's model identifier",value=cloud.model.ifBlank{"Not set"}) {
            edit("Translation Model",cloud.model,hint="Your compatible model ID") {value ->
                runCatching{cloud.enabled=false;cloud.model=value;null as String?}
                    .getOrElse{"Enter a model name up to 128 characters."}
            }
        }
        row("Cloud API Key", "Stored encrypted in Android Keystore",
            value=if(cloud.hasKey) "Saved securely" else "Not set") {
            edit("Cloud Translation API Key","",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                hint="Paste your provider API key") {value ->
                runCatching{cloud.enabled=false;cloud.setKey(value);null as String?}
                    .getOrElse{"Enter a nonblank API key (12–1024 characters)."}
            }
        }
        if(cloud.hasKey) row("Clear Cloud Translation Key","Disable cloud translation and erase the saved API key") {
            PortalChoiceDialog.message(this,"Clear API Key","Erase this device's cloud translation key?",
                listOf("Erase Key" to {cloud.clearKey();render()},"Cancel" to {}))
        }
        row("Capture Region", "Restrict recognition to a part of the game, not the Android screen",
            value=mapOf("FULL" to "Whole game", "SUBTITLES" to "Bottom subtitles",
                "CENTER" to "Center dialogue", "TOP" to "Top of game",
                "CUSTOM" to "Custom rectangle")[portal.translationRegion].orEmpty()) {
            val keys=listOf("FULL","SUBTITLES","CENTER","TOP","CUSTOM")
            PortalChoiceDialog.choice(this,"Game Capture Region",
                listOf("Whole game", "Bottom subtitles (40%)", "Center dialogue (60%)", "Top of game (40%)", "Custom rectangle"),
                keys.indexOf(portal.translationRegion).coerceAtLeast(0)) {index ->
                if(keys[index]=="CUSTOM") startActivity(Intent(this,PortalCropSettingsActivity::class.java))
                else {portal.translationRegion=keys[index];render()}
            }
        }
        row("Edit Custom Capture Rectangle","Drag and resize the capture region using touch or the D-pad") {
            startActivity(Intent(this,PortalCropSettingsActivity::class.java))
        }
        row("Translation Placement", "Keep text near its source or show one subtitle bar",
            value=if(portal.translationPlacement=="SOURCE") "Near original text" else "Subtitle bar") {
            val options=listOf("SOURCE","SUBTITLE")
            PortalChoiceDialog.choice(this,"Translation Placement",listOf("Near original game text","Bottom subtitle bar"),
                options.indexOf(portal.translationPlacement).coerceAtLeast(0)) {index ->
                portal.translationPlacement=options[index];render()
            }
        }
        toggle("Natural Text Eraser (Experimental)",
            "Test live pixel restoration of detected game lettering and draw translations " +
                "where the original text appeared. No black caption bars; detailed moving " +
                "artwork may show imperfect repairs. Automatically uses source placement.",
            portal.translationNaturalInpaint) {
                portal.translationNaturalInpaint=it;render()
            }
        row("Test Natural Subtitle Replace",
            "Compare glyph-only erasing and translated overlays on the bundled still image " +
                "and 60-second Japanese video, with spoken captions running alongside OCR") {
            startActivity(Intent(this,PortalJapaneseTranslationTestActivity::class.java)
                .putExtra(PortalJapaneseTranslationTestActivity.EXTRA_NATURAL_PREVIEW,true))
        }
        row("Caption Text Size","Adjust subtitle and translated text box size",value="${portal.translationTextSizeSp} sp") {
            val values=listOf(12,14,16,19,22,26,30,32)
            PortalChoiceDialog.choice(this,"Translation Text Size",values.map{"$it sp"},
                values.indexOf(portal.translationTextSizeSp).coerceAtLeast(3)) {index ->
                portal.translationTextSizeSp=values[index];render()
            }
        }
        row("Caption Background","Black-box background opacity",value="${portal.translationBackgroundOpacity}%") {
            val values=listOf(0,20,40,60,75,85,95,100)
            PortalChoiceDialog.choice(this,"Translation Background",values.map{"$it%"},
                values.indexOf(portal.translationBackgroundOpacity).coerceAtLeast(5)) {index ->
                portal.translationBackgroundOpacity=values[index];render()
            }
        }
        row("Recognition Frequency","Shorter intervals react faster but consume more CPU and battery",
            value="${portal.translationScanIntervalMs} ms") {
            val values=listOf(800,1200,1800,2500,3400,4500,6000)
            PortalChoiceDialog.choice(this,"OCR Scan Interval",values.map{"$it ms"},
                values.indexOf(portal.translationScanIntervalMs).coerceAtLeast(4)) {index ->
                portal.translationScanIntervalMs=values[index];render()
            }
        }
        row("OCR Language Script", "Auto checks five scripts; select one to reduce memory on TV hardware",
            value=portal.translationOcrScript.lowercase(Locale.ROOT).replaceFirstChar{it.uppercase()}) {
            val codes=listOf("AUTO","LATIN","JAPANESE","CHINESE","KOREAN","DEVANAGARI")
            PortalChoiceDialog.choice(this,"OCR Script",
                listOf("Automatic (five recognizers)","Latin languages","Japanese","Chinese",
                    "Korean","Devanagari (Hindi)"),codes.indexOf(portal.translationOcrScript).coerceAtLeast(0)) {index ->
                portal.translationOcrScript=codes[index];render()
            }
        }
        toggle("Speak Translations", "Optional Android text-to-speech for changing dialogue",
            portal.translationSpeech) {portal.translationSpeech=it;render()}
        val englishVoiceCodes=listOf("en-US","en-GB","en-AU","en-CA","en-IN")
        row("English Spoken Voice",
            "Voice accent for English translated dialogue and the Japanese translation lab",
            value=Locale.forLanguageTag(portal.translationSpeechLanguage).getDisplayCountry(Locale.getDefault())) {
            PortalChoiceDialog.choice(this,"English Spoken Voice",
                englishVoiceCodes.map { code ->
                    "English · " + Locale.forLanguageTag(code).getDisplayCountry(Locale.getDefault()) + " ($code)"
                },englishVoiceCodes.indexOf(portal.translationSpeechLanguage).coerceAtLeast(0)) {index ->
                portal.translationSpeechLanguage=englishVoiceCodes[index];render()
            }
        }
        val speechRates=listOf(.75f,.9f,1f,1.1f,1.25f,1.5f)
        row("Spoken Translation Speed",
            "Playback rate for translated speech (1.0× is normal)",
            value="${portal.translationSpeechRate}×") {
            PortalChoiceDialog.choice(this,"Spoken Translation Speed",
                speechRates.map { "${it}×" },
                speechRates.indexOf(portal.translationSpeechRate).coerceAtLeast(2)) {index ->
                portal.translationSpeechRate=speechRates[index];render()
            }
        }
        row("Android Voice Settings",
            "Install an English TTS voice or choose the device speech engine") {
            val speechSettings=Intent("com.android.settings.TTS_SETTINGS")
            startActivity(if(speechSettings.resolveActivity(packageManager)!=null) speechSettings
                else Intent(Settings.ACTION_SETTINGS))
        }
        toggle("Translation Controller Shortcut", "Hold L3 and press R3 while playing to toggle translated captions",
            portal.translationHotkey) {portal.translationHotkey=it;render()}
        toggle("Save Translation History", "Optional local history of game dialogue and translations; off by default",
            portal.translationHistoryEnabled) {portal.translationHistoryEnabled=it;render()}
        row("Saved Translation History", "Review saved original and translated game dialogue") {
            open("translation-history")
        }
        row("Word Dictionaries and Readings", "Import a licensed dictionary and look up game words") {
            startActivity(Intent(this,PortalWordLearningActivity::class.java))
        }
        page.note("Game video only; never captures other apps or Relay's menu. Automatic recognition supports Latin, Japanese, Chinese, Korean and Devanagari scripts. Translations use Wi-Fi-downloaded on-device language models. Restart the Remote Play session after changing recognition settings.")
    }
    private fun translationHistory() {
        val history=PortalTranslationHistory(this)
        val entries=history.list()
        page.note("Saved only when the history toggle was enabled. Entries remain on this device and are limited to the most recent 50. Game frames are never stored here.")
        if(entries.isEmpty()) page.note("No saved translations")
        entries.forEach {entry ->
            row(entry.source,entry.translated,value=entry.language.uppercase(Locale.ROOT)) {
                PortalChoiceDialog.message(this,"Saved Game Translation",
                    "${entry.source}\n\n${entry.translated}",
                    listOf("Look Up Words" to {
                        startActivity(Intent(this,PortalWordLearningActivity::class.java)
                            .putExtra(PortalWordLearningActivity.EXTRA_TEXT,entry.source))
                    },"Export to AnkiDroid" to {exportAnkiEntry(entry)},"Close" to {}))
            }
        }
        row("Clear Translation History","Permanently erase all saved game text") {
            PortalChoiceDialog.message(this,"Clear Translation History",
                "Erase all ${entries.size} saved translations on this device?",
                listOf("Erase" to {history.clear();render()},"Cancel" to {}))
        }
    }
    private fun exportAnkiEntry(entry:PortalTranslationHistory.Entry) {
        val anki=PortalAnkiExport(this)
        if(!anki.installed()) {
            PortalChoiceDialog.message(this,"AnkiDroid Required",
                "Install AnkiDroid and create a deck with a Basic two-field note type before exporting.")
            return
        }
        if(!anki.permitted()) {
            ankiPermission.launch(PortalAnkiExport.PERMISSION)
            return
        }
        lifecycleScope.launch {
            val options=withContext(Dispatchers.IO) {
                runCatching {anki.decks() to anki.models()}.getOrNull()
            }
            if(options==null || options.first.isEmpty() || options.second.isEmpty()) {
                PortalChoiceDialog.message(this@PortalSettingsActivity,"AnkiDroid Export",
                    "Could not find an accessible deck and Basic two-field note type in AnkiDroid.")
                return@launch
            }
            val decks=options.first
            PortalChoiceDialog.choice(this@PortalSettingsActivity,"Choose Anki Deck",
                decks.map{it.name},0) {index ->
                val deck=decks[index]
                val models=options.second
                PortalChoiceDialog.choice(this@PortalSettingsActivity,"Choose Anki Note Type",
                    models.map{"${it.name} (${it.fieldNames.joinToString(" / ")})"},0) {chosen ->
                    val model=models[chosen]
                    lifecycleScope.launch {
                        val added=withContext(Dispatchers.IO) {
                            runCatching {anki.add(deck.id,model,entry.source,entry.translated)}.getOrDefault(false)
                        }
                        Toast.makeText(this@PortalSettingsActivity,
                            if(added) "Sent to AnkiDroid: ${deck.name}"
                            else "AnkiDroid did not confirm the card was placed in that deck. Check Anki before retrying.",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    private fun cloud() {
        page.note("Cloud Streaming is represented by the home navigation tab. Streaming from PlayStation Plus cloud servers is not implemented in this client.")
        row("Cloud Streaming Home","View the Cloud Streaming screen",icon=PortalUi.Icon.CLOUD) {
            startActivity(Intent(this,PortalMainActivity::class.java)
                .putExtra(PortalMainActivity.EXTRA_START_TAB,"cloud"))
            finish()
        }
    }
    private fun network() {
        row("Wi-Fi Network","Connect or switch network using Android's connection panel",
            icon=PortalUi.Icon.NETWORK) {
            startActivity(Intent(this,PortalConnectivityActivity::class.java)
                .putExtra(PortalConnectivityActivity.EXTRA_PAGE,"wifi"))
        }
        row("Show Network Status", "View the live Wi-Fi diagnostics overlay",
            icon=PortalUi.Icon.NETWORK) {PortalNetworkStatusDialog(this).show()}
        row("Remote Access","Connect from another network",icon=PortalUi.Icon.REMOTE) {
            startActivity(Intent(this,PortalRemoteAccessActivity::class.java))
        }
        row("Connect to Wi-Fi","Choose a Wi-Fi network inside Relay",icon=PortalUi.Icon.NETWORK) {
            startActivity(Intent(this,PortalConnectivityActivity::class.java)
                .putExtra(PortalConnectivityActivity.EXTRA_PAGE,"wifi"))
        }
        if(portal.mode==PortalPreferences.Mode.TRADITIONAL) {
            toggle("Discover Consoles","Find PS4 and PS5 consoles on your local network",prefs.discoveryEnabled) {
                prefs.discoveryEnabled=it
            }
            row("Add Console Manually","Use an IP address or hostname") {
                startActivity(Intent(this,EditManualConsoleActivity::class.java))
            }
        } else {
            row("PS5 Address",value=portal.jbHost) {edit("PS5 Address",portal.jbHost) {
                if(!PortalPreferences.validHost(it)) "Enter a hostname or IP address." else {portal.jbHost=it;null}
            }}
            row("Test JB Connection","Read the console's actual video and audio capabilities") {probe()}
        }
    }
    private fun system() {
        row("First-Time Setup", "Language, network, controller and console setup") {
            startActivity(Intent(this,PortalSetupActivity::class.java))
        }
        row("Use Relay as Home", "Optional Portal-style Android launcher · no app drawer") {
            if(android.os.Build.VERSION.SDK_INT>=29) {
                val roles=getSystemService(android.app.role.RoleManager::class.java)
                if(roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)==true) {
                    startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } else startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        row("Restore Android Home", "Change your default launcher in Android settings") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        row("System Software", "Check signed Relay releases and install verified updates",
            icon=PortalUi.Icon.SYSTEM) {
            startActivity(Intent(this,PortalUpdateActivity::class.java))
        }
        if(portal.mode==PortalPreferences.Mode.TRADITIONAL) {
            row("Registered Consoles",value=registered.size.toString()) {open("consoles")}
            row("Export Paired Consoles","Save the existing console keys and manual addresses") {
                exportAndShareAllSettings(this,lifecycleScope)
            }
            row("Import Paired Consoles","Import a Chiaki settings file") {importSettings.launch(arrayOf("application/json"))}
        }
        row("Session Logs") {open("logs")}
        toggle("Verbose Logging","Include additional connection diagnostics",prefs.logVerbose) {prefs.logVerbose=it}
        row("About Relay","Licenses and source attribution") {
            PortalChoiceDialog.message(this,"Relay",
                "Personal Remote Play client.\n\nTraditional streaming: chiaki-ng, AGPL-3.0 with the OpenSSL exception.\nJB streaming: PBS protocol integration; PBS payload, GPL-3.0.\nJPEG XR gallery previews: Microsoft jxrlib, BSD-2-Clause.\n\nPlayStation and PS5 are trademarks of Sony Interactive Entertainment. Relay is an independent application.\n\nSource and third-party notices are included in this project.")
        }
    }
    private fun display() {
        val slider=SeekBar(this).apply {
            max=100;progress=if(portal.brightness<0) 75 else (portal.brightness*100).toInt()
            contentDescription="Display Brightness"
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) {
                    if(fromUser) {
                        portal.brightness=value.coerceAtLeast(3)/100f
                        window.attributes=window.attributes.apply {screenBrightness=portal.brightness}
                    }
                }
                override fun onStartTrackingTouch(bar:SeekBar?) {}
                override fun onStopTrackingTouch(bar:SeekBar?) {}
            })
        }
        page.note("Display Brightness");page.add(slider)
        row("Use Device Brightness") {portal.brightness=-1f;PortalUi.fullscreen(this);render()}
        row("Video Display",value=portal.fitMode.lowercase().replaceFirstChar{it.uppercase()}) {
            choice("Video Display",listOf("Fit","Fill / crop","Stretch"),listOf("FIT","ZOOM","STRETCH").indexOf(portal.fitMode)) {
                portal.fitMode=listOf("FIT","ZOOM","STRETCH")[it];render()
            }
        }
        toggle("Reduce Motion","Keep interface transitions and the background still",portal.reduceMotion) {portal.reduceMotion=it}
    }
    private fun controller() {
        row("Bluetooth Controllers","Pair, connect and inspect gamepads",
            icon=PortalUi.Icon.CONTROLLER) {
            startActivity(Intent(this,PortalConnectivityActivity::class.java)
                .putExtra(PortalConnectivityActivity.EXTRA_PAGE,"bluetooth"))
        }
        row("Connected Controllers","Automatic mapping, button assignments, stick axes and dead zones",icon=PortalUi.Icon.CONTROLLER) {
            ControllerProfileEditor.show(this)
        }
        toggle("On-Screen Controls","Show the virtual controller during gameplay",prefs.onScreenControlsEnabled) {
            prefs.onScreenControlsEnabled=it;if(it)prefs.touchpadOnlyEnabled=false;render()
        }
        if(portal.mode==PortalPreferences.Mode.TRADITIONAL) {
            toggle("Touchpad Only","Show the touchpad without the other virtual controls",prefs.touchpadOnlyEnabled) {
                prefs.touchpadOnlyEnabled=it;if(it)prefs.onScreenControlsEnabled=false;render()
            }
            toggle("Vibration","Use the controller's vibrator when Android exposes it",prefs.rumbleEnabled) {prefs.rumbleEnabled=it}
            toggle("Device Motion","Send this Android device's motion sensor input",prefs.motionEnabled) {prefs.motionEnabled=it}
        }
        toggle("Touch Feedback","Vibrate when an on-screen button is pressed",prefs.buttonHapticEnabled) {prefs.buttonHapticEnabled=it}
        toggle("Swap Cross and Circle","Exchange the confirm and cancel face buttons",prefs.swapCrossMoon) {prefs.swapCrossMoon=it}
        row("Customize Button Assignments","Full PS5-style diagram, button maps, sticks and triggers",
            icon=PortalUi.Icon.CONTROLLER) {
            startActivity(Intent(this,PortalGamepadRemapActivity::class.java))
        }
        if(portal.mode==PortalPreferences.Mode.JB)
            page.note("The JB input backend supports buttons, sticks and triggers. Touchpad coordinates, motion and controller feedback are not exposed by this payload.")
    }
    private fun audio() {
        page.note("PS Link accessories are managed by PlayStation Portal hardware. Android audio options are available below.")
        toggle("Menu Music","Play the Portal theme while browsing Relay",PortalMenuAudio.isMusicEnabled(this)) {
            PortalMenuAudio.setMusicEnabled(this,it);render()
        }
        if(PortalMenuAudio.isMusicEnabled(this)) {
            page.note("Menu Music Volume")
            page.add(SeekBar(this).apply {
                max=100;progress=(PortalMenuAudio.musicVolume(this@PortalSettingsActivity)*100).toInt()
                contentDescription="Menu Music Volume"
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) {
                        if(fromUser) PortalMenuAudio.setMusicVolume(this@PortalSettingsActivity,value/100f)
                    }
                    override fun onStartTrackingTouch(bar:SeekBar?) {}
                    override fun onStopTrackingTouch(bar:SeekBar?) {}
                })
            })
        }
        if(PortalMenuAudio.availableCues(this).isNotEmpty()) {
            toggle("Navigation Sounds","Play recorded Portal navigation sounds",PortalMenuAudio.isSoundsEnabled(this)) {
                PortalMenuAudio.setSoundsEnabled(this,it)
            }
        }
        row("Audio Output","Manage Bluetooth, USB and wired audio",icon=PortalUi.Icon.AUDIO) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        row("Device Volume") {startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))}
        if(portal.mode==PortalPreferences.Mode.JB) {
            toggle("Stream Audio","48 kHz stereo, 32-bit floating-point PCM",portal.jbAudio) {portal.jbAudio=it}
            page.note("This payload provides one lossless audio format. Video quality does not change the audio format.")
        } else page.note("Audio format is negotiated by Remote Play and decoded by the streaming engine.")
    }
    private fun traditional() {
        row("Pair Console","On your PS5, open Settings > System > Remote Play > Link Device",icon=PortalUi.Icon.REMOTE) {
            startActivity(Intent(this,RegistActivity::class.java))
        }
        row("Registered Consoles",value=registered.size.toString()) {open("consoles")}
        row("Resolution",value=getString(prefs.resolution.title)) {
            choice("Resolution",Preferences.resolutionAll.map {getString(it.title)},Preferences.resolutionAll.indexOf(prefs.resolution)) {
                prefs.resolution=Preferences.resolutionAll[it];render()
            }
        }
        row("Frame Rate",value=getString(prefs.fps.title)) {
            choice("Frame Rate",Preferences.fpsAll.map{getString(it.title)},Preferences.fpsAll.indexOf(prefs.fps)) {
                prefs.fps=Preferences.fpsAll[it];render()
            }
        }
        row("Video Bitrate",value=prefs.bitrate?.let{it.toString()+" kbps"} ?: ("Automatic ("+prefs.bitrateAuto+" kbps)")) {
            edit("Video Bitrate (kbps)",prefs.bitrate?.toString() ?: "",InputType.TYPE_CLASS_NUMBER,
                "Leave empty for automatic. Range: 2000–50000 kbps.") { text ->
                val value=text.toIntOrNull()
                if(text.isNotBlank() && (value==null || value !in 2000..50000)) "Enter 2000–50000, or leave empty."
                else {prefs.bitrate=value;null}
            }
        }
        row("Video Codec",value=getString(prefs.codec.title)) {
            choice("Video Codec",Preferences.codecAll.map{getString(it.title)},Preferences.codecAll.indexOf(prefs.codec)) {
                prefs.codec=Preferences.codecAll[it];render()
            }
        }
    }
    private fun jb() {
        loadJbCapabilities()
        page.note("Players join in connection order (1–4). Each person selects a PS5 profile or Guest on the console. No accounts or controller slots are preset on this device.")
        row("PS5 Address",value=portal.jbHost) {edit("PS5 Address",portal.jbHost) {
            if(!PortalPreferences.validHost(it)) "Enter a hostname or IP address." else {portal.jbHost=it;jbCapabilityKey="";null}
        }}
        row("Video Quality","Auto chooses the size; fixed profiles can still use automatic bitrate",value=if(portal.jbAutoQuality) "Auto" else heightLabel(portal.jbHeight)) {
            val heights=listOf(0)+(jbCapabilities?.resolutions?.takeIf{it.isNotEmpty()} ?: listOf(720,1080,1440,2160))
            val options=PortalResolutionSheet.options(heights.filter {it>0},jbCapabilities?.rateControl!=null)
            val current=PortalResolutionSheet.Option(
                if(portal.jbAutoQuality) 0 else portal.jbHeight,"","",
                portal.jbMaxBandwidthMbps.takeIf {it!=0})
            PortalResolutionSheet.show(this,options,current,"Apply") {chosen->
                portal.jbAutoQuality=chosen.height==0
                if(!portal.jbAutoQuality) portal.jbHeight=chosen.height
                if(chosen.bitrateMbps!=null && jbCapabilities?.rateControl!=null)
                    portal.jbMaxBandwidthMbps=chosen.bitrateMbps
                render()
            }
        }
        val rateSupported=jbCapabilities?.rateControl!=null
        row("Maximum Bandwidth","Video and audio budget with a transport allowance",
            value=if(portal.jbMaxBandwidthMbps==0) "Auto" else "${portal.jbMaxBandwidthMbps} Mbps") {
            if(rateSupported) edit("Maximum Bandwidth (Mbps)",portal.jbMaxBandwidthMbps.toString(),InputType.TYPE_CLASS_NUMBER) {
                val number=it.toIntOrNull()
                if(number==null || (number!=0 && number !in 6..120)) "Enter 6–120 Mbps, or 0 for Auto."
                else {portal.jbMaxBandwidthMbps=number;null}
            } else unavailableJbTuning()
        }
        toggle("Stream Audio","48 kHz stereo · 32-bit PCM · 3.072 Mbps; Off stops audio download",portal.jbAudio) {portal.jbAudio=it;render()}
        toggle("Advanced","Resolution ceiling, encoder tuning and audio buffering",portal.jbAdvanced) {portal.jbAdvanced=it;render()}
        if(portal.jbAdvanced) jbAdvanced(rateSupported)
        page.note(if(rateSupported) "The PS5 uses variable bitrate. The bandwidth budget reserves audio and 10% transport headroom; brief encoder bursts can exceed it." else jbCapabilityMessage)
        toggle("Send Payload Automatically","Reuse a running PS5 service; load once only when the service is confirmed absent",portal.autoPayload) {portal.autoPayload=it}
        if(PortalRemoteAccess(applicationContext).captureQuarantine(portal).snapshot().blocked)
            row("Capture Recovery","Reconnect to an existing stream; automatic reload is paused",value="Review") {captureRecovery()}
        row("Streaming Port",value=portal.jbPort.toString()) {editPort("Streaming Port",portal.jbPort) {portal.jbPort=it}}
        row("Payload Loader Port",value=portal.loaderPort.toString()) {editPort("Payload Loader Port",portal.loaderPort) {portal.loaderPort=it}}
        row("Test JB Connection","Read the service status without loading a payload") {probe()}
        row("Media Gallery","Browse native PS5 screenshots and video clips",icon=PortalUi.Icon.GALLERY) {openGallery()}
        row("Gallery FTP Port",value=portal.ftpPort.toString()) {editPort("Gallery FTP Port",portal.ftpPort) {portal.ftpPort=it}}
        row("Gallery Location",value=portal.galleryRoot) {
            edit("PS5 Gallery Location",portal.galleryRoot) {
                runCatching{portal.galleryRoot=it}.fold({null},{"Enter an absolute directory without parent traversal."})
            }
        }
        page.note("Connect uses the JB service directly. The PS5 must already be jailbroken with its ELF loader running. Native video recording is controlled through the PS5 Create menu.")
    }
    private fun captureRecovery() {
        val guard=PortalRemoteAccess(applicationContext).captureQuarantine(portal)
        val observed=guard.snapshot()
        if(!observed.blocked) {render();return}
        val changingQuality=observed.requestedHeights.isNotEmpty()
        val explanation=if(changingQuality) "The previous PS5 stream stopped while changing quality, and its state could not be confirmed."
            else "The previous PS5 stream stopped before its state could be confirmed."
        PortalChoiceDialog.message(this,"Capture Recovery",
            explanation+"\n\nRelay can reconnect to a streaming service that is still running. Automatic loading is paused.\n\nAfter you restart the PS5 and re-enable its jailbreak, choose PS5 restarted. This only clears Relay's saved pause; it does not restart or load anything on the console.",
            listOf("PS5 restarted" to {
                runCatching {guard.acknowledgeConsoleReset(observed)}.fold({
                    Toast.makeText(this,"Capture recovery cleared. Connect when the PS5 is ready.",Toast.LENGTH_LONG).show()
                    render()
                },{error ->
                    PortalChoiceDialog.message(this,"Capture Recovery",
                        error.message ?: "The saved pause could not be cleared.")
                })
            },"Keep Paused" to {}))
    }
    private fun jbAdvanced(rateSupported:Boolean) {
        if(portal.jbAutoQuality) row("Auto Resolution Ceiling","Auto also respects the screen and hardware decoder",value=heightLabel(portal.jbAutoMaxHeight)) {
            val heights=listOf(720,1080,1440,2160)
            choice("Auto Resolution Ceiling",heights.map(::heightLabel),heights.indexOf(portal.jbAutoMaxHeight)) {
                portal.jbAutoMaxHeight=heights[it];render()
            }
        }
        row("Video Bitrate Override","0 optimizes playback; an explicit rate stays fixed within the bandwidth budget",
            value=if(portal.jbVideoBitrateMbps==0) "Auto" else "${portal.jbVideoBitrateMbps} Mbps") {
            if(rateSupported) edit("Video Bitrate (Mbps)",portal.jbVideoBitrateMbps.toString(),InputType.TYPE_CLASS_NUMBER) {
                val number=it.toIntOrNull()
                if(number==null || number !in 0..100) "Enter 1–100 Mbps, or 0 for Auto."
                else {portal.jbVideoBitrateMbps=number;null}
            } else unavailableJbTuning()
        }
        row("Minimum Encoder QP","18 preserves more detail; larger values allow stronger compression",value=portal.jbMinQp.toString()) {
            if(rateSupported) edit("Minimum Encoder QP",portal.jbMinQp.toString(),InputType.TYPE_CLASS_NUMBER) {
                val number=it.toIntOrNull()
                if(number==null || number !in 18..35) "Enter a value from 18 to 35." else {portal.jbMinQp=number;null}
            } else unavailableJbTuning()
        }
        row("Audio Buffer","More buffering can reduce sound interruptions; Android may require a larger minimum",
            value="${portal.jbAudioBufferMs} ms") {
            val values=listOf(20,50,100)
            choice("Audio Buffer",listOf("20 ms · Low delay","50 ms · Balanced","100 ms · Stable"),values.indexOf(portal.jbAudioBufferMs)) {
                portal.jbAudioBufferMs=values[it];render()
            }
        }
        page.note("This service supplies H.264 at 60 fps and one lossless audio format. Stream Information shows the decoded size and measured traffic.")
    }
    private fun unavailableJbTuning() {
        PortalChoiceDialog.message(this,"Encoder Tuning",jbCapabilityMessage,
            listOf("Check Again" to {jbCapabilityKey="";render()},
            "Use Service Defaults" to {
                portal.jbMaxBandwidthMbps=0;portal.jbVideoBitrateMbps=0;portal.jbMinQp=18;render()
            },"Close" to {}))
    }
    private fun loadJbCapabilities() {
        val access=PortalRemoteAccess(applicationContext)
        val key=if(access.enabled) access.endpoint else portal.jbHost+":"+portal.jbPort
        val now=android.os.SystemClock.elapsedRealtime()
        if(jbCapabilityLoading || (jbCapabilityKey==key && now-jbCapabilityCheckedAt<15000)) return
        jbCapabilityLoading=true;jbCapabilityKey=key;jbCapabilities=null
        lifecycleScope.launch {
            var client:PbsClient?=null
            try {
                client=PortalRemoteAccess(applicationContext).createClient(portal)
                jbCapabilities=client.config()
                jbCapabilityMessage=if(jbCapabilities?.rateControl!=null) "Encoder tuning is available."
                    else "The running PS5 service supports resolution changes but needs an update for bandwidth and encoder tuning."
            } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
              catch(error:Exception) {jbCapabilityMessage="Connect to a compatible PS5 service to enable bandwidth and encoder tuning. "+(error.message ?: "Service unavailable.")}
            finally {client?.close();jbCapabilityLoading=false;jbCapabilityCheckedAt=android.os.SystemClock.elapsedRealtime()}
            if(section=="remote" && portal.mode==PortalPreferences.Mode.JB && !isFinishing) render()
        }
    }
    private fun openGallery() {startActivity(Intent(this,GalleryActivity::class.java))}
    private fun consoles() {
        row("Pair Another Console",icon=PortalUi.Icon.REMOTE) {startActivity(Intent(this,RegistActivity::class.java))}
        if(registered.isEmpty()) page.note("No consoles are paired yet.")
        for(host in registered) row(host.serverNickname ?: "Console",host.serverMac.toString()) {
            PortalChoiceDialog.message(this,host.serverNickname ?: "Console",
                "Remove this console's saved pairing?",
                listOf("Remove Pairing" to {
                            lifecycleScope.launch {getDatabase(this@PortalSettingsActivity).registeredHostDao().delete(host)}
                },"Cancel" to {}))
        }
    }
    private fun logs() {
        val manager=LogManager(this)
        if(manager.files.isEmpty()) page.note("No session logs have been recorded.")
        for(log in manager.files) row(DateFormat.getDateTimeInstance().format(log.date),log.filename) {
            choice("Session Log",listOf("View","Share","Delete","Cancel"),-1) {index->
                when(index) {
                    0 -> lifecycleScope.launch {
                        val contents=withContext(Dispatchers.IO) {log.file.bufferedReader().use{it.readText().takeLast(30000)}}
                        PortalChoiceDialog.message(this@PortalSettingsActivity,"Session Log",contents)
                    }
                    1 -> {
                        val uri=FileProvider.getUriForFile(this,fileProviderAuthority,log.file)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type="text/plain";putExtra(Intent.EXTRA_STREAM,uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=ClipData.newRawUri("",uri)
                        },"Share Session Log"))
                    }
                    2 -> {log.file.delete();render()}
                }
            }
        }
    }
    private fun choice(title:String,labels:List<String>,selected:Int,action:(Int)->Unit) {
        PortalChoiceDialog.choice(this,title,labels,selected,action)
    }
    private fun edit(title:String,current:String,input:Int=InputType.TYPE_CLASS_TEXT,hint:String="",
                     save:(String)->String?) {
        PortalChoiceDialog.edit(this,title,current,input,hint,save,::render)
    }
    private fun editPort(title:String,value:Int,save:(Int)->Unit) =
        edit(title,value.toString(),InputType.TYPE_CLASS_NUMBER) {
            val number=it.toIntOrNull()
            if(number==null || number !in 1..65535) "Enter a port from 1 to 65535." else {save(number);null}
        }
    private fun heightLabel(height:Int)=when(height) {
        2160->"2160p / 4K";else->height.toString()+"p"
    }
    private fun probe() {
        val dialog=PortalChoiceDialog.message(this,"JB Connection",
            "Checking "+portal.jbHost+"…")
        lifecycleScope.launch {
            var client:PbsClient?=null
            val result=try {
                client=PortalRemoteAccess(applicationContext).createClient(portal)
                val config=client.config()
                "Service connected.\n\nVideo: ${config.width} × ${config.height}"+
                    "\nCodec: ${config.codec}\nAdvertised profiles: "+config.resolutions.joinToString {heightLabel(it)}+
                    "\nAudio: "+(config.audio?.let {"${it.rate} Hz, ${it.channels} channels, ${it.bits}-bit ${it.codec}"} ?: "Unavailable")
            } catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
              catch(error:Exception) {"Could not connect: "+(error.message ?: error.javaClass.simpleName)}
              finally {client?.close()}
            if(dialog.isShowing) {
                dialog.dismiss()
                PortalChoiceDialog.message(this@PortalSettingsActivity,"JB Connection",result)
            }
        }
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.keyCode==KeyEvent.KEYCODE_BUTTON_B) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) back()
            return true
        }
        if(event.keyCode==KeyEvent.KEYCODE_BUTTON_A && event.action==KeyEvent.ACTION_DOWN) {
            if(event.repeatCount==0) currentFocus?.performClick()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
