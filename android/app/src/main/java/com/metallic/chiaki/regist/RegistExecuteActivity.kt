// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.databinding.ActivityRegistExecuteBinding
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.main.PortalBackdropView
import com.metallic.chiaki.main.PortalMotion
import com.metallic.chiaki.main.PortalPage
import com.metallic.chiaki.main.PortalUi
import kotlin.math.max

class RegistExecuteActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_REGIST_INFO = "regist_info"
        const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"
        const val RESULT_FAILED = Activity.RESULT_FIRST_USER
    }

    private lateinit var viewModel: RegistExecuteViewModel
    private lateinit var binding: ActivityRegistExecuteBinding
    private lateinit var backdrop: PortalBackdropView
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistExecuteBinding.inflate(layoutInflater)
        val page = PortalPage(this, "Pair a Console") { finish() }
        backdrop = PortalBackdropView(this).apply {
            alpha = .35f
            reduceMotion = PortalPreferences(this@RegistExecuteActivity).reduceMotion
        }
        page.root.addView(backdrop, 0, FrameLayout.LayoutParams(-1, -1))
        page.add(binding.root)
        setContentView(page.root)
        supportActionBar?.hide()
        PortalUi.fullscreen(this)
        PortalMotion.reduceMotion = PortalPreferences(this).reduceMotion
        ViewCompat.setOnApplyWindowInsetsListener(page.root) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }
        var previousWidth = -1
        page.body.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left, PortalUi.dp(this, 840))
            if (width <= 0 || width == previousWidth) return@addOnLayoutChangeListener
            previousWidth = width
            binding.root.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        if (savedInstanceState == null) PortalMotion.crossfade(page.body, 1)

        viewModel = ViewModelProvider(this, viewModelFactory { RegistExecuteViewModel(getDatabase(this)) })
            .get(RegistExecuteViewModel::class.java)
        binding.logTextView.setHorizontallyScrolling(true)
        binding.logTextView.movementMethod = ScrollingMovementMethod()
        viewModel.logText.observe(this, Observer { log ->
            // Assign text before inspecting its Layout; the first log previously disappeared.
            binding.logTextView.text = log
            binding.logTextView.post {
                val textLayout = binding.logTextView.layout ?: return@post
                if (textLayout.lineCount > 0) {
                    val scrollY = textLayout.getLineBottom(textLayout.lineCount - 1) -
                        binding.logTextView.height + binding.logTextView.paddingTop + binding.logTextView.paddingBottom
                    binding.logTextView.scrollTo(0, max(scrollY, 0))
                }
            }
        })
        binding.detailsButton.setOnClickListener {
            val show = binding.detailsContainer.visibility != View.VISIBLE
            binding.detailsContainer.visibility = if (show) View.VISIBLE else View.GONE
            binding.detailsButton.text = if (show) "Hide Connection Details" else "Connection Details"
            if (show) PortalMotion.crossfade(binding.detailsContainer)
        }
        binding.doneButton.setOnClickListener { finish() }
        listOf(binding.detailsButton, binding.doneButton, binding.shareLogButton).forEach { PortalUi.focus(it) }
        viewModel.state.observe(this, Observer { state ->
            val running = state == RegistExecuteViewModel.State.RUNNING
            binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
            backdrop.setConnecting(running)
            binding.infoTextView.visibility = View.VISIBLE
            when (state) {
                RegistExecuteViewModel.State.FAILED -> {
                    binding.infoTextView.setText(R.string.regist_info_failed)
                    binding.statusDetailTextView.text =
                        "Check the console address, Account ID and pairing code, then try again. Connection details are available below."
                    binding.doneButton.text = "Back to Pairing"
                    setResult(RESULT_FAILED)
                }
                RegistExecuteViewModel.State.SUCCESSFUL, RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE -> {
                    binding.infoTextView.setText(R.string.regist_info_success)
                    binding.statusDetailTextView.text =
                        "Your console is ready. Select Connect from the home screen to start Remote Play."
                    binding.doneButton.text = "Done"
                    setResult(RESULT_OK)
                    if (state == RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE) showDuplicateDialog()
                }
                RegistExecuteViewModel.State.STOPPED -> {
                    binding.infoTextView.text = "Pairing Stopped"
                    binding.statusDetailTextView.text = "Return to the pairing screen to try again."
                    binding.doneButton.text = "Back to Pairing"
                    setResult(Activity.RESULT_CANCELED)
                }
                else -> {
                    binding.infoTextView.text = if (running) "Pairing with Your Console" else "Preparing to Pair"
                    binding.statusDetailTextView.text = "Keep your console's pairing screen open."
                    binding.doneButton.text = "Cancel Pairing"
                }
            }
        })
        binding.shareLogButton.setOnClickListener {
            val log = viewModel.logText.value ?: ""
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, log)
            }, resources.getString(R.string.action_share_log)))
        }

        val info = IntentCompat.getParcelableExtra(intent, EXTRA_REGIST_INFO, RegistInfo::class.java)
        if (info == null) {
            finish()
            return
        }
        viewModel.start(info, if (intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
            intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0) else null)
    }

    override fun onResume() {
        super.onResume()
        PortalUi.fullscreen(this)
    }

    override fun onStop() {
        super.onStop()
        // Folding or rotating must not cancel registration held by the retained ViewModel.
        if (!isChangingConfigurations && ::viewModel.isInitialized) viewModel.stop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
            KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
            else event)
    }

    private fun showDuplicateDialog() {
        if (dialog != null) return
        val mac = viewModel.host?.serverMac?.let { MacAddress(it).toString() } ?: ""
        dialog = MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.alert_regist_duplicate, mac))
            .setNegativeButton(R.string.action_regist_discard) { _, _ -> }
            .setPositiveButton(R.string.action_regist_overwrite) { _, _ -> viewModel.saveHost() }
            .create().also { it.show() }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
