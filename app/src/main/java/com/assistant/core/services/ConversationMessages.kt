package com.assistant.core.services

object ConversationMessages {
    fun chatMessage(raw: String): String? {
        val message = raw.removePrefix("[BG] ")
        if (message.startsWith("You:") || message.startsWith("J.A.R.V.I.S.:")) return message
        // Only actual dialogue belongs in saved chat. Runtime producers use
        // several prefixes (and sometimes none), so a diagnostic denylist is
        // insufficient: new status messages must not evict real conversations.
        return null
    }
}
