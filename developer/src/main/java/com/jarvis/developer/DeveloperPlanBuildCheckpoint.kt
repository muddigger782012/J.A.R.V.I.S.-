package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * Commit+CI evidence for one approved, isolated Developer commit covering
 * the complete source scope (possibly several planned steps). No sibling
 * branches are combined. This does NOT attest canonical integration, device
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
            // Only one reviewed commit covering the ENTIRE planned file scope can
            // advance multiple implementation checkpoints. Sibling isolated builds
            // do not qualify, even when every sibling's separate CI passed.
            if (plan.scope.sorted() != commit.paths.sorted()) continue
            val step = TaskPlanner.next(plan) ?: continue
            if (step.paths.isEmpty() || step.status != TaskPlanner.Status.PENDING) continue
            val packet = DeveloperPlanContinuation.suggest(listOf(plan)).packet ?: continue
            if (packet.id != commit.taskId || packet.base != commit.baseCommitSha ||
                packet.paths.sorted() != commit.paths.sorted()) continue
            val implementation = "Verified isolated Developer task commit ${commit.commitSha}; " +
                "exact-source debug CI success: ${ci.runUrl}. " +
                "This records all planned source files in one reviewed cumulative commit; " +
                "not a canonical merge, production-signed APK, installed build, or functional acceptance."
            val validation = "Exact-source Developer CI workflow passed automated tests, lint, " +
                "debug APK assembly and its workflow gates for ${commit.commitSha}: ${ci.runUrl}. " +
                "This is NOT a signed candidate, canonical merge or physical-device acceptance."
            val updated = TaskPlanner.completeVerifiedScopedBuild(plan, step.id,
                implementation, validation, maxOf(now, plan.updatedAtMillis))
            // One atomic plan revision for both evidence-backed checkpoints:
            // a stale callback cannot leave implementation DONE but validation lost.
            store.save(updated, plan.revision)
            return "Verified CI checkpoints recorded for ${plan.id}: all planned implementation " +
                "steps and debug-CI validation in one reviewed commit ${commit.commitSha.take(12)}. " +
                "The final review, canonical merge, signing and installation remain pending."
        }
        return null
    }
}
