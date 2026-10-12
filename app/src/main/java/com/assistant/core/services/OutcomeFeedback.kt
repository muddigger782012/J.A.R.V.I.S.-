package com.assistant.core.services

import java.util.Locale

/** Exact bounded outcome reports: never infer verification from a substring or cloud reply. */
object OutcomeFeedback {
    enum class Result { SUCCESS, FAILURE, UNCLEAR }
    private val success = setOf(
        "it worked", "it works", "it work", "that worked", "that works", "it succeeded",
        "the action worked", "the action succeeded"
    )
    private val failure = setOf(
        "it failed", "it didn't work", "it did not work", "it doesn't work", "it does not work",
        "it isn't working", "it is not working", "that failed", "that didn't work", "that did not work",
        "the action failed", "the action did not work"
    )
    private val unclear = setOf(
        "not sure", "i'm not sure", "i am not sure", "maybe it worked", "it might have worked",
        "it didn't fail", "it did not fail", "the action didn't fail", "the action did not fail"
    )
    fun parse(text: String): Result? {
        val cleaned = text.lowercase(Locale.ROOT).replace('’', '\'').trim()
            .trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        return when (cleaned) {
            in success -> Result.SUCCESS
            in failure -> Result.FAILURE
            in unclear -> Result.UNCLEAR
            else -> null
        }
    }
}
