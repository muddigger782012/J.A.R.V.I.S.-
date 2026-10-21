package com.jarvis.connectionmanager

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight, resolution-independent HUD artwork; no network data is simulated here. */
class NetworkHudView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyan = Color.rgb(39, 215, 255)
    private val dim = Color.argb(115, 21, 133, 170)
    private val blue = Color.rgb(6, 50, 84)
    private var phase = 0f
    private var networkValidated: Boolean? = null

    fun setNetworkValidated(value: Boolean?) {
        networkValidated = value
        contentDescription = when (value) {
            true -> "Network HUD: Android reports a validated network"
            false -> "Network HUD: Android network is not validated"
            null -> "Network HUD: validation not tested"
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * .66f).toInt().coerceAtLeast(180))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width * .27f, height * .42f)
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.RadialGradient(cx, cy, radius, intArrayOf(Color.rgb(19, 119, 166), blue, Color.rgb(1, 17, 33)), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * resources.displayMetrics.density
        for (i in 0..4) {
            paint.color = if (i % 2 == 0) cyan else dim
            val r = radius * (1.12f + i * .15f)
            canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), phase + i * 43f, 65f + i * 9f, false, paint)
            canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), phase + 190f + i * 21f, 54f, false, paint)
        }
        paint.color = dim
        for (latitude in -2..2) {
            val y = cy + latitude * radius / 3.2f
            val half = kotlin.math.sqrt((radius * radius - (y-cy)*(y-cy)).coerceAtLeast(0f))
            canvas.drawOval(RectF(cx-half, y-radius*.08f, cx+half, y+radius*.08f), paint)
        }
        for (longitude in -2..2) {
            val x = cx + longitude * radius / 3.3f
            canvas.drawOval(RectF(x-radius*.10f, cy-radius, x+radius*.10f, cy+radius), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = when (networkValidated) {
            true -> Color.rgb(59, 239, 176)
            false -> Color.rgb(255, 177, 74)
            null -> dim
        }
        canvas.drawCircle(cx, cy + radius * .03f, radius * .075f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = cyan
        for (i in 0..11) {
            val a = (i * 30 + phase) * Math.PI / 180
            val inner = radius * 1.73f
            val outer = radius * 1.81f
            canvas.drawLine(cx + (cos(a)*inner).toFloat(), cy + (sin(a)*inner).toFloat(), cx + (cos(a)*outer).toFloat(), cy + (sin(a)*outer).toFloat(), paint)
        }
        phase = (phase + .15f) % 360f
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(32L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}
