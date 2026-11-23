// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.jb

/** Game-video coordinates only. Never use the Android activity Window as the image source. */
internal object PortalTranslationLayout {
    data class Crop(val x:Int,val y:Int,val width:Int,val height:Int)
    data class Placement(val left:Int,val top:Int,val width:Int)
    fun crop(width:Int,height:Int,region:String):Crop {
        require(width>0 && height>0)
        val (start,end)=when(region) {
            "SUBTITLES" -> .60f to 1f
            "CENTER" -> .20f to .80f
            "TOP" -> 0f to .40f
            else -> 0f to 1f
        }
        val y=(height*start).toInt().coerceIn(0,height-1)
        val h=((height*end).toInt()-y).coerceIn(1,height-y)
        return Crop(0,y,width,h)
    }
    fun placement(sourceLeft:Int,sourceTop:Int,sourceWidth:Int,
        bitmapWidth:Int,bitmapHeight:Int,crop:Crop,viewWidth:Int,viewHeight:Int):Placement {
        require(bitmapWidth>0 && bitmapHeight>0 && viewWidth>0 && viewHeight>0)
        val scaleX=viewWidth.toFloat()/bitmapWidth
        val scaleY=viewHeight.toFloat()/bitmapHeight
        val x=((sourceLeft+crop.x)*scaleX).toInt().coerceIn(0,viewWidth-1)
        val y=((sourceTop+crop.y)*scaleY).toInt().coerceIn(0,viewHeight-1)
        val remaining=(viewWidth-x).coerceAtLeast(1)
        return Placement(x,y,(sourceWidth*scaleX).toInt().coerceIn(1,remaining))
    }
    fun normalized(text:String):String = text.trim().replace(Regex("\\s+")," ").take(260)
    fun changed(previous:String,next:String):Boolean =
        normalized(previous)!=normalized(next)
}
