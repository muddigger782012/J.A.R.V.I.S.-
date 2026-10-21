package com.assistant.core

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/** Settings is a destination list; each editor returns here rather than opening sibling hubs. */
class SettingsActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_panel_surface)
            setPadding(SettingsMenuViews.dp(this@SettingsActivity, 12), 0, SettingsMenuViews.dp(this@SettingsActivity, 12), SettingsMenuViews.dp(this@SettingsActivity, 24))
        }
        UnifiedContentPanel.apply(panel)
        fun section(title: String) { panel.addView(SettingsMenuViews.heading(this, title)) }
        fun destination(title: String, detail: String, icon: Int, target: Class<*>) {
            panel.addView(SettingsMenuViews.row(this, title, detail, icon) { startActivity(Intent(this, target)) })
        }
        panel.addView(SettingsMenuViews.row(this, "Back to chat", icon = R.drawable.ic_search_open) { finish() })
        panel.addView(SettingsMenuViews.heading(this, "Settings").apply { textSize = 24f })
        section("Voice")
        destination("Wake word and listening", "Activation, sensitivity and command recognition", R.drawable.ic_composer_mic, VoiceSettingsActivity::class.java)
        destination("Assistant voice", "Voice choices, previews and spoken responses", R.drawable.ic_composer_headset, AssistantVoiceSettingsActivity::class.java)
        destination("My voice profile", "Practice phrases and approved speech hints", R.drawable.ic_composer_mic, VoiceProfileActivity::class.java)
        section("Personalize")
        destination("Appearance", "Themes, fonts and chat contrast", R.drawable.ic_tools_settings, AppearanceSettingsActivity::class.java)
        section("Intelligence and connections")
        destination("Learning and memory", "Commands, routines, outcomes and local learning", R.drawable.ic_jarvis_core, IntelligenceActivity::class.java)
        panel.addView(SettingsMenuViews.row(this, "AI and weather", "Provider credentials and weather location", R.drawable.ic_composer_tools) { AiConnectionSettings.show(this) })
        destination("Assistant connections", "Optional assistant integrations", R.drawable.ic_composer_tools, AssistantConnectionsActivity::class.java)
        setContentView(ScrollView(this).apply {
            val inset = SettingsMenuViews.dp(this@SettingsActivity, 16)
            setPadding(inset, inset, inset, inset)
            val backgroundValue = android.util.TypedValue()
            theme.resolveAttribute(R.attr.jarvis_background, backgroundValue, true)
            if (backgroundValue.resourceId != 0) setBackgroundResource(backgroundValue.resourceId)
            else setBackgroundColor(backgroundValue.data)
            addView(panel)
        })
        FontPreferences.applyTo(panel, this)
    }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
