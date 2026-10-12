package com.assistant.core.services

import java.util.Locale

enum class AssistantVoiceProfile(val id: String, val label: String, val pitch: Float, val rate: Float) {
    ORIGINAL("original", "JARVIS — Original", 0.94f, 0.93f),
    FEMALE("female", "Default (Female)", 0.92f, 0.95f);

    companion object {
        fun fromId(id: String?) = values().firstOrNull { it.id == id } ?: ORIGINAL
    }
}

/** Engine-independent policy. Android exposes no standard voice gender field. */
object AssistantVoicePolicy {
    data class Candidate(
        val name: String, val country: String, val quality: Int, val latency: Int,
        val language: String = "en", val networkRequired: Boolean = false,
        val installed: Boolean = true
    )

    fun eligible(voice: Candidate) = voice.language == "en" && !voice.networkRequired && voice.installed

    fun identity(name: String) = name.lowercase(Locale.US).replace(Regex("[-_](local|network)$"), "")

    fun select(voices: List<Candidate>, profile: AssistantVoiceProfile, preferred: String = "", distinctFrom: String = ""): Candidate? {
        val available = voices.filter(::eligible).filter { distinctFrom.isBlank() || identity(it.name) != identity(distinctFrom) }
        available.firstOrNull { it.name == preferred }?.let { return it }
        if (preferred.isNotBlank()) available.sortedBy { it.name }.firstOrNull { identity(it.name) == identity(preferred) }?.let { return it }
        return available.sortedBy { it.name }.maxByOrNull { voice ->
            val name = voice.name.lowercase(Locale.US)
            val country = when (voice.country.uppercase(Locale.US)) { "GB" -> 10000; "US" -> 1000; else -> 0 }
            // Only explicit name hints count; "female" must never match "male".
            val female = Regex("(^|[^a-z])female([^a-z]|$)").containsMatchIn(name)
            val male = Regex("(^|[^a-z])male([^a-z]|$)").containsMatchIn(name)
            val hint = when {
                profile == AssistantVoiceProfile.ORIGINAL && male -> 2000
                profile == AssistantVoiceProfile.ORIGINAL && female -> -2000
                profile == AssistantVoiceProfile.FEMALE && female -> 2000
                profile == AssistantVoiceProfile.FEMALE && male -> -2000
                else -> 0
            }
            country + hint + voice.quality + (600 - voice.latency).coerceIn(0, 600)
        }
    }
}
