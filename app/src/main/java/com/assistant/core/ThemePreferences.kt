package com.assistant.core

import android.content.Context
import android.util.TypedValue
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

object ThemePreferences {
    val ids = listOf("default", "stark", "cybertron", "default_alternative", "stark_alternative", "cybertron_alternative", "aurora", "sunset", "glacier", "emerald", "royal")
    val names = listOf("Default", "Stark", "Cybertron", "Default Alternative", "Stark Alternative", "Cybertron Alternative", "Aurora", "Sunset", "Glacier", "Emerald", "Royal")
    fun label(theme: String): String = names[ids.indexOf(theme).coerceAtLeast(0)]
    fun baseTheme(context: Context): String = selected(context).let {
        if (it in listOf("aurora", "sunset", "glacier", "emerald", "royal")) "stark"
        else it.removeSuffix("_alternative")
    }
    fun selected(context: Context): String = context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE)
        .getString("theme", "default").orEmpty().let { if (it in ids) it else "default" }
    fun save(context: Context, theme: String) {
        require(theme in ids)
        context.getSharedPreferences("jarvis_appearance", Context.MODE_PRIVATE).edit().putString("theme", theme).apply()
    }
    fun style(context: Context) = when (selected(context)) {
        "default_alternative" -> R.style.Theme_AssistantPlatform_DefaultAlternative
        "stark_alternative" -> R.style.Theme_AssistantPlatform_StarkAlternative
        "cybertron_alternative" -> R.style.Theme_AssistantPlatform_CybertronAlternative
        "aurora" -> R.style.Theme_AssistantPlatform_Aurora
        "sunset" -> R.style.Theme_AssistantPlatform_Sunset
        "glacier" -> R.style.Theme_AssistantPlatform_Glacier
        "emerald" -> R.style.Theme_AssistantPlatform_Emerald
        "royal" -> R.style.Theme_AssistantPlatform_Royal
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
