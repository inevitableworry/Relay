// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.common.PortalRemoteAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Real HTTPS gateway for JB; system private-network routing for either engine. */
class PortalRemoteAccessActivity : AppCompatActivity() {
    private lateinit var page: PortalPage
    private lateinit var remote: PortalRemoteAccess
    private lateinit var portal: PortalPreferences
    private var checking = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        remote = PortalRemoteAccess(this)
        portal = PortalPreferences(this)
        PortalUi.fullscreen(this)
        page = PortalPage(this, "Remote Access") { finish() }
        setContentView(page.root)
        onBackPressedDispatcher.addCallback(this) { finish() }
        render()
    }
    override fun onResume() { super.onResume(); if(::page.isInitialized) { PortalUi.fullscreen(this); render() } }

    private fun render() {
        page.clear("Remote Access")
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpn = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        page.add(PortalUi.row(this, "Private VPN", "Use a Tailscale subnet router or another VPN to reach your PS5 at its home address.",
            if(vpn) "Connected" else "Not connected", PortalUi.Icon.NETWORK) {
            val tailscale = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            startActivity(tailscale ?: Intent(Settings.ACTION_VPN_SETTINGS))
        })
        if(portal.mode != PortalPreferences.Mode.JB) {
            page.note("Traditional Remote Play needs both TCP and UDP. Connect your private VPN, then use your paired console's IP address or add it manually. HTTPS Relay tunnels support Remote Play JB.")
            return
        }
        page.note("A Relay tunnel connects through your computer when you are away from home. Your computer and PS5 must be on. Local network and private VPN connections use your saved PS5 address.")
        page.add(PortalUi.toggle(this, "Use Relay Tunnel",
            "Connect JB video, audio and controls through the saved HTTPS address.", remote.enabled) { value ->
            runCatching { remote.enabled = value }.onFailure {
                message("Tunnel setup", it.message ?: "Enter the tunnel address and access key first.")
            }
            render()
        })
        page.add(PortalUi.row(this, "Tunnel Address", value=remote.endpoint.ifBlank { "Not set" }) {
            edit("Tunnel Address", remote.endpoint, false) { value ->
                remote.endpoint = value
            }
        })
        page.add(PortalUi.row(this, "Access Key",
            if(remote.token.isBlank()) "Not saved" else "Saved securely on this device") {
            edit("Access Key", "", true) { value -> remote.saveToken(value) }
        })
        page.add(PortalUi.row(this, if(checking) "Checking Tunnel…" else "Test Tunnel",
            "Verify the real PS5 streaming service through this address.", icon=PortalUi.Icon.REMOTE) {
            if(!checking) test()
        })
        page.note("Gallery transfers currently use your home network or private VPN. The HTTPS tunnel carries the JB stream and its controls.")
        if(remote.token.isNotBlank()) page.add(PortalUi.row(this, "Remove Access Key",
            "Turn off the HTTPS tunnel and remove its saved key.") {
            remote.clearKey(); render()
        })
    }

    private fun edit(title: String, initial: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_TEXT or if(secret)
                InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setPadding(page.dp(24), page.dp(16), page.dp(24), page.dp(16))
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                try { save(input.text.toString().trim()); dialog.dismiss(); render() }
                catch(error: Exception) { input.error = error.message ?: "Check this value." }
            }
        }
        dialog.show()
    }

    private fun test() {
        checking = true; render()
        lifecycleScope.launch {
            try {
                val c = remote.testTunnel(portal)
                message("Tunnel Connected",
                    "Your PS5 answered through the saved tunnel.\n\nVideo: ${c.width} × ${c.height} at ${c.fps} fps\nAvailable resolutions: " +
                        c.resolutions.joinToString { "${it}p" } +
                        "\n\nA stream session will verify actual decoding and controller response.")
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { message("Tunnel Unavailable", error.message ?: "Could not reach your PS5.") }
            finally { checking = false; if(!isFinishing && !isDestroyed) render() }
        }
    }

    private fun message(title: String, text: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton("Close", null).show()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B) { finish(); return true }
            if(event.keyCode == KeyEvent.KEYCODE_BUTTON_A) { currentFocus?.performClick(); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}

