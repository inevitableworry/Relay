// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.InputDevice
import java.util.Locale

/**
 * Hardware discovery, not brand-dependent capability promises. Retroid's
 * firmware/model names differ between Pocket 3+, Duo, Flip and successive
 * releases, so always fall back to Android runtime display/gamepad detection.
 * No firmware, fan, governor or private display API is accessed.
 */
internal object PortalHandheldCompatibility {
    enum class Family { RETROID, RAZER_EDGE, OTHER }
    enum class Profile { RETROID_DUO, RETROID_POCKET_3_PLUS, RETROID_OTHER, RAZER_EDGE_5G,
        RAZER_EDGE_WIFI, OTHER }

    fun detect(manufacturer: String, brand: String, model: String, product: String): Profile {
        val haystack=listOf(manufacturer,brand,model,product).joinToString(" ")
            .lowercase(Locale.ROOT)
        val retroid=haystack.contains("retroid") || haystack.contains("pocket 3+") ||
            haystack.contains("pocket3+")
        if(retroid) {
            if(haystack.contains("duo")) return Profile.RETROID_DUO
            if(haystack.contains("pocket 3+") || haystack.contains("pocket3+") ||
                haystack.contains("rp3+")) return Profile.RETROID_POCKET_3_PLUS
            return Profile.RETROID_OTHER
        }
        if(haystack.contains("razer edge") || haystack.contains("razeredge") ||
            haystack.contains("rz45-0460")) {
            return if(haystack.contains("5g") || haystack.contains("rz45-0460"))
                Profile.RAZER_EDGE_5G else Profile.RAZER_EDGE_WIFI
        }
        return Profile.OTHER
    }

    fun family(profile: Profile): Family = when(profile) {
        Profile.RETROID_DUO,Profile.RETROID_POCKET_3_PLUS,Profile.RETROID_OTHER -> Family.RETROID
        Profile.RAZER_EDGE_5G,Profile.RAZER_EDGE_WIFI -> Family.RAZER_EDGE
        Profile.OTHER -> Family.OTHER
    }

    fun name(profile:Profile):String=when(profile) {
        Profile.RETROID_DUO->"Retroid Pocket Duo / Duo Lite family"
        Profile.RETROID_POCKET_3_PLUS->"Retroid Pocket 3+"
        Profile.RETROID_OTHER->"Retroid family (universal)"
        Profile.RAZER_EDGE_5G->"Razer Edge 5G"
        Profile.RAZER_EDGE_WIFI->"Razer Edge Wi-Fi"
        Profile.OTHER->"Other Android device"
    }

    data class DisplayInfo(val id:Int,val name:String,val width:Int,val height:Int,
                           val refreshHz:Float,val presentation:Boolean)
    data class Report(val profile:Profile,val sdk:Int,val availableRamMiB:Long,
                      val lowRam:Boolean,val abis:List<String>,val screens:List<DisplayInfo>,
                      val controllers:List<String>) {
        val secondaryScreens:List<DisplayInfo> get()=screens.filter{it.id!=Display.DEFAULT_DISPLAY}
    }

    fun inspect(context: Context): Report {
        val detected=detect(Build.MANUFACTURER.orEmpty(),Build.BRAND.orEmpty(),
            Build.MODEL.orEmpty(),Build.PRODUCT.orEmpty())
        val manager=context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentationIds=manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map{it.displayId}.toSet()
        val screens=manager.displays.map { display ->
            val mode=display.mode
            DisplayInfo(display.displayId,display.name,mode.physicalWidth,mode.physicalHeight,
                mode.refreshRate,display.displayId in presentationIds)
        }
        val memory=context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val snapshot=ActivityManager.MemoryInfo().apply{memory.getMemoryInfo(this)}
        val devices=InputDevice.getDeviceIds().asIterable().mapNotNull {id->
            InputDevice.getDevice(id)?.takeIf {device->
                device.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD ||
                    device.sources and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK
            }?.name
        }.distinct()
        return Report(detected,Build.VERSION.SDK_INT,snapshot.totalMem/(1024L*1024L),
            memory.isLowRamDevice,Build.SUPPORTED_ABIS.toList(),screens,devices)
    }
}
