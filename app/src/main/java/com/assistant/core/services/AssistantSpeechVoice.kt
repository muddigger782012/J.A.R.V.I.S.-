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

    fun apply(tts: TextToSpeech, profile: AssistantVoiceProfile, preferred: String): Result {
        val voices = available(tts)
        val chosen = AssistantVoicePolicy.select(voices.map(::candidate), profile, preferred)
        val voice = voices.firstOrNull { it.name == chosen?.name }
            ?: return Result(false, "No installed offline English voice. Open Android speech settings to install one.")
        // setVoice supplies its locale. Calling setLanguage afterwards would undo it.
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            return Result(false, "This speech engine could not activate the selected voice. Try another voice.")
        }
        tts.setPitch(profile.pitch)
        tts.setSpeechRate(profile.rate)
        val fallback = if (preferred.isNotBlank() && preferred != voice.name) "Saved voice unavailable; using " else "Using "
        val accent = if (profile == AssistantVoiceProfile.ORIGINAL && voice.locale.country != "GB")
            " No British voice is installed; this is a fallback." else ""
        return Result(true, "$fallback${voice.name} (${voice.locale.displayName}).$accent")
    }
}
