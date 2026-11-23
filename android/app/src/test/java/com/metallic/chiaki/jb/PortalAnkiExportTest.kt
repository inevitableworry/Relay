package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class PortalAnkiExportTest {
    @Test fun escapingOriginalGameTextBlocksMarkup() {
        val result=PortalAnkiExport.safeHtml("Save <script>alert('x')</script> & continue")
        assertFalse(result.contains("<script>"))
        assertTrue(result.contains("&lt;script&gt;"))
        assertTrue(result.contains("&amp; continue"))
    }
    @Test fun multilineSentenceRetainsLineBreaks() {
        assertEquals("First<br>Second",PortalAnkiExport.safeHtml("First\nSecond"))
    }
}
