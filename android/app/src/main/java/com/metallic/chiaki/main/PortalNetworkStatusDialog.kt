// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.metallic.chiaki.jb.PbsStatistics
import java.util.Locale

/** Show Network Status overlay, modeled after Portal 6.0 Troubleshoot.
 * Values that cannot actually be measured by Relay are marked unavailable;
 * the app never fabricates latency/loss percentages or upload throughput.
 */
class PortalNetworkStatusDialog(
    private val activity:AppCompatActivity,
    private val statistics:()->PbsStatistics? = {null}
) {
    private val handler=Handler(Looper.getMainLooper())
    private val values=mutableMapOf<String,TextView>()
    private var dialog:Dialog?=null
    private val refresh=object:Runnable {
        override fun run() {
            if(dialog?.isShowing!=true) return
            updateValues()
            handler.postDelayed(this,1500L)
        }
    }
    private fun dp(value:Int)=PortalUi.dp(activity,value)
    private fun label(value:String,muted:Boolean=false,size:Float=15f)=
        PortalUi.text(activity,value,size,muted).apply {includeFontPadding=false}

    fun show() {
        if(dialog?.isShowing==true || activity.isFinishing || activity.isDestroyed) return
        val root=FrameLayout(activity).apply {
            setBackgroundColor(0xa9212028.toInt())
            isClickable=true
        }
        val scroll=ScrollView(activity).apply {
            isFillViewport=false
            clipToPadding=false
            isVerticalScrollBarEnabled=false
        }
        val panel=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(28),dp(22),dp(22))
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xe936343c.toInt(),0xf02b2932.toInt())).apply {
                cornerRadius=dp(12).toFloat()
            }
            isClickable=true
        }
        panel.addView(label(if(statistics()==null) "Network Status" else "Remote Play",false,21f).apply {
            gravity=Gravity.CENTER
            setPadding(0,0,0,dp(20))
        })
        val keys=listOf(
            "Network name", "Wi-Fi frequency band", "Signal strength",
            "Resolution", "Downstream speed", "Upstream speed", "Latency",
            "Frame corruption", "Downstream loss", "Upstream loss")
        for(key in keys) {
            val line=LinearLayout(activity).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
                minimumHeight=dp(37)
            }
            line.addView(label(key,false,14f),LinearLayout.LayoutParams(0,-2,1.1f))
            val data=label("—",false,14f).apply {
                typeface=android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL)
                maxLines=2
            }
            values[key]=data
            line.addView(data,LinearLayout.LayoutParams(0,-2,1f))
            panel.addView(line)
        }
        panel.addView(label(
            "Only available measurements are shown. Latency is PS5 status RTT, not controller-to-screen delay.",
            true,12f).apply {setPadding(0,dp(18),0,0)})
        scroll.addView(panel,android.widget.FrameLayout.LayoutParams(-1,-2))
        val metrics=activity.resources.displayMetrics
        val width=(metrics.widthPixels*.55f).toInt().coerceAtLeast(dp(285))
            .coerceAtMost(metrics.widthPixels-dp(80))
        root.addView(scroll,FrameLayout.LayoutParams(width,-1,Gravity.START or Gravity.TOP).apply {
            leftMargin=dp(24);topMargin=dp(12);bottomMargin=dp(12)
        })
        val close=PortalUi.primary(activity,"×") {dismiss()}.apply {
            contentDescription="Close Network Status"
            textSize=29f
            background=PortalUi.surface(activity,45)
            setTextColor(Color.WHITE)
        }
        root.addView(close,FrameLayout.LayoutParams(dp(54),dp(48),Gravity.TOP or Gravity.END).apply {
            marginEnd=dp(24);topMargin=dp(18)
        })
        root.setOnClickListener {dismiss()}
        dialog=Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setLayout(-1,-1)
                setGravity(Gravity.CENTER)
                WindowCompat.setDecorFitsSystemWindows(this,false)
                WindowCompat.getInsetsController(this,decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            setOnDismissListener {handler.removeCallbacks(refresh);dialog=null}
            show()
            window?.apply {
                setLayout(-1,-1)
                WindowCompat.getInsetsController(this,decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        updateValues()
        handler.postDelayed(refresh,1500L)
    }

    private fun updateValues() {
        val cm=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiConnected=runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val info=runCatching {
            if(!wifiConnected) null else {
                val networkInfo=if(Build.VERSION.SDK_INT>=31)
                    cm.getNetworkCapabilities(cm.activeNetwork)?.transportInfo as? WifiInfo
                else null
                networkInfo ?: (activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            }
        }.getOrNull()
        val ssid=info?.ssid?.trim('"')?.takeIf {
            it.isNotBlank() && !it.equals("<unknown ssid>",true)
        } ?: if(wifiConnected) "Wi-Fi connected" else "Not connected"
        val frequency=info?.frequency ?: -1
        val band=when(frequency) {
            in 2400..2499 -> "2.4 GHz"
            in 4900..5899 -> "5 GHz"
            in 5925..7124 -> "6 GHz"
            else -> "—"
        }
        val strength=when {
            !wifiConnected -> "Disconnected"
            info==null || info.rssi !in -125..0 -> "—"
            info.rssi>=-57 -> "Strong"
            info.rssi>=-72 -> "Good"
            else -> "Weak"
        }
        val s=statistics()
        val snapshot=mapOf(
            "Network name" to ssid,
            "Wi-Fi frequency band" to band,
            "Signal strength" to strength,
            "Resolution" to if(s!=null && s.decodedWidth>0 && s.decodedHeight>0)
                "${s.decodedHeight}p" else "—",
            "Downstream speed" to if(s!=null)
                String.format(Locale.US,"%.1f Mbps",s.videoMbps+s.audioMbps) else "—",
            "Upstream speed" to "—",
            "Latency" to if(s!=null && s.statusRttMs>0L)
                "${s.statusRttMs} ms" else "—",
            "Frame corruption" to "—",
            "Downstream loss" to "—",
            "Upstream loss" to "—")
        for((key,value) in snapshot) values[key]?.text=value
    }

    private fun dismiss() {dialog?.dismiss()}
}
