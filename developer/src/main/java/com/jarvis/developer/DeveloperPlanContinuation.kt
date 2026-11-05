package com.jarvis.developer

import android.app.Activity
import java.io.File
import java.security.MessageDigest

/**
 * A read-only, one-step scheduler over existing immutable task checkpoints.
 * Never marks local work done, restores approval, or writes to GitHub.
 */
internal object DeveloperPlanContinuation {
    data class Packet(val id: String, val task: String, val paths: List<String>, val base: String,
        val planId: String = "")
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
        val implementation = plan.steps.filter { it.paths.isNotEmpty() }
        // One reviewed, full-scope commit is cumulative by construction: unlike
        // sibling branches, every planned file is compiled from the same tree.
        // Previously reported partial steps cannot be silently treated as CI proof.
        if (implementation.size > 1 && implementation.any { it.status != TaskPlanner.Status.PENDING })
            return Suggestion(label + "partial source checkpoints require a fresh, complete " +
                "plan and reanalysis before creating a cumulative reviewed build. No source operation started.")
        val combined = implementation.size > 1
        val editScope = if (combined) plan.scope.sorted() else next.paths
        val message = buildString {
            append("Local plan: ${plan.id}; step: ${next.id}; revision: ${plan.revision}\n")
            append("Immutable base: ${plan.baseCommitSha}\nAnalysis: ${plan.analysisId}\n")
            append("Task: ${plan.task}\nStep: ${next.title}\n${next.detail}\n")
            if (combined) {
                append("ONE cumulative source proposal for ${implementation.size} planned implementation steps. ")
                append("All approved files must be changed and tested together in a single reviewed commit.\n")
                implementation.forEach { step ->
                    append("${step.id}: ${step.title} — ${step.detail.take(160)}\n")
                }
            }
            append("Prepare only these approved source paths: ${editScope.joinToString(", ")}. ")
            append("No step status, source write, or completion verification is implied.")
        }
        require(message.length <= 4_000)
        val bytes = "${plan.id}\n${next.id}\n${plan.revision}".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val id = plan.id.take(25) + "-" + digest.take(16)
        return Suggestion(label + if (combined)
            "one cumulative, source-scoped proposal for ${implementation.size} planned implementation steps, " +
                "${editScope.size} editable file(s). The complete diff must be reviewed and approved; no change is approved."
            else "next source-scoped step '${next.title}' (${next.id}), " +
                "${editScope.size} editable file(s). A fresh pinned-source diff may be generated; no change is approved.",
            Packet(id, message, editScope.toList(), plan.baseCommitSha, plan.id))
    }
}
