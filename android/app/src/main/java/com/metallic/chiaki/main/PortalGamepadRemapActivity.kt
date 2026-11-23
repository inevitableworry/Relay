// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.session.*

/** Controller remapper with on-screen PlayStation-style diagram and annotated
 * per-button bindings. Edits use the existing GamepadProfiles persistence
 * already consumed by Traditional/JB StreamInput. */
class PortalGamepadRemapActivity:AppCompatActivity() {
    private lateinit var page:PortalPage
    private lateinit var preview:PortalControllerDiagram
    private val store by lazy {GamepadProfiles(this)}
    private var deviceId:Int=-1
    private var capture:GamepadButton?=null
    private var activeAxis:GamepadAxis?=null
    private var section="buttons"
    private val actions=GamepadButton.values().filter {it!=GamepadButton.NONE}
    private fun attached()=GamepadProfiles.connected().firstOrNull {it.id==deviceId}

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        PortalUi.fullscreen(this)
        deviceId=state?.getInt("controller_id") ?: GamepadProfiles.connected().firstOrNull()?.id ?: -1
        page=PortalPage(this,"Button Mapping",::onBack)
        setContentView(page.root)
        render()
    }
    private fun dp(n:Int)=PortalUi.dp(this,n)
    private fun onBack() {
        if(capture!=null || activeAxis!=null || section!="buttons") {
            capture=null;activeAxis=null;section="buttons";render()
        } else finish()
    }
    private fun currentBinding(button:GamepadButton):String {
        val d=attached() ?: return "Auto"
        val profile=store.load(d)
        val explicit=profile.buttons.filterValues {it==button}.keys
            .map {KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")}
        val defaults=listOf(
            KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)
        val automatic=defaults.filter {
            it !in profile.buttons && GamepadButton.automatic(it,false,true)==button
        }.map {KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")}
        val keys=explicit+automatic
        return keys.take(2).joinToString("/").ifEmpty {"Unassigned"}
    }
    private fun render() {
        page.clear(if(section=="buttons") "Button Mapping" else "Analog Mapping")
        val devices=GamepadProfiles.connected()
        if(devices.isEmpty()) {
            page.note("No physical controller is connected. You can inspect the PS5-style button diagram. Pair a Bluetooth/USB controller to save mappings.")
            page.add(PortalUi.row(this,"Pair Bluetooth Controller","Open the Android pairing panel") {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            })
        } else {
            if(attached()==null) deviceId=devices.first().id
            page.add(PortalUi.row(this,"Active Controller",attached()?.name ?: "Controller unavailable",
                "${devices.size} connected") {
                section="devices";render()
            })
        }
        if(section=="devices") {
            for(d in devices) page.add(PortalUi.row(this,d.name,GamepadProfiles.family(d)) {
                deviceId=d.id;section="buttons";render()
            })
            return
        }
        if(section=="buttons") showButtons() else showAxes()
    }
    private fun showButtons() {
        preview=PortalControllerDiagram(this).also {view ->
            view.currentMapping={currentBinding(it)}
            view.selected=capture
            view.onSelect={target ->select(target)}
            page.add(view)
        }
        val state=capture
        page.note(if(state!=null)
            "Mapping ${state.title}: press a physical button on the selected controller. Tap another diagram annotation to change target, or Back to cancel."
            else "Tap any button label or its annotation line. Current mappings are shown beside the illustrated controller.")
        page.add(PortalUi.row(this,"Analog Sticks and Triggers",
            "Map horizontal/vertical axes, triggers, inversion and dead zone") {
            capture=null;section="axes";render()
        })
        for(action in actions) {
            page.add(PortalUi.row(this,action.title,"Current: ${currentBinding(action)}",
                if(capture==action) "Press button…" else "Change") {select(action)})
        }
        page.add(PortalUi.row(this,"Reset This Controller",
            "Remove all custom button and axis mappings") {
            attached()?.let {store.reset(it)}
            capture=null;render()
        })
    }
    private fun select(target:GamepadButton) {
        capture=target
        section="buttons"
        render()
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        val target=capture
        if(target!=null && event.action==KeyEvent.ACTION_DOWN &&
            event.repeatCount==0 && event.deviceId==deviceId &&
            GamepadProfiles.isController(event.device)) {
            val d=attached() ?: return super.dispatchKeyEvent(event)
            val profile=store.load(d)
            store.save(d,profile.copy(buttons=profile.buttons+(event.keyCode to target)))
            capture=null
            PortalMenuAudio.play(this,PortalMenuAudio.Cue.CONFIRM)
            render()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun showAxes() {
        val d=attached()
        page.note("Analog inputs use this controller's actual Android motion ranges. Your selections are stored per physical device.")
        if(d==null) return
        val profile=store.load(d)
        val current=activeAxis
        if(current!=null) {
            page.note("Choose an Android axis for ${current.title}:")
            page.add(PortalUi.row(this,"Automatic","Use the standard controller axis") {
                val mappings=store.load(d).axes-current
                store.save(d,store.load(d).copy(axes=mappings))
                activeAxis=null;render()
            })
            val axes=d.motionRanges.distinctBy {it.axis}.sortedBy {it.axis}
            for(range in axes) for(invert in listOf(false,true)) {
                val name=MotionEvent.axisToString(range.axis).removePrefix("AXIS_")
                page.add(PortalUi.row(this,name,
                    if(invert) "Invert direction" else "Normal direction") {
                    store.save(d,store.load(d).copy(axes=store.load(d).axes+
                        (current to GamepadAxisBinding(range.axis,invert))))
                    activeAxis=null;render()
                })
            }
            return
        }
        for(axis in GamepadAxis.values()) {
            val binding=profile.axes[axis]
            page.add(PortalUi.row(this,axis.title,
                if(binding==null) "Automatic" else MotionEvent.axisToString(binding.axis)+
                    if(binding.inverted) " · Inverted" else "") {
                activeAxis=axis;render()
            })
        }
        page.note("Analog Stick Dead Zone · ${(profile.deadZone*100).toInt()}%")
        page.add(SeekBar(this).apply {
            max=50;progress=(profile.deadZone*100).toInt()
            contentDescription="Stick dead zone"
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,v:Int,fromUser:Boolean) {
                    if(fromUser) store.save(d,store.load(d).copy(deadZone=v/100f))
                }
                override fun onStartTrackingTouch(bar:SeekBar?) {}
                override fun onStopTrackingTouch(bar:SeekBar?) {}
            })
        })
        page.add(PortalUi.row(this,"Return to Button Diagram") {
            section="buttons";render()
        })
    }
    override fun onResume() {super.onResume();if(::page.isInitialized)render()}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putInt("controller_id",deviceId)
        super.onSaveInstanceState(outState)
    }
}
