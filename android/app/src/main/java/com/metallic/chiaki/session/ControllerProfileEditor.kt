// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.session

import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Shared entry point for both Traditional and JB controller settings. */
object ControllerProfileEditor {
    fun show(activity: Activity) {
        val devices = GamepadProfiles.connected()
        if(devices.isEmpty()) {
            MaterialAlertDialogBuilder(activity).setTitle("Controllers")
                .setMessage("Connect a Bluetooth or USB controller to customize it. Recognized Android gamepads use automatic button and stick mapping.")
                .setPositiveButton("Done", null).show()
            return
        }
        MaterialAlertDialogBuilder(activity).setTitle("Controllers")
            .setItems(devices.map { "${it.name}\n${GamepadProfiles.family(it)}" }.toTypedArray()) { _, index ->
                deviceMenu(activity, devices[index])
            }.setNegativeButton("Back", null).show()
    }

    private fun deviceMenu(activity: Activity, device: InputDevice) {
        if(InputDevice.getDevice(device.id) == null) { show(activity); return }
        val store = GamepadProfiles(activity)
        val profile = store.load(device)
        val custom = profile.buttons.size + profile.axes.size
        MaterialAlertDialogBuilder(activity).setTitle(device.name)
            .setItems(arrayOf(
                "Button mapping · ${profile.buttons.size} custom",
                "Analog sticks and triggers · ${profile.axes.size} custom",
                "Stick dead zone · ${(profile.deadZone * 100).toInt()}%",
                "Restore automatic mapping",
                "Controller details"
            )) { _, index ->
                when(index) {
                    0 -> buttons(activity, device)
                    1 -> axes(activity, device)
                    2 -> deadZone(activity, device)
                    3 -> {
                        store.reset(device)
                        deviceMenu(activity, device)
                    }
                    else -> MaterialAlertDialogBuilder(activity).setTitle(device.name)
                        .setMessage("${GamepadProfiles.family(device)}\nVendor ${device.vendorId} · Product ${device.productId}\n${device.motionRanges.map { it.axis }.distinct().size} analog axes\n${custom} custom mappings\n\nAndroid supplies the standard button positions. Controls that Android does not expose cannot be mapped here.")
                        .setPositiveButton("Back") { _, _ -> deviceMenu(activity, device) }.show()
                }
            }.setNegativeButton("Back") { _, _ -> show(activity) }.show()
    }

    private fun buttons(activity: Activity, device: InputDevice) {
        val store = GamepadProfiles(activity)
        val profile = store.load(device)
        val actions = GamepadButton.values()
        MaterialAlertDialogBuilder(activity).setTitle("Button mapping")
            .setItems(actions.map { target ->
                val keys = profile.buttons.filterValues { it == target }.keys
                    .joinToString(", ") { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
                if(keys.isEmpty()) target.title else "${target.title} · $keys"
            }.toTypedArray()) { _, index ->
                val target = actions[index]
                val dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(if(target == GamepadButton.NONE) "Unassign a button" else "Map ${target.title}")
                    .setMessage("Press the button on ${device.name} that should perform this action.\n\nUse the screen to cancel.")
                    .setNegativeButton("Cancel") { _, _ -> buttons(activity, device) }.create()
                dialog.setOnKeyListener { _, keyCode, event ->
                    if(event.deviceId != device.id) false
                    else {
                        if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !event.isCanceled) {
                            val current = store.load(device)
                            store.save(device, current.copy(buttons = current.buttons + (keyCode to target)))
                            dialog.dismiss()
                            buttons(activity, device)
                        }
                        true
                    }
                }
                dialog.show()
            }.setNegativeButton("Back") { _, _ -> deviceMenu(activity, device) }.show()
    }

    private fun axes(activity: Activity, device: InputDevice) {
        val store = GamepadProfiles(activity)
        val profile = store.load(device)
        val targets = GamepadAxis.values()
        MaterialAlertDialogBuilder(activity).setTitle("Analog sticks and triggers")
            .setItems(targets.map { target ->
                val binding = profile.axes[target]
                "${target.title} · " + if(binding == null) "Automatic" else
                    MotionEvent.axisToString(binding.axis).removePrefix("AXIS_") + if(binding.inverted) " inverted" else ""
            }.toTypedArray()) { _, index ->
                val target = targets[index]
                val reported = device.motionRanges.distinctBy { it.axis }.sortedBy { it.axis }
                val bindings = reported.flatMap {
                    listOf(GamepadAxisBinding(it.axis), GamepadAxisBinding(it.axis, true))
                }
                val labels = listOf("Automatic") + bindings.map {
                    MotionEvent.axisToString(it.axis).removePrefix("AXIS_") + if(it.inverted) " · inverted" else ""
                }
                MaterialAlertDialogBuilder(activity).setTitle(target.title)
                    .setItems(labels.toTypedArray()) { _, choice ->
                        val current = store.load(device)
                        val mappings = current.axes.toMutableMap()
                        if(choice == 0) mappings.remove(target) else mappings[target] = bindings[choice - 1]
                        store.save(device, current.copy(axes = mappings))
                        axes(activity, device)
                    }.setNegativeButton("Back") { _, _ -> axes(activity, device) }.show()
            }.setNegativeButton("Back") { _, _ -> deviceMenu(activity, device) }.show()
    }

    private fun deadZone(activity: Activity, device: InputDevice) {
        val store = GamepadProfiles(activity)
        val profile = store.load(device)
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val value = TextView(activity).apply { text = "${(profile.deadZone * 100).toInt()}%" }
        val slider = SeekBar(activity).apply { max = 50; progress = (profile.deadZone * 100).toInt() }
        slider.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = "$progress%" }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(value); addView(slider)
            addView(TextView(activity).apply { text = "Increase this if the sticks move while untouched. Android's reported hardware dead zone is also respected." })
        }
        MaterialAlertDialogBuilder(activity).setTitle("Stick dead zone").setView(content)
            .setPositiveButton("Save") { _, _ ->
                store.save(device, store.load(device).copy(deadZone = slider.progress / 100f))
                deviceMenu(activity, device)
            }.setNegativeButton("Cancel") { _, _ -> deviceMenu(activity, device) }.show()
    }
}

