package com.assistant.core.services

import android.content.Context
import android.content.SharedPreferences

class AssistantVoicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("assistant_speech_voice", Context.MODE_PRIVATE)
    fun selected() = AssistantVoiceProfile.fromId(prefs.getString("profile", null))
    fun voiceName(profile: AssistantVoiceProfile) = prefs.getString("voice_${profile.id}", "").orEmpty()
    fun save(profile: AssistantVoiceProfile, voiceName: String) {
        prefs.edit().putString("profile", profile.id).putString("voice_${profile.id}", voiceName).apply()
    }
    fun rememberFemaleVoice(name: String) {
        if (voiceName(AssistantVoiceProfile.FEMALE).isBlank()) {
            prefs.edit().putString("voice_female", name).apply()
        }
    }
    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
