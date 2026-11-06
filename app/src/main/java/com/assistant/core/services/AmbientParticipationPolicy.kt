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

        // UNKNOWN is expected until real speaker identification exists.
        // Selecting proactive participation at high initiative is the user's
        // explicit opt-in to occasional *advisory* comments from nearby talk;
        // a voice VERIFIED as OTHER still obeys replyToOtherSpeakers above.
        // Do not infer ownership, accept approvals, or convert ordinary room
        // conversation into commands.
        val proactive = (preferences.style == "active" && preferences.initiative >= 60) ||
            (preferences.style == "companion" && preferences.initiative >= 80)
        val usefulPrompt = ADVISORY_PROMPT.containsMatchIn(words) ||
            (preferences.initiative >= 90 && TROUBLESHOOTING_PROMPT.containsMatchIn(words))
        val last = lastOfferAt
        if (proactive && usefulPrompt &&
            (last == null || now >= last && now - last >= 45_000L)) {
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
            """\b(?:i wonder (?:what|why|how)|i can'?t figure out (?:why|how|what)|what do you think|can someone help me understand|any ideas(?: why| how)?|what should (?:we|i) do|why does (?:it|this|the)|how (?:can|do) (?:i|we)|do you think (?:it|that)|could use (?:a hand|some help))\b""",
            RegexOption.IGNORE_CASE
        )
        private val TROUBLESHOOTING_PROMPT = Regex(
            """\b(?:it keeps (?:stalling|surging|missing|dying|cutting out)|this (?:truck|engine|machine|app|microphone) (?:is|keeps|won't|doesn't)|i'?m trying to (?:fix|figure out|diagnose|repair)|i still can'?t get (?:it|this) to work)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
