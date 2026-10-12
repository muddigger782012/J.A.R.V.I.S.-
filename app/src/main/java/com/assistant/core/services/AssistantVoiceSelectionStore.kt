package com.assistant.core.services

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class AssistantVoiceSelection(
    val profile: AssistantVoiceProfile = AssistantVoiceProfile.ORIGINAL,
    val originalVoice: String = "",
    val femaleVoice: String = ""
) {
    fun voiceName(profile: AssistantVoiceProfile) = if (profile == AssistantVoiceProfile.FEMALE) femaleVoice else originalVoice
    fun toJson() = JSONObject().put("profile", profile.id).put("voice_original", originalVoice).put("voice_female", femaleVoice)
    companion object {
        fun fromJson(json: JSONObject) = AssistantVoiceSelection(AssistantVoiceProfile.fromId(json.optString("profile")),
            json.optString("voice_original"), json.optString("voice_female"))
    }
}

/** Fresh reads and atomic replacement shared by UI and :wake; locks preserve concurrent updates. */
class AssistantVoiceSelectionStore(private val directory: File) {
    private val file get() = File(directory, "assistant-speech-voice.json")
    fun load(fallback: () -> AssistantVoiceSelection = { AssistantVoiceSelection() }): AssistantVoiceSelection =
        if (file.isFile) AssistantVoiceSelection.fromJson(JSONObject(file.readText())) else fallback()

    fun update(fallback: () -> AssistantVoiceSelection, change: (AssistantVoiceSelection) -> AssistantVoiceSelection) = synchronized(writeLock) {
        RandomAccessFile(File(directory, "assistant-speech-voice.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val updated = change(load(fallback))
                val temporary = File(directory, "assistant-speech-voice.json.tmp")
                FileOutputStream(temporary).use { stream ->
                    stream.write(updated.toJson().toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                updated
            }
        }
    }
    companion object { private val writeLock = Any() }
}
