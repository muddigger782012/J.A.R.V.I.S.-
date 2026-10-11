package com.assistant.core

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.Keep
import org.xmlpull.v1.XmlPullParser

/** Static Default-theme wallpaper; center cropping preserves the circular HUD. */
@Keep
class DefaultWallpaperDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun inflate(resources: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(resources, parser, attrs, theme)
        if (wallpaper == null) {
            wallpaper = BitmapFactory.decodeResource(resources, R.drawable.default_hud_wallpaper)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(3, 14, 19))
        val image = wallpaper ?: return
        val scale = maxOf(bounds.width().toFloat() / image.width, bounds.height().toFloat() / image.height)
        val width = image.width * scale
        val height = image.height * scale
        val left = bounds.exactCenterX() - width / 2f
        val top = bounds.exactCenterY() - height / 2f
        destination.set(left, top, left + width, top + height)
        val checkpoint = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawBitmap(image, null, destination, paint)
        canvas.drawColor(0x66030E13)
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    companion object {
        // Windows and layout roots share one decoded image rather than duplicating it.
        @Volatile private var wallpaper: Bitmap? = null
    }
}
