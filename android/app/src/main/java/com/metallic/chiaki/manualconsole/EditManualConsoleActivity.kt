// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.manualconsole

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.databinding.ActivityEditManualBinding
import com.metallic.chiaki.main.PortalMotion
import com.metallic.chiaki.main.PortalPage
import com.metallic.chiaki.main.PortalUi

class EditManualConsoleActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MANUAL_HOST_ID = "manual_host_id"
    }

    private lateinit var viewModel: EditManualConsoleViewModel
    private lateinit var binding: ActivityEditManualBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditManualBinding.inflate(layoutInflater)
        val editing = intent.hasExtra(EXTRA_MANUAL_HOST_ID)
        val page = PortalPage(this, if (editing) "Edit Console Address" else "Add Console Manually") { closePage() }
        page.add(binding.root)
        setContentView(page.root)
        supportActionBar?.hide()
        PortalUi.fullscreen(this)
        PortalMotion.reduceMotion = PortalPreferences(this).reduceMotion
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(cutout.left, cutout.top, cutout.right, maxOf(cutout.bottom, keyboard.bottom))
            insets
        }
        var previousWidth = -1
        page.body.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, PortalUi.dp(this, 720))
            if (width <= 0 || width == previousWidth) return@addOnLayoutChangeListener
            previousWidth = width
            binding.root.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        if (savedInstanceState == null) PortalMotion.crossfade(page.body, 1)

        viewModel = ViewModelProvider(this, viewModelFactory {
            EditManualConsoleViewModel(getDatabase(this),
                if (editing) intent.getLongExtra(EXTRA_MANUAL_HOST_ID, 0) else null)
        }).get(EditManualConsoleViewModel::class.java)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closePage()
        })
        viewModel.existingHost?.observe(this, Observer {
            if (savedInstanceState == null || binding.hostEditText.text.isNullOrBlank())
                binding.hostEditText.setText(it.host)
        })
        binding.registeredHostTextView.keyListener = null
        viewModel.selectedRegisteredHost.observe(this, Observer {
            binding.registeredHostTextView.setText(titleForRegisteredHost(it), false)
        })
        viewModel.registeredHosts.observe(this, Observer { hosts ->
            binding.registeredHostTextView.setAdapter(ArrayAdapter(this, R.layout.dropdown_menu_popup_item,
                hosts.map { titleForRegisteredHost(it) }))
            binding.registeredHostTextView.setOnItemClickListener { _, _, position, _ ->
                if (position in hosts.indices) viewModel.selectedRegisteredHost.value = hosts[position]
            }
        })
        viewModel.saveState.observe(this, Observer { state ->
            val saving = state == EditManualConsoleViewModel.SaveState.SAVING
            binding.saveButton.isEnabled = !saving
            binding.saveButton.text = if (saving) "Saving…" else getString(R.string.action_add_manual_save)
            binding.hostEditText.isEnabled = !saving
            binding.registeredHostTextView.isEnabled = !saving
            binding.saveErrorTextView.visibility =
                if (state == EditManualConsoleViewModel.SaveState.FAILED) View.VISIBLE else View.GONE
            binding.saveErrorTextView.text = "The console could not be saved. Please try again."
            if (state == EditManualConsoleViewModel.SaveState.SAVED) {
                setResult(RESULT_OK)
                finish()
            }
        })
        PortalUi.focus(binding.saveButton)
        binding.saveButton.setOnClickListener { saveHost() }
    }

    private fun titleForRegisteredHost(host: RegisteredHost?) =
        if (host == null) getString(R.string.add_manual_regist_on_connect)
        else "${host.serverNickname ?: ""} (${host.serverMac})"

    private fun saveHost() {
        val host = binding.hostEditText.text.toString().trim()
        if (host.isEmpty()) {
            binding.hostEditText.error = getString(R.string.entered_host_invalid)
            binding.hostEditText.requestFocus()
            return
        }
        binding.hostEditText.error = null
        // Finish only after Room has completed the insert/update, so closing cannot cancel it.
        viewModel.saveHost(host)
    }

    private fun closePage() {
        if (!::viewModel.isInitialized || viewModel.saveState.value != EditManualConsoleViewModel.SaveState.SAVING)
            finish()
    }

    override fun onResume() {
        super.onResume()
        PortalUi.fullscreen(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP) closePage()
            return true
        }
        return super.dispatchKeyEvent(if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
            KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
            else event)
    }
}
