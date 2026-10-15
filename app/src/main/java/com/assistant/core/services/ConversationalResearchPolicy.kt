package com.assistant.core.services

/**
 * Conservative decision policy for optional online research in normal chat.
 * Device commands, short conversational replies, and offline-only requests must
 * not be redirected to the search pipeline.
 */
object ConversationalResearchPolicy {
    enum class Decision { ANSWER_LOCALLY, OFFER_RESEARCH, RESEARCH }

    fun decide(message: String, online: Boolean, deviceCommand: Boolean = false,
        previousResearchQuery: String? = null): Decision {
        val query = message.trim().lowercase()
        if (!online || deviceCommand || query.isBlank()) return Decision.ANSWER_LOCALLY
        if (query.startsWith("call ") || query.startsWith("text ") ||
            query.startsWith("open ") || query.startsWith("set ") ||
            query.startsWith("navigate ") || query.startsWith("remind me") ||
            query.startsWith("turn on ") || query.startsWith("turn off ")) return Decision.ANSWER_LOCALLY
        // Explicit requests to retrieve documentation are research, not merely an offer.
        // This also handles follow-ups such as "pull up the technical manual for me".
        val documentationRequests = listOf("technical manual", "service manual",
            "repair manual", "shop manual", "operator manual", "workshop manual",
            "technical documentation", "service documentation", "service procedure")
        val retrievalVerbs = listOf("pull up", "find", "locate", "get me", "show me",
            "look up", "search", "download", "retrieve", "access", "open the")
        if ((documentationRequests.any { query.contains(it) } || Regex("\\boperators?[’\x27]?(?:s)?\\s+manual\\b").containsMatchIn(query)) &&
            retrievalVerbs.any { query.contains(it) }) return Decision.RESEARCH
        val followUp = listOf("how much does it hold", "what is its capacity",
            "what about that", "what about its", "is that correct", "can you verify that",
            "what is the part number", "what does the manual say")
        if (!previousResearchQuery.isNullOrBlank() &&
            followUp.any { query.startsWith(it) }) return Decision.RESEARCH
        if (query.length < 12) return Decision.ANSWER_LOCALLY
        val researchCues = listOf("latest ", "current ", "recent ", "this week",
            "look up", "search for", "find sources", "verify ", "source for ")
        if (researchCues.any { query.contains(it) } ||
            (Regex("\\btoday\\b").containsMatchIn(query) &&
                listOf("news", "price", "weather", "announcement", "release", "updates").any { query.contains(it) }))
            return Decision.RESEARCH
        val technicalCues = listOf("fault code", "torque spec", "wiring diagram",
            "part number", "service manual", "fuel tank capacity", "fluid capacity")
        return if (technicalCues.any { query.contains(it) }) Decision.OFFER_RESEARCH
            else Decision.ANSWER_LOCALLY
    }
}
