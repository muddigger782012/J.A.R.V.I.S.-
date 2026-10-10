package com.assistant.core.services

object CommandPhrases {
    fun navigationDestination(text: String): String? {
        val match = Regex("(?i)^(?:please\\s+)?open\\s+(?:google\\s+)?maps\\s+(?:(?:and\\s+)?(?:give|show|get)\\s+me\\s+directions\\s+to|(?:and\\s+)?(?:navigate|take\\s+me|directions)\\s+to|to)\\s+(.+)$")
            .matchEntire(text.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }
    fun appName(text: String): String? {
        val cleaned = text.trim().removePrefix("please ")
        if (!cleaned.startsWith("open ") || cleaned in setOf("open settings", "open voice settings")) return null
        return cleaned.removePrefix("open ").removeSuffix(" please").removeSuffix(" for me").trim().takeIf { it.isNotBlank() }
    }
}
