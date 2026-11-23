package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class PortalTranslationLayoutTest {
    @Test fun regionIsAlwaysInsideGameBitmap() {
        val bottom=PortalTranslationLayout.crop(1920,1080,"SUBTITLES")
        assertEquals(648,bottom.y)
        assertEquals(432,bottom.height)
        val center=PortalTranslationLayout.crop(1920,1080,"CENTER")
        assertTrue(center.y>0)
        assertTrue(center.y+center.height<=1080)
        assertEquals(1080,PortalTranslationLayout.crop(1920,1080,"FULL").height)
    }
    @Test fun textPlacementScalesAndClampsToGameSurface() {
        val crop=PortalTranslationLayout.crop(960,540,"SUBTITLES")
        val p=PortalTranslationLayout.placement(250,80,300,960,540,crop,1920,1080)
        assertEquals(500,p.left)
        assertEquals((80+crop.y)*2,p.top)
        assertEquals(600,p.width)
        val edge=PortalTranslationLayout.placement(950,500,900,960,540,
            PortalTranslationLayout.crop(960,540,"FULL"),1920,1080)
        assertTrue(edge.left+edge.width<=1920)
    }
    @Test fun dialogueChangeIgnoresWhitespaceOnly() {
        assertFalse(PortalTranslationLayout.changed("Hi   there", " Hi there "))
        assertTrue(PortalTranslationLayout.changed("Hello", "Goodbye"))
    }
}
