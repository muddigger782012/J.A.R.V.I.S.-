package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory

/** Local inventory, not a model-health claim or a percentage of the development plan. */
data class IntelligenceOverview(
    val commandEntries: Int, val pausedEntries: Int, val intentExamples: Int, val approvedRoutines: Int,
    val primaryAi: String, val automaticLearning: Boolean
) {
    companion object {
        fun build(commands: List<ValidatedCommandMemory.SavedPhrase>, intents: List<IntentLearningStore.SavedExample>,
            routines: Int, geminiConfigured: Boolean, gatewayConfigured: Boolean, automaticLearning: Boolean) = IntelligenceOverview(
            commands.size, commands.count { it.failures > 0 }, intents.size, routines.coerceAtLeast(0),
            when { geminiConfigured -> "Gemini"; gatewayConfigured -> "Gateway"; else -> "Not configured" }, automaticLearning)
    }
}

object IntelligenceConnectionCheck {
    const val TOKEN = "JARVIS_CONNECTION_OK"
    const val PROMPT = "Connection test only. Reply with exactly JARVIS_CONNECTION_OK. Do not propose or perform any action."
    fun accepted(reply: String?): Boolean = reply?.trim() == TOKEN
}
