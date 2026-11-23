// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.ocr

/**
 * Removes decorative OCR fragments without deleting CJK quote marks from dialogue.
 * Adapted from the same normalization approach used by PlayTranslate's
 * RecognizedTextNormalizer (https://github.com/dominostars/playtranslate,
 * GPL-3.0). The cursor/bracket character sets are attributed to that project.
 */
internal object PortalOcrTextNormalizer {
    private val cursor = setOf('▼', '▽', '▲', '△', '▸', '▾', '◂', '◀', '▶', '►', '◄', '↓', '↑', '←', '→', '↵', '↩')
    private val brackets = setOf('<', '>', '＜', '＞', '〈', '〉', '《', '》', '«', '»')

    fun cleanLine(source: String): String {
        val trimmed = source.trim { it.isWhitespace() || it == '|' || it in cursor }
            .replace(Regex("[\\t\\r\\n ]+"), " ")
        if (trimmed.isBlank() || trimmed.all { it.isWhitespace() || it in cursor || it in brackets }) {
            return ""
        }
        return trimmed
    }
}
