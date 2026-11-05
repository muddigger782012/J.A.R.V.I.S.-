package com.jarvis.developer

import android.app.Activity
import org.json.JSONObject
import java.io.File

/**
 * Read-only local development progress for the primary chat. These are saved
 * plans and admission states, NOT GitHub CI results or installed-build proof.
 */
internal object DeveloperProgressReport {
    private val commands = setOf(
        "show development progress",
        "show my development progress",
        "show development status",
        "show my development tasks",
        "what is the status of my development tasks",
        "show task progress"
    )
    private val jobStates = setOf(
        "QUEUED", "PAUSED", "CANCELLED", "RUNNING", "REVIEW_READY",
        "FAILED", "AWAITING_APPROVAL", "COMMITTING", "OUTCOME_UNKNOWN",
        "VERIFYING", "READY_FOR_REVIEW", "REVIEW_REQUIRED"
    )

    fun isRequest(message: String): Boolean =
        message.trim().lowercase(java.util.Locale.ROOT).trimEnd('.', '?', '!') in commands

    fun read(activity: Activity): String {
        val base = activity.noBackupFilesDir.canonicalFile
        val plans = TaskPlanStore(File(base, "developer-task-plans")).loadAll()
        val jobs = DevelopmentJobInbox(File(base, "development-inbox")).localSnapshot()
        return summarize(plans, jobs)
    }

    /** Only bounded, validated records are displayed. Never show secrets or raw job payloads. */
    fun summarize(plans: List<TaskPlanner.Plan>, jobs: List<JSONObject>): String {
        require(plans.size <= 10 && jobs.size <= 64)
        plans.forEach(TaskPlanner::validate)
        require(jobs.all { it.optString("state") in jobStates })
        val openReviews = jobs.count {
            it.getString("state") in setOf("REVIEW_READY", "AWAITING_APPROVAL", "READY_FOR_REVIEW", "REVIEW_REQUIRED")
        }
        return buildString {
            append("Development progress — saved on this device\n")
            append("This is local task state, not a live GitHub build or installation check.\n\n")
            append("Task plans: ${plans.size}\n")
            if (plans.isEmpty()) {
                append("No local task plans have been saved.\n")
            } else {
                plans.sortedByDescending { it.updatedAtMillis }.take(3).forEach { plan ->
                    val done = plan.steps.count { it.status == TaskPlanner.Status.DONE }
                    val running = plan.steps.any { it.status == TaskPlanner.Status.RUNNING }
                    val blocked = plan.steps.count { it.status == TaskPlanner.Status.BLOCKED }
                    val next = when {
                        running -> "Interrupted or active checkpoint — recheck before acting"
                        plan.paused -> "Paused — review before resuming"
                        blocked > 0 -> "Blocked steps: $blocked"
                        else -> TaskPlanner.next(plan)?.title ?: "No ready step"
                    }
                    append("• ${plan.id}: $done/${plan.steps.size} reported done; next: $next\n")
                }
                if (plans.size > 3) append("Other plans: ${plans.size - 3}; review in Planning.\n")
            }
            append("\nDevelopment jobs: ${jobs.size}; requiring review: $openReviews\n")
            if (jobs.isEmpty()) {
                append("No saved development jobs.\n")
            } else {
                jobs.asReversed().take(3).forEach { job ->
                    val state = job.getString("state")
                    append("• ${DevelopmentJobHistory.requestLabel(job)}: $state\n")
                }
                if (jobs.size > 3) append("Other jobs: ${jobs.size - 3}; review in Track.\n")
            }
            append("\nLocal checkpoints are owner-reported. Build, signed APK, source merge, and device install are not confirmed by this report. No work or approval was started.")
        }
    }
}
