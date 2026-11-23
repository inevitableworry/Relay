// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.PortalPreferences
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared native Portal surfaces. Text and focus stay accessible at every window size. */
object PortalUi {
    const val WHITE = -0x1
    val MUTED = Color.rgb(194,191,201)
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    // SST is Sony's licensed product typeface. Inter (SIL OFL 1.1) is the
    // closest available distributable fallback for the Portal-like type
    // hierarchy; CJK/Arabic use Android's normal system glyph fallbacks.
    @Volatile private var baseFont:Typeface?=null
    fun font(context:Context,weight:Int=400):Typeface {
        val family=baseFont ?: synchronized(this) {
            baseFont ?: runCatching {
                Typeface.createFromAsset(context.applicationContext.assets,
                    "fonts/inter-variable.ttf")
            }.getOrElse {Typeface.create("sans-serif",Typeface.NORMAL)}
                .also {baseFont=it}
        }
        return if(Build.VERSION.SDK_INT>=28)
            Typeface.create(family,weight.coerceIn(100,900),false)
        else Typeface.create(family,if(weight>=600) Typeface.BOLD else Typeface.NORMAL)
    }
    fun fullscreen(activity: AppCompatActivity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = PortalPreferences(activity).brightness
        }
    }
    fun background(): Drawable = object : Drawable() {
        private val base=Paint()
        private val glow=Paint(Paint.ANTI_ALIAS_FLAG)
        private val footer=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onBoundsChange(bounds:Rect) {
            val w=bounds.width().toFloat().coerceAtLeast(1f)
            val h=bounds.height().toFloat().coerceAtLeast(1f)
            base.shader=LinearGradient(0f,0f,0f,h,
                intArrayOf(0xff414045.toInt(),0xff4a4454.toInt(),0xff574f64.toInt()),null,Shader.TileMode.CLAMP)
            glow.shader=RadialGradient(w*.51f,h*.94f,w*.69f,
                intArrayOf(0x356b5a85,Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            // The 2026 Portal Settings reference has a soft lavender footer
            // band that lifts near the bottom and fades at the screen edge.
            footer.shader=LinearGradient(0f,h*.84f,0f,h,
                intArrayOf(Color.TRANSPARENT,0x41847c9b,0x17847c9b),
                floatArrayOf(0f,.69f,1f),Shader.TileMode.CLAMP)
        }
        override fun draw(canvas:Canvas) {canvas.drawRect(bounds,base);canvas.drawRect(bounds,glow);canvas.drawRect(bounds,footer)}
        override fun setAlpha(alpha:Int) {base.alpha=alpha;glow.alpha=alpha;footer.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?) {
            base.colorFilter=filter;glow.colorFilter=filter;footer.colorFilter=filter;invalidateSelf()
        }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
    }
    fun surface(context: Context, radius: Int = 12): Drawable {
        fun shape(fill: Int, stroke: Boolean = false) = GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(context,radius).toFloat()
            if(stroke) setStroke(dp(context,2), WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0x728f839f.toInt(), true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x818f839f.toInt(), true))
            addState(intArrayOf(android.R.attr.state_selected), shape(0x728f839f.toInt(), true))
            addState(intArrayOf(), shape(0x2d7e7187))
        }
    }
    fun tileSurface(context:Context):Drawable {
        val display=context.resources.displayMetrics
        val scale=min(display.widthPixels/1920f,display.heightPixels/1080f)
        fun shape(focused:Boolean)=GradientDrawable().apply {
            setColor(if(focused) 0x3ac2b9cf else 0x169c93ae)
            cornerRadius=maxOf(dp(context,6).toFloat(),12f*scale)
            setStroke(maxOf(dp(context,1),((if(focused) 3.5f else 2f)*scale).roundToInt()),
                if(focused) WHITE else 0x589e96a8)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),shape(true))
            addState(intArrayOf(android.R.attr.state_pressed),shape(true))
            addState(intArrayOf(),shape(false))
        }
    }
    fun text(context: Context, value: String, size: Float = 18f, muted: Boolean = false) =
        TextView(context).apply {
            text = value; textSize = size; setTextColor(if(muted) MUTED else WHITE)
            typeface = font(context)
        }
    fun focus(view: View) {
        view.isFocusable = true
        view.isSoundEffectsEnabled = false
        // Android's default square highlight obscures Portal's rounded
        // focus borders, especially the circular Settings Back control.
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)
            view.defaultFocusHighlightEnabled=false
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            PortalMotion.focus(v,hasFocus)
            if(hasFocus) PortalMenuAudio.play(v.context,PortalMenuAudio.Cue.FOCUS)
        }
    }
    fun row(context: Context, title: String, summary: String = "", value: String = "",
            icon: Icon? = null, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = surface(context); minimumHeight = dp(context,66)
            setPadding(dp(context,20), dp(context,14), dp(context,18), dp(context,14))
            isClickable = true; focus(this)
            setOnClickListener {
                PortalMotion.shine(this)
                PortalMenuAudio.play(context,PortalMenuAudio.Cue.CONFIRM);onClick()
            }
        }
        if(icon != null) row.addView(ImageView(context).apply {
            setImageDrawable(Glyph(icon)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context,30),dp(context,30)).apply { marginEnd=dp(context,18) })
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(context,title,19f))
            if(summary.isNotEmpty()) addView(text(context,summary,13f,true).apply {
                setPadding(0,dp(context,4),dp(context,10),0)
            })
        },LinearLayout.LayoutParams(0,-2,1f))
        if(value.isNotEmpty()) row.addView(text(context,value,16f,true).apply {
            gravity = Gravity.END; maxLines = 2
        },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(context,12) })
        row.addView(ImageView(context).apply { setImageDrawable(Glyph(Icon.NEXT)) },
            LinearLayout.LayoutParams(dp(context,16),dp(context,20)).apply { marginStart=dp(context,12) })
        row.contentDescription = listOf(title,summary,value).filter { it.isNotEmpty() }.joinToString(", ")
        return row
    }
    fun toggle(context: Context,title: String,summary: String,checked: Boolean,
               onChange: (Boolean)->Unit): LinearLayout {
        lateinit var control: SwitchCompat
        val row = row(context,title,summary,onClick={control.isChecked = !control.isChecked})
        row.removeViewAt(row.childCount-1)
        control = SwitchCompat(context).apply {
            isChecked = checked; isFocusable = false
            isSoundEffectsEnabled = false
            contentDescription = title
            thumbTintList = ColorStateList.valueOf(WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),
                intArrayOf(0xffa8a2bf.toInt(),0xff706b78.toInt()))
            setOnCheckedChangeListener { _, state ->
                PortalMenuAudio.play(context,PortalMenuAudio.Cue.CONFIRM);onChange(state)
            }
        }
        row.addView(control,LinearLayout.LayoutParams(-2,-2))
        return row
    }
    fun primary(context: Context, title: String, action: () -> Unit) = TextView(context).apply {
        text=title; textSize=17f; gravity=Gravity.CENTER; setTextColor(Color.BLACK)
        setPadding(dp(context,26),dp(context,13),dp(context,26),dp(context,13))
        background=GradientDrawable().apply { setColor(WHITE); cornerRadius=dp(context,60).toFloat() }
        isClickable=true; focus(this); setOnClickListener {
            PortalMotion.shine(this)
            PortalMenuAudio.play(context,PortalMenuAudio.Cue.CONFIRM);action()
        }
    }
    enum class Icon { BACK,NEXT,NETWORK,SYSTEM,DISPLAY,SCREEN,CONTROLLER,AUDIO,PS_LINK,CLOUD,REMOTE,GALLERY,RECORD,CONTROL_CENTER,HOME,CLOSE }
    /** Compact filled silhouettes used by the measured 3×2 Settings grid, separate from row icons. */
    class SettingsGlyph(private val icon:Icon):Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var drawableAlpha=255
        override fun draw(canvas:Canvas) {
            canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
            canvas.scale(bounds.width()/64f,bounds.height()/64f)
            paint.color=WHITE;paint.alpha=drawableAlpha;paint.style=Paint.Style.FILL
            paint.strokeJoin=Paint.Join.ROUND;paint.strokeCap=Paint.Cap.ROUND
            fun polygon(vararg points:Float) {
                canvas.drawPath(Path().apply {moveTo(points[0],points[1])
                    for(index in 2 until points.size step 2) lineTo(points[index],points[index+1]);close()},paint)
            }
            when(icon) {
                Icon.NETWORK -> {
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=5.5f
                    canvas.drawCircle(32f,32f,25.5f,paint)
                    canvas.drawOval(21f,6.5f,43f,57.5f,paint)
                    canvas.drawLine(7f,32f,57f,32f,paint)
                    canvas.drawArc(8f,15f,56f,31f,180f,180f,false,paint)
                    canvas.drawArc(8f,33f,56f,49f,0f,180f,false,paint)
                }
                Icon.SYSTEM -> {
                    polygon(32f,5f,59f,20f,32f,35f,5f,20f)
                    polygon(5f,23f,30.5f,37f,30.5f,61f,5f,47f)
                    polygon(33.5f,37f,59f,23f,59f,47f,33.5f,61f)
                }
                Icon.SCREEN,Icon.DISPLAY -> {
                    val shape=Path().apply {
                        fillType=Path.FillType.EVEN_ODD
                        addRoundRect(4f,15f,60f,49f,3f,3f,Path.Direction.CW)
                        addRect(11f,22f,53f,42f,Path.Direction.CW)
                    }
                    canvas.drawPath(shape,paint)
                }
                Icon.CONTROLLER -> canvas.drawPath(Path().apply {
                    moveTo(17f,18f);cubicTo(8f,16f,7f,24f,4f,44f)
                    cubicTo(2f,57f,13f,56f,20f,44f);lineTo(44f,44f)
                    cubicTo(51f,56f,62f,57f,60f,44f);cubicTo(57f,24f,56f,16f,47f,18f)
                    cubicTo(39f,22f,25f,22f,17f,18f);close()
                },paint)
                Icon.AUDIO -> {
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=7f
                    canvas.drawArc(12f,8f,52f,53f,180f,180f,false,paint)
                    paint.style=Paint.Style.FILL
                    canvas.drawRoundRect(8f,29f,20f,54f,5f,5f,paint)
                    canvas.drawRoundRect(44f,29f,56f,54f,5f,5f,paint)
                }
                Icon.PS_LINK -> {
                    // Single earpiece and short wireless bars in the Portal reference.
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=8.5f
                    canvas.drawPath(Path().apply {
                        moveTo(12f,31f);cubicTo(11f,12f,23f,8f,34f,11f)
                        cubicTo(47f,15f,49f,30f,41f,38f)
                        cubicTo(34f,44f,30f,46f,31f,53f)
                    },paint)
                    paint.style=Paint.Style.FILL
                    canvas.drawCircle(32f,55f,5f,paint)
                    canvas.save();canvas.rotate(-30f,49f,46f)
                    canvas.drawRoundRect(43f,38f,59f,43f,2f,2f,paint)
                    canvas.drawRoundRect(40f,47f,61f,52f,2f,2f,paint)
                    canvas.drawRoundRect(37f,56f,52f,61f,2f,2f,paint)
                    canvas.restore()
                }
                Icon.CLOUD -> {
                    // Cloud with controller cutout and the small yellow Plus mark.
                    canvas.drawPath(Path().apply {
                        moveTo(12f,48f);cubicTo(4f,47f,4f,37f,11f,33f)
                        cubicTo(14f,31f,17f,31f,21f,32f)
                        cubicTo(24f,19f,40f,18f,47f,29f)
                        cubicTo(57f,29f,61f,36f,57f,43f)
                        cubicTo(55f,48f,47f,49f,40f,49f);close()
                    },paint)
                    paint.color=0xff3b3644.toInt()
                    canvas.drawRoundRect(23f,35f,43f,46f,5f,5f,paint)
                    paint.color=WHITE
                    paint.strokeWidth=2.8f;paint.style=Paint.Style.STROKE
                    canvas.drawLine(26f,40.5f,32f,40.5f,paint)
                    canvas.drawLine(29f,37.5f,29f,43.5f,paint)
                    canvas.drawCircle(37f,39f,0.8f,paint)
                    canvas.drawCircle(40f,42f,0.8f,paint)
                    paint.style=Paint.Style.FILL
                    paint.color=0xfff5d54d.toInt()
                    canvas.drawRoundRect(46f,40f,53f,62f,2f,2f,paint)
                    canvas.drawRoundRect(38.5f,47.5f,60.5f,54.5f,2f,2f,paint)
                }
                Icon.REMOTE -> {
                    polygon(21f,6f,30f,9f,28f,52f,18f,55f)
                    polygon(33f,9f,42f,6f,40f,51f,31f,52f)
                    canvas.drawPath(Path().apply {moveTo(43f,43f);quadTo(35f,43f,35f,57f)
                        quadTo(38f,63f,43f,54f);lineTo(52f,54f);quadTo(59f,63f,60f,56f)
                        quadTo(59f,43f,52f,43f);close()},paint)
                    canvas.drawCircle(5f,32f,2.5f,paint);canvas.drawCircle(12f,32f,2.5f,paint)
                }
                else -> Glyph(icon).apply {setBounds(4,4,60,60);draw(canvas)}
            }
            canvas.restore()
        }
        override fun setAlpha(alpha:Int) {drawableAlpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter:ColorFilter?) {paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    class Glyph(private val icon: Icon): Drawable() {
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=WHITE; style=Paint.Style.STROKE; strokeWidth=1.8f
            strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
        }
        override fun draw(c: Canvas) {
            c.save(); c.translate(bounds.left.toFloat(),bounds.top.toFloat())
            c.scale(bounds.width()/32f,bounds.height()/32f)
            when(icon) {
                Icon.BACK, Icon.NEXT -> {
                    val direction=if(icon==Icon.BACK) -1f else 1f
                    c.drawLine(16f-direction*3,8f,16f+direction*4,16f,p)
                    c.drawLine(16f+direction*4,16f,16f-direction*3,24f,p)
                }
                Icon.NETWORK -> {c.drawCircle(16f,16f,11f,p)
                    c.drawOval(11f,5f,21f,27f,p);c.drawLine(5f,16f,27f,16f,p)
                    c.drawArc(6f,7f,26f,15f,0f,180f,false,p)
                    c.drawArc(6f,17f,26f,25f,180f,180f,false,p)}
                Icon.DISPLAY -> { c.drawCircle(16f,16f,6f,p)
                    for(i in 0..7) { c.save();c.rotate(i*45f,16f,16f)
                        c.drawLine(16f,3f,16f,6f,p);c.restore() } }
                Icon.SCREEN -> {p.strokeWidth=3.8f;c.drawRoundRect(3f,7f,29f,25f,1f,1f,p);p.strokeWidth=1.8f}
                Icon.CONTROLLER, Icon.REMOTE -> { val path=Path().apply {
                    moveTo(9f,10f);cubicTo(4f,10f,2f,23f,6f,24f)
                    cubicTo(9f,25f,11f,20f,13f,20f);lineTo(19f,20f)
                    cubicTo(21f,20f,23f,25f,26f,24f);cubicTo(30f,23f,28f,10f,23f,10f)
                    close() };c.drawPath(path,p);c.drawLine(8f,15f,14f,15f,p)
                    c.drawLine(11f,12f,11f,18f,p);c.drawCircle(23f,14f,.7f,p);c.drawCircle(21f,17f,.7f,p) }
                Icon.AUDIO -> { c.drawArc(6f,4f,26f,26f,180f,180f,false,p)
                    c.drawRoundRect(5f,15f,10f,26f,2f,2f,p);c.drawRoundRect(22f,15f,27f,26f,2f,2f,p) }
                Icon.PS_LINK -> {c.drawArc(6f,4f,24f,27f,180f,215f,false,p)
                    c.drawLine(21f,21f,28f,17f,p);c.drawLine(21f,26f,29f,22f,p)}
                Icon.CLOUD -> {c.drawArc(5f,12f,27f,27f,185f,170f,false,p)
                    c.drawLine(6f,24f,23f,24f,p);c.drawLine(25f,21f,25f,29f,p)
                    c.drawLine(21f,25f,29f,25f,p)}
                Icon.SYSTEM -> {val cube=Path().apply {
                    moveTo(16f,3f);lineTo(28f,10f);lineTo(28f,23f);lineTo(16f,30f)
                    lineTo(4f,23f);lineTo(4f,10f);close()
                };c.drawPath(cube,p);c.drawLine(4f,10f,16f,17f,p)
                    c.drawLine(28f,10f,16f,17f,p);c.drawLine(16f,17f,16f,30f,p)}
                Icon.GALLERY -> { c.drawRoundRect(4f,6f,28f,26f,2f,2f,p)
                    c.drawCircle(21f,12f,2f,p);c.drawLine(5f,23f,12f,15f,p)
                    c.drawLine(12f,15f,18f,22f,p);c.drawLine(18f,22f,23f,18f,p);c.drawLine(23f,18f,28f,23f,p) }
                Icon.RECORD -> { c.drawCircle(16f,16f,12f,p);p.style=Paint.Style.FILL
                    c.drawCircle(16f,16f,6f,p);p.style=Paint.Style.STROKE }
                Icon.CONTROL_CENTER -> { c.drawLine(4f,26f,28f,26f,p)
                    c.drawRoundRect(4f,8f,11f,22f,1f,1f,p)
                    c.drawRoundRect(14f,12f,19f,22f,1f,1f,p);c.drawRoundRect(22f,12f,27f,22f,1f,1f,p) }
                Icon.HOME -> { c.drawRoundRect(6f,4f,14f,28f,1f,1f,p);c.drawLine(10f,6f,10f,26f,p)
                    c.drawRoundRect(17f,19f,28f,26f,2f,2f,p) }
                Icon.CLOSE -> {c.drawLine(8f,8f,24f,24f,p);c.drawLine(24f,8f,8f,24f,p)}
            }
            c.restore()
        }
        override fun setAlpha(alpha: Int) {p.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter: ColorFilter?) {p.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}

class PortalPage(val activity: AppCompatActivity, title: String, onBack: () -> Unit) {
    val root=FrameLayout(activity).apply { background=PortalUi.background() }
    private val column=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    val heading=PortalUi.text(activity,title,26f).apply {typeface=PortalUi.font(activity,600)}
    val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    val scroll=ScrollView(activity).apply {isFillViewport=true;isVerticalScrollBarEnabled=false}
    private val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
    private val backButton=ImageButton(activity)
    private var safeLeft=0
    private var safeTop=0
    private var safeRight=0
    private var safeBottom=0
    init {
        root.addView(column,FrameLayout.LayoutParams(-1,-1))
        header.addView(backButton.apply {
            setImageDrawable(PortalUi.Glyph(PortalUi.Icon.BACK))
            scaleType=ImageView.ScaleType.CENTER_INSIDE
            fun roundSurface(focused:Boolean)=GradientDrawable().apply {
                shape=GradientDrawable.OVAL
                setColor(if(focused) 0x61777184 else 0x2f77717e)
                if(focused) setStroke(dp(2),PortalUi.WHITE)
            }
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),roundSurface(true))
                addState(intArrayOf(android.R.attr.state_pressed),roundSurface(true))
                addState(intArrayOf(),roundSurface(false))
            }
            contentDescription="Back";setOnClickListener {
                PortalMenuAudio.play(activity,PortalMenuAudio.Cue.BACK);onBack()
            };PortalUi.focus(this)
        },LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginEnd=dp(12)})
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        column.addView(header,LinearLayout.LayoutParams(-1,-2))
        column.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        scroll.addView(body,FrameLayout.LayoutParams(-1,-2))
        root.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_ ->updatePadding()}
        ViewCompat.setOnApplyWindowInsetsListener(root) {_,insets ->
            val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            safeLeft=safe.left;safeTop=safe.top;safeRight=safe.right
            safeBottom=maxOf(safe.bottom,insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            updatePadding()
            insets
        }
    }
    private fun updatePadding() {
        val inset=(root.width*.044f).toInt().coerceAtLeast(dp(16))
        val tall=activity.resources.configuration.screenWidthDp >= 700 && root.width > root.height * 1.15f
        val top=if(tall) (root.height*.085f).toInt() else dp(12)
        val headerHeight=if(tall) (root.height*.075f).toInt().coerceAtLeast(dp(54)) else dp(54)
        column.setPadding(maxOf(inset,safeLeft),maxOf(top,dp(8),safeTop),
            maxOf(inset,safeRight),maxOf(dp(18),safeBottom))
        if(header.layoutParams.height!=headerHeight)
            header.layoutParams=header.layoutParams.apply {height=headerHeight}
        val backDiameter=if(tall) (headerHeight*.76f).toInt().coerceAtLeast(dp(44)) else dp(44)
        if(backButton.layoutParams.width!=backDiameter || backButton.layoutParams.height!=backDiameter)
            backButton.layoutParams=backButton.layoutParams.apply {width=backDiameter;height=backDiameter}
        val backInset=(backDiameter*.31f).toInt().coerceAtLeast(dp(11))
        backButton.setPadding(backInset,backInset,backInset,backInset)
        val gap=if(tall) (root.height*.05f).toInt() else dp(10)
        scroll.setPadding(0,gap,0,0)
        heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
            maxOf(if(tall) root.height*.038f*activity.resources.configuration.fontScale else 0f,
                24f*activity.resources.displayMetrics.scaledDensity))
    }
    fun dp(v:Int)=PortalUi.dp(activity,v)
    fun add(view: View) {body.addView(view,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(10)})}
    fun note(text:String) { add(PortalUi.text(activity,text,14f,true).apply {
        setPadding(dp(18),dp(8),dp(18),dp(8))
    }) }
    fun clear(title:String) { heading.text=title;body.removeAllViews();scroll.scrollTo(0,0) }
}
