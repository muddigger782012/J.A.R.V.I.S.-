package com.assistant.core

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Opt-in utility page shell. Chat, HUD, onboarding and browser surfaces do not use it. */
abstract class MenuActivity(private val pageTitle: String, private val legacyBackId: Int = View.NO_ID) : ThemedActivity() {
    override fun setContentView(layoutResID: Int) = install(layoutInflater.inflate(layoutResID, null))
    override fun setContentView(view: View?) = install(requireNotNull(view))
    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) = install(requireNotNull(view))

    private fun install(content: View) {
        // Programmatic and legacy root ScrollViews need a stable ID for Android's
        // own hierarchy-state restoration. Existing IDs are never replaced.
        if (content.id == View.NO_ID) content.id = R.id.menuPageBody
        val originalBack = (if (legacyBackId != View.NO_ID) content.findViewById<View>(legacyBackId) else null)?.also {
            (it.parent as? ViewGroup)?.removeView(it)
        }
        val root = LinearLayout(this).apply {
            id = R.id.menuPageRoot
            orientation = LinearLayout.VERTICAL
            val background = android.util.TypedValue()
            theme.resolveAttribute(R.attr.jarvis_background, background, true)
            if (background.resourceId != 0) setBackgroundResource(background.resourceId) else setBackgroundColor(background.data)
            addView(SettingsMenuViews.navigation(this@MenuActivity, pageTitle,
                // A visible Back button leaves this page even when Android
                // launched it as a task root (platform Back can background it).
                back = { finish() }, close = { closeMenus(this@MenuActivity) }, originalBack = originalBack))
            addView(FrameLayout(this@MenuActivity).apply {
                id = R.id.menuPageContent
                addView(content, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        super.setContentView(root)
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    override fun onResume() {
        super.onResume()
        findViewById<View>(R.id.menuPageContent)?.let { UnifiedMenuSection.compactActions(it) }
    }

    companion object {
        const val RETURN_TO_CHAT = "jarvis_return_to_chat"
        fun closeMenus(activity: Activity) {
            activity.startActivity(Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(RETURN_TO_CHAT, true))
            if (activity !is MainActivity) activity.finish()
        }
    }
}
