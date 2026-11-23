// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.res.ColorStateList
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity

class MainActivity : AppCompatActivity()
{
	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private var discoveryMenuItem: MenuItem? = null
	private lateinit var hostsAdapter: DisplayHostRecyclerViewAdapter
	private var allHosts: List<DisplayHost> = emptyList()
	private enum class HomeTab { REMOTE, SEARCH, CLOUD }
	private var currentTab = HomeTab.REMOTE

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)
		// Android 15+ draws behind transparent system bars by default. Keep
		// controls clear of the status bar, gesture area and Samsung taskbar.
		ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
			val safe = insets.getInsets(
				WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
			insets
		}

		title = ""
		setSupportActionBar(binding.toolbar)

		binding.floatingActionButton.setOnClickListener {
			expandFloatingActionButton(!binding.floatingActionButton.isExpanded)
		}
		binding.floatingActionButtonDialBackground.setOnClickListener {
			expandFloatingActionButton(false)
		}

		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.addManualLabelButton.setOnClickListener { addManualConsole() }

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.registerLabelButton.setOnClickListener { showRegistration() }

		setupBackNavigation()

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this)) })
			.get(MainViewModel::class.java)

			hostsAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::editHost, this::deleteHost)
			binding.hostsRecyclerView.adapter = hostsAdapter
			// Recalculate for split screen and fold posture changes, including live resizes.
			val gridLayoutManager = GridLayoutManager(this, gridColumns(resources.configuration.screenWidthDp, 1))
			binding.hostsRecyclerView.layoutManager = gridLayoutManager
			binding.hostsRecyclerView.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
				val availableWidthDp = ((right - left) / resources.displayMetrics.density).toInt()
				if(availableWidthDp > 0) {
					val target = gridColumns(availableWidthDp, hostsAdapter.itemCount)
					if(gridLayoutManager.spanCount != target) gridLayoutManager.spanCount = target
				}
			}
			binding.remoteTab.setOnClickListener { selectTab(HomeTab.REMOTE) }
			binding.searchTab.setOnClickListener { selectTab(HomeTab.SEARCH) }
			binding.cloudTab.setOnClickListener { selectTab(HomeTab.CLOUD) }
			binding.hostSearchEditText.addTextChangedListener {
				refreshHosts()
				binding.hostsRecyclerView.scrollToPosition(0)
			}
			selectTab(HomeTab.REMOTE)
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
				allHosts = it
				refreshHosts()
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
		})

		viewModel.discoveryActive.observe(this, Observer { active ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
			updateEmptyInfo()
		})
	}

		private fun gridColumns(widthDp: Int, hostCount: Int): Int = minOf(when {
			widthDp >= 840 -> 3
			widthDp >= 600 -> 2
			else -> 1
		}, hostCount.coerceAtLeast(1))

		private fun selectTab(tab: HomeTab)
		{
			currentTab = tab
			val cloud = tab == HomeTab.CLOUD
			binding.searchContainer.visibility = if(tab == HomeTab.SEARCH) View.VISIBLE else View.GONE
			binding.consolesHeader.visibility = if(cloud) View.GONE else View.VISIBLE
			binding.cloudInfoLayout.visibility = if(cloud) View.VISIBLE else View.GONE
			binding.hostsRecyclerView.visibility = if(cloud) View.GONE else View.VISIBLE
			for((button, selected) in listOf(
				binding.remoteTab to (tab == HomeTab.REMOTE),
				binding.searchTab to (tab == HomeTab.SEARCH),
				binding.cloudTab to cloud
			)) {
				button.isSelected = selected
				button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this,
					if(selected) R.color.portal_card_selected else R.color.portal_tab_track))
				button.setTextColor(ContextCompat.getColor(this,
					if(selected) R.color.portal_white else R.color.portal_muted))
			}
			refreshHosts()
		}

		private fun refreshHosts()
		{
			val query = if(currentTab == HomeTab.SEARCH)
				binding.hostSearchEditText.text?.toString()?.trim().orEmpty() else ""
			val matches = if(query.isEmpty()) allHosts else allHosts.filter { host ->
				host.name.orEmpty().contains(query, ignoreCase = true) ||
					host.host.contains(query, ignoreCase = true) ||
					host.id.orEmpty().contains(query, ignoreCase = true) ||
				((host as? DiscoveredDisplayHost)?.discoveredHost?.runningAppName
					.orEmpty().contains(query, ignoreCase = true))
			}
			hostsAdapter.hosts = matches
			val availableWidthDp =
				(binding.hostsRecyclerView.width / resources.displayMetrics.density).toInt()
			val layoutManager = binding.hostsRecyclerView.layoutManager as? GridLayoutManager
			if(availableWidthDp > 0 && layoutManager != null) {
				val target = gridColumns(availableWidthDp, matches.size)
				if(layoutManager.spanCount != target) layoutManager.spanCount = target
			}
			binding.consolesCountTextView.text = getString(R.string.portal_console_count, matches.size)
			updateEmptyInfo()
		}

	private fun updateEmptyInfo()
	{
			if(currentTab != HomeTab.CLOUD && hostsAdapter.hosts.isEmpty())
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
				if(currentTab == HomeTab.SEARCH && !binding.hostSearchEditText.text.isNullOrBlank()) {
					binding.emptyInfoImageView.setImageResource(R.drawable.ic_discover_off)
					binding.emptyInfoTextView.setText(R.string.portal_search_empty)
					return
				}
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}

		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

		/** Controller button mapping applies only to the home screen, never the stream. */
		override fun dispatchKeyEvent(event: KeyEvent): Boolean
		{
			if(event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0)
				return super.dispatchKeyEvent(event)
			when(event.keyCode)
			{
				KeyEvent.KEYCODE_BUTTON_L1 -> {
					selectTab(HomeTab.values()[(currentTab.ordinal + HomeTab.values().size - 1) % HomeTab.values().size])
					return true
				}
				KeyEvent.KEYCODE_BUTTON_R1 -> {
					selectTab(HomeTab.values()[(currentTab.ordinal + 1) % HomeTab.values().size])
					return true
				}
				KeyEvent.KEYCODE_BUTTON_X -> { showRegistration(); return true }
				KeyEvent.KEYCODE_BUTTON_Y -> { openSettings(); return true }
				KeyEvent.KEYCODE_BUTTON_B -> {
					when {
						binding.floatingActionButton.isExpanded -> expandFloatingActionButton(false)
						currentTab != HomeTab.REMOTE -> selectTab(HomeTab.REMOTE)
						else -> onBackPressedDispatcher.onBackPressed()
					}
					return true
				}
				KeyEvent.KEYCODE_BUTTON_A -> {
					val focused = currentFocus
					if(focused != null && (focused is EditText || focused.id == R.id.menuButton ||
						focused.id == R.id.remoteTab || focused.id == R.id.searchTab ||
						focused.id == R.id.cloudTab || focused.id == R.id.floatingActionButton ||
						focused.id == R.id.registerButton || focused.id == R.id.addManualButton)) {
						focused.performClick()
						return true
					}
					if(currentTab == HomeTab.CLOUD) return true
					val focusedCard = focused?.let { binding.hostsRecyclerView.findContainingItemView(it) }
					val focusedPosition = focusedCard?.let { binding.hostsRecyclerView.getChildAdapterPosition(it) }
					val host = if(focusedPosition != null && focusedPosition >= 0)
						hostsAdapter.hosts.getOrNull(focusedPosition)
					else hostsAdapter.hosts.firstOrNull()
					if(host != null) hostTriggered(host)
					return true
				}
			}
			return super.dispatchKeyEvent(event)
		}

	private fun expandFloatingActionButton(expand: Boolean)
	{
		binding.floatingActionButton.isExpanded = expand
		binding.floatingActionButton.isActivated = binding.floatingActionButton.isExpanded
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	private fun setupBackNavigation()
	{
		onBackPressedDispatcher.addCallback(this) {
			if(binding.floatingActionButton.isExpanded)
			{
				expandFloatingActionButton(false)
			}
			else
			{
					if(currentTab != HomeTab.REMOTE)
					{
						selectTab(HomeTab.REMOTE)
						return@addCallback
					}
				isEnabled = false
				onBackPressedDispatcher.onBackPressed()
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = viewModel.discoveryActive.value ?: false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_settings ->
		{
				openSettings()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

		private fun openSettings()
		{
			startActivity(Intent(this, SettingsActivity::class.java))
		}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addManualButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.registerButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect() {
				val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup) { _, _ ->
						wakeupHost(host)
					}
					.setNeutralButton(R.string.action_connect_immediately) { _, _ ->
						connect()
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.show()
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
