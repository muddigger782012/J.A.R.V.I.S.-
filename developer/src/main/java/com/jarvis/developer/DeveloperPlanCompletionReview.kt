package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * Last user-facing checkpoint for an isolated debug CI build. Requires live
 * passing CI and the precise locally persisted implementation+validation
 * receipts. It has no source writer, merge, signing or publication authority.
 */
internal class DeveloperPlanCompletionReview(
    private val directory: (Activity) -> File = {
        File(it.noBackupFilesDir.canonicalFile, "developer-task-plans")
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    internal data class Prepared(
        val plan: TaskPlanner.Plan, val commitSha: String, val runUrl: String,
        val observedAt: Long, val text: String
    )
    private var reviewed: Prepared? = null
    private val ciUrl = Regex(
        "https://github\\.com/muddigger782012/J\\.A\\.R\\.V\\.I\\.S\\.-/actions/runs/[1-9][0-9]*"
    )
    private val maxAge = 600_000L

    private fun checkCommit(commit: PatchPublisher.CommitResult, ci: PatchPublisher.CiStatus) {
        PatchPublisher.validateResult(commit)
        require(commit.target == PatchEngine.Target.DEVELOPER &&
            commit.baseBranch == "feature/jarvis-developer" &&
            ci.state == PatchPublisher.CiState.PASSED && ci.conclusion == "success" &&
            ciUrl.matches(ci.runUrl.orEmpty()))
    }

    private fun matches(plan: TaskPlanner.Plan, commit: PatchPublisher.CommitResult, url: String): Boolean {
        TaskPlanner.validate(plan)
        if (plan.paused || plan.baseCommitSha != commit.baseCommitSha ||
            plan.scope.sorted() != commit.paths.sorted() ||
            plan.steps.any { it.status == TaskPlanner.Status.RUNNING } ||
            plan.steps.none { it.paths.isNotEmpty() }) return false
        val implementations = plan.steps.filter { it.paths.isNotEmpty() }
        val validate = plan.steps.singleOrNull { it.id == "validate" } ?: return false
        val review = plan.steps.singleOrNull { it.id == "review" } ?: return false
        return implementations.all { it.status == TaskPlanner.Status.DONE &&
            it.checkpoint.startsWith(
                "Verified isolated Developer task commit ${commit.commitSha}; exact-source debug CI success: $url.") } &&
            validate.status == TaskPlanner.Status.DONE &&
            review.status == TaskPlanner.Status.PENDING &&
            review.dependsOn == listOf("validate") && review.paths.isEmpty() &&
            validate.checkpoint.startsWith(
                "Exact-source Developer CI workflow passed automated tests, lint, debug APK assembly " +
                    "and its workflow gates for ${commit.commitSha}: $url.")
    }

    @Synchronized fun prepare(activity: Activity, commit: PatchPublisher.CommitResult,
        ci: PatchPublisher.CiStatus): Prepared {
        reviewed = null
        checkCommit(commit, ci)
        val plans = TaskPlanStore(directory(activity)).loadAll()
        require(plans.size <= 10)
        plans.forEach(TaskPlanner::validate)
        val found = plans.filter { matches(it, commit, requireNotNull(ci.runUrl)) }
        require(found.size == 1) { "An exact, unreviewed plan is required" }
        val plan = found.single()
        return Prepared(plan, commit.commitSha, requireNotNull(ci.runUrl), now(),
            "Developer final completion evidence — review only\n" +
            "Plan: ${plan.id} (revision ${plan.revision})\n" +
            "Goal: ${plan.task}\n" +
            "Original canonical source: ${plan.baseCommitSha}\n" +
            "Reviewed isolated task commit: ${commit.commitSha}\n" +
            "Changed paths: ${plan.scope.joinToString(", ")}\n" +
            "Fresh exact-source debug CI: ${ci.runUrl} (PASSED)\n" +
            "Implementation and automated validation are recorded. Canonical merge, signed " +
                "release, updater installation and live-device functionality are NOT verified.\n" +
            "To acknowledge this LOCAL review only, type exactly: Confirm Developer completion review. " +
                "This does not authorize a GitHub write or publication.")
    }

    @Synchronized fun arm(displayed: Prepared) {
        require(displayed.text.contains("Confirm Developer completion review"))
        require(now() in displayed.observedAt..(displayed.observedAt + maxAge))
        reviewed = displayed
    }

    @Synchronized fun confirm(activity: Activity, commit: PatchPublisher.CommitResult,
        ci: PatchPublisher.CiStatus): String {
        val observed = reviewed
        reviewed = null // One-time, even after an invalid or stale attempt.
        if (observed == null) return "No current completion evidence was reviewed. " +
            "Type Review Developer completion first. Nothing was changed."
        return try {
            checkCommit(commit, ci)
            require(commit.commitSha == observed.commitSha && ci.runUrl == observed.runUrl &&
                now() in observed.observedAt..(observed.observedAt + maxAge))
            val store = TaskPlanStore(directory(activity))
            val current = store.loadAll().single { it.id == observed.plan.id }
            require(current == observed.plan && matches(current, commit, requireNotNull(ci.runUrl)))
            val done = TaskPlanner.transition(current, "review", TaskPlanner.Status.DONE,
                "Owner reviewed exact isolated Developer task commit ${commit.commitSha} and " +
                    "passing CI ${ci.runUrl}. This acknowledges local debug evidence only; " +
                    "not canonical merge, signed release, installation or live acceptance.",
                maxOf(now(), current.updatedAtMillis))
            store.save(done, current.revision)
            "Developer plan ${current.id}: final LOCAL review acknowledged at revision " +
                "${done.revision}. CI remains tied to ${commit.commitSha.take(12)}. " +
                "Canonical integration, signed in-app update and physical-device acceptance " +
                "still require separate owner-controlled steps."
        } catch (_: Exception) {
            "Completion review expired or its exact CI/plan identity changed. Consent was " +
                "consumed; review fresh evidence again. No checkpoint was marked complete."
        }
    }
}
