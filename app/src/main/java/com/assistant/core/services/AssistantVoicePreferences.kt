package com.assistant.core.services

import android.content.Context
import android.content.Intent

class AssistantVoicePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val legacy = appContext.getSharedPreferences("assistant_speech_voice", Context.MODE_PRIVATE)
    private val store = AssistantVoiceSelectionStore(appContext.filesDir)
    private fun legacySelection() = AssistantVoiceSelection(AssistantVoiceProfile.fromId(legacy.getString("profile", null)),
        legacy.getString("voice_original", "").orEmpty(), legacy.getString("voice_female", "").orEmpty())
    fun load() = store.load(::legacySelection)
    fun selected() = load().profile
    fun voiceName(profile: AssistantVoiceProfile) = load().voiceName(profile)
    fun save(profile: AssistantVoiceProfile, voiceName: String) {
        store.update(::legacySelection) { current ->
            if (profile == AssistantVoiceProfile.FEMALE) current.copy(profile = profile, femaleVoice = voiceName)
            else current.copy(profile = profile, originalVoice = voiceName)
        }
        notifyChanged()
    }
    fun rememberFemaleVoice(name: String) {
        if (voiceName(AssistantVoiceProfile.FEMALE).isBlank()) {
            store.update(::legacySelection) { current -> if (current.femaleVoice.isBlank()) current.copy(femaleVoice = name) else current }
            notifyChanged()
        }
    }
    private fun notifyChanged() = appContext.sendBroadcast(Intent(ACTION_CHANGED).setPackage(appContext.packageName))
    companion object { const val ACTION_CHANGED = "com.assistant.core.action.ASSISTANT_VOICE_CHANGED" }
}
