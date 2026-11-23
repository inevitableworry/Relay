// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.learning

import java.util.Locale

/** Readings are present only when a local dictionary entry supplies them. */
object PortalReadingFormatter {
    fun forToken(token: PortalWordToken, language: String): String? {
        val raw = token.entry?.reading?.takeIf { it.isNotBlank() } ?: return null
        val hasHan = token.surface.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
        if (!hasHan) return null
        return when (PortalWordDictionary.normalizeLanguage(language)) {
            "ja" -> katakanaToHiragana(raw)
            "zh" -> numberedPinyinToMarks(raw)
            else -> null
        }
    }

    fun katakanaToHiragana(reading: String): String = buildString {
        reading.forEach { c ->
            append(if (c in '\u30A1'..'\u30F6') (c.code - 0x60).toChar() else c)
        }
    }

    /** Render CC-CEDICT-style numbered pinyin; leave already-marked syllables intact. */
    fun numberedPinyinToMarks(reading: String): String = PINYIN.replace(reading) { match ->
        val input = match.value
        val tone = input.last().digitToInt()
        val syllable = input.dropLast(1)
            .replace("u:", "ü", ignoreCase = true)
            .replace('v', 'ü').replace('V', 'Ü')
        if (tone == 5 || tone == 0) return@replace syllable
        val lower = syllable.lowercase(Locale.ROOT)
        val vowelPosition = when {
            'a' in lower -> lower.indexOf('a')
            'e' in lower -> lower.indexOf('e')
            "ou" in lower -> lower.indexOf('o')
            else -> lower.indexOfLast { it in "aeiouü" }
        }
        if (vowelPosition < 0) return@replace syllable
        val unmarked = lower[vowelPosition]
        val vowelIndex = "aeiouü".indexOf(unmarked)
        if (vowelIndex < 0) return@replace syllable
        val mapped = TONE_VOWELS[vowelIndex][tone - 1]
        val character = if (syllable[vowelPosition].isUpperCase()) mapped.uppercaseChar() else mapped
        syllable.substring(0, vowelPosition) + character + syllable.substring(vowelPosition + 1)
    }

    private val PINYIN = Regex("[A-Za-züÜvV:]+[1-5]")
    private val TONE_VOWELS = listOf("āáǎà", "ēéěè", "īíǐì", "ōóǒò", "ūúǔù", "ǖǘǚǜ")
}
