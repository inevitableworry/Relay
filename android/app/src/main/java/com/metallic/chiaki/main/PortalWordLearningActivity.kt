// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.text.InputType
import android.text.method.LinkMovementMethod
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.jb.learning.PortalDictionaryStore
import com.metallic.chiaki.jb.learning.PortalReadingFormatter
import com.metallic.chiaki.jb.learning.PortalWordDictionary
import com.metallic.chiaki.jb.learning.PortalWordLearningSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** An explicit, Portal-style local dictionary reader, separate from the game window.
 * It never reads pixels, opens the camera or requires Accessibility.
 */
class PortalWordLearningActivity:AppCompatActivity() {
    companion object {
        const val EXTRA_TEXT="game_ocr_text"
        const val EXTRA_LANGUAGE="game_ocr_language"
    }
    private lateinit var page:PortalPage
    private lateinit var store:PortalDictionaryStore
    private var text=""
    private var language="ja"
    private var dictionary=PortalWordDictionary.empty("ja")
    private val importDocument=registerForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri==null) return@registerForActivityResult
        val selectedLanguage=language
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use {stream->store.importTsv(selectedLanguage,stream)}
                        ?: error("Could not open selected dictionary")
                }
            }
            result.fold({imported->
                if(language==selectedLanguage) dictionary=imported.dictionary
                render()
                PortalChoiceDialog.message(this@PortalWordLearningActivity,"Dictionary Imported",
                    "Added ${imported.dictionary.entries.size} entries for $selectedLanguage. " +
                        "Ignored ${imported.ignoredRows} invalid rows.")
            },{error->
                PortalChoiceDialog.message(this@PortalWordLearningActivity,"Dictionary Import Failed",
                    error.message ?: "Invalid dictionary file.")
            })
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        PortalUi.fullscreen(this)
        text=(state?.getString("text") ?: intent.getStringExtra(EXTRA_TEXT)).orEmpty().take(1200)
        language=(state?.getString("language") ?: intent.getStringExtra(EXTRA_LANGUAGE))
            .orEmpty().lowercase(Locale.ROOT).substringBefore('-').takeIf{it.matches(Regex("[a-z]{2,3}"))} ?: "ja"
        store=PortalDictionaryStore(this)
        dictionary=PortalWordDictionary.empty(language)
        page=PortalPage(this,"Words and Readings",::finish)
        setContentView(page.root)
        onBackPressedDispatcher.addCallback(this) {finish()}
        loadDictionary()
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("text",text);state.putString("language",language)
        super.onSaveInstanceState(state)
    }
    private fun loadDictionary() {
        lifecycleScope.launch {
            val selected=language
            val result=withContext(Dispatchers.IO){runCatching{store.load(selected)}}
            if(language==selected && !isFinishing) {
                dictionary=result.getOrElse{PortalWordDictionary.empty(selected)}
                render()
            }
        }
    }
    private fun render() {
        if(!::page.isInitialized) return
        page.clear("Words and Readings")
        page.note("This reads only the game text passed from Relay or text you entered. " +
            "Definitions and readings come from imported dictionaries, never invented translations.")
        page.add(PortalUi.row(this,"Source Language","Used to select one local dictionary",
            value=language.uppercase(Locale.ROOT)) {
            val languages=listOf("ja","zh","ko","en","es","fr","de","pt","ru","hi","th")
            PortalChoiceDialog.choice(this,"Dictionary Language",languages.map {
                Locale.forLanguageTag(it).getDisplayName(Locale.getDefault())+" ($it)"
            },languages.indexOf(language).coerceAtLeast(0)) {index->
                language=languages[index]
                dictionary=PortalWordDictionary.empty(language)
                loadDictionary()
            }
        })
        page.add(PortalUi.row(this,"Enter or Edit Game Text",
            "Review a short game sentence without restarting playback") {
            PortalChoiceDialog.edit(this,"Game Text",text,InputType.TYPE_CLASS_TEXT,
                "Original-language game dialogue",{value-> text=value.take(1200);null},::render)
        })
        if(text.isBlank()) page.note("No detected game text yet. Enable game translation during playback, " +
            "or choose Enter or Edit Game Text.")
        else {
            val tokens=dictionary.tokenize(text)
            val sentence=TextView(this).apply {
                textSize=23f
                setTextColor(0xffffffff.toInt())
                this.text=PortalWordLearningSheet.annotate(this@PortalWordLearningActivity.text,
                    tokens,language,true) {word->
                    showWord(word.surface,word.entry?.meaning,PortalReadingFormatter.forToken(word,language))
                }
                movementMethod=LinkMovementMethod.getInstance()
                linksClickable=true
                setPadding(page.dp(18),page.dp(18),page.dp(18),page.dp(18))
            }
            page.add(sentence)
            tokens.distinctBy{it.surface}.take(45).forEach {token ->
                val reading=PortalReadingFormatter.forToken(token,language)
                page.add(PortalUi.row(this,token.surface,
                    token.entry?.meaning ?: "No imported definition",
                    value=reading.orEmpty()) {
                    showWord(token.surface,token.entry?.meaning,reading)
                })
            }
        }
        page.note("Local dictionary: ${dictionary.entries.size} entries. " +
            "Import a UTF-8 TSV file with word, reading and definition columns.")
        page.add(PortalUi.row(this,"Import Dictionary TSV","Select a licensed local UTF-8 dictionary file") {
            importDocument.launch(arrayOf("text/tab-separated-values","text/plain","*/*"))
        })
        page.add(PortalUi.row(this,"Delete Local Dictionary","Remove only the $language word list") {
            PortalChoiceDialog.message(this,"Delete Dictionary",
                "Delete the ${dictionary.entries.size} saved $language entries?",
                listOf("Delete" to {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO){store.delete(language)}
                        dictionary=PortalWordDictionary.empty(language);render()
                    }
                },"Cancel" to {}))
        })
    }
    private fun showWord(word:String,meaning:String?,reading:String?) {
        PortalChoiceDialog.message(this,"Word Lookup · $word",
            listOfNotNull(reading?.let{"Reading: $it"},
                "Definition: ${meaning ?: "No imported definition for this word"}").joinToString("\n\n"))
    }
}
