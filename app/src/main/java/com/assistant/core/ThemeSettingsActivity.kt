package com.assistant.core

import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class ThemeSettingsActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_themes)
        findViewById<Button>(R.id.btnCloseThemes).setOnClickListener { finish() }
        val options = listOf(R.id.btnThemeDefault to "default", R.id.btnThemeStark to "stark", R.id.btnThemeCybertron to "cybertron")
        options.forEach { (id, name) ->
            findViewById<Button>(id).apply {
                val active = ThemePreferences.selected(this@ThemeSettingsActivity) == name
                text = name.replaceFirstChar { it.uppercase() } + if (active) " • Active" else " • Apply"
                contentDescription = text
                setOnClickListener {
                    if (!active) {
                        ThemePreferences.save(this@ThemeSettingsActivity, name)
                        recreate()
                    }
                }
            }
        }
        findViewById<TextView>(R.id.tvThemeCurrent).text = "Current theme: " + ThemePreferences.selected(this).replaceFirstChar { it.uppercase() }
    }
}
