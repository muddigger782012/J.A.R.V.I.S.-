package com.assistant.core.services

/**
 * First conservative participation gate for Ambient Companion mode.
 *
 * Without voice diarization an Android transcript has no trusted speaker ID.
 * UNKNOWN is never treated as verified OWNER. In an explicitly started
 * Personal Live Chat the user may select high-initiative participation:
 * question-like unknown speech can generate conversational text ONLY, never
 * device commands, and known OTHER speakers remain subject to their toggle.
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

        // This policy is called only while the user has explicitly opened
        // Live Chat. Active/very-high-initiative Companion opts into answering
        // question-like room conversation without a wake word. UNKNOWN is not
        // proof of the owner's identity: OFFER_HELP must NEVER use the device
        // action router, confirmations, or privilege-bearing commands.
        val optedIntoAmbientTalk = (preferences.style == "active" && preferences.initiative >= 75) ||
            (preferences.style == "companion" && preferences.initiative >= 95)
        val permitted = speaker != Speaker.OTHER || preferences.replyToOtherSpeakers
        val questionLike = CONVERSATIONAL_QUESTION.containsMatchIn(words) ||
            ADVISORY_PROMPT.containsMatchIn(words)
        val cooldown = (50_000L - preferences.initiative * 350L).coerceIn(15_000L, 50_000L)
        val last = lastOfferAt
        if (optedIntoAmbientTalk && permitted && questionLike &&
            !DEVICE_COMMAND.containsMatchIn(words) &&
            (last == null || now - last >= cooldown)) {
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
            """\b(?:i wonder (?:what|why|how)|i can'?t figure out why|what do you think|can someone help me understand|any idea(?:s)?\s+(?:what|why|how)|what(?:'s| is) (?:wrong|going on))\b""",
            RegexOption.IGNORE_CASE
        )
        private val CONVERSATIONAL_QUESTION = Regex(
            """^(?:(?:well|so|and|okay|hey)\s+)?(?:what|why|how|where|when|who|is|are|can|could|should|would|do|does|did)\b""",
            RegexOption.IGNORE_CASE
        )
        private val DEVICE_COMMAND = Regex(
            """^\s*(?:stop|start|open|set|turn|call|dial|send|text|delete|install|uninstall|run|launch|navigate|execute|tap|press)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
