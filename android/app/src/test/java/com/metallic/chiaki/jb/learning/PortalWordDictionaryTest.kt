// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.learning

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PortalWordDictionaryTest {
    @Test fun parsesLocalTsvAndIgnoresIncompleteRows() {
        val imported = PortalWordDictionary.importTsv("ja", StringReader(
            "\uFEFFword\treading\tdefinition\n# locally licensed\n猫\tネコ\tcat\nbroken\t\n東京\tトウキョウ\tTokyo"
        ))
        assertEquals(2, imported.dictionary.entries.size)
        assertEquals(1, imported.ignoredRows)
        assertEquals("cat", imported.dictionary.lookup("猫")?.meaning)
        assertNull(imported.dictionary.lookup("犬"))
    }

    @Test fun longestLocalPhraseIsSelectedAndOffsetsReferToOcrString() {
        val dictionary = PortalWordDictionary.fromEntries("ja", listOf(
            PortalWordEntry("東京", "とうきょう", "Tokyo"),
            PortalWordEntry("東京駅", "とうきょうえき", "Tokyo Station"),
            PortalWordEntry("駅", "えき", "station")
        ))
        val text = "😀東京駅で会う"
        val tokens = dictionary.tokenize(text)
        assertEquals("東京駅", tokens.first().surface)
        assertEquals(2, tokens.first().start) // emoji occupies two UTF-16 units
        assertEquals(5, tokens.first().end)
        assertEquals("Tokyo Station", tokens.first().entry?.meaning)
        assertEquals("で", tokens[1].surface)
        assertEquals("会", tokens[2].surface) // unknown Han remains selectable
    }

    @Test fun latinEntryDoesNotMatchInsideLongerWord() {
        val dict = PortalWordDictionary.fromEntries("en", listOf(PortalWordEntry("cat", null, "cat")))
        assertNull(dict.tokenize("catalog").first().entry)
        assertEquals("cat", dict.tokenize("cat sat").first().entry?.word)
    }

    @Test fun readingsRequireRealDictionaryDataAndOnlySupportedScripts() {
        val jp = PortalWordDictionary.fromEntries("ja", listOf(PortalWordEntry("猫", "ネコ", "cat")))
        assertEquals("ねこ", PortalReadingFormatter.forToken(jp.tokenize("猫").first(), "ja"))
        assertNull(PortalReadingFormatter.forToken(jp.tokenize("犬").first(), "ja"))
        assertNull(PortalReadingFormatter.forToken(jp.tokenize("猫").first(), "en"))
    }

    @Test fun chineseToneMarksUseSuppliedNumberedPinyin() {
        assertEquals("Běi jīng", PortalReadingFormatter.numberedPinyinToMarks("Bei3 jing1"))
        assertEquals("nǚ", PortalReadingFormatter.numberedPinyinToMarks("nu:3"))
        assertEquals("Lǜ", PortalReadingFormatter.numberedPinyinToMarks("Lv4"))
        assertEquals("ma", PortalReadingFormatter.numberedPinyinToMarks("ma5"))
        assertEquals("běi", PortalReadingFormatter.numberedPinyinToMarks("běi"))
    }

    @Test fun importRejectsOversizedInputWithoutPartialDictionary() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalWordDictionary.importTsv("ja", StringReader("猫\tねこ\t" + "a".repeat(2200)))
        }
    }
}
