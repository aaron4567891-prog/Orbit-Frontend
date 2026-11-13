package au.orbit.frontend

import android.graphics.Bitmap

/** Fit the visible console, rather than the theme asset's transparent canvas. */
internal object SystemTileArtwork {
    fun trim(bitmap:Bitmap):Bitmap {
        if(!bitmap.hasAlpha())return bitmap
        var left=bitmap.width;var top=bitmap.height;var right=-1;var bottom=-1
        val pixels=IntArray(bitmap.width)
        for(y in 0 until bitmap.height) {
            bitmap.getPixels(pixels,0,bitmap.width,0,y,bitmap.width,1)
            for(x in pixels.indices)if((pixels[x] ushr 24)>12) {
                left=minOf(left,x);right=maxOf(right,x)
                top=minOf(top,y);bottom=maxOf(bottom,y)
            }
        }
        if(right<left || bottom<top)return bitmap
        val margin=(maxOf(right-left+1,bottom-top+1)*0.015f).toInt()
        left=(left-margin).coerceAtLeast(0);top=(top-margin).coerceAtLeast(0)
        right=(right+margin).coerceAtMost(bitmap.width-1)
        bottom=(bottom+margin).coerceAtMost(bitmap.height-1)
        return Bitmap.createBitmap(bitmap,left,top,right-left+1,bottom-top+1)
    }
}
