package com.assistant.core

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Settings-style expandable section that hosts existing views and listeners.
 * The caller retains ownership of all controls and their behavior.
 */
object UnifiedMenuSection {
    /** Wrap a pre-existing screen without replacing its controls or listeners. */
    fun install(scroll: ScrollView, title: String, detail: String, icon: Int) {
        if (scroll.childCount != 1) return
        val existing = scroll.getChildAt(0)
        scroll.removeView(existing)
        val wrapper = create(scroll.context, title, detail, icon, existing, initiallyExpanded = true)
        scroll.addView(wrapper)
    }

    fun create(
        context: Context,
        title: String,
        detail: String,
        icon: Int,
        content: View,
        initiallyExpanded: Boolean = false
    ): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        val header = SettingsMenuViews.row(context, title, detail, icon) {
            content.visibility =
                if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        section.addView(header)
        section.addView(content)
        return section
    }
}
