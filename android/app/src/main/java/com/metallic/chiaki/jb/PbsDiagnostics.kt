// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.util.Log

/** Fixed event/cause identifiers only: never URLs, tokens, HTTP bodies, or exception messages. */
internal object PbsDiagnostics {
    fun event(remote: Boolean, event: String, stage: PbsFailureStage, reason: String = "none", attempt: Int = 0, count: Int = 0) {
        fun safe(value: String) = value.lowercase(java.util.Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(48)
        Log.i("RelayJbConnection", "event=${safe(event)} route=${if(remote) "remote" else "lan"} stage=${stage.name.lowercase(java.util.Locale.ROOT)} reason=${safe(reason)} attempt=$attempt count=$count")
    }
}
