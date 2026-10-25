package com.assistant.core.services

/**
 * Shared wire-format rules for user-initiated Assistant -> Developer review drafts.
 * This parser is intentionally pure Kotlin, and NEVER grants execution authority.
 */
object DeveloperReviewProtocol {
    const val VERSION = 1
    const val MAX_LENGTH = 8192
    private val idPattern = Regex("[A-Za-z0-9_-]{1,80}")
    private val allowedKinds = setOf("diagnostics.review.request", "capabilities.request")

    data class Envelope(val version: Int, val kind: String, val requestId: String, val body: String)

    fun validate(envelope: Envelope): Boolean =
        envelope.version == VERSION &&
        envelope.kind in allowedKinds &&
        idPattern.matches(envelope.requestId) &&
        envelope.body.length <= MAX_LENGTH &&
        envelope.body.none { it == '\u0000' }

    /** Text is only a review draft; it is never an executable command. */
    fun asReviewDraft(envelope: Envelope): String {
        require(validate(envelope)) { "Unsupported or oversized Developer review" }
        return buildString {
            appendLine("J.A.R.V.I.S. Developer review draft (no approval)")
            appendLine("Protocol: ${envelope.version}")
            appendLine("Request: ${envelope.requestId}")
            appendLine("Kind: ${envelope.kind}")
            appendLine("Source evidence and repository revision must be independently verified.")
            appendLine("No source writes, merges, signing, publication or installation authorized.")
            append(envelope.body)
        }
    }
}
