// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class PortalGameTranslationTest {
    @Test fun subtitleCropNeverIncludesWindowCoordinates() {
        val region=PortalGameTranslation.subtitleRegion(1920,1080)
        assertEquals(0,region.x)
        assertEquals(648,region.y)
        assertEquals(1920,region.width)
        assertEquals(432,region.height)
    }
    @Test fun cropRejectsMissingVideo() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalGameTranslation.subtitleRegion(0,1080)
        }
    }
}
