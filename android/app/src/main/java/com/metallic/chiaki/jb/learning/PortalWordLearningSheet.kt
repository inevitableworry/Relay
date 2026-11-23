// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.learning

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Explicit, focusable word lookup sheet for OCR that the existing game-video
 * pipeline has already obtained with user consent. This never captures a view,
 * screen, window, or game audio and does not add touch targets above gameplay.
 */
object PortalWordLearningSheet {
    /**
     * Call from a user-invoked menu/hotkey. Import is delegated to the host's
     * document picker; host then calls PortalDictionaryStore.importTsv off-main.
     */
    fun show(
        activity: Activity,
        gameVideoOcrText: String,
        language: String,
        dictionary: PortalWordDictionary,
        onImportRequested: (() -> Unit)? = null,
        showReadings: Boolean = true
    ): AlertDialog {
        val locale = PortalWordDictionary.normalizeLanguage(language)
        require(dictionary.language == locale) { "Dictionary language does not match OCR language" }
        val text = gameVideoOcrText.take(1200)
        val tokens = dictionary.tokenize(text)
        val dp = activity.resources.displayMetrics.density

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        val details = TextView(activity).apply {
            this.text = if (dictionary.entries.isEmpty()) {
                "No local $locale dictionary installed. Import a licensed TSV file to show readings and definitions."
            } else "Select a word to inspect its local definition."
            textSize = 16f
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            setTextIsSelectable(true)
        }

        fun select(token: PortalWordToken) {
            val reading = PortalReadingFormatter.forToken(token, locale)
            val definition = token.entry?.meaning ?: "No local definition available."
            details.text = buildString {
                append(token.surface)
                if (reading != null) append("  ·  ").append(reading)
                append('\n').append(definition)
            }
        }

        val sentence = TextView(activity).apply {
            textSize = 22f
            setLineSpacing(8 * dp, 1.15f)
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
            this.text = annotate(gameVideoOcrText.take(1200), tokens, locale, showReadings) { select(it) }
            movementMethod = LinkMovementMethod.getInstance()
            linksClickable = true
        }
        container.addView(sentence)
        container.addView(details)

        val wordsTitle = TextView(activity).apply {
            this.text = "Words · ${dictionary.entries.size} local entries"
            textSize = 15f
            setPadding(0, (14 * dp).toInt(), 0, (8 * dp).toInt())
        }
        container.addView(wordsTitle)
        // Buttons remain reachable with D-pad / TV remote focus, independent of tap spans.
        tokens.distinctBy { it.surface }.take(64).forEach { token ->
            val button = Button(activity).apply {
                val reading = if (showReadings) PortalReadingFormatter.forToken(token, locale) else null
                this.text = if (reading == null) token.surface else "${token.surface}  ·  $reading"
                isAllCaps = false
                contentDescription = "Look up ${token.surface}"
                setOnClickListener { select(token) }
            }
            container.addView(button)
        }

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(container)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Game reading and dictionary · ${locale.uppercase()}")
            .setView(scroll)
            .setNegativeButton("Close", null)
        if (onImportRequested != null) builder.setNeutralButton("Import TSV") { _, _ -> onImportRequested() }
        return builder.create().also { it.show() }
    }

    /** Original sentence plus optional ruby and interactive word spans, without OCR. */
    fun annotate(
        text: String,
        tokens: List<PortalWordToken>,
        language: String,
        showReadings: Boolean = true,
        onWordSelected: ((PortalWordToken) -> Unit)? = null
    ): SpannableString = SpannableString(text).apply {
        tokens.forEach { token ->
            if (token.start < 0 || token.end > text.length || token.start >= token.end ||
                text.substring(token.start, token.end) != token.surface) return@forEach
            if (showReadings) {
                PortalReadingFormatter.forToken(token, language)?.let { reading ->
                    setSpan(PortalRubySpan(reading), token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (onWordSelected != null) setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = onWordSelected(token)
                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = true
                    ds.color = Color.rgb(115, 174, 246)
                }
            }, token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
