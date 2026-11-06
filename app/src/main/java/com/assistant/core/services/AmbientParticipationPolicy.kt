package com.assistant.core.services

/**
 * First conservative participation gate for Ambient Companion mode.
 *
 * Without voice diarization an Android transcript has no trusted speaker ID.
 * UNKNOWN is never treated as OWNER for spontaneous replies. Live Chat keeps
 * listening without sending passive room speech to the AI or executing commands.
 */
class AmbientParticipationPolicy(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Speaker { OWNER, OTHER, UNKNOWN }
    enum class Decision { OBSERVE, RESPOND, OFFER_HELP }

    private var addressedAt: Long? = null
    private var lastOfferAt: Long? = null

    fun reset() {
        addressedAt = null
        lastOfferAt = null
    }

    fun decide(
        transcript: String,
        settings: LiveChatCompanionSettings,
        speaker: Speaker = Speaker.UNKNOWN
    ): Decision {
        val words = transcript.trim()
        if (words.isEmpty()) return Decision.OBSERVE
        val preferences = settings.normalized()
        if (speaker == Speaker.OTHER && !preferences.replyToOtherSpeakers) return Decision.OBSERVE
        val now = elapsedMillis()
        if (DIRECT_ADDRESS.containsMatchIn(words) || END_VOCATIVE.containsMatchIn(words)) {
            addressedAt = now
            return Decision.RESPOND
        }
        if (preferences.style == "quiet") return Decision.OBSERVE

        val recentAddress = addressedAt
        if (preferences.contextEnabled && recentAddress != null &&
            now >= recentAddress && now - recentAddress <= 45_000L &&
            FOLLOW_UP.containsMatchIn(words)) {
            addressedAt = now
            return Decision.RESPOND
        }

        // No verified owner diarization exists yet. In active mode a high
        // initiative setting can offer *advice* only on an explicit request
        // for input and only when the owner is known or the user explicitly
        // opted in to broader room participation.
        val speakerPermitted = speaker == Speaker.OWNER || preferences.replyToOtherSpeakers
        val last = lastOfferAt
        if (preferences.style == "active" && preferences.initiative >= 70 &&
            speakerPermitted && ADVISORY_PROMPT.containsMatchIn(words) &&
            (last == null || now - last >= 90_000L)) {
            lastOfferAt = now
            return Decision.OFFER_HELP
        }
        return Decision.OBSERVE
    }

    companion object {
        // A casual mention such as "Jarvis has bugs" is not an address.
        // Only a vocative at the beginning or end of an utterance qualifies.
        private val DIRECT_ADDRESS = Regex(
            """^\s*(?:(?:hey|ok|okay|yo|please)\s+)?jarvis(?:\s*[,!?;:]\s*|\s+(?=(?:can|could|would|will|please|look|find|check|show|tell|explain|what|why|how|when|where|help|stop|start|open|set|turn|remind)\b)|\s*$)""",
            RegexOption.IGNORE_CASE
        )
        private val END_VOCATIVE = Regex(""",\s*jarvis\s*[?.!]*$""", RegexOption.IGNORE_CASE)
        private val FOLLOW_UP = Regex(
            """^(?:and |so |then |well )?(?:what about|how about|can you|could you|would you|will you|please |tell me|explain|look that up|check that|why |how |what do you think)\b""",
            RegexOption.IGNORE_CASE
        )
        private val ADVISORY_PROMPT = Regex(
            """\b(?:i wonder (?:what|why|how)|i can'?t figure out why|what do you think|can someone help me understand)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
