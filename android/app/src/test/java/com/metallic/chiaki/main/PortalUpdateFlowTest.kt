// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import org.junit.Assert.*
import org.junit.Test

class PortalUpdateFlowTest {
    @Test fun previewCompletesEveryStageWithoutInstallingAnything() {
        val flow=PortalUpdateFlow()
        flow.start()
        val stages=listOf(PortalUpdateFlow.Stage.CHECKING,
            PortalUpdateFlow.Stage.DOWNLOADING,PortalUpdateFlow.Stage.VERIFYING,
            PortalUpdateFlow.Stage.INSTALLING,PortalUpdateFlow.Stage.RESTARTING)
        for(stage in stages) {
            assertEquals(stage,flow.stage)
            flow.setProgress(25)
            flow.setProgress(100)
            flow.advance()
        }
        assertEquals(PortalUpdateFlow.Stage.COMPLETE,flow.stage)
        assertEquals(100,flow.progress)
        flow.start()
        assertEquals(PortalUpdateFlow.Stage.CHECKING,flow.stage)
    }

    @Test fun previewDisallowsSkipAndRegressionAndCanCancel() {
        val flow=PortalUpdateFlow()
        assertThrows(IllegalArgumentException::class.java) {flow.advance()}
        flow.start()
        assertThrows(IllegalArgumentException::class.java) {flow.setProgress(101)}
        flow.setProgress(60)
        assertThrows(IllegalArgumentException::class.java) {flow.setProgress(20)}
        assertThrows(IllegalArgumentException::class.java) {flow.advance()}
        flow.cancel()
        assertEquals(PortalUpdateFlow.Stage.CANCELLED,flow.stage)
        flow.start()
        assertEquals(0,flow.progress)
    }
}
