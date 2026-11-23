// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Relay-native, controller-focusable PlayStation-inspired keyboard.
 * This is an independent implementation, not firmware artwork or a claim of
 * pixel-perfect PlayStation Portal keyboard reproduction.
 */
internal object PortalOnScreenKeyboard {
    private val rows = listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
    private val symbolRows = listOf("1234567890", "!@#$%^&*()", "-_=+[]{};", ".,?/\\:~")

    fun show(context: Context, initial: String, onText: (String) -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun panel(color: Int, radius: Int = 9) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), 0xff514c69.toInt())
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(15))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff121526.toInt(), 0xff202036.toInt(), 0xff0d1020.toInt())
            ).apply { cornerRadius = dp(17).toFloat() }
        }
        val title = TextView(context).apply {
            text = "Enter text"
            textSize = 17f
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, 0, dp(9))
        }
        root.addView(title)
        val text = StringBuilder(initial)
        val entry = TextView(context).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            background = panel(0xff090e19.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(48)
        }
        root.addView(entry, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        var upper = false
        var symbols = false
        val keyContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyContainer)
        fun update() {
            entry.text = text.toString() + "▏"
            onText(text.toString())
        }
        fun button(label: String, action: () -> Unit): Button = Button(context).apply {
            this.text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(0xfff2f0fa.toInt())
            background = panel(0xff303344.toInt(), 6)
            isFocusable = true
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }
        fun addRow(keys: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            keyContainer.addView(row, LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(5) })
            keys.forEach { (name, action) ->
                row.addView(button(name, action), LinearLayout.LayoutParams(0, -1, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                })
            }
        }
        fun write(c: Char) { if(text.length < 120) { text.append(c); update() } }
        lateinit var render: () -> Unit
        render = {
            keyContainer.removeAllViews()
            (if(symbols) symbolRows else rows).forEach { sequence ->
                addRow(sequence.map { ch ->
                    val letter = if(!symbols && !upper) ch.lowercaseChar() else ch
                    letter.toString() to { write(letter) }
                })
            }
            addRow(listOf(
                "a/A" to { upper = !upper; render() },
                "@#" to { symbols = !symbols; render() },
                "SPACE" to { write(' ') },
                "⌫" to { if(text.isNotEmpty()) { text.deleteCharAt(text.length - 1); update() } },
                "Done" to { update(); dialog.dismiss() }
            ))
        }
        render()
        entry.text = text.toString() + "▏"
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout((context.resources.displayMetrics.widthPixels * .94f).toInt().coerceAtMost(dp(690)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        }
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * .94f).toInt().coerceAtMost(dp(690)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
