package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * Local plan metadata review. The second explicit chat command advances only
 * a user-reported ANALYZE checkpoint, never test evidence or source approval.
 * Review is memory-only, short-lived, and bound to the exact saved revision.
 */
internal class DeveloperPlanAnalysisReview(
    private val directory: (Activity) -> File = {
        File(it.noBackupFilesDir.canonicalFile, "developer-task-plans")
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    private var reviewed: TaskPlanner.Plan? = null

    @Synchronized fun review(activity: Activity): String {
        reviewed = null
        val plans = TaskPlanStore(directory(activity)).loadAll()
        require(plans.size <= 10)
        plans.forEach(TaskPlanner::validate)
        val plan = plans.maxWithOrNull(compareBy<TaskPlanner.Plan> { it.updatedAtMillis }.thenBy { it.id })
            ?: return "No saved Developer plan. Create a source-scoped plan first; no checkpoint was changed."
        if (plan.paused || plan.steps.any { it.status == TaskPlanner.Status.RUNNING })
            return "Developer plan ${plan.id} is paused or has interrupted running work. " +
                "Recover and explicitly resume it before reviewing an analysis checkpoint."
        val next = TaskPlanner.next(plan)
        if (next?.id != "analyze" || next.status != TaskPlanner.Status.PENDING)
            return "Developer plan ${plan.id} does not have an eligible pending analysis checkpoint. " +
                "Use Continue Developer plan to inspect the next step; no status was changed."
        reviewed = plan
        return buildString {
            append("Developer plan analysis checkpoint — owner review only\n")
            append("Plan: ${plan.id} | Revision: ${plan.revision}\n")
            append("Goal: ${plan.task}\n")
            append("Pinned source: feature/jarvis-developer @ ${plan.baseCommitSha}\n")
            append("Read-only analysis ID: ${plan.analysisId}\n")
            append("Editable scope (${plan.scope.size}):\n${plan.scope.joinToString("\n")}\n")
            append("Next: ${plan.steps.firstOrNull { it.paths.isNotEmpty() }?.title ?: "No implementation step"}\n")
            append("Dependencies and assumptions remain subject to source review. This metadata " +
                "is not a live GitHub read, build, passing test or signed APK.\n")
            append("If you accept this scope and checkpoint metadata, type exactly: " +
                "Approve Developer plan analysis. This only records YOUR analysis review; " +
                "subsequent code diffs require a separate full review and approval.")
        }
    }

    @Synchronized fun confirm(activity: Activity): String {
        val snapshot = reviewed
        reviewed = null // Consumes even a failed or stale approval attempt.
        if (snapshot == null) return "No current Developer plan analysis review is pending. " +
            "Type Review Developer plan first. Nothing was changed."
        return try {
            val store = TaskPlanStore(directory(activity))
            val current = store.loadAll().singleOrNull { it.id == snapshot.id }
                ?: error("Missing plan")
            TaskPlanner.validate(current)
            require(current == snapshot && !current.paused &&
                current.steps.none { it.status == TaskPlanner.Status.RUNNING } &&
                TaskPlanner.next(current)?.id == "analyze") { "Review is stale" }
            val time = maxOf(now(), current.updatedAtMillis)
            val complete = TaskPlanner.transition(current, "analyze", TaskPlanner.Status.DONE,
                "Owner explicitly reviewed and accepted this local plan's source scope, pinned analysis " +
                    "metadata and prerequisites. This is owner-reported, not independently verified CI.",
                time)
            store.save(complete, current.revision)
            "Developer plan ${current.id}: analysis checkpoint acknowledged and saved at revision " +
                "${complete.revision} (owner-reported only). Type Continue Developer plan to prepare " +
                "the next eligible scoped diff. No provider call, source write, passing test, " +
                "release or installation was implied."
        } catch (_: Exception) {
            "Developer plan changed or could not be saved. The review was consumed; " +
                "read the current plan again. No successful checkpoint was assumed."
        }
    }
}
