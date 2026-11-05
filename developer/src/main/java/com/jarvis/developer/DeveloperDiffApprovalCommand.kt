package com.jarvis.developer

import java.util.Locale

/** Explicit owner authorizations are never inferred from AI content or natural language. */
internal object DeveloperDiffApprovalCommand {
    // Internal typed-consent marker, never accepted as a literal user suffix.
    // PatchWorkbench still binds approval to its source-verified current review ID.
    const val REVIEWED = "reviewed-current-proposal"
    private const val SHORT_APPROVAL = "approve developer diff"
    private val pattern = Regex("^approve developer diff ([a-f0-9]{16})$")
    fun isAttempt(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).startsWith(SHORT_APPROVAL)
    fun parse(message: String): String? {
        val normalized = message.trim().lowercase(Locale.ROOT)
        if (normalized == SHORT_APPROVAL) return REVIEWED
        return pattern.matchEntire(normalized)?.groupValues?.get(1)
    }
    fun matches(prefix: String, proposalId: String): Boolean =
        proposalId.matches(Regex("[a-f0-9]{64}")) &&
            (prefix == REVIEWED || (prefix.matches(Regex("[a-f0-9]{16}")) &&
                proposalId.startsWith(prefix)))
}
