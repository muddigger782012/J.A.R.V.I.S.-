package com.assistant.core.services

import android.content.Context

/** Approved defaults for the personal assistant's live conversational behavior. */
data class LiveChatCompanionSettings(
    val style: String = "companion",
    val initiative: Int = 55,
    val interruptionsEnabled: Boolean = true,
    val contextEnabled: Boolean = true
) {
    companion object {
        fun load(context: Context): LiveChatCompanionSettings {
            val prefs = context.getSharedPreferences("jarvis_live_companion", Context.MODE_PRIVATE)
            return LiveChatCompanionSettings(
                style = prefs.getString("style", "companion") ?: "companion",
                initiative = prefs.getInt("initiative", 55).coerceIn(0, 100),
                interruptionsEnabled = prefs.getBoolean("interruptions_enabled", true),
                contextEnabled = prefs.getBoolean("context_enabled", true)
            )
        }
    }
}
