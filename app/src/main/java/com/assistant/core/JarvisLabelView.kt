package com.assistant.core

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.min

/** Jarvis label: vector contours traced from the approved reference lettering. */
class JarvisLabelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val artwork = requireNotNull(AppCompatResources.getDrawable(context, R.drawable.jarvis_label_sharp)).mutate()

    init {
        contentDescription = "J.A.R.V.I.S."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val scale = min(availableWidth / 376f, availableHeight / 41f)
        if (scale <= 0f) return
        val drawWidth = 376f * scale
        val drawHeight = 41f * scale
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - drawWidth else paddingLeft.toFloat()
        val top = paddingTop + (availableHeight - drawHeight) / 2f
        artwork.setBounds(0, 0, drawWidth.toInt().coerceAtLeast(1), drawHeight.toInt().coerceAtLeast(1))
        canvas.save()
        canvas.translate(left, top)
        artwork.draw(canvas)
        canvas.restore()
    }
}
