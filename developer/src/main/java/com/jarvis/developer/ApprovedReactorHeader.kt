package com.jarvis.developer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animates two annular portions of the existing approved header bitmap.
 * The lettering, border and reactor center are always drawn from the original bitmap.
 * No generated replacement artwork is used.
 */
class ApprovedReactorHeader(context: Context) : View(context) {
    private val bitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.developer_approved_header)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cx = 230f
    private val cy = 245f
    private var assemblyAngle = 0f
    private var outerAngle = 0f
    private val assembly = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 16000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { assemblyAngle = it.animatedValue as Float; invalidate() }
    }
    private val outer = ValueAnimator.ofFloat(0f, -360f).apply {
        duration = 26000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { outerAngle = it.animatedValue as Float; invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * bitmap.height.toFloat() / bitmap.width).toInt().coerceAtLeast(1))
    }

    private fun annulus(inner: Float, outer: Float): Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        addCircle(cx, cy, outer, Path.Direction.CW)
        addCircle(cx, cy, inner, Path.Direction.CW)
    }

    private fun redrawRotatingBand(canvas: Canvas, ring: Path, angle: Float) {
        canvas.drawPath(ring, clearPaint)
        canvas.save()
        canvas.clipPath(ring)
        canvas.rotate(angle, cx, cy)
        canvas.drawBitmap(bitmap, 0f, 0f, imagePaint)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saved = canvas.save()
        canvas.scale(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val layer = canvas.saveLayer(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), null)
        canvas.drawBitmap(bitmap, 0f, 0f, imagePaint)
        // The center (< 44 px) is deliberately never cleared or rotated.
        redrawRotatingBand(canvas, annulus(44f, 110f), assemblyAngle)
        // Outer segmented ring rotates in the opposite direction.
        redrawRotatingBand(canvas, annulus(113f, 137f), outerAngle)
        canvas.restoreToCount(layer)
        canvas.restoreToCount(saved)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) != 0f) {
            assembly.start()
            outer.start()
        }
    }

    override fun onDetachedFromWindow() {
        assembly.cancel()
        outer.cancel()
        super.onDetachedFromWindow()
    }
}
