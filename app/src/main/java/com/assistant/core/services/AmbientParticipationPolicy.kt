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
    private var lastAmbientAnswerAt: Long? = null

    fun reset() {
        addressedAt = null
        lastOfferAt = null
        lastAmbientAnswerAt = null
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

        // Keep a bounded conversation alive after a direct address or an
        // offered ambient answer. Natural replies should not require the wake
        // word every turn, but they must never gain command permissions.
        val recentConversation = addressedAt
        // One Android transcript can be replayed via different provider callbacks.
        // Do not fire multiple ambient answers for rapid duplicate turns.
        val priorAmbientAnswer = lastAmbientAnswerAt
        val ambientReplyGap = priorAmbientAnswer == null ||
            (now >= priorAmbientAnswer && now - priorAmbientAnswer >= 800L)
        val isConversationalFollowUp = FOLLOW_UP.containsMatchIn(words) ||
            (words.length <= 140 && CONTEXTUAL_REPLY.containsMatchIn(words))
        if (preferences.contextEnabled && recentConversation != null &&
            now >= recentConversation && now - recentConversation <= 45_000L &&
            isConversationalFollowUp && ambientReplyGap &&
            !DEVICE_COMMAND.containsMatchIn(words)) {
            addressedAt = now
            lastAmbientAnswerAt = now
            return Decision.OFFER_HELP // Strictly text-only; NOT the device router.
        }

        // This policy is called only while the user has explicitly opened
        // Live Chat. Active/very-high-initiative Companion opts into answering
        // question-like room conversation without a wake word. UNKNOWN is not
        // proof of the owner's identity: OFFER_HELP must NEVER use the device
        // action router, confirmations, or privilege-bearing commands.
        val permitted = speaker != Speaker.OTHER || preferences.replyToOtherSpeakers
        val advisory = ADVISORY_PROMPT.containsMatchIn(words)
        val question = CONVERSATIONAL_QUESTION.containsMatchIn(words)
        val relevantObservation = PROBLEM_STATEMENT.containsMatchIn(words) ||
            TROUBLESHOOTING_PROMPT.containsMatchIn(words)
        // Even the approved Companion 55% level can weigh in when asked a
        // clearly open-ended question. Raising initiative broadens this to
        // natural questions and then relevant problem statements; it no longer
        // requires a verified owner or the wake word for every useful reply.
        val optedIntoAmbientTalk = when (preferences.style) {
            "companion" -> preferences.initiative >= 55 && advisory ||
                preferences.initiative >= 85 && question ||
                preferences.initiative >= 100 && relevantObservation
            "active" -> preferences.initiative >= 50 && advisory ||
                preferences.initiative >= 75 && question ||
                preferences.initiative >= 90 && relevantObservation
            else -> false
        }
        val cooldown = (90_000L - preferences.initiative * 750L).coerceIn(15_000L, 75_000L)
        val last = lastOfferAt
        if (optedIntoAmbientTalk && permitted && ambientReplyGap &&
            !DEVICE_COMMAND.containsMatchIn(words) &&
            (last == null || now - last >= cooldown)) {
            lastOfferAt = now
            lastAmbientAnswerAt = now
            addressedAt = now // A natural reply to this offer can keep the thread going.
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
            """^(?:and |so |then |well )?(?:what about|how about|what should (?:we|i) (?:try|do) next|can you|could you|would you|will you|please |tell me|explain|look that up|check that|why |how |what do you think)\b""",
            RegexOption.IGNORE_CASE
        )
        private val CONTEXTUAL_REPLY = Regex(
            """^\s*(?:(?:yes|yeah|yep|no|nope|right|exactly|correct|true)\b|(?:that's|that is)\s+(?:right|correct|it|not it|what i meant|exactly what i meant)\b|(?:it|this|that)\s+(?:still|didn't|doesn't|won't|isn't|wasn't)\b|i\s+(?:tried|did|mean|think|agree|don't think|can't|cannot)\b|(?:keep going|go on|tell me more|explain that|okay[,.]?\s+but)\b)""",
            RegexOption.IGNORE_CASE
        )
        private val ADVISORY_PROMPT = Regex(
            """\b(?:i wonder (?:what|why|how)|i can'?t figure out (?:why|how|what)|what do you think|can someone help me understand|any ideas(?: why| how)?|what should (?:we|i) do|how (?:can|do) (?:i|we)|do you think (?:it|that)|could use (?:a hand|some help))\b""",
            RegexOption.IGNORE_CASE
        )
        private val CONVERSATIONAL_QUESTION = Regex(
            """^(?:(?:well|so|and|okay|hey)\s+)?(?:what|why|how|where|when|who|is|are|can|could|should|would|do|does|did)\b""",
            RegexOption.IGNORE_CASE
        )
        private val PROBLEM_STATEMENT = Regex(
            """\b(?:i (?:can'?t|cannot) (?:figure|understand)|(?:it|this|that|he|she|the microphone|my microphone|the app|the engine|the truck) (?:keeps?|doesn'?t|won'?t)\b|something(?:'s| is) wrong|(?:this|it) is driving me crazy)""",
            RegexOption.IGNORE_CASE
        )
        private val TROUBLESHOOTING_PROMPT = Regex(
            """\b(?:it keeps (?:stalling|surging|missing|dying|cutting out)|this (?:truck|engine|machine|app|microphone) (?:is|keeps|won't|doesn't)|i'?m trying to (?:fix|figure out|diagnose|repair)|i still can'?t get (?:it|this) to work)\b""",
            RegexOption.IGNORE_CASE
        )
        private val DEVICE_COMMAND = Regex(
            """^\s*(?:please\s+)?(?:(?:can|could|would|will)\s+you\s+(?:please\s+)?)?(?:stop|start|open|set|turn|call|dial|send|text|delete|install|uninstall|run|launch|navigate|execute|tap|press|unlock|lock|approve|pay|transfer|buy|purchase|change|disable|enable|restart)\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
