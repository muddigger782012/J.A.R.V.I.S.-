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
    fun panel(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.glass_panel_surface)
        val inset = dp(context, 12)
        setPadding(inset, inset, inset, inset)
    }
    fun screen(context: Context, panel: View) = android.widget.ScrollView(context).apply {
        val value = TypedValue()
        context.theme.resolveAttribute(R.attr.jarvis_background, value, true)
        if (value.resourceId != 0) setBackgroundResource(value.resourceId) else setBackgroundColor(value.data)
        val inset = dp(context, 16)
        setPadding(inset, inset, inset, inset)
        addView(panel)
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
