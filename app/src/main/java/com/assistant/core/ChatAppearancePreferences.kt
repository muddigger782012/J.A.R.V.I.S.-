package com.assistant.core

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils

object ChatAppearancePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
    fun darkness(context: Context) = prefs(context).getInt("chat_box_darkness", 0).coerceIn(0, 100)
    fun save(context: Context, value: Int) {
        prefs(context).edit().putInt("chat_box_darkness", value.coerceIn(0, 100)).apply()
    }
    fun background(context: Context, user: Boolean, value: Int = darkness(context)): Drawable? {
        val resource = if (user) R.drawable.chat_bubble_user else R.drawable.chat_bubble_assistant
        val drawable = AppCompatResources.getDrawable(context, resource)?.mutate()
        if (drawable is GradientDrawable && value > 0) {
            val fill = ThemePreferences.color(context, if (user) R.color.jarvis_button_fill else R.color.jarvis_output_surface)
            drawable.setColor(ColorUtils.blendARGB(fill, Color.BLACK, value.coerceIn(0, 100) / 100f))
        }
        return drawable
    }
}
