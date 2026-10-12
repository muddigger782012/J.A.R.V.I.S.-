package com.assistant.core.services

import org.json.JSONObject

enum class SpeechChannel { REGULAR, LIVE_CHAT }

data class VoiceResponseOptions(
    val regularSpeech: Boolean = true,
    val liveSpeech: Boolean = true,
    val liveDialogue: Boolean = false
) {
    fun allowsSpeech(channel: SpeechChannel) = if (channel == SpeechChannel.LIVE_CHAT) liveSpeech else regularSpeech
    fun toJson() = JSONObject().put("regularSpeech", regularSpeech).put("liveSpeech", liveSpeech).put("liveDialogue", liveDialogue)
    companion object {
        fun fromJson(json: JSONObject) = VoiceResponseOptions(
            json.optBoolean("regularSpeech", true), json.optBoolean("liveSpeech", true), json.optBoolean("liveDialogue", false)
        )
    }
}
