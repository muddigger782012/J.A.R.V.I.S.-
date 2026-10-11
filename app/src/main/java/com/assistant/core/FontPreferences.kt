package com.assistant.core

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.abs

object FontPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
    fun percent(context: Context) = prefs(context).getInt("font_percent", 100).coerceIn(75, 150)
    fun colorHex(context: Context) = prefs(context).getString("font_color", "").orEmpty()
    fun parseColor(hex: String): Int? = if (Regex("#[0-9a-fA-F]{6}").matches(hex)) Color.parseColor(hex) else null
    fun signature(context: Context) = "${percent(context)}:${colorHex(context)}"
    fun save(context: Context, percent: Int, hex: String) {
        require(hex.isEmpty() || parseColor(hex) != null)
        prefs(context).edit().putInt("font_percent", percent.coerceIn(75, 150))
            .putString("font_color", hex.uppercase()).apply()
    }

    fun applyTo(root: View, context: Context) {
        val scale = percent(context) / 100f
        val color = parseColor(colorHex(context))
        fun visit(view: View) {
            if (view.getTag(R.id.font_preview_exempt) == true) return
            if (view is TextView) {
                val last = view.getTag(R.id.font_applied_size) as? Float
                var original = view.getTag(R.id.font_original_size) as? Float
                // Capture freshly created chat messages and later programmatic text-size changes.
                if (original == null || (last != null && abs(view.textSize - last) > 0.1f)) {
                    original = view.textSize
                    view.setTag(R.id.font_original_size, original)
                }
                val target = original * scale
                if (abs(view.textSize - target) > 0.1f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
                view.setTag(R.id.font_applied_size, target)
                if (color != null && view.currentTextColor != color) view.setTextColor(color)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }
}
