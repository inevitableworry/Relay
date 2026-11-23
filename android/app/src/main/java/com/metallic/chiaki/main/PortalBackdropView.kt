// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural reconstruction studied against Sony's 2025 screenshots and the timestamped
 * footage documented in docs/research/portal-motion.md. No extracted animation is used.
 * Geometry stays circular on phones, tablets, foldables and multi-window surfaces.
 */
class PortalBackdropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val backgroundPaint = Paint()
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(.12f, -.06f, 1.85f,
            intArrayOf(0x002e1a91, 0x743323ba, 0x4a272367, Color.TRANSPARENT),
            floatArrayOf(0f, .49f, .70f, 1f), Shader.TileMode.CLAMP)
    }
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Sony's clean still keeps a dark body, but its outer blue level blends into
        // the surrounding haze. APK15 had a 31-level blue drop across the edge.
        shader = RadialGradient(0f, 0f, 1.018f,
            intArrayOf(0xff170e3e.toInt(), 0xff160e38.toInt(), 0xff1b1248.toInt(), 0x001b1248),
            floatArrayOf(0f, .72f, .968f, 1f), Shader.TileMode.CLAMP)
    }
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(0x344d4eaa, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(0x653c20ff, 0x29240be8, Color.TRANSPARENT),
            floatArrayOf(0f, .36f, 1f), Shader.TileMode.CLAMP)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f,
            intArrayOf(0xffecf0ff.toInt(), 0xffbfcaff.toInt(), 0xff6675ff.toInt(),
                0x802f25ff.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .08f, .19f, .38f, 1f), Shader.TileMode.CLAMP)
    }
    private val sphereClip = Path().apply { addCircle(0f, 0f, 1f, Path.Direction.CW) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringRect = RectF()
    private val particleCount = 2600
    private val clusterCount = 19
    private val clusterX = FloatArray(clusterCount)
    private val clusterY = FloatArray(clusterCount)
    private val clusterZ = FloatArray(clusterCount)
    private val clusterSpread = FloatArray(clusterCount)
    private val px = FloatArray(particleCount)
    private val py = FloatArray(particleCount)
    private val pz = FloatArray(particleCount)
    private val phases = FloatArray(particleCount)
    private val strengths = FloatArray(particleCount)
    private val points = Array(8) { FloatArray(particleCount * 2) }
    private val pointCounts = IntArray(8)
    private val glowX = FloatArray(88)
    private val glowY = FloatArray(88)
    private val glowStrength = FloatArray(88)
    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var attached = false
    private var running = false
    private var systemMotionEnabled = true
    private var elapsedNanos = 0L
    private var lastFrameNanos = 0L
    private var connecting = false
    private var connectionScene = false
    private var connectionStart = 0f
    private var defocused = false

    var reduceMotion: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateMotion()
            invalidate()
        }

    private val motionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            systemMotionEnabled = PortalMotion.enabled(context)
            updateMotion()
            invalidate()
        }
    }
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!canAnimate()) {
                running = false
                lastFrameNanos = 0L
                return
            }
            if (lastFrameNanos != 0L) {
                elapsedNanos += (frameTimeNanos - lastFrameNanos).coerceIn(0L, 100_000_000L)
            }
            lastFrameNanos = frameTimeNanos
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // Fixed seed: reproducible visual QA, with clusters plus a sparse background population.
        val random = Random(0x504f5254414cL)
        for (i in 0 until clusterCount) {
            val latitude = -.91f + i * 1.82f / (clusterCount - 1)
            val angle = i * 2.39996f + random.nextFloat() * .45f
            val circle = sqrt(1f - latitude * latitude)
            clusterX[i] = cos(angle) * circle
            clusterY[i] = latitude
            clusterZ[i] = sin(angle) * circle
            clusterSpread[i] = .055f + random.nextFloat() * .105f
        }
        for (i in 0 until particleCount) {
            val cluster = i % clusterCount
            val spread = clusterSpread[cluster]
            val x: Float
            val y: Float
            val z: Float
            if (i % 11 == 0) {
                val latitude = random.nextFloat() * 2f - 1f
                val angle = random.nextFloat() * 2f * PI.toFloat()
                val circle = sqrt(1f - latitude * latitude)
                x = cos(angle) * circle
                y = latitude
                z = sin(angle) * circle
            } else {
                // Unequal, elongated clusters reproduce the observed nebula-like structure.
                x = clusterX[cluster] + random.nextGaussian().toFloat() * spread
                y = clusterY[cluster] + random.nextGaussian().toFloat() * spread * 1.8f
                z = clusterZ[cluster] + random.nextGaussian().toFloat() * spread
            }
            val shell = .87f + random.nextFloat() * .12f
            val length = sqrt(x * x + y * y + z * z)
            px[i] = x / length * shell
            py[i] = y / length * shell
            pz[i] = z / length * shell
            phases[i] = random.nextFloat() * 2f * PI.toFloat()
            strengths[i] = .42f + random.nextFloat() * .58f
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Outward ring observed at 02:56.283 in Tech Mark's November 2025 connection footage. */
    fun setConnecting(value: Boolean) {
        if (connecting == value) return
        connecting = value
        if (value) connectionScene = true
        connectionStart = elapsedNanos / 1_000_000_000f
        invalidate()
    }

    fun setDefocused(value: Boolean) {
        if (defocused == value) return
        defocused = value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRenderEffect(if (value) RenderEffect.createBlurEffect(
                8f * resources.displayMetrics.density, 8f * resources.displayMetrics.density,
                Shader.TileMode.CLAMP) else null)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        systemMotionEnabled = PortalMotion.enabled(context)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver)
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        attached = false
        updateMotion()
        context.contentResolver.unregisterContentObserver(motionObserver)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (attached) updateMotion()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (attached) updateMotion()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (attached) updateMotion()
    }

    private fun canAnimate() = attached && isShown && windowVisibility == VISIBLE &&
        hasWindowFocus() && systemMotionEnabled && !reduceMotion

    private fun updateMotion() {
        val animate = canAnimate()
        if (animate == running) return
        running = animate
        lastFrameNanos = 0L
        if (animate) Choreographer.getInstance().postFrameCallback(frameCallback)
        else Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        centerX = w * .5f
        centerY = h * .5f
        baseRadius = min(h * .3315f, w * .40f)
        backgroundPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xff030307.toInt(), 0xff1a1a22.toInt(), 0xff23232b.toInt()),
            floatArrayOf(0f, .67f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseRadius <= 0f) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val motion = systemMotionEnabled && !reduceMotion
        val time = if (motion) elapsedNanos / 1_000_000_000f else 12f
        val growth = if (connectionScene) 1f else ((time - .1f) / .9f).coerceIn(0f, 1f)
        val easedGrowth = 1f - (1f - growth) * (1f - growth)
        val radius = baseRadius * (.04f + .96f * easedGrowth)
        val introRim = if (motion && !connectionScene) ((2.7f - time) / 1.8f).coerceIn(0f, 1f) else 0f
        val softness = if (defocused) .65f else 1f

        canvas.save()
        canvas.translate(centerX, height * .916f)
        canvas.scale(baseRadius * 1.40f, baseRadius * .085f)
        canvas.drawCircle(0f, 0f, 1f, floorPaint)
        canvas.restore()
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(radius, radius)
        haloPaint.alpha = (215 + introRim * 40).toInt()
        canvas.drawCircle(0f, 0f, 1.85f, haloPaint)
        spherePaint.alpha = (255 * growth).toInt()
        canvas.drawCircle(0f, 0f, 1.018f, spherePaint)
        canvas.restore()

        drawClouds(canvas, radius, time, growth * softness)
        drawParticles(canvas, radius, time, growth * softness)
        if (introRim > 0f) drawRing(canvas, radius, time * 570f - 90f,
            (growth * 430f).coerceAtMost(360f), introRim)
        if (connecting && motion) {
            val phase = time - connectionStart
            // The visible ring expands beyond the sphere and disappears; the next frames
            // show only ambient haze. No repeating partial arc was observed. Its start is
            // hidden by a camera cut, so this 850 ms entry curve is an explicit approximation.
            val pulse = (phase / .85f).coerceIn(0f, 1f)
            if (pulse < 1f) {
                val remaining = 1f - pulse
                val ringRadius = baseRadius * (1f + .88f * (1f - remaining * remaining))
                drawRing(canvas, ringRadius, -90f, 360f, .65f * remaining * remaining)
            }
        }
    }

    private fun drawClouds(canvas: Canvas, radius: Float, time: Float, visibility: Float) {
        val c = cos(time * .075f)
        val s = sin(time * .075f)
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(radius, radius)
        canvas.clipPath(sphereClip)
        for (i in 0 until clusterCount) {
            val x = clusterX[i] * c + clusterZ[i] * s
            val depth = -clusterX[i] * s + clusterZ[i] * c
            val y = clusterY[i]
            val spread = .12f + clusterSpread[i] * 1.45f
            cloudPaint.alpha = ((40f + (depth + 1f) * 34f) * visibility).toInt()
            canvas.save()
            canvas.translate((x * .974f + y * .225f) * .94f,
                (y * .974f - x * .225f) * .94f)
            canvas.scale(spread, spread * 1.6f)
            canvas.drawCircle(0f, 0f, 1f, cloudPaint)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas, radius: Float, time: Float, visibility: Float) {
        pointCounts.fill(0)
        val angle = time * .075f
        val c = cos(angle)
        val s = sin(angle)
        var glows = 0
        for (i in 0 until particleCount) {
            // Longitude rotation, local latitude drift and individual twinkle keep the clusters alive.
            val x = px[i] * c + pz[i] * s
            val depth = -px[i] * s + pz[i] * c
            val y = py[i] + .014f * sin(time * .55f + phases[i]) * (1f - py[i] * py[i])
            val screenX = centerX + (x * .974f + y * .225f) * radius
            val screenY = centerY + (y * .974f - x * .225f) * radius
            val twinkle = .81f + .19f * sin(time * 1.3f + phases[i])
            val strength = strengths[i] * (.52f + .48f * (depth + 1f) * .5f) * twinkle
            val bin = (strength * 7.99f).toInt().coerceIn(0, 7)
            val count = pointCounts[bin]
            points[bin][count] = screenX
            points[bin][count + 1] = screenY
            pointCounts[bin] = count + 2
            if (i % 31 == 0 && glows < glowX.size && depth > -.4f) {
                glowX[glows] = screenX
                glowY[glows] = screenY
                glowStrength[glows] = strength
                glows++
            }
        }
        val scale = radius / 358f
        // Nested, faint widths approximate optical bloom without a hard-edged halo disk.
        // The APK14 comparison showed sufficient density but overly sharp uniform dots;
        // Sony's reference has dim blue point clouds and a few much brighter white-blue peaks.
        // Every pass reuses the binned coordinates; no per-frame blur surfaces or bitmaps.
        for (bin in 2..7) {
            pointPaint.color = Color.argb(((2f + bin * .6f) * visibility).toInt(), 30, 18, 255)
            pointPaint.strokeWidth = (10f + bin * 1.4f) * scale
            canvas.drawPoints(points[bin], 0, pointCounts[bin], pointPaint)
            pointPaint.color = Color.argb(((5f + bin * 1.5f) * visibility).toInt(), 30, 18, 255)
            pointPaint.strokeWidth = (5.5f + bin * .9f) * scale
            canvas.drawPoints(points[bin], 0, pointCounts[bin], pointPaint)
            pointPaint.color = Color.argb(((8f + bin * 2f) * visibility).toInt(), 30, 18, 255)
            pointPaint.strokeWidth = (2.6f + bin * .45f) * scale
            canvas.drawPoints(points[bin], 0, pointCounts[bin], pointPaint)
        }
        for (bin in 0..7) {
            pointPaint.color = Color.argb(((48 + bin * 25) * visibility).toInt(),
                20 + bin * 3, 22 + bin * 3, 255)
            pointPaint.strokeWidth = (1.05f + bin * .22f) * scale
            canvas.drawPoints(points[bin], 0, pointCounts[bin], pointPaint)
        }
        for (i in 0 until glows) {
            val strength = glowStrength[i]
            val dot = (6f + strength * 11f) * scale
            glowPaint.alpha = ((190f + 65f * strength) * visibility).toInt()
            canvas.save()
            canvas.translate(glowX[i], glowY[i])
            canvas.scale(dot, dot)
            canvas.drawCircle(0f, 0f, 1f, glowPaint)
            canvas.restore()
        }
    }

    private fun drawRing(canvas: Canvas, radius: Float, start: Float, sweep: Float, opacity: Float) {
        ringRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        ringPaint.strokeWidth = radius * .048f
        ringPaint.color = Color.argb((45 * opacity).toInt(), 108, 50, 255)
        canvas.drawArc(ringRect, start, sweep, false, ringPaint)
        ringPaint.strokeWidth = radius * .018f
        ringPaint.color = Color.argb((120 * opacity).toInt(), 151, 100, 255)
        canvas.drawArc(ringRect, start, sweep, false, ringPaint)
        ringPaint.strokeWidth = radius * .005f
        ringPaint.color = Color.argb((245 * opacity).toInt(), 223, 225, 255)
        canvas.drawArc(ringRect, start, sweep, false, ringPaint)
    }
}
