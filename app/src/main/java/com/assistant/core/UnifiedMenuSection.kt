package com.assistant.core

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

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

    /**
     * Split a screen at existing control IDs without recreating the controls.
     * Each section retains its original View objects, IDs and listeners.
     */
    fun group(
        scroll: ScrollView,
        groups: List<Triple<Int, String, String>>,
        icon: Int
    ) {
        val wrapper = scroll.getChildAt(0) as? LinearLayout ?: return
        val content = wrapper.getChildAt(1) as? LinearLayout ?: return
        val markers = groups.mapNotNull { (id, title, detail) ->
            val child = content.findViewById<View>(id)
            if (child?.parent === content) Triple(child, title, detail) else null
        }.sortedBy { content.indexOfChild(it.first) }
        if (markers.isEmpty()) return
        val original = (0 until content.childCount).map { content.getChildAt(it) }
        val positions = markers.map { original.indexOf(it.first) }
        val sections = markers.mapIndexed { index, marker ->
            val from = if (index == 0) 0 else positions[index]
            val until = if (index + 1 < positions.size) positions[index + 1] else original.size
            val groupContent = LinearLayout(scroll.context).apply {
                orientation = LinearLayout.VERTICAL
            }
            original.subList(from, until).forEach { child ->
                (child.parent as? ViewGroup)?.removeView(child)
                groupContent.addView(child)
            }
            create(scroll.context, marker.second, marker.third, icon, groupContent, initiallyExpanded = true)
        }
        content.removeAllViews()
        sections.forEach { content.addView(it) }
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
        lateinit var header: View
        header = SettingsMenuViews.row(context, title, detail, icon) {
            val expanded = content.visibility != View.VISIBLE
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            header.isSelected = expanded
            header.contentDescription = "$title. $detail. " +
                if (expanded) "Expanded. Double tap to collapse." else "Collapsed. Double tap to expand."
            header.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
        header.isSelected = initiallyExpanded
        header.contentDescription = "$title. $detail. " +
            if (initiallyExpanded) "Expanded. Double tap to collapse." else "Collapsed. Double tap to expand."
        // A restrained, theme-colored HUD accent, not a border around every control.
        val accent = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    ColorUtils.setAlphaComponent(SettingsMenuViews.color(context, R.attr.jarvis_primary), 115),
                    ColorUtils.setAlphaComponent(SettingsMenuViews.color(context, R.attr.jarvis_primary), 0)
                )
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        section.addView(header)
        section.addView(accent, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, SettingsMenuViews.dp(context, 1)
        ))
        section.addView(content)
        return section
    }
}
