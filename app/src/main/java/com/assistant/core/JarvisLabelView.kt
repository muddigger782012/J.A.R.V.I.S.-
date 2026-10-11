package com.assistant.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Jarvis label: the exact lettering from the supplied reference, not a substitute font. */
class JarvisLabelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val artwork = reference(context)
    // Only the original lettering; the circle above/below it is deliberately outside this viewport.
    private val source = Rect(575, 415, 951, 456)
    private val destination = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        val tint = ThemePreferences.color(context, R.color.jarvis_on_dark)
        // Preserve the reference's anti-aliased strokes as alpha, with the theme's label color.
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0f, 0f, 0f, 0f, Color.red(tint).toFloat(),
            0f, 0f, 0f, 0f, Color.green(tint).toFloat(),
            0f, 0f, 0f, 0f, Color.blue(tint).toFloat(),
            .2126f, .7152f, .0722f, 0f, 0f
        )))
    }

    init {
        contentDescription = "J.A.R.V.I.S."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val scale = min(availableWidth / source.width(), availableHeight / source.height())
        if (scale <= 0f) return
        val drawWidth = source.width() * scale
        val drawHeight = source.height() * scale
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - drawWidth else paddingLeft.toFloat()
        val top = paddingTop + (availableHeight - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(artwork, source, destination, paint)
    }

    companion object {
        @Volatile private var cachedReference: Bitmap? = null
        private fun reference(context: Context): Bitmap = cachedReference ?: synchronized(this) {
            cachedReference ?: BitmapFactory.decodeResource(context.resources, R.drawable.jarvis_label_reference)
                .also { cachedReference = it }
        }
    }
}
