// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Explicit opt-in; saves only recognized game text and translation, never video frames. */
internal class PortalTranslationHistory(context:Context) {
    data class Entry(val source:String,val translated:String,val language:String,val timestamp:Long)
    private val store=context.applicationContext.getSharedPreferences("relay_translation_history",Context.MODE_PRIVATE)
    private fun readRaw():JSONArray = runCatching {JSONArray(store.getString("entries","[]"))}
        .getOrElse {JSONArray()}
    fun list():List<Entry> {
        val raw=readRaw()
        return (0 until raw.length()).mapNotNull {idx ->
            val entry=raw.optJSONObject(idx) ?: return@mapNotNull null
            val source=entry.optString("source").take(260)
            val translated=entry.optString("translated").take(300)
            if(source.isBlank() || translated.isBlank()) return@mapNotNull null
            Entry(source,translated,entry.optString("language"),entry.optLong("time"))
        }
    }
    fun add(source:String,translated:String,language:String) {
        val original=PortalTranslationLayout.normalized(source)
        val result=translated.trim().take(300)
        if(original.isBlank() || result.isBlank()) return
        val previous=list().filterNot {it.source==original && it.translated==result}.take(49)
        val all=listOf(Entry(original,result,language,System.currentTimeMillis()))+previous
        val raw=JSONArray()
        for(entry in all) raw.put(JSONObject().put("source",entry.source)
            .put("translated",entry.translated).put("language",entry.language)
            .put("time",entry.timestamp))
        store.edit().putString("entries",raw.toString()).apply()
    }
    fun clear() {store.edit().remove("entries").apply()}
}
