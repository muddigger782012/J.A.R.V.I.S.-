package com.assistant.core

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import androidx.annotation.Keep
import org.xmlpull.v1.XmlPullParser

/** Same HUD shader, without Android 8's radial XML/theme radius loss. */
@Keep
class HudPulseDrawable : GradientDrawable() {
    private var sourceResources: Resources? = null

    override fun inflate(resources: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        sourceResources = resources
        configure(resources, theme)
    }

    override fun canApplyTheme() = true
    override fun applyTheme(theme: Resources.Theme) {
        sourceResources?.let { configure(it, theme) }
    }

    // GradientDrawable's cached ConstantState recreates the base class and would
    // discard this theme handling. This tiny drawable is inflated for its view.
    override fun getConstantState(): Drawable.ConstantState? = null

    private fun configure(resources: Resources, theme: Resources.Theme?) {
        fun color(attribute: Int, fallback: Int): Int {
            val value = TypedValue()
            if (theme?.resolveAttribute(attribute, value, true) == true) {
                if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
                if (value.resourceId != 0) return resources.getColor(value.resourceId, theme)
            }
            return resources.getColor(fallback, theme)
        }
        val radius = TypedValue()
        shape = RECTANGLE
        gradientType = RADIAL_GRADIENT
        setGradientCenter(0.5f, 0.35f)
        gradientRadius = if (theme?.resolveAttribute(R.attr.jarvis_pulse_radius, radius, true) == true &&
            radius.type == TypedValue.TYPE_DIMENSION) radius.getDimension(resources.displayMetrics)
            else TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 420f, resources.displayMetrics)
        colors = intArrayOf(color(R.attr.jarvis_pulse_tint, R.color.jarvis_pulse_default_tint),
            color(R.attr.jarvis_pulse_end, R.color.jarvis_pulse_end))
    }
}
