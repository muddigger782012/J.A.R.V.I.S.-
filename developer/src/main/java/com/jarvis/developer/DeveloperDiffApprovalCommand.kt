package com.jarvis.developer

import java.util.Locale

/** Explicit owner authorizations are never inferred from AI content or natural language. */
internal object DeveloperDiffApprovalCommand {
    private val pattern = Regex("^approve developer diff ([a-f0-9]{16})$")
    fun isAttempt(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).startsWith("approve developer diff")
    fun parse(message: String): String? =
        pattern.matchEntire(message.trim().lowercase(Locale.ROOT))?.groupValues?.get(1)
    fun matches(prefix: String, proposalId: String): Boolean =
        prefix.matches(Regex("[a-f0-9]{16}")) &&
            proposalId.matches(Regex("[a-f0-9]{64}")) &&
            proposalId.startsWith(prefix)
}
