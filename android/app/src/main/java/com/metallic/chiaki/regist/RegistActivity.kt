// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PortalPreferences
import com.metallic.chiaki.databinding.ActivityRegistBinding
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import com.metallic.chiaki.main.PortalMotion
import com.metallic.chiaki.main.PortalPage
import com.metallic.chiaki.main.PortalUi
import java.lang.IllegalArgumentException

class RegistActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_HOST = "regist_host"
		const val EXTRA_BROADCAST = "regist_broadcast"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"

		private const val PIN_LENGTH = 8

		private const val REQUEST_REGIST = 1
	}

	private lateinit var viewModel: RegistViewModel
	private lateinit var binding: ActivityRegistBinding

	private lateinit var page: PortalPage

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistBinding.inflate(layoutInflater)
		page = PortalPage(this, "Pair a Console") { finish() }
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
		configureAdaptiveForm()
		if(savedInstanceState == null) PortalMotion.crossfade(page.body, 1)

		viewModel = ViewModelProvider(this).get(RegistViewModel::class.java)

		binding.hostEditText.setText(intent.getStringExtra(EXTRA_HOST) ?: "255.255.255.255")
		binding.broadcastCheckBox.isChecked = intent.getBooleanExtra(EXTRA_BROADCAST, true)

		binding.registButton.setOnClickListener { doRegist() }
		PortalUi.focus(binding.registButton)
		binding.pinEditText.setOnEditorActionListener { _, action, _ ->
			if(action == EditorInfo.IME_ACTION_DONE) { doRegist(); true } else false
		}

		binding.ps4VersionRadioGroup.check(when(viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5) {
			RegistViewModel.ConsoleVersion.PS5 -> R.id.ps5RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> R.id.ps4VersionGE8RadioButton
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> R.id.ps4VersionGE7RadioButton
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.id.ps4VersionLT7RadioButton
		})

		binding.ps4VersionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
			viewModel.ps4Version.value = when(checkedId)
			{
				R.id.ps5RadioButton -> RegistViewModel.ConsoleVersion.PS5
				R.id.ps4VersionGE8RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_8
				R.id.ps4VersionGE7RadioButton -> RegistViewModel.ConsoleVersion.PS4_GE_7
				R.id.ps4VersionLT7RadioButton -> RegistViewModel.ConsoleVersion.PS4_LT_7
				else -> RegistViewModel.ConsoleVersion.PS5
			}
		}

		viewModel.ps4Version.observe(this, Observer {
			binding.psnAccountIdHelpGroup.visibility = if(it == RegistViewModel.ConsoleVersion.PS4_LT_7) View.GONE else View.VISIBLE
			binding.psnIdTextInputLayout.hint = getString(when(it!!)
			{
				RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.hint_regist_psn_online_id
				else -> R.string.hint_regist_psn_account_id
			})
			binding.pinHelpBeforeTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_before else R.string.regist_pin_instructions_ps4_before)
			binding.pinHelpNavigationTextView.setText(if(it.isPS5) R.string.regist_pin_instructions_ps5_navigation else R.string.regist_pin_instructions_ps4_navigation)
		})
	}

	private fun configureAdaptiveForm()
	{
		var previousWidth = -1
		page.body.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
			val width = minOf(right - left, PortalUi.dp(this, 1100))
			if(width <= 0 || width == previousWidth) return@addOnLayoutChangeListener
			previousWidth = width
			binding.root.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
				gravity = Gravity.CENTER_HORIZONTAL
			}
			val wide = width >= PortalUi.dp(this, 840)
			binding.registColumns.orientation = if(wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
			binding.instructionsPanel.layoutParams = LinearLayout.LayoutParams(
				if(wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, if(wide) .38f else 0f).apply {
					marginEnd = if(wide) PortalUi.dp(this@RegistActivity, 28) else 0
				}
			binding.detailsPanel.layoutParams = LinearLayout.LayoutParams(
				if(wide) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, if(wide) .62f else 0f).apply {
					topMargin = if(wide) 0 else PortalUi.dp(this@RegistActivity, 22)
				}
		}
	}

	override fun onResume() {
		super.onResume()
		PortalUi.fullscreen(this)
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if(event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
			if(event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
			return true
		}
		return super.dispatchKeyEvent(if(event.keyCode == KeyEvent.KEYCODE_BUTTON_A)
			KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
				event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
			else event)
	}

	private fun doRegist()
	{
		val ps4Version = viewModel.ps4Version.value ?: RegistViewModel.ConsoleVersion.PS5

		val host = binding.hostEditText.text.toString().trim()
		val hostValid = host.isNotEmpty()
		val broadcast = binding.broadcastCheckBox.isChecked

		val psnId = binding.psnIdEditText.text.toString().trim()
		val psnOnlineId: String? = if(ps4Version == RegistViewModel.ConsoleVersion.PS4_LT_7) psnId else null
		val psnAccountId: ByteArray? =
			if(ps4Version != RegistViewModel.ConsoleVersion.PS4_LT_7)
				try { Base64.decode(psnId, Base64.DEFAULT) } catch(e: IllegalArgumentException) { null }
			else
				null
		val psnIdValid = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> psnOnlineId?.isNotEmpty() ?: false
			else -> psnAccountId != null && psnAccountId.size == RegistInfo.ACCOUNT_ID_SIZE
		}


		val pin = binding.pinEditText.text.toString()
		val pinValid = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

		binding.hostEditText.error = if(!hostValid) getString(R.string.entered_host_invalid) else null
		binding.psnIdEditText.error =
			if(!psnIdValid)
				getString(when(ps4Version)
				{
					RegistViewModel.ConsoleVersion.PS4_LT_7 -> R.string.regist_psn_online_id_invalid
					else -> R.string.regist_psn_account_id_invalid
				})
			else
				null
		binding.pinEditText.error = if(!pinValid) getString(R.string.regist_pin_invalid, PIN_LENGTH) else null

		if(!hostValid || !psnIdValid || !pinValid)
		{
			when {
				!hostValid -> binding.hostEditText.requestFocus()
				!psnIdValid -> binding.psnIdEditText.requestFocus()
				else -> binding.pinEditText.requestFocus()
			}
			return
		}
		WindowCompat.getInsetsController(window, page.root).hide(WindowInsetsCompat.Type.ime())

		val target = when(ps4Version)
		{
			RegistViewModel.ConsoleVersion.PS5 -> Target.PS5_1
			RegistViewModel.ConsoleVersion.PS4_GE_8 -> Target.PS4_10
			RegistViewModel.ConsoleVersion.PS4_GE_7 -> Target.PS4_9
			RegistViewModel.ConsoleVersion.PS4_LT_7 -> Target.PS4_8
		}

		val registInfo = RegistInfo(target, host, broadcast, psnOnlineId, psnAccountId, pin.toInt())

		Intent(this, RegistExecuteActivity::class.java).also {
			it.putExtra(RegistExecuteActivity.EXTRA_REGIST_INFO, registInfo)
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				it.putExtra(RegistExecuteActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0L))
			startActivityForResult(it, REQUEST_REGIST)
		}
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		super.onActivityResult(requestCode, resultCode, data)
		if(requestCode == REQUEST_REGIST && resultCode == RESULT_OK)
			finish()
	}
}
