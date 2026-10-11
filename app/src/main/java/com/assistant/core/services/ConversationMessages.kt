package com.assistant.core.services

object ConversationMessages {
    fun chatMessage(raw: String): String? {
        val message = raw.removePrefix("[BG] ")
        if (message.startsWith("You:") || message.startsWith("J.A.R.V.I.S.:")) return message
        if (raw.startsWith("[BG]") || raw.startsWith("[WAKE]") || raw.startsWith("[VOICE]")) return null
        return raw
    }
}
