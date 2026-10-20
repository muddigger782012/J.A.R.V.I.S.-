package com.assistant.core.services

import java.security.MessageDigest
import java.util.Locale

/**
 * Phase 6: deterministic, read-only proposals from the existing local result journal.
 *
 * Integration: parseCommand(input)?.let { render(generate(resultHistory.snapshot()), it) }.
 * Route these commands before learned/cloud routing and reserve them from teaching. Reading
 * history needs no new collection consent. This object never records feedback, modifies learning,
 * accepts a patch, executes a regression, or sends anything. Separate manual synthetic evaluation
 * lives in ImprovementEvaluation and requires explicit opt-in; this object never collects it.
 *
 * Input is newest first, as returned by IntelligenceResultHistory.snapshot(), and only the first
 * MAX_RECORDS rows are inspected. Timestamps plus canonical targets conservatively identify attempts
 * because the journal has no attempt IDs. Same-millisecond rows for a target count once, even with
 * different wording/details. Retries recorded at different times cannot reliably be deduplicated.
 * Audit SUCCESS, learning counters, free-text details and transcript wording are never verification.
 */
object SupervisedImprovement {
    const val MAX_RECORDS = IntelligenceResultHistory.LIMIT
    const val MAX_PROPOSALS = 20
    const val MIN_OCCURRENCES = 2

    enum class Command { REVIEW_PROPOSALS, STATUS }
    enum class Category(val label: String) {
        USER_REPORTED_FAILURE("User-reported failure"),
        VERIFICATION_GAP("Device result not verified"),
        UNCLEAR_OUTCOME("Outcome still unclear"),
        CONFLICTING_OUTCOMES("Conflicting results for one attempt")
    }
    enum class ActionFamily(val label: String) {
        MEDIA_VOLUME("Media volume"), TIMER("Timer"), APP_LAUNCH("App launch"),
        APP_UPDATE("App updater"), OTHER("Other local action")
    }
    enum class State(val label: String) {
        NEEDS_REVIEW("Needs human review"),
        AWAITING_VERIFICATION("Recovery reported by user; device verification pending"),
        MONITORING("New evidence after recovery; recurrence not yet established"),
        RESOLVED("Resolved by later device verification of this target")
    }
    data class RegressionCase(val name: String, val scenario: String, val expected: String)
    data class Evidence(
        val occurrences: Int,
        val activeOccurrences: Int,
        val userConfirmations: Int,
        val deviceVerifiedSuccesses: Int,
        /** Local links into the existing journal; no raw command, app label or detail is copied. */
        val problemTimes: List<Long>,
        val lastDeviceVerification: Long?,
        val lastUserConfirmation: Long?
    )
    data class Proposal(
        /** Opaque local reference, never an executable capability or an approval token. */
        val id: String,
        val family: ActionFamily,
        val category: Category,
        val state: State,
        val evidence: Evidence,
        val suggestedReview: String,
        /** Synthetic fixtures only. The original private phrase is not a regression fixture. */
        val regressionCases: List<RegressionCase>
    )
    data class Report(
        val inspectedRecords: Int,
        val ignoredInvalidRecords: Int,
        val coalescedRecords: Int,
        val distinctAttempts: Int,
        val inputTruncated: Boolean,
        val userReportedFailures: Int,
        val unverifiedOutcomes: Int,
        val unclearOutcomes: Int,
        val conflictingOutcomes: Int,
        val userConfirmations: Int,
        val deviceVerifiedSuccesses: Int,
        val belowThresholdGroups: Int,
        val openProposals: Int,
        val resolvedProposals: Int,
        val omittedProposals: Int,
        val proposals: List<Proposal>
    )

    /** Only these whole commands; normalization allows case, whitespace and final punctuation. */
    fun parseCommand(text: String): Command? {
        if (text.length > 80) return null
        return when (normalize(text).trimEnd('.', '?', '!').trim()) {
            "review improvement proposals" -> Command.REVIEW_PROPOSALS
            "improvement status" -> Command.STATUS
            else -> null
        }
    }

