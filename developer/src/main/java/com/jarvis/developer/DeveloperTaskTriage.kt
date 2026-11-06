package com.jarvis.developer

import android.app.Activity
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * A read-only Codex-style "what next?" diagnosis built only from verified,
 * bounded local task checkpoints. Never performs a model call, approval,
 * GitHub write, CI lookup, release dispatch, or device install.
 */
internal object DeveloperTaskTriage {
    private val requests = setOf(
        "what should developer do next",
        "what should jarvis developer do next",
        "what is blocking developer",
        "what's blocking developer",
        "why is developer stuck",
        "diagnose developer task",
        "diagnose developer workflow",
        "show developer blockers",
        "show next developer action",
        "what's next for developer"
    )
    private val jobStates = setOf(
        "QUEUED", "PAUSED", "CANCELLED", "RUNNING", "REVIEW_READY",
        "FAILED", "AWAITING_APPROVAL", "COMMITTING", "OUTCOME_UNKNOWN",
        "VERIFYING", "READY_FOR_REVIEW", "REVIEW_REQUIRED"
    )
    private val results = setOf(
        "PASSED", "FAILED", "RUNNING", "PENDING", "BLOCKED", "UNKNOWN", "NOT_RUN"
    )

    fun isRequest(message: String): Boolean =
        message.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!') in requests

    fun read(activity: Activity): String {
        val root = activity.noBackupFilesDir.canonicalFile
        val plans = TaskPlanStore(File(root, "developer-task-plans")).loadAll()
        val jobs = DevelopmentJobInbox(File(root, "development-inbox")).localSnapshot()
        return summarize(plans, jobs)
    }

    /** Only verified local state labels and bounded plan titles enter the report. */
    fun summarize(plans: List<TaskPlanner.Plan>, jobs: List<JSONObject>): String {
        require(plans.size <= 10 && jobs.size <= 64) { "Unbounded Developer task evidence" }
        plans.forEach(TaskPlanner::validate)
        require(jobs.all { it.optString("state") in jobStates }) {
            "Unknown persisted Developer task state"
        }
        val plan = plans.maxWithOrNull(
            compareBy<TaskPlanner.Plan> { it.updatedAtMillis }.thenBy { it.id }
        )
        val job = jobs.lastOrNull()
        val state = job?.getString("state")
        val checks = job?.optJSONObject("verification")
        fun gate(key: String): String = checks?.optString(key)?.takeIf { it in results }
            ?: "UNVERIFIED"

        val nextPlan = plan?.let {
            when {
                it.paused -> "Latest plan is paused. Review its recorded scope in Planning before resuming."
                it.steps.any { step -> step.status == TaskPlanner.Status.RUNNING } ->
                    "A plan step is running or was interrupted. Reconcile that checkpoint in Planning; do not start a duplicate."
                it.steps.any { step -> step.status == TaskPlanner.Status.BLOCKED } ->
                    "One or more plan steps are blocked. Resolve their prerequisites in Planning first."
                else -> {
                    val next = TaskPlanner.next(it)
                    when {
                        next == null -> "No eligible plan step remains; inspect evidence before marking completion."
                        next.id == "analyze" -> "Review the plan with 'Review Developer plan'. Its analysis approval is separate from code approval."
                        next.paths.isNotEmpty() ->
                            "Editable source checkpoint ready. Send 'Continue Developer plan' for a new pinned-source diff preview."
                        next.id == "validate" ->
                            "Validation checkpoint pending. Check the exact committed source and CI before local completion review."
                        next.id == "review" ->
                            "Final evidence review pending. Send 'Review Developer completion' only with a verified matching commit."
                        else -> "Review the next recorded plan checkpoint before proceeding."
                    }
                }
            }
        } ?: "No saved Developer plan. Describe an implementation task to generate a new source-scoped proposal."

        val jobGuidance = when (state) {
            null -> "No saved Developer job receipt."
            "OUTCOME_UNKNOWN" -> "An operation's outcome is uncertain. Inspect Track and reconcile its exact commit or installation before any retry."
            "COMMITTING" -> "A write is pending. Inspect Track; do not initiate another commit or assume success."
            "AWAITING_APPROVAL" -> "An exact change awaits owner review. Inspect the full diff and approve only its current fingerprint."
            "REVIEW_REQUIRED" -> "Evidence needs owner review on Track. No completed build or release can be inferred."
            "READY_FOR_REVIEW" -> if (gate("SIGNED_APK") == "PASSED")
                "A signed artifact is recorded. Inspect it on Track; signing alone does not establish publication or installation."
                else "An artifact requires verification. Inspect Track before any installation or completion claim."
            "FAILED" -> "Inspect failure evidence in Track. For a verified compiler failure, check exact Developer CI before 'Prepare Developer repair'."
            "VERIFYING" -> "Build verification is still unconfirmed. Recheck the exact task CI in Track or with 'Check saved Developer build'."
            "REVIEW_READY" -> "Source-analysis findings need review in Track before committing to an editable file scope."
            "RUNNING", "QUEUED" -> "A local job remains active or queued. Refresh Track; no passing build or completed task is established."
            "PAUSED" -> "The job is paused. Inspect its state on Track before explicitly resuming."
            "CANCELLED" -> "The previous job was cancelled. Start a new scoped task if still required; old approval is not restored."
            else -> "The saved job state needs manual review before further work."
        }

        return buildString {
            append("Developer task diagnosis — local records only\n")
            append("No live GitHub CI, production release, or installed APK was checked.\n\n")
            append("Latest job: ${state ?: "NONE"}\n")
            if (job != null) {
                append("Source: ${gate("SOURCE_COMMITTED")} · Build: ${gate("BUILD_SUCCESSFUL")} · ")
                append("Tests: ${gate("TESTS_PASSED")}\n")
                append("Signing: ${gate("SIGNED_APK")} · Device: ${gate("DEVICE_INSTALLED")}\n")
            }
            append("Job action: $jobGuidance\n\n")
            append("Latest plan: ")
            if (plan == null) append("NONE\n")
            else {
                append("${plan.steps.count { it.status == TaskPlanner.Status.DONE }}/${plan.steps.size}")
                append(" locally reported checkpoints; paused=${plan.paused}\n")
            }
            append("Plan action: $nextPlan\n\n")
            append("These receipts may describe different tasks. They do not transfer source approval, retry writes, or prove a public release.")
        }
    }
}
