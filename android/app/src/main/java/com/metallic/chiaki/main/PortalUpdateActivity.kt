// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Real signed-release update client. No simulated downloads or silent installs.
 * Android's package installer obtains explicit confirmation from the user. */
class PortalUpdateActivity:AppCompatActivity() {
    private lateinit var page:PortalPage
    private lateinit var headline:TextView
    private lateinit var description:TextView
    private lateinit var percentage:TextView
    private lateinit var bar:ProgressBar
    private lateinit var startButton:TextView
    private lateinit var checkButton:TextView
    private lateinit var details:TextView
    private lateinit var orb:PortalUpdateOrbView
    private var stage=PortalUpdateFlow.Stage.READY
    private var percent=0
    private var status="Check the signed stable channel for your device."
    private var available:RelayUpdateClient.Release?=null
    private var running:Job?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        PortalUi.fullscreen(this)
        page=PortalPage(this,"System Software",::finish)
        setContentView(page.root)
        val pad=PortalUi.dp(this,16)
        page.note("Update System Software")
        page.note("Version ${BuildConfig.VERSION_NAME}")
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
            background=PortalUi.surface(context,15)
            setPadding(pad*2,pad*2,pad*2,pad*2)
        }
        orb=PortalUpdateOrbView(this).apply {
            contentDescription="Software update animation indicator"
        }
        panel.addView(orb,LinearLayout.LayoutParams(-1,PortalUi.dp(this,110)))
        headline=PortalUi.text(this,"System Software Update",23f).apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            gravity=Gravity.CENTER
        }
        panel.addView(headline,LinearLayout.LayoutParams(-1,-2))
        description=PortalUi.text(this,
            "Signed updates are verified and installed only with Android's approval.",
            15f,true).apply {
            gravity=Gravity.CENTER
            setPadding(0,pad,0,pad)
        }
        panel.addView(description,LinearLayout.LayoutParams(-1,-2))
        percentage=PortalUi.text(this,"",15f).apply {
            gravity=Gravity.END
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        }
        panel.addView(percentage,LinearLayout.LayoutParams(-1,-2).apply {
            bottomMargin=PortalUi.dp(this@PortalUpdateActivity,5)
        })
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100
            progress=0
            isIndeterminate=false
            val track=GradientDrawable().apply {
                setColor(0x608b8599);cornerRadius=PortalUi.dp(this@PortalUpdateActivity,3).toFloat()
            }
            val fill=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xff8e84d8.toInt(),Color.WHITE)).apply {
                cornerRadius=PortalUi.dp(this@PortalUpdateActivity,3).toFloat()
            }
            progressDrawable=LayerDrawable(arrayOf(track,ClipDrawable(fill,Gravity.START,ClipDrawable.HORIZONTAL))).apply {
                setId(0,android.R.id.background);setId(1,android.R.id.progress)
            }
            contentDescription="Update animation progress"
        }
        panel.addView(bar,LinearLayout.LayoutParams(-1,PortalUi.dp(this,7)).apply {
            bottomMargin=pad*2
        })
        startButton=PortalUi.primary(this,"Check for Updates") {onUpdatePressed()}
        panel.addView(startButton,LinearLayout.LayoutParams(-1,-2))
        checkButton=PortalUi.primary(this,"Device Information") {
            PortalChoiceDialog.message(this,"Device Information",
                "Relay Version: ${BuildConfig.VERSION_NAME}\n"+
                "Application ID: ${packageName}\n"+
                "Build Code: ${BuildConfig.VERSION_CODE}\n"+
                "Flavor: ${BuildConfig.FLAVOR}\n"+
                "Channel: stable\n"+
                "Signing: Ed25519 pinned public key\n"+
                "Origin: configured HTTPS update service\n\n"+
                "Every installation requires system confirmation.")
        }.apply {
            setTextColor(Color.WHITE)
            background=PortalUi.surface(this@PortalUpdateActivity,50)
        }
        panel.addView(checkButton,LinearLayout.LayoutParams(-1,-2).apply {topMargin=pad})
        page.add(panel)
        details=PortalUi.text(this,
            "Signed release feed Â· SHA-256 integrity Â· Android system installer",13f,true)
        page.add(details)
        updateScreen()
    }

    private fun onUpdatePressed() {
        if(running?.isActive==true) return
        val found=available
        if(found==null || found.versionCode<=BuildConfig.VERSION_CODE) {
            checkForUpdates()
            return
        }
        PortalChoiceDialog.message(this,"Verified Relay Update",
            "Version ${found.versionName} (${found.versionCode})\n"+
            "Size ${found.byteSize/1024/1024} MiB\n\n"+
            "Download the signed update and open Android's installer?",
            listOf("Download and Install" to {downloadUpdate(found)},
                   "Not Now" to {}))
    }

    private fun checkForUpdates() {
        stage=PortalUpdateFlow.Stage.CHECKING
        percent=0
        status="Checking the signed stable release feed..."
        PortalMenuAudio.play(this,PortalMenuAudio.Cue.UPDATE_START)
        updateScreen()
        running=lifecycleScope.launch {
            try {
                val found=withContext(Dispatchers.IO) {
                    RelayUpdateClient.check(this@PortalUpdateActivity,
                        BuildConfig.FLAVOR,packageName)
                }
                if(found==null) {
                    available=null
                    status="No signed stable release is currently published for this device."
                } else if(found.versionCode<=BuildConfig.VERSION_CODE) {
                    available=null
                    status="You have the latest signed stable release (${found.versionName})."
                } else {
                    available=found
                    status="Version ${found.versionName} is verified and ready to download."
                }
                stage=PortalUpdateFlow.Stage.READY
            } catch(error:Exception) {
                available=null
                stage=PortalUpdateFlow.Stage.READY
                status="Could not verify updates: "+
                    (error.localizedMessage?:error.javaClass.simpleName).take(180)
            } finally {
                running=null
                updateScreen()
            }
        }
    }

    private fun downloadUpdate(candidate:RelayUpdateClient.Release) {
        if(running?.isActive==true) return
        stage=PortalUpdateFlow.Stage.DOWNLOADING
        percent=0
        status="Downloading verified release ${candidate.versionName}..."
        updateScreen()
        running=lifecycleScope.launch {
            try {
                val apk=withContext(Dispatchers.IO) {
                    RelayUpdateClient.download(this@PortalUpdateActivity,candidate) {p ->
                        runOnUiThread {
                            percent=p
                            if(!isDestroyed) updateScreen()
                        }
                    }
                }
                stage=PortalUpdateFlow.Stage.VERIFYING
                percent=100
                status="SHA-256 verified. Checking the Android package..."
                updateScreen()
                stage=PortalUpdateFlow.Stage.INSTALLING
                status="Confirm the update in Android's installer."
                updateScreen()
                RelayUpdateClient.openInstaller(this@PortalUpdateActivity,candidate,apk)
                PortalMenuAudio.play(this@PortalUpdateActivity,PortalMenuAudio.Cue.UPDATE_COMPLETE)
            } catch(error:Exception) {
                stage=PortalUpdateFlow.Stage.READY
                percent=0
                status="Update was not installed: "+
                    (error.localizedMessage?:error.javaClass.simpleName).take(180)
            } finally {
                running=null
                updateScreen()
            }
        }
    }

    private fun updateScreen() {
        headline.text=if(stage==PortalUpdateFlow.Stage.READY) "System Software Update"
            else stage.title
        orb.setStage(stage,percent)
        percentage.text=if(stage==PortalUpdateFlow.Stage.DOWNLOADING) "${percent}%" else ""
        bar.progress=percent
        bar.isIndeterminate=stage==PortalUpdateFlow.Stage.CHECKING
        description.text=status
        val active=running?.isActive==true
        startButton.text=when {
            active->"Working..."
            available!=null && available!!.versionCode>BuildConfig.VERSION_CODE->
                "Download and Install Update"
            else->"Check for Updates"
        }
        startButton.isEnabled=!active
        startButton.alpha=if(active) .67f else 1f
        checkButton.isEnabled=!active
        checkButton.alpha=if(active) .6f else 1f
    }

    override fun onDestroy() {
        running?.cancel()
        super.onDestroy()
    }
}
