package com.assistant.core

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/** Compact, wrapping menu rows with theme-owned colors and ordinary touch targets. */
object SettingsMenuViews {
    /** Persistent, opaque themed navigation, outside scrolling or expandable content. */
    fun navigation(context: Context, page: String, back: () -> Unit, close: () -> Unit, originalBack: View? = null): View = LinearLayout(context).apply {
        id = R.id.menuPageNavigation
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 4))
        background = android.graphics.drawable.GradientDrawable().apply {
            val corner = TypedValue(); context.theme.resolveAttribute(R.attr.jarvis_corner, corner, true)
            cornerRadius = corner.getDimension(context.resources.displayMetrics)
            setColor(ColorUtils.compositeColors(color(context, R.attr.jarvis_surface_glass_strong), color(context, R.attr.jarvis_bg_deep)))
            setStroke(dp(context, 1), color(context, R.attr.jarvis_panel_stroke))
        }
        fun button(label: String, id: Int, icon: Int, action: () -> Unit, existing: View? = null): View {
            val view = existing ?: com.google.android.material.button.MaterialButton(
                android.view.ContextThemeWrapper(context, R.style.Widget_AssistantPlatform_QuietButton), null, 0).apply { this.id = id }
            view.contentDescription = if (label == "Back") "Back to previous screen" else "Close $page and return to Chat"
            view.minimumHeight = dp(context, 48)
            if (view is TextView) {
                view.text = label
                view.setTextColor(color(context, R.attr.jarvis_on_dark))
                view.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                val drawable = ContextCompat.getDrawable(context, icon)?.mutate()
                drawable?.setTint(color(context, R.attr.jarvis_primary))
                view.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
                view.compoundDrawablePadding = dp(context, 6)
            }
            if (existing == null) view.setOnClickListener { action() }
            return view
        }
        addView(button("Back", R.id.menuPageBack, R.drawable.ic_menu_back, back, originalBack), LinearLayout.LayoutParams(-2, -2))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(button("Close", R.id.menuPageClose, R.drawable.ic_menu_close, close), LinearLayout.LayoutParams(-2, -2))
        FontPreferences.applyTo(this, context)
    }
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun color(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }
    fun heading(context: Context, text: String) = TextView(context).apply {
        this.text = text; textSize = 13f
        setTextColor(color(context, R.attr.jarvis_on_dark_muted))
        setPadding(dp(context, 14), dp(context, 18), dp(context, 14), dp(context, 8))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    fun row(context: Context, title: String, detail: String? = null, icon: Int, action: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 48)
        setPadding(dp(context, 14), dp(context, 10), dp(context, 12), dp(context, 10))
        isClickable = true; isFocusable = true
        contentDescription = listOfNotNull(title, detail).joinToString(". ")
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(color(context, R.attr.jarvis_primary), 48)), null, null)
        addView(ImageView(context).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(color(context, R.attr.jarvis_primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply { marginEnd = dp(context, 14) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(color(context, R.attr.jarvis_on_dark)) })
            if (detail != null) addView(TextView(context).apply {
                text = detail; textSize = 12f; setTextColor(color(context, R.attr.jarvis_on_dark_muted))
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "›"; textSize = 20f; setTextColor(color(context, R.attr.jarvis_on_dark_muted))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        setOnClickListener { action() }
    }
}
