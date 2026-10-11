package com.assistant.core

import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class ThemeSettingsActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_themes)
        findViewById<Button>(R.id.btnCloseThemes).setOnClickListener { finish() }
        val options = listOf(
            R.id.btnThemeDefault to "default", R.id.btnThemeStark to "stark", R.id.btnThemeCybertron to "cybertron",
            R.id.btnThemeDefaultAlternative to "default_alternative",
            R.id.btnThemeStarkAlternative to "stark_alternative",
            R.id.btnThemeCybertronAlternative to "cybertron_alternative",
            R.id.btnThemeAurora to "aurora",
            R.id.btnThemeSunset to "sunset",
            R.id.btnThemeGlacier to "glacier",
            R.id.btnThemeEmerald to "emerald",
            R.id.btnThemeRoyal to "royal",
            R.id.btnThemeHacker to "hacker"
        )
        options.forEach { (id, name) ->
            findViewById<Button>(id).apply {
                val active = ThemePreferences.selected(this@ThemeSettingsActivity) == name
                text = ThemePreferences.label(name) + if (active) " • Active" else " • Apply"
                contentDescription = text
                setOnClickListener {
                    if (!active) {
                        ThemePreferences.save(this@ThemeSettingsActivity, name)
                        recreate()
                    }
                }
            }
        }
        findViewById<TextView>(R.id.tvThemeCurrent).text = "Current theme: " + ThemePreferences.label(ThemePreferences.selected(this))
    }
}
