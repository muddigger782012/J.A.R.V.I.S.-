package com.assistant.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * Opt-in glass panel contrast adjustment. Only the panel fill changes.
 * The stroke layer and wallpaper are left untouched.
 */
object UnifiedContentPanel {
    fun apply(panel: View, context: Context = panel.context) {
        val background = panel.background?.constantState?.newDrawable(context.resources)?.mutate()
            ?: return
        val layers = background as? LayerDrawable ?: return
        if (layers.numberOfLayers < 2) return
        val fill = layers.getDrawable(0) as? GradientDrawable ?: return
        val original = SettingsMenuViews.color(context, R.attr.jarvis_surface_glass)
        val increasedAlpha = (Color.alpha(original) + 13).coerceAtMost(255)
        fill.setColor(ColorUtils.setAlphaComponent(original, increasedAlpha))
        panel.background = layers
    }
}
