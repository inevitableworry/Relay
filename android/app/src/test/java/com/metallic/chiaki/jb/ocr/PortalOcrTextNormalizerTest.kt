// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalOcrTextNormalizerTest {
    @Test fun trimsDialogueCursorsAndBorderPipes() {
        assertEquals("この先へ進む？", PortalOcrTextNormalizer.cleanLine(" | ▼ この先へ進む？ ▸ | "))
    }

    @Test fun keepsChineseQuotationAndPunctuationInActualText() {
        assertEquals("《三国演义》", PortalOcrTextNormalizer.cleanLine("《三国演义》"))
        assertEquals("《A》", PortalOcrTextNormalizer.cleanLine("《A》"))
    }

    @Test fun dropsOrphanDecorationsWithoutDroppingDialogue() {
        assertEquals("", PortalOcrTextNormalizer.cleanLine("《》"))
        assertEquals("", PortalOcrTextNormalizer.cleanLine("↓↓▼"))
        assertEquals("Bonjour !", PortalOcrTextNormalizer.cleanLine("  Bonjour   !  "))
    }

    @Test fun selectionUsesLanguageSpecificScriptEvidence() {
        val japanese = "おはよう、勇者"
        val cjk = "你好世界"
        assertTrue(PortalOcrScriptScore.score(PortalOcrScript.JAPANESE, japanese) >
            PortalOcrScriptScore.score(PortalOcrScript.CHINESE, japanese))
        assertTrue(PortalOcrScriptScore.score(PortalOcrScript.CHINESE, cjk) >
            PortalOcrScriptScore.score(PortalOcrScript.LATIN, cjk))
        assertTrue(PortalOcrScriptScore.score(PortalOcrScript.KOREAN, "안녕하세요") > 0)
        assertTrue(PortalOcrScriptScore.score(PortalOcrScript.DEVANAGARI, "नमस्ते") > 0)
    }
}
