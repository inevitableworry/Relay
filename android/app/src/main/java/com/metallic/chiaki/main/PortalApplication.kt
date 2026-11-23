// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Application

/** One menu audio owner for navigation between activities and background transitions. */
class PortalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PortalMenuAudio.install(this)
    }
}
