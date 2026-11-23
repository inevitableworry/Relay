// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Universal Android multi-display: secondary controls, retaining Android
 * Relay home UI on the primary display. Nothing is rendered over the video and
 * the lower panel does not request extra capture/overlay privileges.
 *
 * Used by the dualScreen and legacy aynThor flavors, without vendor APIs.
 * Requires the OS to expose an independently addressable second display.
 * If firmware presents one combined logical display, no panel is launched.
 */
internal class PortalThorSecondScreen(
    private val activity: PortalMainActivity,
    private val onRemote: () -> Unit,
    private val onCloud: () -> Unit,
    private val onSearch: () -> Unit,
    private val onQuickMenu: () -> Unit,
    private val onSettings: () -> Unit,
    private val onConnect: () -> Unit,
    private val onSearchText: (String) -> Unit
) : DisplayManager.DisplayListener {
    private val manager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var current: Presentation? = null
    private var currentId = Display.INVALID_DISPLAY
    private val clickOnce = mutableMapOf<String, Long>()

    fun start() {
        if (running) return
        running = true
        manager.registerDisplayListener(this, mainHandler)
        refresh()
    }

    fun stop() {
        if (!running) return
        running = false
        manager.unregisterDisplayListener(this)
        dismiss()
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()

    private fun dismiss() {
        try { current?.dismiss() } catch (_: Exception) { }
        current = null
        currentId = Display.INVALID_DISPLAY
    }

    private fun refresh() {
        if (!running || activity.isFinishing || activity.isDestroyed) return
        val extra = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }
            ?: manager.displays.firstOrNull {
                it.isValid && it.displayId != Display.DEFAULT_DISPLAY
            }
        if (extra == null) { dismiss(); return }
        if (currentId == extra.displayId && current?.isShowing == true) return
        dismiss()
        try {
            current = ThorPanel(extra).also { it.show() }
            currentId = extra.displayId
        } catch (_: WindowManager.InvalidDisplayException) {
            // Vendor firmware can remove display #2 during a mode change.
            dismiss()
        }
    }

    private inner class ThorPanel(display: Display) : Presentation(
        activity, display, android.R.style.Theme_Material_NoActionBar
    ) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            val dp = resources.displayMetrics.density
            val spacing = (14 * dp).toInt()
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(spacing, spacing, spacing, spacing)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(11, 12, 27), Color.rgb(35, 20, 77), Color.rgb(8, 9, 24))
                )
            }
            val header = TextView(context).apply {
                text = "RELAY  •  SECOND DISPLAY"
                setTextColor(Color.rgb(235, 230, 255))
                textSize = 18f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            root.addView(header)
            val description = TextView(context).apply {
                text = "Primary display: Remote Play and Portal UI\nSecondary display: independent controls"
                setTextColor(Color.rgb(197, 190, 230))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
            }
            root.addView(description)
            val scroll = ScrollView(context).apply { isFillViewport = true }
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun row(label: String, action: () -> Unit) {
                val button = Button(context).apply {
                    text = label
                    isAllCaps = false
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = 17 * dp
                        setColor(Color.argb(236, 51, 43, 93))
                        setStroke((1.3f * dp).toInt(), Color.rgb(133, 119, 195))
                    }
                    setOnClickListener {
                        // Limit controller/touch double-taps when focus is changing
                        val now = android.os.SystemClock.elapsedRealtime()
                        val previous = clickOnce[label] ?: 0L
                        if (now - previous > 240L) {
                            clickOnce[label] = now
                            activity.runOnUiThread { action() }
                        }
                    }
                }
                buttons.addView(button, LinearLayout.LayoutParams(-1, (64 * dp).toInt()).apply {
                    bottomMargin = (9 * dp).toInt()
                })
            }
            row("Connect to PS5", onConnect)
            row("Remote Play", onRemote)
            row("Cloud", onCloud)
            row("Search") {
                onSearch()
                showKeyboard(root)
            }
            row("Quick Menu", onQuickMenu)
            row("Settings", onSettings)
            scroll.addView(buttons)
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            setContentView(root)
        }

        private fun showKeyboard(root: LinearLayout) {
            val density = resources.displayMetrics.density
            fun dp(n: Int) = (n * density).toInt()
            val content = StringBuilder()
            var shifted = false
            var symbols = false
            val normalRows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
            val symbolRows = listOf("1234567890", "!@#$%^&*()", "-_=+[]{};", ".,?/\\:~")
            val edit = TextView(context).apply {
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 20, 35))
                    cornerRadius = dp(7).toFloat()
                }
            }
            val holder = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun update() {
                edit.text = content.toString() + "▏"
                onSearchText(content.toString())
            }
            fun key(c: String, action: () -> Unit): Button = Button(context).apply {
                text = c
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                minWidth = 0
                minHeight = 0
                minimumHeight = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(Color.rgb(48, 44, 83))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), Color.rgb(118, 110, 169))
                }
                setOnClickListener { action() }
            }
            fun addRow(keys: List<Pair<String, () -> Unit>>) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                holder.addView(row, LinearLayout.LayoutParams(-1, dp(39)).apply {
                    bottomMargin = dp(3)
                })
                keys.forEach { (label, action) ->
                    row.addView(key(label, action), LinearLayout.LayoutParams(0, -1, 1f).apply {
                        marginStart = dp(1)
                        marginEnd = dp(1)
                    })
                }
            }
            lateinit var drawKeys: () -> Unit
            drawKeys = {
                holder.removeAllViews()
                (if (symbols) symbolRows else normalRows).forEach { sequence ->
                    addRow(sequence.map { original ->
                        val character = if (!shifted && !symbols) original.lowercaseChar() else original
                        character.toString() to {
                            if (content.length < 120) {
                                content.append(character)
                                update()
                            }
                        }
                    })
                }
                addRow(listOf(
                    "a/A" to { shifted = !shifted; drawKeys() },
                    "@#" to { symbols = !symbols; drawKeys() },
                    "SPACE" to { if (content.length < 120) { content.append(' '); update() } },
                    "DEL" to { if (content.isNotEmpty()) { content.deleteCharAt(content.length-1); update() } },
                    "DONE" to { update(); dismiss(); refresh() }
                ))
            }
            root.removeAllViews()
            root.addView(TextView(context).apply {
                text = "RELAY • SEARCH"
                textSize = 17f
                setTextColor(Color.WHITE)
            })
            root.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(9) })
            root.addView(holder, LinearLayout.LayoutParams(-1, -2))
            root.addView(key("Back to Menu") { dismiss(); refresh() })
            drawKeys()
            update()
        }
    }
}
