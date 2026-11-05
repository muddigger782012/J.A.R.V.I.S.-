package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * Records a repaired task as completion of its ORIGINAL approved plan only
 * when both commit identities and their exact failed/passed CI evidence form
 * one direct Git parent chain. No separate sibling branch is treated as a
 * combined build. Never grants approval, merges, signs or installs.
 */
internal object DeveloperPlanRepairCheckpoint {
    private const val CANONICAL = "feature/jarvis-developer"
    private val runUrl = Regex(
        "https://github\\.com/muddigger782012/J\\.A\\.R\\.V\\.I\\.S\\.-/actions/runs/[1-9][0-9]*"
    )

    data class Origin(val original: PatchPublisher.CommitResult,
        val failedCi: PatchPublisher.CiStatus, val failure: BuildDiagnostics.Report)

    fun record(activity: Activity, origin: Origin, repair: PatchPublisher.CommitResult,
        passedCi: PatchPublisher.CiStatus): String? =
        record(TaskPlanStore(File(activity.noBackupFilesDir.canonicalFile, "developer-task-plans")),
            origin, repair, passedCi, System.currentTimeMillis())

    internal fun record(store: TaskPlanStore, origin: Origin, repair: PatchPublisher.CommitResult,
        passedCi: PatchPublisher.CiStatus, now: Long): String? {
        val original = origin.original
        PatchPublisher.validateResult(original)
        PatchPublisher.validateResult(repair)
        val candidate = DeveloperFailureRepairDraft.from(original, origin.failedCi, origin.failure)
            ?: return null
        if (original.target != PatchEngine.Target.DEVELOPER || original.baseBranch != CANONICAL ||
            original.taskId.startsWith("repair-") ||
            repair.target != PatchEngine.Target.DEVELOPER || !repair.taskId.startsWith("repair-") ||
            repair.baseBranch != original.branch || repair.baseCommitSha != original.commitSha ||
            repair.branch == original.branch || repair.paths.sorted() != candidate.paths.sorted() ||
            passedCi.state != PatchPublisher.CiState.PASSED || passedCi.conclusion != "success" ||
            !runUrl.matches(passedCi.runUrl.orEmpty())) return null
        val plans = store.loadAll()
        require(plans.size <= 10)
        plans.forEach(TaskPlanner::validate)
        for (plan in plans) {
            if (plan.paused || plan.baseCommitSha != original.baseCommitSha ||
                plan.scope.sorted() != original.paths.sorted() ||
                plan.steps.any { it.status == TaskPlanner.Status.RUNNING }) continue
            val step = TaskPlanner.next(plan) ?: continue
            if (step.status != TaskPlanner.Status.PENDING || step.paths.isEmpty()) continue
            val packet = DeveloperPlanContinuation.suggest(listOf(plan)).packet ?: continue
            if (packet.id != original.taskId || packet.base != original.baseCommitSha ||
                packet.paths.sorted() != original.paths.sorted()) continue
            val lineage = "Repair provenance: direct parent ${original.commitSha} on ${original.branch}; " +
                "failed CI ${origin.failedCi.runUrl}; scope inherited from the original owner-reviewed commit."
            val implementation = "Verified isolated Developer task commit ${repair.commitSha}; " +
                "exact-source debug CI success: ${passedCi.runUrl}. " + lineage +
                " Repair changed only ${repair.paths.size} in-scope compiler file(s); " +
                "not a canonical merge, signed release or physical-device acceptance."
            val validation = "Exact-source Developer CI workflow passed automated tests, lint, " +
                "debug APK assembly and its workflow gates for ${repair.commitSha}: " +
                "${passedCi.runUrl}. " + lineage +
                " The repaired child commit has one verified passing source tree; " +
                "not a production-signed or installed build."
            val updated = TaskPlanner.completeVerifiedScopedBuild(plan, step.id,
                implementation, validation, maxOf(now, plan.updatedAtMillis))
            store.save(updated, plan.revision)
            return "Verified repaired Developer plan ${plan.id}: original failed commit " +
                "${original.commitSha.take(12)} -> owner-approved repair " +
                "${repair.commitSha.take(12)} -> passing exact CI. " +
                "Source implementation and validation recorded; final owner review, " +
                "canonical integration, signing and installation remain pending."
        }
        return null
    }

    /** Validate a saved repaired child against the exact receipt, never a sibling tree. */
    fun matchesRepairedCommit(plan: TaskPlanner.Plan,
        commit: PatchPublisher.CommitResult, url: String): Boolean {
        if (commit.target != PatchEngine.Target.DEVELOPER ||
            commit.baseBranch == CANONICAL || !commit.taskId.startsWith("repair-") ||
            commit.paths.any { it !in plan.scope }) return false
        val marker = "Repair provenance: direct parent ${commit.baseCommitSha} " +
            "on ${commit.baseBranch};"
        val implementation = plan.steps.filter { it.paths.isNotEmpty() }
        val validation = plan.steps.singleOrNull { it.id == "validate" } ?: return false
        return implementation.isNotEmpty() && implementation.all {
            it.checkpoint.startsWith(
                "Verified isolated Developer task commit ${commit.commitSha}; " +
                    "exact-source debug CI success: $url.") &&
                it.checkpoint.contains(marker)
        } && validation.checkpoint.contains(marker)
    }
}
