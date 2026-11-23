// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Dialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/** Original Portal-style Max Resolution sheet; same icon treatment and
 * hierarchy as current Portal while preserving Relay's additional profiles.
 * Does not claim a Sony firmware asset was extracted or installed. */
object PortalResolutionSheet {
    data class Option(val height:Int,val title:String,val description:String,
                      val bitrateMbps:Int?=null)
    fun options(heights:List<Int>,rateSupported:Boolean):List<Option> {
        val output=mutableListOf(Option(0,"Auto","Select the best resolution for your display and connection."))
        for(height in heights.distinct().sortedDescending()) {
            if(height==1080 && rateSupported) {
                output+=Option(1080,"1080p High Quality",
                    "Prioritizes detail; uses more bandwidth.",30)
                output+=Option(1080,"1080p Standard","Prioritizes stable playback.",16)
            } else {
                output+=Option(height,when(height) {
                    2160->"4K · 2160p"
                    else->"${height}p"
                }, when(height) {
                    2160,1440->"Advanced Relay resolution · higher network usage."
                    720->"Uses less data for weaker connections."
                    else->"Available streaming resolution."
                })
            }
        }
        return output
    }
    fun show(activity:AppCompatActivity,choices:List<Option>,selected:Option?,
             applyLabel:String="Apply and Reconnect",onApply:(Option)->Unit) {
        if(choices.isEmpty()) return
        val dp={n:Int->PortalUi.dp(activity,n)}
        val frame=FrameLayout(activity).apply {background=PortalUi.background()}
        val header=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(26),dp(23),dp(26),dp(20))
        }
        header.addView(PortalUi.text(activity,"Max Resolution",25f),LinearLayout.LayoutParams(-1,-2))
        header.addView(PortalUi.text(activity,
            "Select the maximum resolution for Remote Play. The stream reconnects to apply changes.",
            14f,true).apply {setPadding(0,dp(9),0,0)})
        var active=selected?.let {opt->choices.indexOfFirst {
            it.height==opt.height && (it.bitrateMbps==opt.bitrateMbps || opt.bitrateMbps==null)
        }}?.takeIf {it>=0} ?: 0
        val list=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val rows=mutableListOf<LinearLayout>()
        fun redraw() {
            rows.forEachIndexed {index,row->
                row.isSelected=active==index
                row.contentDescription=choices[index].title+
                    if(index==active) ", selected" else ""
            }
        }
        for((index,opt) in choices.withIndex()) {
            val row=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                background=PortalUi.surface(context,8)
                isClickable=true
                PortalUi.focus(this)
                setPadding(dp(16),dp(12),dp(16),dp(12))
            }
            row.addView(ImageView(activity).apply {
                setImageDrawable(ResolutionIcon(opt.height))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(55),dp(44)).apply {marginEnd=dp(13)})
            val labels=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
            labels.addView(PortalUi.text(activity,opt.title,18f))
            labels.addView(PortalUi.text(activity,opt.description,13f,true))
            row.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(PortalUi.text(activity,if(index==active) "✓" else "",23f).apply {
                tag="selected_indicator"
            },LinearLayout.LayoutParams(dp(36),-2))
            row.setOnClickListener {
                active=index
                rows.forEachIndexed {i,v->
                    (v.findViewWithTag<View>("selected_indicator") as? TextView)?.text=
                        if(i==active) "✓" else ""
                }
                redraw()
            }
            rows+=row
            list.addView(row,LinearLayout.LayoutParams(-1,-2).apply {
                bottomMargin=dp(8)
            })
        }
        redraw()
        val scroller=ScrollView(activity).apply {
            isFillViewport=false
            isVerticalScrollBarEnabled=false
            addView(list)
        }
        val outer=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(28),dp(16),dp(28),dp(24))
            addView(header)
            addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        }
        val dialog=Dialog(activity).apply {requestWindowFeature(Window.FEATURE_NO_TITLE)}
        val buttons=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.END
        }
        buttons.addView(PortalUi.primary(activity,"Cancel") {dialog.dismiss()}.apply {
            background=PortalUi.surface(activity,40)
            setTextColor(Color.WHITE)
        },LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
        buttons.addView(PortalUi.primary(activity,applyLabel) {
            val chosen=choices[active]
            dialog.dismiss()
            onApply(chosen)
        },LinearLayout.LayoutParams(0,-2,1.3f))
        outer.addView(buttons,LinearLayout.LayoutParams(-1,-2))
        frame.addView(outer,FrameLayout.LayoutParams(-1,-1))
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(-1,-1)
            decorView.systemUiVisibility=(
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        dialog.show()
        dialog.window?.setLayout(-1,-1)
    }
    class ResolutionIcon(private val height:Int):Drawable() {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas:Canvas) {
            val save=canvas.save()
            canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/64f,bounds.height()/64f)
            p.color=Color.WHITE
            p.style=Paint.Style.FILL
            canvas.drawRoundRect(8f,13f,56f,46f,4f,4f,p)
            canvas.drawRect(28f,46f,36f,52f,p)
            canvas.drawRoundRect(21f,51f,43f,54f,2f,2f,p)
            p.color=0xff282532.toInt()
            p.typeface=Typeface.create("sans-serif-black",Typeface.BOLD)
            p.textSize=if(height>=1000) 13f else 17f
            p.textAlign=Paint.Align.CENTER
            canvas.drawText(if(height==0) "AUTO" else height.toString(),
                32f,36f,p)
            p.style=Paint.Style.FILL
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha:Int){p.alpha=alpha}
        override fun setColorFilter(filter:ColorFilter?){p.colorFilter=filter}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
