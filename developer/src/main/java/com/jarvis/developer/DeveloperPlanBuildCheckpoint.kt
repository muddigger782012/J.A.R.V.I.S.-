package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * Commit+CI evidence for exactly one approved, isolated Developer
 * implementation step. This does NOT attest canonical integration, device
 * acceptance, signed release or successful deployment.
 */
internal object DeveloperPlanBuildCheckpoint {
    private const val BASE_BRANCH = "feature/jarvis-developer"
    private val githubRun = Regex(
        "https://github\\.com/muddigger782012/J\\.A\\.R\\.V\\.I\\.S\\.-/actions/runs/[1-9][0-9]*"
    )

    fun record(activity: Activity, commit: PatchPublisher.CommitResult,
        ci: PatchPublisher.CiStatus): String? =
        record(TaskPlanStore(File(activity.noBackupFilesDir.canonicalFile, "developer-task-plans")),
            commit, ci, System.currentTimeMillis())

    internal fun record(store: TaskPlanStore, commit: PatchPublisher.CommitResult,
        ci: PatchPublisher.CiStatus, now: Long): String? {
        PatchPublisher.validateResult(commit)
        if (commit.target != PatchEngine.Target.DEVELOPER || commit.baseBranch != BASE_BRANCH ||
            ci.state != PatchPublisher.CiState.PASSED || ci.conclusion != "success" ||
            !githubRun.matches(ci.runUrl.orEmpty())) return null
        val plans = store.loadAll()
        require(plans.size <= 10)
        plans.forEach(TaskPlanner::validate)
        for (plan in plans) {
            if (plan.baseCommitSha != commit.baseCommitSha || plan.paused ||
                plan.steps.any { it.status == TaskPlanner.Status.RUNNING }) continue
            // Sibling isolated commits on the same canonical source do not form a
            // cumulative tested build. Do not mark dependencies done in multi-step plans.
            if (plan.steps.count { it.paths.isNotEmpty() } != 1 ||
                plan.scope.sorted() != commit.paths.sorted()) continue
            val step = TaskPlanner.next(plan) ?: continue
            if (step.paths.isEmpty() || step.status != TaskPlanner.Status.PENDING) continue
            val packet = DeveloperPlanContinuation.suggest(listOf(plan)).packet ?: continue
            if (packet.id != commit.taskId || packet.base != commit.baseCommitSha ||
                packet.paths.sorted() != commit.paths.sorted()) continue
            val checkpoint = "Verified isolated Developer task commit ${commit.commitSha}; " +
                "exact-source debug CI success: ${ci.runUrl}. " +
                "This records scoped implementation only; not a canonical merge, " +
                "production-signed APK, installed build, or functional acceptance."
            val updated = TaskPlanner.transition(plan, step.id, TaskPlanner.Status.DONE,
                checkpoint, maxOf(now, plan.updatedAtMillis))
            // Atomic revision check rejects concurrent edits and stale callbacks.
            store.save(updated, plan.revision)
            return "Verified CI checkpoint recorded for ${plan.id}/${step.id}: " +
                "isolated commit ${commit.commitSha.take(12)}; debug CI PASSED. " +
                "Validation, canonical merge and release acceptance remain separate."
        }
        return null
    }
}
