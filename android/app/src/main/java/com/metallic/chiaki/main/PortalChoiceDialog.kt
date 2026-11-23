// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Native Portal-style full-screen sheets. No Material/Android alert list,
 * except actual OS-controlled Wi-Fi/Bluetooth/security permission prompts. */
object PortalChoiceDialog {
    private fun dp(activity:AppCompatActivity,n:Int)=PortalUi.dp(activity,n)

    private fun sheet(activity:AppCompatActivity,title:String,
                      fill:(Dialog,PortalPage)->Unit):Dialog {
        val dialog=Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val page=PortalPage(activity,title,{dialog.dismiss()})
        dialog.setContentView(page.root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        fill(dialog,page)
        dialog.show()
        dialog.window?.setLayout(-1,-1)
        return dialog
    }

    fun choice(activity:AppCompatActivity,title:String,labels:List<String>,
               selected:Int,action:(Int)->Unit) {
        sheet(activity,title) {dialog,page->
            page.note("Select an option")
            labels.forEachIndexed {index,name->
                page.add(PortalUi.row(activity,name,
                    if(index==selected) "Currently selected" else "",
                    if(index==selected) "✓" else "") {
                    PortalMenuAudio.play(activity,PortalMenuAudio.Cue.CONFIRM)
                    dialog.dismiss();action(index)
                })
            }
            page.add(PortalUi.primary(activity,"Cancel") {dialog.dismiss()})
        }
    }

    fun message(activity:AppCompatActivity,title:String,message:String,
                buttons:List<Pair<String,()->Unit>> = listOf("Close" to {})):Dialog =
        sheet(activity,title) {dialog,page->
            page.note(message)
            for((title,action) in buttons) {
                page.add(PortalUi.primary(activity,title) {
                    dialog.dismiss();action()
                })
            }
        }

    fun edit(activity:AppCompatActivity,title:String,current:String,
             type:Int=InputType.TYPE_CLASS_TEXT,hint:String="",
             save:(String)->String?,onSaved:()->Unit) {
        sheet(activity,title) {dialog,page->
            val field=EditText(activity).apply {
                inputType=type
                isSingleLine=true
                setText(current)
                selectAll()
                this.hint=hint
                setTextColor(Color.WHITE)
                setHintTextColor(0xffb7b1c0.toInt())
                background=PortalUi.surface(activity,9)
                setPadding(dp(activity,22),dp(activity,18),dp(activity,22),dp(activity,18))
            }
            page.add(field)
            page.add(PortalUi.primary(activity,"Save") {
                val error=save(field.text.toString().trim())
                if(error!=null) field.error=error
                else {dialog.dismiss();onSaved()}
            })
            page.add(PortalUi.row(activity,"Cancel") {dialog.dismiss()})
        }
    }
}