    fun generate(journal: List<IntelligenceResultHistory.Entry>): Report {
        val bounded = journal.take(MAX_RECORDS)
        val valid = bounded.filter(::valid)
        val attempts = valid.groupBy { AttemptKey(it.time, target(it.action)) }.map { (key, rows) ->
            Attempt(key.time, key.target, classify(rows.map { it.outcome }.toSet()))
        }.sortedBy { it.time }
        val candidates = mutableListOf<Proposal>()
        var belowThreshold = 0
        attempts.groupBy { it.target }.forEach { (target, events) ->
            val verified = events.filter { it.kind == Kind.DEVICE_VERIFIED }
            val confirmed = events.filter { it.kind == Kind.USER_CONFIRMED }
            val lastVerified = verified.lastOrNull()?.time
            val lastConfirmed = confirmed.lastOrNull()?.time
            Category.values().forEach categoryLoop@ { category ->
                val problems = events.filter { it.kind.category == category }
                if (problems.isEmpty()) return@categoryLoop
                // A verified success ends an episode. Isolated issues on either side must not
                // combine into a fabricated recurrence; user confirmation alone cannot end it.
                var episodeCount = 0
                var recurrent = false
                events.forEach { event ->
                    if (event.kind == Kind.DEVICE_VERIFIED) episodeCount = 0
                    else if (event.kind.category == category) {
                        episodeCount++
                        if (episodeCount >= MIN_OCCURRENCES) recurrent = true
                    }
                }
                if (!recurrent) {
                    belowThreshold++
                    return@categoryLoop
                }
                val active = problems.count { lastVerified == null || it.time > lastVerified }
                val state = when {
                    active == 0 -> State.RESOLVED
                    active < MIN_OCCURRENCES -> State.MONITORING
                    lastConfirmed != null && lastConfirmed > problems.last().time -> State.AWAITING_VERIFICATION
                    else -> State.NEEDS_REVIEW
                }
                candidates += Proposal(
                    fingerprint("${target.reference}:${category.name}"), target.family, category, state,
                    Evidence(problems.size, active, confirmed.size, verified.size,
                        problems.map { it.time }, lastVerified, lastConfirmed),
                    review(target.family, category), regressions(target.family, category)
                )
            }
        }
        val ordered = candidates.sortedWith(compareBy<Proposal> { it.state == State.RESOLVED }
            .thenByDescending { it.evidence.problemTimes.last() }.thenBy { it.id })
        return Report(
            bounded.size, bounded.size - valid.size, valid.size - attempts.size, attempts.size,
            journal.size > MAX_RECORDS,
            attempts.count { it.kind == Kind.USER_FAILED }, attempts.count { it.kind == Kind.NOT_VERIFIED },
            attempts.count { it.kind == Kind.UNCLEAR }, attempts.count { it.kind == Kind.CONFLICTING },
            attempts.count { it.kind == Kind.USER_CONFIRMED }, attempts.count { it.kind == Kind.DEVICE_VERIFIED },
            belowThreshold, candidates.count { it.state != State.RESOLVED },
            candidates.count { it.state == State.RESOLVED }, (ordered.size - MAX_PROPOSALS).coerceAtLeast(0),
            ordered.take(MAX_PROPOSALS)
        )
    }

