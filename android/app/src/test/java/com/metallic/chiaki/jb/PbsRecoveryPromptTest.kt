package com.metallic.chiaki.jb

import org.junit.Assert.*
import org.junit.Test

class PbsRecoveryPromptTest {
    @Test fun quarantinedCaptureOffersExplicitRestartAcknowledgment() {
        assertTrue(PbsRecoveryPrompt.needsConsoleRestartAcknowledgment(
            PbsCaptureQuarantinedException().message.orEmpty()))
    }
    @Test fun connectionTimeoutDoesNotPermitRestartAcknowledgment() {
        assertFalse(PbsRecoveryPrompt.needsConsoleRestartAcknowledgment(
            "The PS5 streaming service at 192.0.2.10:9999 did not answer."))
    }
    @Test fun unrelatedErrorsNeverOfferQuarantineReset() {
        assertFalse(PbsRecoveryPrompt.needsConsoleRestartAcknowledgment(
            "PS5 refused controller ownership"))
        assertFalse(PbsRecoveryPrompt.needsConsoleRestartAcknowledgment(""))
    }
}
