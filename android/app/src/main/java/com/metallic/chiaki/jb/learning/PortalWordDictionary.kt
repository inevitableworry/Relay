// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.learning

import java.io.BufferedReader
import java.io.Reader
import java.util.Locale

/** One local, user-supplied dictionary record. Reading/meaning are never synthesized. */
data class PortalWordEntry(
    val word: String,
    val reading: String?,
    val meaning: String
)

data class PortalWordToken(
    val surface: String,
    /** UTF-16 offsets into the supplied OCR sentence, compatible with Android spans. */
    val start: Int,
    val end: Int,
    val entry: PortalWordEntry?
)

data class PortalDictionaryImport(
    val dictionary: PortalWordDictionary,
    val ignoredRows: Int
)

/**
 * Bounded dictionary for explicit, offline word selection from game-video OCR.
 *
 * The import format is UTF-8 TSV: `word<TAB>reading<TAB>definition`, with a
 * header and `#` comments permitted. `reading` can be blank. No PlayTranslate
 * dictionary data is bundled: the user supplies entries they may lawfully use.
 * This is dictionary-guided matching, not a Japanese morphological analyzer.
 */
class PortalWordDictionary private constructor(
    val language: String,
    val entries: Map<String, PortalWordEntry>
) {
    fun lookup(word: String): PortalWordEntry? = entries[word]

    /** Longest dictionary phrase wins; unknown Han characters remain selectable individually. */
    fun tokenize(text: String, maxTokens: Int = 128): List<PortalWordToken> {
        require(maxTokens in 1..1024)
        val tokens = ArrayList<PortalWordToken>()
        var i = 0
        while (i < text.length && tokens.size < maxTokens) {
            val cp = text.codePointAt(i)
            if (!Character.isLetterOrDigit(cp)) {
                i += Character.charCount(cp)
                continue
            }

            var match: PortalWordEntry? = null
            // Bounded phrase lengths keep OCR frame analysis fast with large dictionaries.
            var cursor = i
            while (cursor < text.length && cursor - i < MAX_WORD_LENGTH) {
                cursor += Character.charCount(text.codePointAt(cursor))
                val candidate = entries[text.substring(i, cursor)] ?: continue
                // Latin words must not be matched inside larger words.
                val following = if (cursor < text.length) text.codePointAt(cursor) else -1
                if (isLatinWord(candidate.word) && following != -1 && Character.isLetterOrDigit(following)) continue
                match = candidate
            }

            val end = if (match != null) i + match.word.length else unmatchedEnd(text, i)
            tokens.add(PortalWordToken(text.substring(i, end), i, end, match))
            i = end
        }
        return tokens
    }

    companion object {
        const val MAX_ENTRIES = 60_000
        const val MAX_WORD_LENGTH = 64
        private const val MAX_TEXT_LENGTH = 2048

        fun normalizeLanguage(language: String): String {
            val code = language.trim().lowercase(Locale.ROOT).substringBefore('-')
            require(code.matches(Regex("[a-z]{2,3}"))) { "Invalid language code" }
            return code
        }

        fun empty(language: String): PortalWordDictionary = PortalWordDictionary(normalizeLanguage(language), emptyMap())

        fun fromEntries(language: String, entries: Collection<PortalWordEntry>): PortalWordDictionary {
            require(entries.size <= MAX_ENTRIES) { "Too many dictionary entries" }
            val index = LinkedHashMap<String, PortalWordEntry>(entries.size)
            entries.forEach { entry ->
                require(entry.word.isNotBlank() && entry.word.length <= MAX_WORD_LENGTH &&
                    entry.meaning.isNotBlank()) { "Invalid dictionary entry" }
                index[entry.word] = entry
            }
            return PortalWordDictionary(normalizeLanguage(language), index)
        }

        /** Reject oversized records instead of silently importing partial/corrupt meanings. */
        fun importTsv(language: String, reader: Reader): PortalDictionaryImport {
            val index = LinkedHashMap<String, PortalWordEntry>()
            var ignored = 0
            BufferedReader(reader).use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    require(line.length <= MAX_TEXT_LENGTH) { "Dictionary row exceeds $MAX_TEXT_LENGTH characters" }
                    val clean = line.removePrefix("\uFEFF")
                    if (clean.isBlank() || clean.startsWith('#') || clean.startsWith("word\treading\t", ignoreCase = true)) continue
                    val cols = clean.split('\t', limit = 3)
                    if (cols.size != 3 || cols[0].isBlank() || cols[0].length > MAX_WORD_LENGTH || cols[2].isBlank()) {
                        ignored++
                        continue
                    }
                    val word = cols[0].trim()
                    val reading = cols[1].trim().ifEmpty { null }
                    val meaning = cols[2].trim()
                    if (word.isEmpty() || meaning.isEmpty()) { ignored++; continue }
                    index[word] = PortalWordEntry(word, reading, meaning)
                    require(index.size <= MAX_ENTRIES) { "Dictionary exceeds $MAX_ENTRIES entries" }
                }
            }
            return PortalDictionaryImport(PortalWordDictionary(normalizeLanguage(language), index), ignored)
        }

        private fun isLatinWord(word: String): Boolean = word.any { it in 'A'..'Z' || it in 'a'..'z' }

        private fun unmatchedEnd(text: String, start: Int): Int {
            val first = text.codePointAt(start)
            if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN) {
                return start + Character.charCount(first)
            }
            // Group contiguous non-Han letters into a word, preserving original offsets.
            var end = start + Character.charCount(first)
            while (end < text.length) {
                val cp = text.codePointAt(end)
                if (!Character.isLetterOrDigit(cp) || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) break
                end += Character.charCount(cp)
            }
            return end
        }
    }
}
