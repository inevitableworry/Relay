// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.view.KeyEvent

/** Opt-in hold-L3, tap-R3 shortcut. Does not open Android accessibility services. */
internal class PortalTranslationHotkey {
    private var l3=false
    private var r3=false
    private var fired=false
    fun onKey(key:Int,down:Boolean):Boolean {
        when(key) {
            KeyEvent.KEYCODE_BUTTON_THUMBL -> l3=down
            KeyEvent.KEYCODE_BUTTON_THUMBR -> r3=down
            else -> return false
        }
        if(!l3 || !r3) {fired=false;return false}
        if(fired) return false
        fired=true
        return true
    }
    fun reset() {l3=false;r3=false;fired=false}
}
