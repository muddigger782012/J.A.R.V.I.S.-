package com.assistant.core.services

/** A duration edit is data for a pending proposal, never a new timer command. */
object PendingTimerCorrection {
    private val pattern = Regex("(?i)^(?:actually[, :]? )?(?:no[, :]? )?make (?:that|it) (.+ (?:seconds?|minutes?|hours?))$")
    private fun match(text: String) = pattern.matchEntire(text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " "))
    fun isRequest(text: String): Boolean = match(text) != null
    fun action(text: String): LearnableDeviceAction? {
        val edit = match(text) ?: return null
        return ExplicitCommandTeaching.resolveDevice("start timer for ${edit.groupValues[1]}") { null }
    }
}
