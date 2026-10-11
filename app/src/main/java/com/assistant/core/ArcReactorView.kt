package com.assistant.core

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** Arc Reactor: approved live-chat artwork with independently rotating rings. */
class ArcReactorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val layers = artwork(context)
    private var elapsedMs = 0L
    private var lastFrameMs = 0L
    private var motionEnabled = true
    private val tick = Runnable { invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
        lastFrameMs = 0L
        removeCallbacks(tick)
        invalidate()
    }

    private fun canAnimate() = motionEnabled && isAttachedToWindow && isShown &&
        windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val animate = canAnimate()
        if (animate && lastFrameMs != 0L) elapsedMs += (now - lastFrameMs).coerceAtMost(100L)
        lastFrameMs = if (animate) now else 0L
        val seconds = elapsedMs / 1000.0
        val side = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (side <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        bounds.set(-side / 2f, -side / 2f, side / 2f, side / 2f)
        layers.forEachIndexed { index, bitmap ->
            val angle = when (index) {
                1 -> (seconds * 360.0 / 36.0 % 360.0).toFloat()
                2 -> (-seconds * 360.0 / 28.0 % 360.0).toFloat()
                else -> 0f
            }
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angle)
            canvas.drawBitmap(bitmap, null, bounds, paint)
            canvas.restore()
        }
        // Light only: the central hardware stays stationary rather than breathing in size.
        val alpha = ((0.025 + 0.015 * sin(seconds * Math.PI * 2.0 / 5.0)) * 255).toInt()
        paint.shader = RadialGradient(cx, cy, side * 115f / 1254f,
            Color.argb(alpha, 195, 247, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, side * 115f / 1254f, paint)
        paint.shader = null
        removeCallbacks(tick)
        if (animate) postDelayed(tick, 33L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameMs = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        lastFrameMs = 0L
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(tick)
        lastFrameMs = 0L
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(tick)
        lastFrameMs = 0L
        if (visibility == VISIBLE) invalidate()
    }

    companion object {
        // Shared across header, conversation and settings; no per-frame bitmap allocation.
        @Volatile private var cachedLayers: List<Bitmap>? = null
        private fun artwork(context: Context): List<Bitmap> = cachedLayers ?: synchronized(this) {
            cachedLayers ?: buildLayers(context).also { cachedLayers = it }
        }

        private fun buildLayers(context: Context): List<Bitmap> {
            val source = BitmapFactory.decodeResource(context.resources, R.drawable.arc_reactor_art)
            val size = source.width
            val scale = size / 1254f
            val center = size / 2f
            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            val result = listOf(0f to 319f, 319f to 522f, 522f to 621f).map { (inner, outer) ->
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(layer)
                canvas.drawBitmap(source, 0f, 17f * scale, null)
                val stops = if (inner == 0f) floatArrayOf(0f, (outer - 3f) / 627f, (outer + 3f) / 627f, 1f)
                    else floatArrayOf(0f, (inner - 3f) / 627f, (inner + 3f) / 627f,
                        (outer - 3f) / 627f, (outer + 3f) / 627f, 1f)
                val colors = if (inner == 0f) intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT)
                    else intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE, Color.WHITE,
                        Color.TRANSPARENT, Color.TRANSPARENT)
                maskPaint.shader = RadialGradient(center, center, center, colors, stops, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), maskPaint)
                layer
            }
            source.recycle()
            return result
        }
    }
}
