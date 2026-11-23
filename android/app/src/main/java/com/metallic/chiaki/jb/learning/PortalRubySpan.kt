// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb.learning

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/** Word-wide ruby rendering for a dictionary-provided reading above its written form. */
class PortalRubySpan(private val reading: String) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val ruby = Paint(paint).apply { textSize *= 0.52f }
        val width = maxOf(paint.measureText(text, start, end), ruby.measureText(reading))
        if (fm != null) {
            val source = paint.fontMetricsInt
            val rubyHeight = ceil(ruby.fontMetrics.descent - ruby.fontMetrics.ascent + ruby.textSize * 0.1f).toInt()
            fm.ascent = source.ascent - rubyHeight
            fm.top = source.top - rubyHeight
            fm.descent = source.descent
            fm.bottom = source.bottom
        }
        return ceil(width).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val ruby = Paint(paint).apply { textSize *= 0.52f }
        val baseWidth = paint.measureText(text, start, end)
        val rubyWidth = ruby.measureText(reading)
        val width = maxOf(baseWidth, rubyWidth)
        canvas.drawText(text, start, end, x + (width - baseWidth) / 2f, y.toFloat(), paint)
        val rubyBaseline = y + paint.fontMetrics.ascent - ruby.fontMetrics.descent - ruby.textSize * 0.1f
        canvas.drawText(reading, x + (width - rubyWidth) / 2f, rubyBaseline, ruby)
    }
}