    fun render(report: Report, command: Command = Command.REVIEW_PROPOSALS): String = buildString {
        appendLine("Supervised improvement: ${report.openProposals} open, ${report.resolvedProposals} resolved proposals.")
        appendLine("Distinct recorded attempts reviewed: ${report.distinctAttempts}. " +
            "User-reported failures: ${report.userReportedFailures}; unverified: ${report.unverifiedOutcomes}; " +
            "unclear: ${report.unclearOutcomes}; conflicting: ${report.conflictingOutcomes}.")
        appendLine("User-confirmed successes: ${report.userConfirmations}; device-verified successes: ${report.deviceVerifiedSuccesses}.")
        if (report.belowThresholdGroups > 0)
            appendLine("${report.belowThresholdGroups} target/category groups have insufficient evidence of recurrence.")
        if (command == Command.REVIEW_PROPOSALS) {
            if (report.proposals.isEmpty()) appendLine("No recurrent issue meets the evidence threshold.")
            report.proposals.forEachIndexed { index, proposal ->
                appendLine("${index + 1}. ${proposal.family.label}: ${proposal.category.label}. ${proposal.state.label}.")
                appendLine("Evidence: ${proposal.evidence.occurrences} distinct occurrences; " +
                    "${proposal.evidence.activeOccurrences} since the last device verification.")
                appendLine("Review proposal: ${proposal.suggestedReview}")
                proposal.regressionCases.forEach {
                    appendLine("Regression: ${it.name}. ${it.scenario} Expected: ${it.expected}")
                }
            }
        }
        if (report.inputTruncated) appendLine("Only the bounded newest journal window was inspected.")
        if (report.ignoredInvalidRecords > 0) appendLine("Invalid journal rows were ignored.")
        if (report.coalescedRecords > 0) appendLine("Same-time records for one target were counted as one attempt.")
        if (report.omittedProposals > 0) appendLine("${report.omittedProposals} proposals are outside this bounded display.")
        append("This review only reads existing local history. Separate improvement evaluation is opt-in; use 'review improvement evaluation' to inspect its setting and manual reports. " +
            "Counts describe recorded evidence, not measured reliability or a proven root cause. " +
            "A person must review any proposed change and its regression results before approval. " +
            "No code, learning, permissions or deployment was changed.")
    }

    private enum class Kind(val category: Category? = null) {
        USER_FAILED(Category.USER_REPORTED_FAILURE), NOT_VERIFIED(Category.VERIFICATION_GAP),
        UNCLEAR(Category.UNCLEAR_OUTCOME), CONFLICTING(Category.CONFLICTING_OUTCOMES),
        USER_CONFIRMED, DEVICE_VERIFIED
    }
    private data class Target(val family: ActionFamily, val reference: String)
    private data class AttemptKey(val time: Long, val target: Target)
    private data class Attempt(val time: Long, val target: Target, val kind: Kind)

    private fun classify(outcomes: Set<IntelligenceResultHistory.Outcome>): Kind {
        val device = IntelligenceResultHistory.Outcome.DEVICE_VERIFIED in outcomes
        val user = IntelligenceResultHistory.Outcome.USER_CONFIRMED in outcomes
        val failed = IntelligenceResultHistory.Outcome.USER_FAILED in outcomes
        val unverified = IntelligenceResultHistory.Outcome.NOT_VERIFIED in outcomes
        val unclear = IntelligenceResultHistory.Outcome.UNCLEAR in outcomes
        return when {
            (device || user) && (failed || unverified || unclear) -> Kind.CONFLICTING
            failed -> Kind.USER_FAILED
            unverified -> Kind.NOT_VERIFIED
            unclear -> Kind.UNCLEAR
            device -> Kind.DEVICE_VERIFIED
            else -> Kind.USER_CONFIRMED
        }
    }

    private fun valid(entry: IntelligenceResultHistory.Entry) = entry.time >= 0 &&
        entry.phrase.isNotBlank() && entry.phrase.length <= 240 &&
        entry.action.isNotBlank() && entry.action.length <= 160 &&
        entry.detail.isNotBlank() && entry.detail.length <= 320

