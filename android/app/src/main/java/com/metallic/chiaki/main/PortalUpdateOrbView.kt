// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Original Relay circular update indicator; no Sony image or video frames. */
internal class PortalUpdateOrbView(context:Context):View(context) {
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap=Paint.Cap.ROUND
        strokeJoin=Paint.Join.ROUND
    }
    private var state=PortalUpdateFlow.Stage.READY
    private var completed=0
    private var spin=0f
    private var animator:ValueAnimator?=null

    fun setStage(stage:PortalUpdateFlow.Stage,percent:Int) {
        completed=percent.coerceIn(0,100)
        if(state!=stage) {
            state=stage
            when(stage) {
                PortalUpdateFlow.Stage.CHECKING,
                PortalUpdateFlow.Stage.DOWNLOADING,
                PortalUpdateFlow.Stage.VERIFYING,
                PortalUpdateFlow.Stage.INSTALLING,
                PortalUpdateFlow.Stage.RESTARTING -> start()
                else -> stop()
            }
        }
        invalidate()
    }
    private fun start() {
        stop()
        if(!PortalMotion.enabled(context)) return
        animator=ValueAnimator.ofFloat(0f,360f).apply {
            duration=1900
            repeatCount=ValueAnimator.INFINITE
            interpolator=LinearInterpolator()
            addUpdateListener {spin=it.animatedValue as Float;invalidate()}
            start()
        }
    }
    private fun stop() {animator?.cancel();animator=null;spin=0f}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f
        val cy=height/2f
        val r=min(width,height)*.275f
        val stroke=resources.displayMetrics.density*2.2f
        pen.style=Paint.Style.STROKE
        pen.strokeWidth=stroke
        pen.color=0x528b81a3
        canvas.drawCircle(cx,cy,r,pen)
        pen.color=0xffd9d3ee.toInt()
        val bounds=RectF(cx-r,cy-r,cx+r,cy+r)
        when(state) {
            PortalUpdateFlow.Stage.READY -> canvas.drawArc(bounds,-90f,105f,false,pen)
            PortalUpdateFlow.Stage.COMPLETE -> {
                pen.strokeWidth=stroke*1.6f
                pen.color=Color.WHITE
                canvas.drawCircle(cx,cy,r,pen)
                canvas.drawLine(cx-r*.45f,cy,cx-r*.08f,cy+r*.35f,pen)
                canvas.drawLine(cx-r*.08f,cy+r*.35f,cx+r*.57f,cy-r*.44f,pen)
            }
            PortalUpdateFlow.Stage.CANCELLED -> canvas.drawArc(bounds,-90f,90f,false,pen)
            else -> {
                val sweep=55f+completed*.8f
                canvas.drawArc(bounds,spin-90f,sweep,false,pen)
                pen.strokeWidth=stroke*2.8f
                pen.color=0x658c82fa
                val outer=RectF(cx-r-stroke*2,cy-r-stroke*2,cx+r+stroke*2,cy+r+stroke*2)
                canvas.drawArc(outer,spin-115f,32f,false,pen)
            }
        }
    }
    override fun onDetachedFromWindow() {stop();super.onDetachedFromWindow()}
}
