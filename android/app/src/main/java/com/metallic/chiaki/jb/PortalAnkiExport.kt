// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/** User-initiated AnkiDroid public-provider integration, patterned after upstream
 * PlayTranslate's GPL-3.0 AnkiManager. Never transfers anything automatically.
 * Supports Basic two-field note types; other deck schemas require explicit mapping.
 */
internal class PortalAnkiExport(private val context:Context) {
    data class Deck(val id:Long,val name:String)
    data class Model(val id:Long,val name:String,val fieldNames:List<String>)
    companion object {
        const val PERMISSION="com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private val decksUri by lazy {Uri.parse("content://com.ichi2.anki.flashcards/decks")}
        private val modelsUri by lazy {Uri.parse("content://com.ichi2.anki.flashcards/models")}
        private val notesUri by lazy {Uri.parse("content://com.ichi2.anki.flashcards/notes")}
        const val SEPARATOR="\u001f"
        fun safeHtml(text:String):String = text.take(1200).replace(SEPARATOR," ")
            .replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
            .replace("\"","&quot;").replace("\n","<br>")
    }
    fun installed():Boolean=try {context.packageManager.getPackageInfo("com.ichi2.anki",0);true}
        catch(_:PackageManager.NameNotFoundException){false}
    fun permitted():Boolean=ContextCompat.checkSelfPermission(context,PERMISSION)==PackageManager.PERMISSION_GRANTED
    fun decks():List<Deck> {
        val result=mutableListOf<Deck>()
        context.contentResolver.query(decksUri,null,null,null,null)?.use {cursor ->
            val id=cursor.getColumnIndex("deck_id").takeIf {it>=0} ?: cursor.getColumnIndex("_id")
            val name=cursor.getColumnIndex("deckName").takeIf {it>=0} ?: cursor.getColumnIndex("deck_name")
            if(id>=0 && name>=0) while(cursor.moveToNext()) {
                result.add(Deck(cursor.getLong(id),cursor.getString(name).orEmpty()))
            }
        }
        return result.filter {it.name.isNotBlank()}
    }
    fun models():List<Model> {
        val result=mutableListOf<Model>()
        context.contentResolver.query(modelsUri,null,null,null,null)?.use {cursor ->
            val id=cursor.getColumnIndex("model_id").takeIf{it>=0} ?: cursor.getColumnIndex("_id")
            val name=cursor.getColumnIndex("modelName").takeIf{it>=0} ?: cursor.getColumnIndex("model_name")
            val fields=cursor.getColumnIndex("fieldNames").takeIf{it>=0} ?: cursor.getColumnIndex("field_names")
            if(id>=0 && name>=0 && fields>=0) while(cursor.moveToNext()) {
                val raw=cursor.getString(fields).orEmpty()
                val fieldNames=raw.split(SEPARATOR).filter{it.isNotBlank()}
                result.add(Model(cursor.getLong(id),cursor.getString(name).orEmpty(),fieldNames))
            }
        }
        return result.filter {it.fieldNames.size==2 && it.name.contains("Basic",ignoreCase=true)}
    }
    fun add(deckId:Long,model:Model,original:String,translated:String):Boolean {
        if(!permitted() || !installed() || model.fieldNames.size!=2 ||
            deckId<=0 || original.isBlank() || translated.isBlank()) return false
        val fields=listOf(safeHtml(original.take(260)),safeHtml(translated.take(300)))
        val values=ContentValues().apply {
            put("mid",model.id)
            put("flds",fields.joinToString(SEPARATOR))
            put("tags","relay_game_translation")
            put("did",deckId)
        }
        val inserted=context.contentResolver.insert(notesUri,values) ?: return false
        // AnkiDroid ignores did on some API versions, fix deck for card 0.
        val card=Uri.withAppendedPath(inserted,"cards/0")
        val relocated=context.contentResolver.update(card,ContentValues().apply {put("deck_id",deckId)},null,null)
        return relocated>0
    }
}
