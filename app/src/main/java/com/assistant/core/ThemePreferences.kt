package com.assistant.core

import android.content.Context
import android.util.TypedValue
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

object ThemePreferences {
    val names = listOf("Default", "Stark", "Cybertron")
    fun selected(context: Context): String = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
        .getString("theme", "default").orEmpty().let { if (it in listOf("default", "stark", "cybertron")) it else "default" }
    fun save(context: Context, theme: String) {
        require(theme in listOf("default", "stark", "cybertron"))
        context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE).edit().putString("theme", theme).apply()
    }
    fun style(context: Context) = when (selected(context)) {
        "stark" -> R.style.Theme_AssistantPlatform_Stark
        "cybertron" -> R.style.Theme_AssistantPlatform_Cybertron
        else -> R.style.Theme_AssistantPlatform
    }
    fun color(context: Context, resource: Int): Int {
        val name = context.resources.getResourceEntryName(resource)
        val attribute = context.resources.getIdentifier(name, "attr", context.packageName)
        val value = TypedValue()
        return if (attribute != 0 && context.theme.resolveAttribute(attribute, value, true)) value.data
        else ContextCompat.getColor(context, resource)
    }
}

open class ThemedActivity : AppCompatActivity() {
    private var displayedTheme = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        displayedTheme = ThemePreferences.selected(this)
        setTheme(ThemePreferences.style(this))
        super.onCreate(savedInstanceState)
    }
    override fun onResume() {
        super.onResume()
        if (displayedTheme != ThemePreferences.selected(this)) recreate()
    }
}
