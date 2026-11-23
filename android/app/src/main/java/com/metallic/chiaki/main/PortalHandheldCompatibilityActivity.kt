// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.BuildConfig

/** Device/secondary-screen diagnostics without altering Relay's established UI. */
class PortalHandheldCompatibilityActivity : AppCompatActivity() {
    private lateinit var page:PortalPage
    private var second:Presentation?=null
    private var activeDisplayId:Int?=null
    private val displays by lazy {getSystemService(Context.DISPLAY_SERVICE) as DisplayManager}
    private val listener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId:Int){refresh()}
        override fun onDisplayChanged(displayId:Int){refresh()}
        override fun onDisplayRemoved(displayId:Int){
            if(activeDisplayId==displayId)closeSecond()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        PortalUi.fullscreen(this)
        page=PortalPage(this,"Handheld Compatibility",::finish)
        setContentView(page.root)
        onBackPressedDispatcher.addCallback(this){finish()}
        displays.registerDisplayListener(listener,null)
        refresh()
    }

    private fun refresh() {
        if(isFinishing || !::page.isInitialized)return
        page.clear("Handheld Compatibility")
        val report=PortalHandheldCompatibility.inspect(this)
        val flavor=BuildConfig.FLAVOR
        val profile=PortalHandheldCompatibility.name(report.profile)
        page.note("Relay edition: $flavor. Detected hardware: $profile.")
        page.note("Android API ${report.sdk}. Installed architecture options: " +
            report.abis.joinToString(", ") + ". RAM ${report.availableRamMiB} MiB"+
            if(report.lowRam) " (Android low-RAM device)" else "")
        page.note("The Retroid package supports arm64 and 32-bit ARM, including Pocket 3+. " +
            "The dedicated Razer Edge edition targets its 64-bit Snapdragon GPU. " +
            "Relay uses the installed OS video decoder instead of assuming a particular chipset.")
        page.note("Controller detection: " +
            if(report.controllers.isEmpty()) "No Android gamepad reported. Pair/attach a controller and reopen." 
            else report.controllers.joinToString(", "))
        report.screens.forEach {display->
            page.add(PortalUi.row(this,"Display ${display.id}: ${display.name}",
                "${display.width} × ${display.height} at ${display.refreshHz.toInt()} Hz" +
                    (if(display.presentation) " — presentation-capable" else ""),"",null) { })
        }
        val external=displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull {it.displayId!=Display.DEFAULT_DISPLAY}
        if(external==null) {
            page.note("No separate presentation-capable screen is exposed by Android. " +
                "On the Pocket Duo, the lower panel may use vendor display management; " +
                "Relay will not pretend two independent screens are available.")
        }else {
            page.add(PortalUi.row(this,"Secondary Screen","Show Relay status on the " +
                "detected additional display; the live game remains on the main screen",
                if(second!=null)"Active" else "Available",null) {
                if(second!=null)closeSecond() else showSecond(external)
                refresh()
            })
        }
        page.note("This screen is diagnostic. It does not change the PS Portal menu, " +
            "remap controller buttons, alter clock speeds, or take screenshots of other apps.")
    }

    private fun showSecond(display:Display) {
        closeSecond()
        val p=object:Presentation(this,display) {
            override fun onCreate(savedInstanceState:Bundle?) {
                super.onCreate(savedInstanceState)
                val root=FrameLayout(context).apply{background=PortalUi.background()}
                val column=LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL
                    gravity=Gravity.CENTER
                    setPadding(32,24,32,24)
                }
                column.addView(PortalUi.text(context,"RELAY",32f))
                column.addView(PortalUi.text(context,"Secondary Display",22f))
                column.addView(PortalUi.text(context,
                    "Remote Play stays on the primary screen.\n"+
                    "Open Handheld Compatibility to close this view.",16f,true))
                root.addView(column,FrameLayout.LayoutParams(-1,-1))
                setContentView(root)
            }
        }
        runCatching{p.show();second=p;activeDisplayId=display.displayId}
            .onFailure{p.dismiss();second=null;activeDisplayId=null}
    }

    private fun closeSecond() {second?.dismiss();second=null;activeDisplayId=null}
    override fun onDestroy(){displays.unregisterDisplayListener(listener);closeSecond();super.onDestroy()}
}
