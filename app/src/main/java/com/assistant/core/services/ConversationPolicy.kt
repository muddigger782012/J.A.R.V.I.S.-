package com.assistant.core.services

import java.util.Locale

/** Conversational fallbacks advise; they never execute, approve, or learn a command. */
object ConversationPolicy {
    enum class Obstacle { OFFLINE, NOT_CONFIGURED, PROVIDER_FAILED }

    fun fallback(input: String, obstacle: Obstacle): String {
        val reason = when (obstacle) {
            Obstacle.OFFLINE -> "An online connection is needed for a flexible answer to that request."
            Obstacle.NOT_CONFIGURED -> "Connect an AI provider in Voice Settings → Weather and AI for a flexible answer. You can use your Gemini key or configured gateway."
            Obstacle.PROVIDER_FAILED -> "The AI provider didn't return a usable answer. Retry, check the selected model and connection, or configure an alternative provider."
        }
        val next = when {
            ConfirmationWords.isConfirmation(input) -> "What would you like to continue with? No device action is awaiting approval."
            Regex("(?i)\\b(?:timer|alarm)\\b").containsMatchIn(input) ->
                "I can still use Android's Clock app: try 'start a timer for 5 minutes' or 'set an alarm for 7 am', with the duration or time you want."
            Regex("(?i)\\b(?:call|dial|phone)\\b").containsMatchIn(input) ->
                "I can open the dialer with 'dial' followed by an exact contact or number. Contacts access is needed for names; you can review the number before calling."
            Regex("(?i)\\b(?:message|text|sms)\\b").containsMatchIn(input) ->
                "I can prepare a message with 'text' followed by a contact, 'saying', and your message, for you to review in the messaging app."
            Regex("(?i)\\b(?:remember|memory|forget)\\b").containsMatchIn(input) ->
                "You can review local preferences with 'show assistant memory', or commands with 'show learned commands'."
            else -> "Time, date, arithmetic and supported Android commands remain available locally. Try 'show offline capabilities' for examples. To record a missing feature for review, say 'request a capability to' followed by your objective."
        }
        return "$reason $next"
    }

    fun isTimeQuery(text: String): Boolean {
        val cleaned = clean(text).replace(Regex("^(?:(?:hey )?jarvis )"), "")
        return cleaned in setOf("time", "what time", "what time is it", "whats the time", "tell me the time",
            "current time", "what is the time", "what is the current time", "whats the current time",
            "what s the time", "what s the current time")
    }

    fun isCalendarQuery(text: String): Boolean {
        val cleaned = clean(text)
        return Regex("^(?:(?:show|read|list)(?: me)? (?:my |the )?(?:calendar|schedule|appointments|events)|" +
            "(?:what is on|what s on|whats on|what do i have on) my (?:calendar|schedule)|" +
            "what do i have|(?:what is |whats )?(?:my |the )?next (?:event|appointment))" +
            "(?: (?:for |on )?(?:today|tomorrow|next week))?$").matches(cleaned)
    }

    fun isScreenQuery(text: String): Boolean = clean(text).removePrefix("please ").removePrefix("can you ") in
        setOf("what is on my screen", "whats on my screen", "what s on my screen", "read my screen", "read screen", "screen context")

    private fun clean(text: String) = text.lowercase(Locale.ROOT).replace('’', '\'')
        .replace("'", " ").replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ").trim()
}
