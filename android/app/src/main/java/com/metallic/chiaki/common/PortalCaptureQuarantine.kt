// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.common

import android.content.Context
import com.metallic.chiaki.jb.PbsCaptureQuarantine

/** Separate from user settings and credentials so a retry or settings import cannot erase it. */
class PortalCaptureQuarantine(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PbsCaptureQuarantine.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val quarantine = PbsCaptureQuarantine(object: PbsCaptureQuarantine.Store {
        override fun read(key: String): String? = synchronized(unconfirmedWrites) {
            unconfirmedWrites[key] ?: try { preferences.getString(key, null) }
                catch(_: ClassCastException) { "invalid_record" }
        }
        override fun commit(changes: Map<String, String?>): Boolean = synchronized(unconfirmedWrites) {
            val before = changes.keys.associateWith(::read)
            val editor = preferences.edit()
            // SharedPreferences changes memory even when commit() reports disk failure.
            // Retain the conservative state in this process, and include it in the next disk write.
            unconfirmedWrites.forEach { (key, value) -> editor.putString(key, value) }
            changes.forEach { (key, value) -> if(value == null) editor.remove(key) else editor.putString(key, value) }
            val saved = try { editor.commit() } catch(_: Exception) { false }
            if(saved) unconfirmedWrites.clear()
            else changes.forEach { (key, value) ->
                unconfirmedWrites[key] = before[key] ?: value ?: "unconfirmed_storage"
            }
            saved
        }
    })

    fun endpoint(primary: String, related: List<String> = emptyList()): PbsCaptureQuarantine.Endpoint =
        quarantine.endpoint(primary, related)

    companion object { private val unconfirmedWrites = mutableMapOf<String, String>() }
}
