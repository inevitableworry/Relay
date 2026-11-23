package com.metallic.chiaki.main

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalHandheldCompatibilityTest {
    @Test fun retroidPocket3PlusByModel() {
        assertEquals(PortalHandheldCompatibility.Profile.RETROID_POCKET_3_PLUS,
            PortalHandheldCompatibility.detect("Retroid", "Retroid", "Retroid Pocket 3+", "rp3plus"))
    }

    @Test fun retroidPocketDuoByModel() {
        assertEquals(PortalHandheldCompatibility.Profile.RETROID_DUO,
            PortalHandheldCompatibility.detect("Retroid", "Retroid", "Retroid Pocket Duo", "duo"))
    }

    @Test fun laterRetroidUsesUniversalProfile() {
        assertEquals(PortalHandheldCompatibility.Profile.RETROID_OTHER,
            PortalHandheldCompatibility.detect("Retroid", "Retroid", "Pocket 5", "retroid_pocket_5"))
    }

    @Test fun razerEdgeCellularByModel() {
        assertEquals(PortalHandheldCompatibility.Profile.RAZER_EDGE_5G,
            PortalHandheldCompatibility.detect("Razer", "Razer", "Razer Edge 5G", "RZ45-0460"))
    }

    @Test fun unknownDeviceIsNotMisidentified() {
        assertEquals(PortalHandheldCompatibility.Profile.OTHER,
            PortalHandheldCompatibility.detect("Google", "Google", "Pixel 9 Pro Fold", "comet"))
    }
}
