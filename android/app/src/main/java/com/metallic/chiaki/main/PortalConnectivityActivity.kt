// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.session.GamepadProfiles

/** Portal-style connectivity front-end. Modern stock Android deliberately
 * reserves global Wi-Fi switching and Bluetooth bonding to the system/user.
 * We show the status and invoke its approval panel instead of pretending the
 * app can override network or pairing security settings. */
class PortalConnectivityActivity:AppCompatActivity() {
    companion object {
        const val EXTRA_PAGE="page"
    }
    private lateinit var page:PortalPage
    private var section="wifi"
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        PortalUi.fullscreen(this)
        section=state?.getString("section") ?: intent.getStringExtra(EXTRA_PAGE) ?: "wifi"
        page=PortalPage(this,"",::finish)
        setContentView(page.root)
        render()
    }
    private fun render() {
        page.clear(if(section=="bluetooth") "Bluetooth Controllers" else "Wi-Fi Network")
        if(section=="bluetooth") renderBluetooth() else renderWifi()
    }
    private fun renderWifi() {
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities=cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi=capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
        val internet=capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        page.note("Connection status: ${if(wifi) "Wi-Fi connected" else "Not connected to Wi-Fi"}.")
        page.note("Internet: ${if(internet) "Available" else "Not verified"}.")
        page.add(PortalUi.row(this,"Connect to Wi-Fi","Choose an access point without leaving Relay") {
            val panel=if(Build.VERSION.SDK_INT>=29)
                Intent("android.settings.panel.action.WIFI")
            else Intent(Settings.ACTION_WIFI_SETTINGS)
            try {startActivity(panel)}
            catch(_:Exception) {
                runCatching {startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}
                    .onFailure {Toast.makeText(this,"Wi-Fi settings unavailable",Toast.LENGTH_LONG).show()}
            }
        })
        page.add(PortalUi.row(this,"Network Status","Check connection details and signal strength") {
            PortalNetworkStatusDialog(this).show()
        })
        page.add(PortalUi.row(this,"Refresh Network","Read the latest Android Wi-Fi status") {
            render()
        })
        page.note("Android requires your approval when switching networks. Relay cannot silently force the entire device onto a Wi-Fi access point.")
    }
    private fun renderBluetooth() {
        val bt=runCatching {(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter}.getOrNull()
        // Bluetooth status requires BLUETOOTH_CONNECT on newer Android builds.
        val state=runCatching {if(bt?.isEnabled==true) "Enabled" else "Disabled"}.getOrDefault("Permission required")
        page.note("Bluetooth: $state")
        page.add(PortalUi.row(this,"Pair or Connect Controller",
            "Use Android's secure Bluetooth device chooser") {
            runCatching {startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))}
                .onFailure {Toast.makeText(this,"Bluetooth settings unavailable",Toast.LENGTH_LONG).show()}
        })
        val devices=GamepadProfiles.connected()
        page.note("Connected controllers: ${devices.size}")
        for(d in devices) page.add(PortalUi.row(this,d.name,GamepadProfiles.family(d),
            "Configure") {
            startActivity(Intent(this,PortalGamepadRemapActivity::class.java)
                .putExtra("controller_id",d.id))
        })
        page.add(PortalUi.row(this,"Controller Mapping",
            "Illustrated PS5 layout, button assignments, sticks and triggers") {
            startActivity(Intent(this,PortalGamepadRemapActivity::class.java))
        })
        page.note("Bluetooth pairing needs the Android system's confirmation. After pairing, gamepads appear here automatically when Android reports them.")
    }
    override fun onResume() {super.onResume();if(::page.isInitialized)render()}
    override fun onSaveInstanceState(out:Bundle) {out.putString("section",section);super.onSaveInstanceState(out)}
}
