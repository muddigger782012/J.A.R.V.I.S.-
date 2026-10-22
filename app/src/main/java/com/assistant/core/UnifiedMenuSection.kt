package com.assistant.core

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Button
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
        val originalParams = existing.layoutParams
        scroll.removeView(existing)
        val wrapper = create(scroll.context, title, detail, icon, existing, initiallyExpanded = true)
        // ScrollView measures its direct child as a full-width container. Do
        // not reuse the original child's weighted/zero-height layout params.
        scroll.addView(wrapper, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // A ScrollView child becomes a LinearLayout child: convert its layout
        // params rather than passing incompatible ScrollView.LayoutParams.
        existing.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            if (originalParams is ViewGroup.MarginLayoutParams) {
                setMargins(
                    originalParams.leftMargin, originalParams.topMargin,
                    originalParams.rightMargin, originalParams.bottomMargin
                )
            }
        }
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
        val content = wrapper.getChildAt(wrapper.childCount - 1) as? LinearLayout ?: return
        val markers = groups.mapNotNull { (id, title, detail) ->
            val child = content.findViewById<View>(id)
            if (child?.parent === content) Triple(child, title, detail) else null
        }.sortedBy { content.indexOfChild(it.first) }
        if (markers.isEmpty()) return
        val original = (0 until content.childCount).map { content.getChildAt(it) }
        val positions = markers.map { original.indexOf(it.first) }
        if (positions.distinct().size != positions.size) return
        // Preserve every original view and its layout params, including views
        // preceding the first marker. Previously these were silently placed
        // under the first section and their original layout sizing was lost.
        fun moveToSection(child: View, destination: LinearLayout) {
            val previous = child.layoutParams
            (child.parent as? ViewGroup)?.removeView(child)
            val layout = LinearLayout.LayoutParams(previous.width, previous.height)
            if (previous is ViewGroup.MarginLayoutParams) {
                layout.setMargins(previous.leftMargin, previous.topMargin,
                    previous.rightMargin, previous.bottomMargin)
            }
            if (previous is LinearLayout.LayoutParams) {
                layout.weight = previous.weight
                layout.gravity = previous.gravity
            }
            destination.addView(child, layout)
        }
        val sections = markers.mapIndexed { index, marker ->
            val from = positions[index]
            val until = if (index + 1 < positions.size) positions[index + 1] else original.size
            val groupContent = LinearLayout(scroll.context).apply {
                orientation = LinearLayout.VERTICAL
            }
            original.subList(from, until).forEach { child ->
                moveToSection(child, groupContent)
            }
            create(scroll.context, marker.second, marker.third, icon, groupContent, initiallyExpanded = true)
        }
        val prefix = original.subList(0, positions.first())
        prefix.forEach { child ->
            moveToSection(child, content)
        }
        sections.forEach { content.addView(it) }
    }

    /**
     * Restyle real action buttons in place. Never substitute a proxy View:
     * callers may update labels, enabled state, visibility or click handlers
     * after screen creation, and the original control must remain operable.
     */
    fun compactActions(scroll: View) {
        val context = scroll.context
        val accent = SettingsMenuViews.color(context, R.attr.jarvis_primary)
        val foreground = SettingsMenuViews.color(context, R.attr.jarvis_on_dark)
        fun restyle(view: View) {
            if (view is Button) {
                view.isAllCaps = false
                view.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                view.minimumHeight = SettingsMenuViews.dp(context, 52)
                view.setPadding(
                    SettingsMenuViews.dp(context, 16),
                    SettingsMenuViews.dp(context, 12),
                    SettingsMenuViews.dp(context, 16),
                    SettingsMenuViews.dp(context, 12)
                )
                view.setTextColor(foreground)
                // Keep each real Button and its dynamic state/listeners. Settings
                // uses quiet rows within a shared panel instead of individual pills.
                view.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 65)),
                    null,
                    null
                )
                view.layoutParams?.let { params ->
                    if (params.height == 0) params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    view.layoutParams = params
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) restyle(view.getChildAt(i))
            }
        }
        restyle(scroll)
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
            setBackgroundResource(R.drawable.glass_panel_surface)
            UnifiedContentPanel.apply(this)
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
