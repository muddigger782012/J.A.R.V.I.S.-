package com.assistant.core.services

/**
 * Conservative decision policy for optional online research in normal chat.
 * Device commands, short conversational replies, and offline-only requests must
 * not be redirected to the search pipeline.
 */
object ConversationalResearchPolicy {
    enum class Decision { ANSWER_LOCALLY, OFFER_RESEARCH, RESEARCH }

    fun decide(message: String, online: Boolean, deviceCommand: Boolean = false): Decision {
        val query = message.trim().lowercase()
        if (!online || deviceCommand || query.length < 12) return Decision.ANSWER_LOCALLY
        if (query.startsWith("call ") || query.startsWith("text ") ||
            query.startsWith("open ") || query.startsWith("set ") ||
            query.startsWith("navigate ") || query.startsWith("remind me") ||
            query.startsWith("turn on ") || query.startsWith("turn off ")) return Decision.ANSWER_LOCALLY
        val researchCues = listOf("latest ", "current ", "recent ", "this week",
            "look up", "search for", "find sources", "verify ", "source for ")
        if (researchCues.any { query.contains(it) } ||\n            (Regex("\\btoday\\b").containsMatchIn(query) &&\n                listOf("news", "price", "weather", "announcement", "release", "updates").any { query.contains(it) }))\n            return Decision.RESEARCH
        val technicalCues = listOf("fault code", "torque spec", "wiring diagram",
            "part number", "service manual", "fuel tank capacity", "fluid capacity")
        return if (technicalCues.any { query.contains(it) }) Decision.OFFER_RESEARCH
            else Decision.ANSWER_LOCALLY
    }
}
