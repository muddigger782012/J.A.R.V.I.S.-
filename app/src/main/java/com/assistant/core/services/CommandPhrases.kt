package com.assistant.core.services

object CommandPhrases {
    fun isLastMissedCall(text: String): Boolean {
        val normalized = text.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        return normalized in setOf("who was my last missed call", "who was the last missed call", "what was my last missed call",
            "show my last missed call", "last missed call", "my last missed call", "who called me last that i missed",
            "who was my most recent missed call", "show my most recent missed call")
    }

    fun isAmazonMusicRequest(text: String): Boolean {
        val normalized = text.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ").trim().removePrefix("please ")
            .removeSuffix(" please").removeSuffix(" for me")
        return normalized in setOf("play prime music", "play amazon music", "play amazon prime music",
            "open prime music", "open amazon music", "open amazon prime music")
    }

    fun navigationDestination(text: String): String? {
        val match = Regex("(?i)^(?:please\\s+)?open\\s+(?:google\\s+)?maps\\s+(?:(?:and\\s+)?(?:give|show|get)\\s+me\\s+directions\\s+to|(?:and\\s+)?(?:navigate|take\\s+me|directions)\\s+to|to)\\s+(.+)$")
            .matchEntire(text.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }
    fun appName(text: String): String? {
        var name = Regex("(?i)^(?:please\\s+)?open\\s+(.+)$").matchEntire(text.trim())?.groupValues?.get(1) ?: return null
        repeat(2) { name = name.replace(Regex("(?i)\\s+(?:please|for\\s+me)$"), "").trim() }
        return name.takeIf { it.isNotBlank() && it.lowercase(java.util.Locale.ROOT) !in setOf("settings", "voice settings") }
    }
}
