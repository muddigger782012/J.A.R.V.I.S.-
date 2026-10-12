package com.assistant.core.services

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Shared by chat, background/live speech and the settings audition. No cloud requests. */
object AssistantSpeechVoice {
    private fun candidate(voice: Voice) = AssistantVoicePolicy.Candidate(
        voice.name, voice.locale.country, voice.quality, voice.latency, voice.locale.language,
        voice.isNetworkConnectionRequired, voice.features?.contains("notInstalled") != true
    )

    fun available(tts: TextToSpeech): List<Voice> = tts.voices.orEmpty()
        .filter { AssistantVoicePolicy.eligible(candidate(it)) }
        .sortedWith(compareBy<Voice> { if (it.locale.country == "GB") 0 else 1 }.thenBy { it.name })

    fun rememberPreviousVoice(tts: TextToSpeech, prefs: AssistantVoicePreferences) {
        if (prefs.voiceName(AssistantVoiceProfile.FEMALE).isNotBlank()) return
        // The previous code called setLanguage AFTER selecting a voice, which reset
        // it to the engine's locale default. Preserve that actual voice on upgrade.
        val legacy = available(tts).maxByOrNull { voice ->
            val name = voice.name.lowercase(Locale.US)
            val country = when (voice.locale.country.uppercase(Locale.US)) { "GB" -> 300; "US" -> 180; else -> 100 }
            country + voice.quality * 2 + (600 - voice.latency).coerceAtLeast(0) +
                when { name.contains("male") -> 120; name.contains("m") -> 30; else -> 0 }
        }
        tts.setLanguage(legacy?.locale ?: Locale.UK)
        tts.voice?.let { prefs.rememberFemaleVoice(it.name) }
    }

    data class Result(val usable: Boolean, val description: String)

    fun apply(tts: TextToSpeech, profile: AssistantVoiceProfile, preferred: String, femaleVoice: String = ""): Result {
        val voices = available(tts)
        val candidates = voices.map(::candidate)
        val female = AssistantVoicePolicy.select(candidates, AssistantVoiceProfile.FEMALE, femaleVoice)
        val chosen = AssistantVoicePolicy.select(candidates, profile, preferred,
            if (profile == AssistantVoiceProfile.ORIGINAL) female?.name.orEmpty() else "")
        val voice = voices.firstOrNull { it.name == chosen?.name }
            ?: return Result(false, if (voices.isNotEmpty())
                "No distinct voice is installed for Original. Install another offline English voice in Android speech settings, or use Default (Female)."
                else "No installed offline English voice. Open Android speech settings to install one.")
        // setVoice supplies its locale. Calling setLanguage afterwards would undo it.
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            return Result(false, "This speech engine could not activate the selected voice. Try another voice.")
        }
        if (tts.voice?.name != voice.name) return Result(false, "The speech engine did not confirm the selected voice. Choose another installed voice.")
        tts.setPitch(profile.pitch)
        tts.setSpeechRate(profile.rate)
        val fallback = if (preferred.isNotBlank() && preferred != voice.name) "Saved voice unavailable; using " else "Using "
        val accent = if (profile == AssistantVoiceProfile.ORIGINAL && voice.locale.country != "GB")
            " No distinct British voice is available; this uses another English accent." else ""
        return Result(true, "$fallback${voice.name} (${voice.locale.displayName}).$accent\nInstalled offline voices: ${voices.map { AssistantVoicePolicy.identity(it.name) }.distinct().size}")
    }
}
