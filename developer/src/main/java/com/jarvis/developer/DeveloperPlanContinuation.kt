package com.jarvis.developer

import android.app.Activity
import java.io.File
import java.security.MessageDigest

/**
 * A read-only, one-step scheduler over existing immutable task checkpoints.
 * Never marks local work done, restores approval, or writes to GitHub.
 */
internal object DeveloperPlanContinuation {
    data class Packet(val id: String, val task: String, val paths: List<String>, val base: String)
    data class Suggestion(val message: String, val packet: Packet? = null)

    fun suggest(activity: Activity): Suggestion =
        suggest(TaskPlanStore(File(activity.noBackupFilesDir.canonicalFile, "developer-task-plans")).loadAll())

    fun suggest(plans: List<TaskPlanner.Plan>): Suggestion {
        require(plans.size <= 10)
        plans.forEach(TaskPlanner::validate)
        val plan = plans.maxWithOrNull(compareBy<TaskPlanner.Plan> { it.updatedAtMillis }.thenBy { it.id })
            ?: return Suggestion("No saved Developer task plan. Create an explicit source-scoped plan first; no work was started.")
        val label = "Developer plan ${plan.id}, revision ${plan.revision}: "
        if (plan.steps.any { it.status == TaskPlanner.Status.RUNNING })
            return Suggestion(label + "a RUNNING checkpoint requires recovery/verification. " +
                "Use Reload / recover in Planning; an interrupted operation is not automatically resumed.")
        if (plan.paused) return Suggestion(label + "paused. Review saved decisions and explicitly resume it before continuing.")
        val next = TaskPlanner.next(plan) ?: return Suggestion(label +
            "no eligible next step. Review blocked dependencies or completed checkpoints; no completion is inferred.")
        if (next.paths.isEmpty()) return Suggestion(label + "next checkpoint '${next.title}' (${next.id}) " +
            "requires explicit evidence/review in Planning. No step was marked DONE automatically.")
        require(next.status == TaskPlanner.Status.PENDING && next.paths.all { it in plan.scope })
        val message = buildString {
            append("Local plan: ${plan.id}; step: ${next.id}; revision: ${plan.revision}\n")
            append("Immutable base: ${plan.baseCommitSha}\nAnalysis: ${plan.analysisId}\n")
            append("Task: ${plan.task}\nStep: ${next.title}\n${next.detail}\n")
            append("Prepare only these approved source paths: ${next.paths.joinToString(", ")}. ")
            append("No step status, source write, or completion verification is implied.")
        }
        require(message.length <= 4_000)
        val bytes = "${plan.id}\n${next.id}\n${plan.revision}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val id = plan.id.take(25) + "-" + digest.take(16)
        return Suggestion(label + "next source-scoped step '${next.title}' (${next.id}), " +
            "${next.paths.size} editable file(s). A fresh pinned-source diff may be generated; no change is approved.",
            Packet(id, message, next.paths.toList(), plan.baseCommitSha))
    }
}
