// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalVideoCropTest {
    @Test fun namedPresetsAreVideoRelative() {
        assertEquals(PortalPixelCrop(0, 648, 1920, 432),
            PortalVideoCrop.preset("SUBTITLES").pixels(1920, 1080))
        assertEquals(PortalPixelCrop(0, 0, 1280, 720),
            PortalVideoCrop.preset("ALL").pixels(1280, 720))
    }

    @Test fun customCropScalesWithVideoResolution() {
        val crop = PortalVideoCrop(.25f, .5f, .75f, 1f)
        assertEquals(PortalPixelCrop(480, 540, 960, 540), crop.pixels(1920, 1080))
        assertEquals(PortalPixelCrop(320, 360, 640, 360), crop.pixels(1280, 720))
    }

    @Test fun serializesIndependentlyOfDisplaySizeAndLocale() {
        val crop = PortalVideoCrop(.1234f, .25f, .8765f, .90f)
        assertEquals("1234,2500,8765,9000", crop.encode())
        val decoded = PortalVideoCrop.decode(crop.encode())!!
        assertTrue(kotlin.math.abs(crop.left - decoded.left) < .0001f)
        assertEquals(crop.pixels(1000, 1000), decoded.pixels(1000, 1000))
    }

    @Test fun malformedSavedValuesNeverEscapeVideoBounds() {
        assertNull(PortalVideoCrop.decode(null))
        assertNull(PortalVideoCrop.decode("NaN,0,1,1"))
        assertNull(PortalVideoCrop.decode("-2,0,1000,1000"))
        assertNull(PortalVideoCrop.decode("200,0,100,1000"))
        assertNull(PortalVideoCrop.decode("0,0,0,10000"))
    }

    @Test fun draggingOutsideVideoClipsAndStillGivesNonemptyCrop() {
        val crop = PortalVideoCrop.fromDrag(1.5f, -.2f, 1.5f, -.2f)
        assertTrue(crop.left >= 0f && crop.top >= 0f)
        assertTrue(crop.right <= 1f && crop.bottom <= 1f)
        assertTrue(crop.right - crop.left >= PortalVideoCrop.MIN_SPAN - .00001f)
        assertTrue(crop.bottom - crop.top >= PortalVideoCrop.MIN_SPAN - .00001f)
        val px = crop.pixels(1, 1)
        assertEquals(1, px.width)
        assertEquals(1, px.height)
    }

    @Test fun convertsFramePixelCropBackIntoNormalizedCrop() {
        val pixels = PortalPixelCrop(32, 48, 128, 200)
        assertEquals(pixels, PortalVideoCrop.fromPixels(pixels, 256, 400).pixels(256, 400))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMissingVideoBitmap() {
        PortalVideoCrop.FULL.pixels(0, 1080)
    }
}
