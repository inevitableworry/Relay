// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

/** Update display states shared by real signed-release flows.
 * APK manifest, download and verification are handled by RelayUpdateClient.
 * The platform installer remains user-approved; no simulated download. */
internal class PortalUpdateFlow {
    enum class Stage(val title:String) {
        READY("System Software Update"),
        CHECKING("Checking for updates..."),
        DOWNLOADING("Downloading..."),
        VERIFYING("Verifying update data..."),
        INSTALLING("Updating..."),
        RESTARTING("Finishing setup..."),
        COMPLETE("System Software Update Complete"),
        CANCELLED("Update cancelled")
    }
    var stage:Stage=Stage.READY
        private set
    var progress:Int=0
        private set

    fun start() {
        check(stage in setOf(Stage.READY,Stage.COMPLETE,Stage.CANCELLED))
        stage=Stage.CHECKING
        progress=0
    }
    fun setProgress(value:Int) {
        check(stage in setOf(Stage.CHECKING,Stage.DOWNLOADING,Stage.VERIFYING,
            Stage.INSTALLING,Stage.RESTARTING))
        require(value in 0..100)
        require(value>=progress) { "Update progress must be monotonic within each stage" }
        progress=value
    }
    fun advance() {
        require(progress==100) { "Finish each stage before advancing" }
        stage=when(stage) {
            Stage.CHECKING->Stage.DOWNLOADING
            Stage.DOWNLOADING->Stage.VERIFYING
            Stage.VERIFYING->Stage.INSTALLING
            Stage.INSTALLING->Stage.RESTARTING
            Stage.RESTARTING->Stage.COMPLETE
            else->error("Cannot advance from $stage")
        }
        progress=if(stage==Stage.COMPLETE) 100 else 0
    }
    fun cancel() {
        if(stage!=Stage.COMPLETE) {
            stage=Stage.CANCELLED
            progress=0
        }
    }
}
