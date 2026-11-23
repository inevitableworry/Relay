// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** One bounded real scene capture. SurfaceView buffers need PixelCopy; View.draw supplies UI above them. */
internal class PortalMenuSnapshot(private val activity:AppCompatActivity,private val excluded:View) {
    private val main=Handler(Looper.getMainLooper())
    private var pending:Job?=null
    private data class Layer(val view:SurfaceView,val bounds:RectF,val clip:RectF)

    fun cancel() {pending?.cancel();pending=null}

    fun capture(radiusPixels:Int,ready:(Bitmap?)->Unit) {
        cancel()
        pending=activity.lifecycleScope.launch {
            var foreground:Bitmap?=null
            var composite:Bitmap?=null
            val buffers=mutableListOf<Bitmap>()
            try {
                val decor=activity.window.decorView
                if(decor.width<=0 || decor.height<=0) {ready(null);return@launch}
                val scale=512f/max(decor.width,decor.height).coerceAtLeast(512)
                val width=(decor.width*scale).roundToInt().coerceAtLeast(1)
                val height=(decor.height*scale).roundToInt().coerceAtLeast(1)
                val origin=IntArray(2).also {decor.getLocationOnScreen(it)}
                val layers=mutableListOf<Layer>()
                fun collect(view:View) {
                    if(view===excluded || !view.isShown) return
                    if(view is SurfaceView && view.width>0 && view.height>0) {
                        val location=IntArray(2).also {view.getLocationOnScreen(it)}
                        val visible=Rect()
                        if(view.getGlobalVisibleRect(visible)) layers.add(Layer(view,
                            RectF((location[0]-origin[0])*scale,(location[1]-origin[1])*scale,
                                (location[0]-origin[0]+view.width)*scale,(location[1]-origin[1]+view.height)*scale),
                            RectF((visible.left-origin[0])*scale,(visible.top-origin[1])*scale,
                                (visible.right-origin[0])*scale,(visible.bottom-origin[1])*scale)))
                    } else if(view is ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(decor)
                if(layers.size>4) {ready(null);return@launch}
                val ui=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                foreground=ui
                val alpha=excluded.alpha
                try {
                    excluded.alpha=0f
                    decor.draw(Canvas(ui).apply {scale(scale,scale)})
                } finally {excluded.alpha=alpha}
                val scene=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                composite=scene
                val canvas=Canvas(scene)
                canvas.drawColor(Color.BLACK)
                val paint=Paint(Paint.FILTER_BITMAP_FLAG)
                // A failed/unavailable video buffer uses the opaque panel; it must not pretend to capture video.
                val copied=withTimeoutOrNull(600L) {
                    for(layer in layers) {
                        val frame=copySurface(layer.view,layer.bounds.width().roundToInt().coerceAtLeast(1),
                            layer.bounds.height().roundToInt().coerceAtLeast(1)) ?: return@withTimeoutOrNull false
                        buffers.add(frame)
                        canvas.save();canvas.clipRect(layer.clip)
                        canvas.drawBitmap(frame,null,layer.bounds,paint);canvas.restore()
                    }
                    true
                } ?: false
                if(!copied) {ready(null);return@launch}
                // Software View drawing retains the transparent SurfaceView hole and all overlaid labels/controls.
                canvas.drawBitmap(ui,0f,0f,paint)
                withContext(Dispatchers.Default) {
                    blur(scene,(radiusPixels*scale*.65f).roundToInt().coerceIn(4,24))
                }
                ensureActive()
                composite=null // Ownership passes to the menu drawable.
                ready(scene)
            } catch(_:CancellationException) {
                // PixelCopy cannot be cancelled; its callback releases any late buffer without applying it.
            } catch(_:Exception) {if(isActive) ready(null)}
            finally {
                foreground?.recycle();composite?.recycle();buffers.forEach {it.recycle()}
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun copySurface(view:SurfaceView,width:Int,height:Int):Bitmap? =
        suspendCancellableCoroutine {continuation ->
            val result=Bitmap.createBitmap(width.coerceAtMost(512),height.coerceAtMost(512),Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(view,result,{status ->
                    if(continuation.isActive && status==PixelCopy.SUCCESS)
                        continuation.resume(result,onCancellation={result.recycle()})
                    else {result.recycle();if(continuation.isActive) continuation.resume(null)}
                },main)
            } catch(_:Exception) {result.recycle();if(continuation.isActive) continuation.resume(null)}
        }

    /** Three separable box passes approximate a soft lens blur without a full-size render target. */
    private suspend fun blur(bitmap:Bitmap,radius:Int) {
        val width=bitmap.width;val height=bitmap.height
        val pixels=IntArray(width*height);val scratch=IntArray(pixels.size)
        bitmap.getPixels(pixels,0,width,0,0,width,height)
        val context=currentCoroutineContext()
        fun pass(source:IntArray,dest:IntArray,horizontal:Boolean) {
            val length=if(horizontal) width else height
            val lines=if(horizontal) height else width
            val divisor=radius*2+1
            for(line in 0 until lines) {
                if(line%16==0) context.ensureActive()
                fun index(at:Int)=if(horizontal) line*width+at.coerceIn(0,length-1)
                    else at.coerceIn(0,length-1)*width+line
                var red=0;var green=0;var blue=0
                for(at in -radius..radius) {
                    val color=source[index(at)]
                    red+=color shr 16 and 255;green+=color shr 8 and 255;blue+=color and 255
                }
                for(at in 0 until length) {
                    dest[index(at)]=Color.rgb(red/divisor,green/divisor,blue/divisor)
                    val old=source[index(at-radius)];val next=source[index(at+radius+1)]
                    red+=(next shr 16 and 255)-(old shr 16 and 255)
                    green+=(next shr 8 and 255)-(old shr 8 and 255)
                    blue+=(next and 255)-(old and 255)
                }
            }
        }
        repeat(3) {pass(pixels,scratch,true);pass(scratch,pixels,false)}
        bitmap.setPixels(pixels,0,width,0,0,width,height)
    }
}
