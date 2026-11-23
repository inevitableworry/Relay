// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.WeakHashMap

/**
 * Plays the user's supplied Portal recording only while app menus are foreground.
 * Source/processing provenance is retained in docs/portal-reference/audio.
 * Missing sound-effect recordings stay silent: there are no synthesized substitutes.
 */
object PortalMenuAudio {
    enum class Cue(val asset: String) {
        FOCUS("menu_focus.ogg"), CONFIRM("menu_confirm.ogg"), BACK("menu_back.ogg"),
        MENU_OPEN("menu_open.ogg"), MENU_CLOSE("menu_close.ogg"),
        REVEAL("menu_reveal.ogg"), UPDATE_START("update_start.ogg"),
        UPDATE_COMPLETE("update_complete.ogg")
    }

    private const val TAG = "PortalMenuAudio"
    private const val MUSIC_ASSET = "portal_audio/portal_home_loop.pcm"
    private val main = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var manager: AudioManager? = null
    private val resumed = WeakHashMap<Activity, Boolean>()
    private val suppressed = WeakHashMap<Activity, Boolean>()
    private var musicWanted = false
    private var player: PortalMusicLoop? = null
    private var failed = false
    private var outputRecoveryAttempts = 0
    private var focusGranted = false
    private var focusRequested = false
    private var ducked = false
    private var focusRequest: AudioFocusRequest? = null
    private var sounds: SoundPool? = null
    private val cueIds = mutableMapOf<Cue, Int>()
    private val loadedSounds = mutableSetOf<Int>()
    private val lastCueTime = LongArray(Cue.entries.size)
    private val musicAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val pauseIfBackground = Runnable { updateWanted() }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when(change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if(focusRequested) {
                        focusGranted = true
                        ducked = false
                        updateMusic()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    applyVolume()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    focusGranted = false
                    pausePlayer()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    focusGranted = false
                    focusRequested = false
                    pausePlayer()
                }
            }
        }
    }

    fun install(app: Application) {
        if(application != null) return
        application = app
        manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(musicAttributes)
                .setOnAudioFocusChangeListener(focusListener, main).build()
        }
        loadSounds(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                // Stop before a decoder/session starts, not after its first video frame.
                if(isStream(activity)) { musicWanted = false; stopMusic() }
            }
            override fun onActivityResumed(activity: Activity) {
                main.removeCallbacks(pauseIfBackground)
                resumed[activity] = !isStream(activity)
                failed = false
                outputRecoveryAttempts = 0
                updateWanted()
            }
            override fun onActivityPaused(activity: Activity) {
                resumed.remove(activity)
                // Normal activity navigation resumes its next menu in the same main-loop turn.
                main.removeCallbacks(pauseIfBackground)
                main.postDelayed(pauseIfBackground, 100L)
            }
            override fun onActivityStopped(activity: Activity) {
                if(resumed.isEmpty()) updateWanted()
            }
            override fun onActivityDestroyed(activity: Activity) {
                resumed.remove(activity);suppressed.remove(activity);updateWanted()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    private fun isStream(activity: Activity) = activity.javaClass.name in setOf(
        "com.metallic.chiaki.stream.StreamActivity", "com.metallic.chiaki.jb.JbStreamActivity")
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("relay_portal", Context.MODE_PRIVATE)
    private fun ensureInstalled(context: Context) {
        if(application == null) install(context.applicationContext as Application)
    }

    fun isMusicEnabled(context: Context) = preferences(context).getBoolean("menu_music", true)
    fun musicVolume(context: Context) = preferences(context).getFloat("menu_music_volume", .55f).coerceIn(0f, 1f)
    fun isSoundsEnabled(context: Context) = preferences(context).getBoolean("menu_sounds", true)
    fun setMusicEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("menu_music", enabled).apply()
        failed = false
        refresh(context)
    }
    fun setMusicVolume(context: Context, volume: Float) {
        preferences(context).edit().putFloat("menu_music_volume", volume.coerceIn(0f, 1f)).apply()
        refresh(context)
    }
    fun setSoundsEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("menu_sounds", enabled).apply()
        if(!enabled) sounds?.autoPause()
    }
    fun availableCues(context: Context): Set<Cue> {
        ensureInstalled(context)
        return cueIds.keys.toSet()
    }
    fun refresh(context: Context) {
        ensureInstalled(context)
        if(Looper.myLooper() == Looper.getMainLooper()) updateWanted()
        else main.post { updateWanted() }
    }

    /** Boot logos and gallery video have no menu-music overlay. */
    fun suppressMusic(activity: Activity, suppress: Boolean) {
        ensureInstalled(activity)
        if(suppress) suppressed[activity] = true else suppressed.remove(activity)
        updateWanted()
    }

    fun play(context: Context, cue: Cue) {
        ensureInstalled(context)
        if(Looper.myLooper() != Looper.getMainLooper()) { main.post { play(context, cue) }; return }
        if(resumed.isEmpty() || !isSoundsEnabled(context)) return
        val sound = cueIds[cue] ?: return
        if(sound !in loadedSounds) return
        val now = SystemClock.uptimeMillis()
        if(now - lastCueTime[cue.ordinal] < 70L) return
        lastCueTime[cue.ordinal] = now
        sounds?.play(sound, .7f, .7f, 1, 0, 1f)
    }

    private fun loadSounds(app: Application) {
        val files = app.assets.list("portal_audio")?.toSet().orEmpty()
        val available = Cue.entries.filter { it.asset in files }
        if(available.isEmpty()) return
        val pool = SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        sounds = pool
        pool.setOnLoadCompleteListener { _, id, status -> if(status == 0) loadedSounds.add(id) }
        available.forEach { cue ->
            runCatching { app.assets.openFd("portal_audio/" + cue.asset).use { pool.load(it, 1) } }
                .onSuccess { cueIds[cue] = it }
                .onFailure { Log.w(TAG, "Cannot load recorded cue ${cue.name}", it) }
        }
    }

    private fun updateWanted() {
        val app = application ?: return
        musicWanted = resumed.isNotEmpty() && resumed.values.all { it } &&
            resumed.keys.none { suppressed[it] == true } && isMusicEnabled(app)
        updateMusic()
    }

    private fun updateMusic() {
        if(!musicWanted) { stopMusic(); return }
        if(failed) return
        if(!focusRequested) requestFocus()
        if(!focusGranted) return
        val app = application ?: return
        if(player == null) {
            player = PortalMusicLoop(app,MUSIC_ASSET) { failedPlayer,error ->
                main.post {
                    if(player === failedPlayer) {
                        Log.w(TAG,"Menu recording playback failed",error)
                        val recover = error is PortalMusicLoop.OutputException &&
                            error.code == AudioTrack.ERROR_DEAD_OBJECT && outputRecoveryAttempts < 1
                        failed = !recover
                        player = null
                        failedPlayer.close()
                        stopMusic()
                        if(recover) {
                            outputRecoveryAttempts++
                            // A disconnected audio route may invalidate its output track.
                            // Retry once, and only if a menu still owns foreground playback.
                            main.postDelayed({ if(musicWanted && player == null) updateMusic() }, 150L)
                        }
                    }
                }
            }
        }
        applyVolume()
        player?.play()
    }

    private fun applyVolume() {
        val app = application ?: return
        val volume = musicVolume(app) * if(ducked) .2f else 1f
        player?.setVolume(volume)
    }
    private fun pausePlayer() {
        player?.pause()
    }
    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val audio = manager ?: return
        val result = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            focusRequest?.let { audio.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        else audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        focusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = focusGranted
        ducked = false
    }
    @Suppress("DEPRECATION")
    private fun stopMusic() {
        pausePlayer()
        if(focusRequested) {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                focusRequest?.let { manager?.abandonAudioFocusRequest(it) }
            else manager?.abandonAudioFocus(focusListener)
        }
        focusRequested = false
        focusGranted = false
        ducked = false
    }
}