    private fun normalize(text: String) = text.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    private fun target(action: String): Target {
        val normalized = normalize(action).trimEnd('.', '?', '!').trim()
        val direction = when (normalized) {
            "change media volume up", "raise media volume one step", "volume up" -> "up"
            "change media volume down", "lower media volume one step", "volume down" -> "down"
            else -> null
        }
        val level = Regex("set media volume to ([0-9]{1,3})% \\(nearest supported level\\)")
            .matchEntire(normalized)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
        val timer = Regex("start a ([0-9]{1,5})-second timer").matchEntire(normalized)
            ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..86400 }
        val (family, key) = when {
            direction != null -> ActionFamily.MEDIA_VOLUME to "volume:$direction"
            level != null -> ActionFamily.MEDIA_VOLUME to "volume:level:$level"
            timer != null -> ActionFamily.TIMER to "timer:$timer"
            normalized in setOf("open the j.a.r.v.i.s. updater",
                "open the j.a.r.v.i.s. updater (android installation approval still required)") ->
                ActionFamily.APP_UPDATE to "updater"
            normalized.startsWith("open ") -> ActionFamily.APP_LAUNCH to normalized
            else -> ActionFamily.OTHER to normalized
        }
        // A hash is only a private local grouping reference. It is not an anonymized export format.
        return Target(family, fingerprint(key))
    }

    private fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun review(family: ActionFamily, category: Category): String {
        val evidenceReview = when (category) {
            Category.USER_REPORTED_FAILURE -> "Reproduce the reported failure locally; inspect rejection and outcome handling without assuming its root cause."
            Category.VERIFICATION_GAP -> "Inspect the verification path and missing readback; an unverified result does not prove execution failed."
            Category.UNCLEAR_OUTCOME -> "Inspect pending-outcome handling and preserve uncertainty without recording success or collecting more feedback."
            Category.CONFLICTING_OUTCOMES -> "Inspect attempt correlation and conflicting result recording before trusting either result."
        }
        val actionReview = when (family) {
            ActionFamily.MEDIA_VOLUME -> "Check supported volume bounds, nearest-level rounding and device readback."
            ActionFamily.TIMER -> "Check the bounded duration and timer request; Android accepting a request is not proof a timer started."
            ActionFamily.APP_LAUNCH -> "Check exact installed-app binding and launch rejection; request acceptance is not proof the intended app opened."
            ActionFamily.APP_UPDATE -> "Check updater launch separately from installation; installation still requires Android approval."
            ActionFamily.OTHER -> "Identify one bounded existing handler in the linked local journal before proposing an implementation change."
        }
        return "$evidenceReview $actionReview Review a minimal correction and run synthetic regressions before requesting human approval."
    }

    private fun regressions(family: ActionFamily, category: Category): List<RegressionCase> {
        val reproduction = when (category) {
            Category.USER_REPORTED_FAILURE -> RegressionCase("Reported failure stays a report",
                "A synthetic pending action receives the exact feedback 'it failed'.",
                "Record a user-reported failure once; do not claim device verification or learn success.")
            Category.VERIFICATION_GAP -> RegressionCase("Missing verification stays unverified",
                "A synthetic action returns without matching device readback.",
                "Keep the result unverified; do not infer a root cause or save a successful lesson.")
            Category.UNCLEAR_OUTCOME -> RegressionCase("Uncertainty remains pending",
                "A synthetic pending action receives the exact feedback 'not sure'.",
                "Preserve the original pending target and expiry; do not record success or failure.")
            Category.CONFLICTING_OUTCOMES -> RegressionCase("Conflicts fail closed",
                "One target and timestamp contain both failure and success records.",
                "Count one conflicting attempt; do not count a verified recovery.")
        }
        val boundedAction = when (family) {
            ActionFamily.MEDIA_VOLUME -> RegressionCase("Volume bounds and readback",
                "Use a synthetic stream at its maximum, then at a supported middle level.",
                "An unchanged step remains unverified; only matching requested device readback verifies success.")
            ActionFamily.TIMER -> RegressionCase("Bounded timer request",
                "Request a synthetic 60-second timer with an accepting and a rejecting timer handler.",
                "Reject out-of-range durations; distinguish launch failure, awaiting user outcome and device verification.")
            ActionFamily.APP_LAUNCH -> RegressionCase("Exact app binding",
                "Resolve a synthetic Test App, an ambiguous label and a missing app.",
                "Only the unique installed target may be requested; acceptance alone is not verified success.")
            ActionFamily.APP_UPDATE -> RegressionCase("Updater is not installation",
                "Open a synthetic updater while installation approval is absent.",
                "Never report an installed update or bypass Android approval.")
            ActionFamily.OTHER -> RegressionCase("Unknown handler remains bounded",
                "A synthetic problem has no single supported local handler.",
                "Keep it for human review without inventing a handler or executing a proposed change.")
        }
        return listOf(reproduction, boundedAction, RegressionCase("Target-specific recovery",
            "After recurring problems, verify a different target, then the original target on a later attempt.",
            "Only later device verification of the original target resolves it; a new problem reopens monitoring."))
    }
}
