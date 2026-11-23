// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.common.PortalRemoteAccess
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.main.*
import com.metallic.chiaki.session.StreamInput
import com.metallic.chiaki.stream.AspectRatioFrameLayout
import com.metallic.chiaki.stream.TransformMode
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class JbStreamActivity:AppCompatActivity(),SurfaceHolder.Callback,PbsStreamSession.Listener {
    private lateinit var root:FrameLayout
    private lateinit var ratio:AspectRatioFrameLayout
    private lateinit var video:SurfaceView
    private lateinit var backdrop:PortalBackdropView
    private lateinit var connection:PortalConnectionLayout
    private lateinit var connectionText:TextView
    private lateinit var connectionSubtitle:TextView
    private var gameTranslation:PortalGameTranslation?=null
    private var audioCaptions:com.metallic.chiaki.main.PortalAudioCaptionsController?=null
    private val translationHotkey=PortalTranslationHotkey()
    private lateinit var input:StreamInput
    private lateinit var menu:PortalQuickMenu
    private lateinit var record:ImageButton
    private lateinit var quick:ImageButton
    private val portal by lazy {PortalPreferences(this)}
    private val prefs by lazy {Preferences(this)}
    private val controls=MutableLiveData(false)
    private var session:PbsStreamSession?=null
    private var startup:Job?=null
    private var recoveryJob:Job?=null
    private val recovery=PbsRecoveryPolicy()
    private var recoveryAttempt=0
    private var controlsJob:Job?=null
    private var foreground=false
    private var live=false
    private var gameplayInputReady=false
    private var failed=false
    private var statistics:PbsStatistics?=null
    private var config:PbsConfig?=null
    private var quality:JbQualityPolicy?=null
    private var requestedProfile:PbsProfileRequest?=null
    private var qualityReason=""
    private var qualityEnvironmentDirty=false
    private var assignedPlayer=0
    private var playerState:PbsPartyStatus?=null
    private var consoleUsers:List<PbsConsoleUser> = emptyList()
    private val playerAvatars=mutableMapOf<Int,Bitmap>()
    private var playerObserver:Job?=null
    private var lastProfilesAt=0L
    private var errorDialog:android.app.Dialog?=null
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(portal.mode!=PortalPreferences.Mode.JB) {finish();return}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PortalUi.fullscreen(this)
        input=StreamInput(this,prefs).also {
            it.observe(this)
            it.controllerStateChangedCallback={pad ->session?.setControllerState(pad)}
        }
        root=FrameLayout(this).apply {setBackgroundColor(Color.BLACK)}
        ratio=AspectRatioFrameLayout(this).apply {
            aspectRatio=16f/9f;mode=TransformMode.valueOf(portal.fitMode)
        }
        video=SurfaceView(this)
        ratio.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        root.addView(ratio,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        val controlsContainer=FrameLayout(this).apply {id=CONTROLS_ID}
        root.addView(controlsContainer,FrameLayout.LayoutParams(-1,-1))
        connection=PortalConnectionLayout(this)
        backdrop=PortalBackdropView(this).apply {reduceMotion=portal.reduceMotion;setConnecting(true)}
        connection.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        val messages=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            setPadding(dp(24),dp(20),dp(24),dp(20))
        }
        connectionText=PortalUi.text(this,"Connecting to your PS5",24f).apply {gravity=Gravity.CENTER}
        messages.addView(connectionText,LinearLayout.LayoutParams(-1,-2))
        connectionSubtitle=PortalUi.text(this,"Remote Play JB  ·  "+connectionRouteLabel(),14f,true).apply {
            gravity=Gravity.CENTER;setPadding(0,dp(12),0,0)
        }
        messages.addView(connectionSubtitle)
        connection.addView(messages,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply {topMargin=dp(65)})
        connection.addView(PortalUi.primary(this,"Cancel") {finish()},
            FrameLayout.LayoutParams(dp(170),-2,Gravity.BOTTOM or Gravity.END).apply{
                bottomMargin=dp(28);marginEnd=dp(32)
            })
        root.addView(connection,FrameLayout.LayoutParams(-1,-1))
        gameTranslation=PortalGameTranslation(this,video,root) {
            foreground && live && !failed && ::menu.isInitialized && !menu.isShowing
        }
        audioCaptions=com.metallic.chiaki.main.PortalAudioCaptionsController(this,root)
        quick=overlayButton(PortalUi.Icon.SYSTEM,"Quick menu").apply {
            setOnClickListener {menu.show(!menu.isShowing)}
        }
        root.addView(quick,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.TOP or Gravity.END).apply{
            topMargin=dp(18);marginEnd=dp(18)
        })
        record=overlayButton(PortalUi.Icon.RECORD,"PS5 recording and screenshots").apply {
            visibility=View.GONE
            setOnClickListener {PortalMenuAudio.play(context,PortalMenuAudio.Cue.CONFIRM);openCapture()}
        }
        root.addView(record,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.TOP or Gravity.END).apply{
            topMargin=dp(18);marginEnd=dp(78)
        })
        TouchControlsFragment.positionOverlayActions(root,quick,record)
        menu=PortalQuickMenu(this,root) {_ ->updateInputSuspension()}
        buildMenu()
        setContentView(root)
        if(state==null) supportFragmentManager.beginTransaction()
            .replace(CONTROLS_ID,DefaultTouchControlsFragment(),"jb_touch").commitNow()
        video.holder.addCallback(this)
        onBackPressedDispatcher.addCallback(this) {if(menu.isShowing)menu.back() else menu.show(true)}
    }
    private fun dp(v:Int)=PortalUi.dp(this,v)
    private fun connectionRouteLabel():String {
        val access=PortalRemoteAccess(applicationContext)
        return if(access.enabled) runCatching{java.net.URI(access.endpoint).host}.getOrNull() ?: "Relay Tunnel" else portal.jbHost
    }
    private fun overlayButton(icon:PortalUi.Icon,description:String)=ImageButton(this).apply {
        setImageDrawable(PortalUi.Glyph(icon));contentDescription=description;alpha=.52f
        background=GradientDrawable().apply{setColor(0x77000000);cornerRadius=dp(32).toFloat()}
        setPadding(dp(10),dp(10),dp(10),dp(10));PortalUi.focus(this)
    }
    private fun buildMenu() {
        menu.reset()
        menu.row("PS5","Disconnect",PortalUi.Icon.REMOTE) {finish()}
        menu.players(::choosePlayer)
        refreshPlayers()
        if(assignedPlayer<=1) {
            menu.row("PS5 Control Center",icon=PortalUi.Icon.CONTROL_CENTER) {
                menu.onClosed {if(live && foreground) session?.pulseButton(PbsProtocol.Button.PS,120)}
            }
            menu.row("PS5 Home",icon=PortalUi.Icon.HOME) {
                menu.onClosed {if(live && foreground) session?.pulseButton(PbsProtocol.Button.PS,1000)}
            }
        }
        menu.maxResolution({if(portal.jbAutoQuality) "Auto" else portal.jbHeight.toString()+"p"}) {showMaxResolution()}
        menu.brightness()
        menu.row("Audio Devices",icon=PortalUi.Icon.AUDIO) {openSettings("audio")}
        menu.row("Record and Take Screenshots","Open the PS5 Create menu",PortalUi.Icon.RECORD) {openCapture()}
        menu.row("On-Screen Controls",if(prefs.onScreenControlsEnabled) "On" else "Off",PortalUi.Icon.CONTROLLER) {
            prefs.onScreenControlsEnabled=!prefs.onScreenControlsEnabled
            menu.show(false);controls.value=live && prefs.onScreenControlsEnabled;buildMenu()
        }
        menu.row("Stream Information",icon=PortalUi.Icon.NETWORK) {
            val s=statistics
            val text=if(s==null) "Waiting for decoded video."
            else "Decoded video: "+s.decodedWidth+" × "+s.decodedHeight+
                "\nDecoded frames: "+s.decodedFrames+
                "\nFrame rate: "+String.format(java.util.Locale.US,"%.1f fps",s.decodedFps)+
                "\nReceived video: "+String.format(java.util.Locale.US,"%.2f Mbps",s.videoMbps)+
                "\nReceived audio: "+String.format(java.util.Locale.US,"%.2f Mbps",s.audioMbps)+
                "\nCombined media: "+String.format(java.util.Locale.US,"%.2f Mbps",s.videoMbps+s.audioMbps)+
                "\nRelative stream backlog: "+s.streamLagMs+" ms"+
                "\nStatus round trip: "+(if(s.statusRttMs>=0) "${s.statusRttMs} ms" else "Unavailable")+
                "\nQuality: "+(if(portal.jbAutoQuality) "Auto · " else "")+(requestedProfile?.height ?: portal.jbHeight)+"p"+
                "\nEncoder target: "+(config?.rateControl?.bitrateBps?.let{String.format(java.util.Locale.US,"%.1f Mbps",it/1_000_000.0)} ?: "Service default")+
                "\nBandwidth budget: "+(if(portal.jbMaxBandwidthMbps==0) "Automatic" else "${portal.jbMaxBandwidthMbps} Mbps including audio allowance")+
                "\nAudio: "+(if(!portal.jbAudio) "Off" else config?.audio?.let{it.rate.toString()+" Hz, "+it.channels+" channels, "+it.bits+"-bit PCM"} ?: "Unavailable")+
                if(qualityReason.isBlank()) "" else "\n\n"+qualityReason
            PortalChoiceDialog.message(this,"Remote Play JB",text)
        }
        menu.row("Translate Game Text",
            if(portal.autoTranslateGame) "Auto on · ${portal.translationTarget}" else "Off · Video only",
            PortalUi.Icon.GALLERY) {toggleGameTranslation()}
        menu.row("Live Spoken Captions", "Recognize and translate PS5 speech locally · audio permission required",
            PortalUi.Icon.AUDIO) {
            menu.onClosed {
                if(!live || !foreground) {
                    Toast.makeText(this,"Connect Remote Play before starting audio captions",Toast.LENGTH_SHORT).show()
                } else audioCaptions?.toggle()
            }
        }
        menu.row("Translate Current Frame", "Scan the current decoded game video only",
            PortalUi.Icon.GALLERY) {
            menu.onClosed {
                if(!live || !foreground) return@onClosed
                if(gameTranslation?.isEnabled!=true) {
                    portal.autoTranslateGame=true
                    startAutoGameTranslation()
                }
                gameTranslation?.scanNow()
            }
        }
        menu.row("Words and Readings", "Local dictionary and Furigana/Pinyin",
            PortalUi.Icon.GALLERY) {
            menu.onClosed {
                startActivity(Intent(this,PortalWordLearningActivity::class.java)
                    .putExtra(PortalWordLearningActivity.EXTRA_TEXT,gameTranslation?.currentGameText.orEmpty())
                    .putExtra(PortalWordLearningActivity.EXTRA_LANGUAGE,
                        gameTranslation?.detectedSourceLanguage.orEmpty().ifBlank {"ja"}))
            }
        }
        menu.row("Troubleshoot", "Show Network Status", PortalUi.Icon.NETWORK) {
            menu.onClosed {PortalNetworkStatusDialog(this) {statistics}.show()}
        }
        menu.row("Settings",icon=PortalUi.Icon.SYSTEM) {openSettings("remote")}
        menu.row("Media Gallery",icon=PortalUi.Icon.GALLERY) {
            startActivity(Intent(this,GalleryActivity::class.java))
        }
        menu.row("Close",icon=PortalUi.Icon.CLOSE) {menu.show(false)}
    }

    private fun toggleGameTranslation() {
        if(portal.autoTranslateGame) {
            portal.autoTranslateGame=false
            gameTranslation?.stop()
            buildMenu()
            return
        }
        portal.autoTranslateGame=true
        startAutoGameTranslation()
        buildMenu()
    }

    private fun startAutoGameTranslation() {
        if(!portal.autoTranslateGame || !foreground || !live || failed) return
        if(gameTranslation?.isEnabled==true) return
        gameTranslation?.enableAuto(portal.translationTarget) {status ->
            if(!isFinishing && !isDestroyed) Toast.makeText(this,status,Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPlayers() {
        if(!::menu.isInitialized) return
        for(player in 1..4) {
            val slot=playerState?.slots?.firstOrNull {it.player==player}
            val isSelf=assignedPlayer==player
            val verifiedUser=slot?.avatarUserId?.let { id -> consoleUsers.firstOrNull { it.userId==id } }
            val title=if(isSelf) "P"+player+" · This device" else "Player "+player
            val status=when {
                slot?.accountName?.isNotBlank()==true -> slot.accountName
                isSelf -> "Choose profile on PS5"
                slot?.connected==true -> "Choosing PS5 profile"
                else -> "Waiting for player"
            }
            menu.updatePlayer(player,title,status,verifiedUser?.let{playerAvatars[it.userId]})
        }
    }

    private fun choosePlayer(player:Int) {
        if(assignedPlayer!=player) {
            val connected=playerState?.slots?.firstOrNull{it.player==player}?.connected==true
            PortalChoiceDialog.message(this,"Player "+player,if(connected)
                    "Controller "+player+" is connected from another device. PS5 profile selection happens on that console screen."
                    else "Controller "+player+" is waiting for the next Remote Play JB connection.")
            return
        }
        PortalChoiceDialog.message(this,"Player "+player+" · Controller "+player,
            "Account selection happens on the PS5. Choose an existing account or Play as Guest there. This app does not assign your account or player number in advance.",
            listOf("PS button" to {
                menu.onClosed {if(foreground && live) session?.pulseButton(PbsProtocol.Button.PS,180)}
            },"Close" to {}))
    }

    private fun observePlayers() {
        playerObserver?.cancel()
        playerObserver=lifecycleScope.launch {
            while(isActive && foreground && live) {
                val current=session?.client ?: break
                playerState=runCatching{current.partyStatus()}.getOrNull()
                val now=SystemClock.elapsedRealtime()
                if(now-lastProfilesAt>15000L && current.playerSlot>0) {
                    lastProfilesAt=now
                    consoleUsers=runCatching{current.consoleUsers()}.getOrDefault(emptyList())
                    for(user in consoleUsers) if(user.hasAvatar && !playerAvatars.containsKey(user.userId)) {
                        val avatar=runCatching{current.consoleAvatar(user.userId)}.getOrNull()
                        if(avatar!=null) playerAvatars[user.userId]=avatar
                    }
                }
                refreshPlayers()
                delay(2500L)
            }
        }
    }
    private fun showMaxResolution() {
        if(assignedPlayer>1) {
            Toast.makeText(this,"Player 1 controls the shared video quality",Toast.LENGTH_LONG).show()
            return
        }
        if(session?.client?.captureRecoveryBlocked == true) {
            Toast.makeText(this,"The current capture is available. Confirm the PS5 restart in Capture Recovery before changing quality.",Toast.LENGTH_LONG).show()
            return
        }
        val profiles=config?.resolutions?.takeIf{it.isNotEmpty()} ?: listOf(720,1080,1440,2160)
        val choices=PortalResolutionSheet.options(profiles,config?.rateControl!=null)
        val current=PortalResolutionSheet.Option(
            if(portal.jbAutoQuality) 0 else portal.jbHeight,"","",
            portal.jbMaxBandwidthMbps.takeIf {it>0})
        PortalResolutionSheet.show(this,choices,current) {selected->
                portal.jbAutoQuality=selected.height==0
                if(!portal.jbAutoQuality) portal.jbHeight=selected.height
                if(selected.bitrateMbps!=null && config?.rateControl!=null)
                    portal.jbMaxBandwidthMbps=selected.bitrateMbps
                try {
                    val activeConfig=config ?: throw IllegalStateException("Wait for the PS5 capabilities before changing quality")
                    quality=JbQualityEnvironment.create(this,activeConfig,portal)
                    reconnect(quality!!.initial(SystemClock.elapsedRealtime()),"Applying video quality")
                } catch(error:Exception) {onError(error.message ?: "The selected quality is unavailable")}
            }
    }
    private fun reconnect(request:PbsProfileRequest,reason:String,resetRecovery:Boolean=true) {
        if(!foreground || isFinishing) return
        recoveryJob?.cancel();recoveryJob=null
        if(resetRecovery) {recovery.reset();recoveryAttempt=0}
        if(com.metallic.chiaki.BuildConfig.DEBUG)
            android.util.Log.d("RelayJbQuality", "reconnect height=${request.height} bitrate=${request.videoBitrateBps} minqp=${request.minQp} reason=$reason")
        requestedProfile=request;qualityReason=reason
        setGameplayInputReady(false)
        input.releaseAllInputs()
        menu.show(false)
        live=false;gameTranslation?.stop();audioCaptions?.stop();failed=false;statistics=null;controls.value=false;record.visibility=View.GONE
        connection.showConnecting();connectionText.text=reason
        backdrop.setConnecting(true)
        val active=session
        if(active!=null && video.holder.surface.isValid) {
            // start joins the old controller cleanup before opening its replacement.
            active.start(video.holder.surface,request.height,request.videoBitrateBps,request.minQp,portal.jbAudioBufferMs,
                if(resetRecovery) Long.MAX_VALUE else recovery.deadlineMs())
        } else {
            startup?.cancel();startup=null;active?.close();session=null;startIfReady()
        }
    }
    private fun openSettings(page:String) {
        // The next resume must retry with the values the user changed in Settings.
        failed=false
        startActivity(Intent(this,PortalSettingsActivity::class.java)
            .putExtra(PortalSettingsActivity.EXTRA_PAGE,page))
    }
    private fun setGameplayInputReady(ready:Boolean) {
        gameplayInputReady=ready
        updateInputSuspension()
    }
    private fun updateInputSuspension() {
        if(!::input.isInitialized || !::menu.isInitialized) return
        val blocked=!gameplayInputReady || !foreground || !hasWindowFocus() || menu.isShowing
        input.setInputSuspended(blocked)
        session?.setInputEnabled(!blocked)
        if(blocked) supportFragmentManager.fragments.filterIsInstance<TouchControlsFragment>().forEach {it.releaseControls()}
        controls.value=live && prefs.onScreenControlsEnabled && !blocked
        record.visibility=if(live && gameplayInputReady && !menu.isShowing) View.VISIBLE else View.GONE
    }
    private fun openCapture() {
        if(!live) return
        // This opens Sony's own capture interface. It does not manufacture a recording state.
        menu.onClosed {if(live && foreground) session?.openCreateMenu()}
    }
    override fun onAttachFragment(fragment:Fragment) {
        super.onAttachFragment(fragment)
        if(fragment is TouchControlsFragment) {
            fragment.onScreenControlsEnabled=controls
            controlsJob?.cancel()
            controlsJob=fragment.controllerState.onEach { if(::input.isInitialized) input.touchControllerState=it }
                .launchIn(lifecycleScope)
        }
    }
    override fun onResume() {
        super.onResume()
        if(!::root.isInitialized) return
        foreground=true;PortalUi.fullscreen(this)
        connectionSubtitle.text="Remote Play JB  ·  "+connectionRouteLabel()
        backdrop.reduceMotion=portal.reduceMotion
        ratio.mode=TransformMode.valueOf(portal.fitMode)
        if(portal.mode!=PortalPreferences.Mode.JB) {finish();return}
        if(!failed) startIfReady()
    }
    override fun onPause() {
        foreground=false
        audioCaptions?.stop()
        translationHotkey.reset()
        gameTranslation?.stop()
        playerObserver?.cancel();playerObserver=null
        setGameplayInputReady(false)
        startup?.cancel();startup=null
        recoveryJob?.cancel();recoveryJob=null;recovery.reset();recoveryAttempt=0
        if(::input.isInitialized) input.releaseAllInputs()
        session?.close();session=null
        live=false;controls.value=false
        super.onPause()
    }
    override fun onDestroy() {
        audioCaptions?.dispose()
        gameTranslation?.dispose()
        startup?.cancel();recoveryJob?.cancel();controlsJob?.cancel();playerObserver?.cancel()
        session?.close();errorDialog?.dismiss()
        super.onDestroy()
    }
    override fun onConfigurationChanged(configuration:Configuration) {
        super.onConfigurationChanged(configuration)
        qualityEnvironmentDirty=true
    }
    override fun surfaceCreated(holder:SurfaceHolder) {startIfReady()}
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) {}
    override fun surfaceDestroyed(holder:SurfaceHolder) {
        recoveryJob?.cancel();recoveryJob=null
        startup?.cancel();startup=null;session?.close();session=null;live=false;controls.value=false
        setGameplayInputReady(false)
    }
    private fun startIfReady() {
        if(!foreground || !video.holder.surface.isValid || startup?.isActive==true || session!=null || failed) return
        connection.showConnecting();connectionText.text="Connecting to your PS5"
        backdrop.setConnecting(true)
        setGameplayInputReady(false)
        startup=lifecycleScope.launch {
            var unownedClient:PbsClient?=null
            try {
                val remoteAccess=PortalRemoteAccess(applicationContext)
                val client=remoteAccess.ensureStreamService(portal) {text ->connectionText.text=text}
                unownedClient=client
                val initial=client.config()
                quality=JbQualityEnvironment.create(this@JbStreamActivity,initial,portal)
                qualityEnvironmentDirty=false
                val request=quality!!.initial(SystemClock.elapsedRealtime())
                if(com.metallic.chiaki.BuildConfig.DEBUG)
                    android.util.Log.d("RelayJbQuality", "start auto=${portal.jbAutoQuality} height=${request.height} bitrate=${request.videoBitrateBps} minqp=${request.minQp}")
                requestedProfile=request
                qualityReason=if(portal.jbAutoQuality) "Auto is measuring playback at ${request.height}p" else ""
                ensureActive()
                if(!foreground || !video.holder.surface.isValid) {client.close();return@launch}
                session=PbsStreamSession(client,this@JbStreamActivity).also {
                    it.setAudioEnabled(portal.jbAudio)
                    it.setInputEnabled(gameplayInputReady && !menu.isShowing && hasWindowFocus())
                    it.start(video.holder.surface,request.height,request.videoBitrateBps,request.minQp,portal.jbAudioBufferMs)
                }
                unownedClient=null // The session now owns transport cleanup.
            } catch(_:CancellationException) {}
            catch(error:Exception) {onError(error.message ?: "Could not start the PS5 stream.")}
            finally {unownedClient?.close()}
        }
    }
    override fun onConnecting() {connectionText.text="Connecting to your PS5"}
    override fun onControllerAssigned(player:Int) {
        assignedPlayer=player
        connectionSubtitle.text="Player $player · Choose account or Guest on your PS5"
        buildMenu()
    }
    override fun onCapabilities(config:PbsConfig) {this.config=config}
    override fun onConnected(config:PbsConfig) {
        if(!foreground) return
        this.config=config
        if(assignedPlayer>1) {
            requestedProfile=PbsProfileRequest(config.height)
            qualityReason="Following Player 1's shared video settings"
        }
        if(config.recoveryVerificationOnly) {
            val desired=requestedProfile
            val actual=PbsProfileRequest(config.height,config.rateControl?.bitrateBps,config.rateControl?.activeMinQp)
            requestedProfile=actual
            if(session?.client?.captureRecoveryBlocked != true && desired!=null &&
                (desired.height!=actual.height || (desired.videoBitrateBps!=null && desired.videoBitrateBps!=actual.videoBitrateBps) ||
                    (desired.minQp!=null && desired.minQp!=actual.minQp))) {
                reconnect(desired,"Restoring your video quality",resetRecovery=false)
                return
            }
            qualityReason=if(session?.client?.captureRecoveryBlocked == true)
                "Playing at ${config.height}p. Capture Recovery confirmation is needed before changing quality."
                else "Connection recovered at ${config.height}p"
        }
        PbsDiagnostics.event(session?.client?.isRemote == true,
            if(recoveryAttempt > 0) "recovered" else "connected", PbsFailureStage.VIDEO, attempt=recoveryAttempt)
        recoveryAttempt=0
        requestedProfile?.let{quality?.started(it,SystemClock.elapsedRealtime())}
        live=true;failed=false
        startAutoGameTranslation()
        observePlayers()
        setGameplayInputReady(false)
        backdrop.setConnecting(false)
        // The session emits this callback only after a real MediaCodec output frame.
        connection.reveal {
            if(foreground && live && !failed) setGameplayInputReady(true)
        }
    }
    override fun onVideoSizeChanged(width:Int,height:Int) {
        if(width>0 && height>0) ratio.aspectRatio=width.toFloat()/height.toFloat()
    }
    override fun onStatistics(statistics:PbsStatistics) {
        this.statistics=statistics
        if(assignedPlayer>1) return
        if(!foreground || !live || !gameplayInputReady || menu.isShowing || !hasWindowFocus()) return
        if(session?.client?.captureRecoveryBlocked == true) return
        if(qualityEnvironmentDirty && portal.jbAutoQuality) {
            qualityEnvironmentDirty=false
            val active=config
            val selected=requestedProfile
            if(active!=null && selected!=null) {
                try {
                    quality=JbQualityEnvironment.create(this,active,portal)
                    val adjusted=quality!!.resume(selected,statistics.sampledAtMs)
                    if(adjusted!=selected) {reconnect(adjusted,"Auto adjusted to the current screen size");return}
                } catch(error:Exception) {onError(error.message ?: "Auto quality is unavailable");return}
            }
        }
        quality?.sample(JbQualityPolicy.Sample(statistics.sampledAtMs,statistics.decodedFps,
            config?.fps?.toDouble() ?: 60.0,statistics.frameAgeMs,statistics.streamLagMs,
            statistics.videoMbps,statistics.audioMbps,statistics.audioUnderruns))?.let {
            reconnect(it.request,it.reason)
        }
    }
    override fun onProfileUnavailable(message:String) {
        if(!foreground || isFinishing) return
        val fallback=quality?.rejectCurrent(SystemClock.elapsedRealtime())
        if(fallback!=null) reconnect(fallback.request,fallback.reason) else onError(message)
    }
    override fun onInputUnavailable(message:String) {
        if(foreground && !isFinishing) Toast.makeText(this,message,Toast.LENGTH_LONG).show()
    }
    override fun onPlaybackInterrupted() {
        playerObserver?.cancel()
        gameTranslation?.stop()
        audioCaptions?.stop()
        if(!foreground || isFinishing) return
        live=false;statistics=null
        setGameplayInputReady(false);input.releaseAllInputs()
        controls.value=false;record.visibility=View.GONE
        menu.show(false)
        connection.showConnecting();connectionText.text="Connection interrupted"
        backdrop.setConnecting(true)
    }
    override fun onConnectionInterrupted(error:PbsConnectionException,playedForMs:Long) {
        if(!foreground || isFinishing) return
        val active=session
        val request=requestedProfile
        val now=SystemClock.elapsedRealtime()
        val retry=recovery.next(now,playedForMs)
        if(active==null || request==null || retry==null) {
            PbsDiagnostics.event(active?.client?.isRemote == true,"recovery_exhausted",error.stage,error.reason)
            onError("The connection could not recover. Check your network and try again.");return
        }
        onPlaybackInterrupted()
        failed=false;recoveryAttempt=retry.attempt
        connectionText.text="Reconnecting to your PS5"
        PbsDiagnostics.event(active.client.isRemote,"retry_scheduled",error.stage,error.reason,retry.attempt)
        recoveryJob?.cancel()
        recoveryJob=lifecycleScope.launch {
            delay(retry.delayMs)
            if(!foreground || isFinishing || session!==active || !video.holder.surface.isValid) return@launch
            // Reuse the client and exact-owner journal. This path never uploads or restarts a payload.
            active.start(video.holder.surface,request.height,request.videoBitrateBps,request.minQp,
                portal.jbAudioBufferMs,retry.deadlineMs)
        }
    }
    override fun onError(message:String) {
        gameTranslation?.stop()
        audioCaptions?.stop()
        if(!foreground || isFinishing) return
        recoveryJob?.cancel();recoveryJob=null;recoveryAttempt=0
        failed=true;live=false;controls.value=false;record.visibility=View.GONE
        setGameplayInputReady(false)
        connection.showConnecting();connectionText.text="Can't connect to your PS5"
        errorDialog?.dismiss()
        val options=if(PbsRecoveryPrompt.needsConsoleRestartAcknowledgment(message))
            listOf("Capture Recovery" to {showCaptureRecoveryConfirmation()},
                "Settings" to {openSettings("remote")},"Back" to {finish()})
        else listOf("Retry" to {
            session?.close();session=null;startup=null;failed=false;recovery.reset();startIfReady()
        },"Settings" to {openSettings("remote")},"Back" to {finish()})
        errorDialog=PortalChoiceDialog.message(this,"Remote Play JB",message,options)
        errorDialog?.setOnCancelListener{finish()}
    }
    private fun showCaptureRecoveryConfirmation() {
        val guard=PortalRemoteAccess(applicationContext).captureQuarantine(portal)
        val observed=guard.snapshot()
        if(!observed.blocked) {failed=false;startIfReady();return}
        errorDialog=PortalChoiceDialog.message(this,"Capture Recovery",
            "Relay saved an earlier unverified PS5 capture failure at " +
            (observed.requestedHeights.singleOrNull()?.let {"${it}p"} ?: "an unknown resolution") + ". " +
            "Automatic payload loading remains blocked to avoid damaging the running " +
            "capture state.\n\nOnly confirm if the PS5 has actually been restarted " +
            "since that failure and the jailbreak and ELF loader were enabled again. " +
            "This button does not reboot the console.",
            listOf("Yes, PS5 restarted" to {
                runCatching {guard.acknowledgeConsoleReset(observed)}.fold({
                    session?.close();session=null;startup=null;failed=false;recovery.reset()
                    startIfReady()
                }, {err ->
                    onError(err.message ?: "The saved recovery state changed. Please reopen Capture Recovery.")
                })
            },"Keep Paused" to {onError(PbsCaptureQuarantinedException().message.orEmpty())}))
    }
    override fun onStopped() {live=false;gameTranslation?.stop();audioCaptions?.stop();setGameplayInputReady(false)}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(!::menu.isInitialized) return super.dispatchKeyEvent(event)
        if(portal.translationHotkey && !menu.isShowing && foreground && live &&
            translationHotkey.onKey(event.keyCode,event.action==KeyEvent.ACTION_DOWN)) {
            toggleGameTranslation()
            return true
        }
        if(input.opensQuickMenu(event)) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) menu.show(!menu.isShowing)
            return true
        }
        if(menu.isShowing) {
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_B || event.keyCode==KeyEvent.KEYCODE_BACK) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)menu.back();return true
            }
            if(event.keyCode==KeyEvent.KEYCODE_BUTTON_A) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)currentFocus?.performClick()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        return input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }
    override fun onGenericMotionEvent(event:MotionEvent):Boolean {
        if(!::input.isInitialized || menu.isShowing) return super.onGenericMotionEvent(event)
        return input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(!::input.isInitialized) return
        updateInputSuspension()
        if(hasFocus) PortalUi.fullscreen(this)
    }
    companion object {private const val CONTROLS_ID=0x00ee0021}
}
