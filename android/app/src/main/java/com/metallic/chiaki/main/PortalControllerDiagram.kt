// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.metallic.chiaki.session.GamepadButton
import kotlin.math.abs
import kotlin.math.min

/** Scalable, original PS5-style controller diagram with touchable annotations.
 * This is an editable vector control, not a static screenshot of Sony artwork.
 * Each label shows the actual Android input binding assigned to its action. */
class PortalControllerDiagram(context:Context):View(context) {
    data class Label(val button:GamepadButton,val x:Float,val y:Float,
                     val sourceX:Float,val sourceY:Float,val right:Boolean)
    private val face=Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE}
    private val labels=listOf(
        Label(GamepadButton.L2,.055f,.09f,.31f,.20f,false),
        Label(GamepadButton.L1,.055f,.19f,.34f,.26f,false),
        Label(GamepadButton.SHARE,.055f,.30f,.43f,.34f,false),
        Label(GamepadButton.UP,.055f,.41f,.29f,.46f,false),
        Label(GamepadButton.LEFT,.055f,.52f,.25f,.53f,false),
        Label(GamepadButton.DOWN,.055f,.63f,.29f,.60f,false),
        Label(GamepadButton.L3,.055f,.74f,.40f,.65f,false),
        Label(GamepadButton.PS,.055f,.85f,.50f,.78f,false),
        Label(GamepadButton.R2,.945f,.09f,.70f,.20f,true),
        Label(GamepadButton.R1,.945f,.19f,.68f,.26f,true),
        Label(GamepadButton.OPTIONS,.945f,.30f,.58f,.34f,true),
        Label(GamepadButton.TRIANGLE,.945f,.41f,.76f,.42f,true),
        Label(GamepadButton.CIRCLE,.945f,.52f,.81f,.50f,true),
        Label(GamepadButton.CROSS,.945f,.63f,.76f,.58f,true),
        Label(GamepadButton.SQUARE,.945f,.74f,.71f,.50f,true),
        Label(GamepadButton.R3,.945f,.85f,.60f,.65f,true),
        Label(GamepadButton.TOUCHPAD,.50f,.045f,.50f,.37f,true)
    )
    var selected:GamepadButton?=null
        set(value){field=value;invalidate()}
    var currentMapping:(GamepadButton)->String={"Auto"}
        set(value){field=value;invalidate()}
    var onSelect:((GamepadButton)->Unit)?=null
    init {
        minimumHeight=dp(370)
        contentDescription="PS5-style gamepad diagram with touchable mapping annotations"
        isClickable=true
    }
    private fun dp(n:Int)=(resources.displayMetrics.density*n+.5f).toInt()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        val ideal=(width*.52f).toInt().coerceIn(dp(300),dp(575))
        setMeasuredDimension(width,resolveSize(ideal,heightMeasureSpec))
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val layer=canvas.save()
        canvas.scale(width/1000f,height/520f)
        drawPad(canvas)
        face.typeface=PortalUi.font(context)
        face.textSize=15f
        for(label in labels) {
            val active=selected==label.button
            val x=label.x*1000f
            val y=label.y*520f
            val ax=label.sourceX*1000f
            val ay=label.sourceY*520f
            stroke.strokeWidth=if(active) 2.7f else 1.2f
            stroke.color=if(active) Color.WHITE else 0xa8c9c0d5.toInt()
            val elbow=if(label.right) x-110f else x+110f
            val route=Path().apply {moveTo(ax,ay);lineTo(elbow,y);lineTo(x,y)}
            canvas.drawPath(route,stroke)
            face.style=Paint.Style.FILL
            face.color=if(active) Color.WHITE else 0xffd9d4e4.toInt()
            canvas.drawCircle(ax,ay,if(active)5.5f else 3.5f,face)
            val line="${label.button.title}: ${currentMapping(label.button)}"
            val trimmed=if(line.length>25) line.take(23)+"…" else line
            face.textAlign=if(label.right) Paint.Align.RIGHT else Paint.Align.LEFT
            face.textSize=if(trimmed.length>21) 12f else 14f
            canvas.drawText(trimmed,x,y-6f,face)
        }
        canvas.restoreToCount(layer)
    }
    private fun drawPad(c:Canvas) {
        val body=Path().apply {
            moveTo(329f,147f)
            cubicTo(291f,139f,260f,161f,239f,189f)
            cubicTo(210f,229f,177f,354f,211f,396f)
            cubicTo(228f,416f,248f,400f,283f,363f)
            cubicTo(302f,345f,326f,331f,351f,333f)
            lineTo(649f,333f)
            cubicTo(683f,331f,709f,360f,745f,397f)
            cubicTo(783f,415f,802f,358f,794f,313f)
            cubicTo(785f,238f,752f,167f,699f,147f)
            cubicTo(665f,137f,637f,149f,607f,151f)
            cubicTo(555f,155f,445f,155f,393f,151f)
            cubicTo(370f,150f,350f,147f,329f,147f)
            close()
        }
        face.style=Paint.Style.FILL
        face.shader=LinearGradient(300f,130f,700f,402f,
            intArrayOf(0xffe9e8f2.toInt(),0xffb0aebf.toInt(),0xffe7e6ee.toInt()),
            null,Shader.TileMode.CLAMP)
        c.drawPath(body,face);face.shader=null
        stroke.color=0xff777486.toInt();stroke.strokeWidth=2.5f
        c.drawPath(body,stroke)
        face.color=0xff292836.toInt()
        c.drawRoundRect(RectF(335f,158f,665f,332f),30f,30f,face)
        face.color=0xff454258.toInt()
        c.drawRoundRect(RectF(422f,171f,578f,246f),12f,12f,face)
        stroke.color=0xffb4aacd.toInt();stroke.strokeWidth=2f
        c.drawRoundRect(RectF(422f,171f,578f,246f),12f,12f,stroke)
        face.color=0xff242332.toInt()
        c.drawCircle(396f,332f,49f,face)
        c.drawCircle(604f,332f,49f,face)
        stroke.color=0xff8a8996.toInt();stroke.strokeWidth=5f
        c.drawCircle(396f,332f,45f,stroke)
        c.drawCircle(604f,332f,45f,stroke)
        face.color=0xff575568.toInt()
        c.drawCircle(396f,332f,31f,face)
        c.drawCircle(604f,332f,31f,face)
        // D-pad geometry.
        face.color=0xff343343.toInt()
        c.drawRoundRect(RectF(285f,243f,313f,323f),6f,6f,face)
        c.drawRoundRect(RectF(259f,269f,339f,297f),6f,6f,face)
        // Face buttons, translucent colored symbols instead of product logos.
        face.style=Paint.Style.STROKE;face.strokeWidth=3.5f
        val spots=listOf(764f to 240f,794f to 270f,764f to 300f,734f to 270f)
        for((x,y) in spots) {
            face.color=0xff424050.toInt();face.style=Paint.Style.FILL
            c.drawCircle(x,y,15f,face)
            face.color=0xffd6cbec.toInt();face.style=Paint.Style.STROKE
            c.drawCircle(x,y,9f,face)
        }
        face.style=Paint.Style.FILL
        face.color=0xffe7e4f2.toInt()
        c.drawCircle(500f,346f,14f,face)
        face.color=0xff302d3b.toInt()
        c.drawCircle(500f,346f,7f,face)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.action==MotionEvent.ACTION_UP) {
            val x=event.x/width
            val y=event.y/height
            val hit=labels.filter { abs(it.y-y)<.065f && abs(it.x-x)<.30f }
                .minByOrNull {abs(it.y-y)+abs(it.x-x)}
            if(hit!=null) {performClick();onSelect?.invoke(hit.button)}
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
