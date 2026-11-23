// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.jb.ocr.PortalCropEditorView
import kotlin.math.min

/** Full-screen controller/touch editor for a crop in decoded game-video coordinates.
 * Does not read screen pixels or request media projection permissions.
 */
class PortalCropSettingsActivity:AppCompatActivity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        PortalUi.fullscreen(this)
        val prefs=PortalPreferences(this)
        val dp=resources.displayMetrics.density
        val padding=(24*dp).toInt()
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(padding,padding,padding,padding)
            setBackgroundColor(0xff090d25.toInt())
        }
        layout.addView(PortalUi.text(this,"Custom Game Text Capture",27f))
        layout.addView(PortalUi.text(this,
            "Drag a corner to resize, drag inside to move. Use the D-pad for fine adjustments. " +
                "Only decoded game video inside this 16:9 outline will be scanned.",15f,true))
        val preview=FrameLayout(this).apply {setBackgroundColor(0xff152048.toInt())}
        layout.addView(preview,LinearLayout.LayoutParams(-1,0,1f).apply {
            topMargin=(16*dp).toInt();bottomMargin=(12*dp).toInt()
        })
        val editor=PortalCropEditorView(this).apply {setCrop(prefs.translationCustomCrop)}
        preview.addView(editor,FrameLayout.LayoutParams(-1,-1))
        editor.addOnLayoutChangeListener {_,left,top,right,bottom,_,_,_,_ ->
            val w=(right-left).toFloat();val h=(bottom-top).toFloat()
            if(w<=0 || h<=0) return@addOnLayoutChangeListener
            val imageW=min(w,h*16f/9f)
            val imageH=imageW*9f/16f
            editor.setVideoViewport(RectF((w-imageW)/2f,(h-imageH)/2f,
                (w+imageW)/2f,(h+imageH)/2f))
        }
        val buttons=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        val cancel=PortalUi.primary(this,"Cancel") {finish()}
        val save=PortalUi.primary(this,"Use Selected Area") {
            prefs.translationCustomCrop=editor.crop
            prefs.translationRegion="CUSTOM"
            finish()
        }
        buttons.addView(cancel,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=(9*dp).toInt()})
        buttons.addView(save,LinearLayout.LayoutParams(0,-2,1f))
        layout.addView(buttons)
        setContentView(layout)
        editor.requestFocus()
    }
}
