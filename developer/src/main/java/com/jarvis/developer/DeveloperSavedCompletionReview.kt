package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * Restart-safe read-only evidence review using a user-saved *bookmark*.
 *
 * The bookmark alone proves nothing. A separate persisted, source-pinned
 * implementation+validation record and a fresh complete GitHub CI report
 * must agree before an owner can acknowledge the local final review.
 * No diff, code, token, commit authorization, merge or publication is restored.
 */
internal class DeveloperSavedCompletionReview(
    private val historyDirectory: (Activity) -> File = {
        File(it.noBackupFilesDir.canonicalFile, "developer-repair-history")
    },
    private val planDirectory: (Activity) -> File = {
        File(it.noBackupFilesDir.canonicalFile, "developer-task-plans")
    },
    private val feedback: (String, RepairHistoryStore.Bookmark, PatchPreviewCancellation) -> BuildDiagnostics.Report =
        { token, saved, cancellation ->
            BuildFeedback.read(token, saved.branch, saved.commitSha, saved.target, cancellation)
        },
    private val now: () -> Long = System::currentTimeMillis
) {
    internal data class Prepared(
        val plan: TaskPlanner.Plan, val bookmark: RepairHistoryStore.Bookmark,
        val runUrl: String, val atMillis: Long, val text: String
    )

    private var currentReview: Prepared? = null
    private val approvalWindow = 600_000L
    private val baseUrl = Regex(
        "https://github\\.com/muddigger782012/J\\.A\\.R\\.V\\.I\\.S\\.-/actions/runs/[1-9][0-9]*"
    )

    private fun matchingPlan(plan: TaskPlanner.Plan, bookmark: RepairHistoryStore.Bookmark,
        url: String): Boolean {
        TaskPlanner.validate(plan)
        if (plan.paused || plan.steps.any { it.status == TaskPlanner.Status.RUNNING } ||
            plan.steps.none { it.paths.isNotEmpty() }) return false
        val implementations = plan.steps.filter { it.paths.isNotEmpty() }
        val validation = plan.steps.singleOrNull { it.id == "validate" } ?: return false
        val final = plan.steps.singleOrNull { it.id == "review" } ?: return false
        // The persisted receipts were originally generated only after an exact
        // scoped, source-bound approved CommitResult and passing CI. Do not
        // infer that history itself ever approved a patch.
        return implementations.all { it.status == TaskPlanner.Status.DONE &&
            it.checkpoint.startsWith(
                "Verified isolated Developer task commit ${bookmark.commitSha}; exact-source debug CI success: $url.") } &&
            validation.status == TaskPlanner.Status.DONE &&
            final.status == TaskPlanner.Status.PENDING &&
            final.dependsOn == listOf("validate") &&
            TaskPlanner.next(plan)?.id == final.id &&
            validation.checkpoint.startsWith(
                "Exact-source Developer CI workflow passed automated tests, lint, debug APK assembly " +
                    "and its workflow gates for ${bookmark.commitSha}: $url.")
    }

    private fun checkedReport(bookmark: RepairHistoryStore.Bookmark,
        report: BuildDiagnostics.Report): String {
        RepairHistoryStore.validate(bookmark)
        BuildDiagnostics.validateReport(report)
        require(bookmark.target == PatchEngine.Target.DEVELOPER &&
            report.target == PatchEngine.Target.DEVELOPER &&
            report.branch == bookmark.branch && report.commitSha == bookmark.commitSha &&
            report.status == "completed" && report.conclusion == "success" &&
            report.evidenceComplete && report.totals != null &&
            report.totals.failures == 0 && report.totals.errors == 0) {
            "Saved task completion requires complete exact-commit CI and passing JUnit evidence"
        }
        val prefix = report.runUrl.substringBefore("/attempts/")
        require(baseUrl.matches(prefix) && report.runUrl.startsWith(prefix + "/attempts/"))
        return prefix
    }

    private fun latest(activity: Activity): RepairHistoryStore.Bookmark =
        RepairHistoryStore(historyDirectory(activity)).load()
            .firstOrNull { it.target == PatchEngine.Target.DEVELOPER }
            ?: error("No saved Developer task bookmark")

    @Synchronized fun prepare(activity: Activity, token: String,
        cancellation: PatchPreviewCancellation): Prepared {
        currentReview = null
        cancellation.check()
        val bookmark = latest(activity)
        val report = feedback(token, bookmark, cancellation)
        cancellation.check()
        val url = checkedReport(bookmark, report)
        val plans = TaskPlanStore(planDirectory(activity)).loadAll()
        require(plans.size <= 10)
        plans.forEach(TaskPlanner::validate)
        val matches = plans.filter { matchingPlan(it, bookmark, url) }
        require(matches.size == 1) { "No unique eligible saved-plan CI checkpoint" }
        val plan = matches.single()
        return Prepared(plan, bookmark, url, now(),
            "Saved Developer task recovered for LOCAL completion review — no approval restored\n" +
                "Plan: ${plan.id}; revision ${plan.revision}\n" +
                "Goal: ${plan.task}\n" +
                "Original Developer source: ${plan.baseCommitSha}\n" +
                "Saved isolated task branch: ${bookmark.branch}\n" +
                "Reverified commit: ${bookmark.commitSha}\n" +
                "Reviewed edit scope: ${plan.scope.joinToString(", ")}\n" +
                "Fresh CI: ${report.runUrl} — completed/success; " +
                    "${requireNotNull(report.totals).tests} JUnit tests; no failures/errors.\n" +
                "This local review is NOT production signing, merge, in-app publication " +
                    "or device acceptance.\n" +
                "Type exactly: Confirm Developer completion review " +
                    "to acknowledge only this saved plan checkpoint.")
    }

    @Synchronized fun arm(displayed: Prepared) {
        require(displayed.text.contains("Confirm Developer completion review") &&
            now() in displayed.atMillis..(displayed.atMillis + approvalWindow))
        currentReview = displayed
    }

    @Synchronized fun confirm(activity: Activity, token: String,
        cancellation: PatchPreviewCancellation): String {
        val prior = currentReview
        currentReview = null // One-time; neither failure nor cancellation preserves consent.
        if (prior == null) return "No fresh saved Developer completion review was displayed. " +
            "Type Review Developer completion first. No checkpoint changed."
        return try {
            cancellation.check()
            val bookmark = latest(activity)
            require(bookmark == prior.bookmark &&
                now() in prior.atMillis..(prior.atMillis + approvalWindow))
            val report = feedback(token, bookmark, cancellation)
            cancellation.check()
            val url = checkedReport(bookmark, report)
            require(url == prior.runUrl)
            val store = TaskPlanStore(planDirectory(activity))
            val plan = store.loadAll().single { it.id == prior.plan.id }
            require(plan == prior.plan && matchingPlan(plan, bookmark, url))
            cancellation.check()
            val done = TaskPlanner.transition(plan, "review", TaskPlanner.Status.DONE,
                "Owner explicitly acknowledged saved Developer task commit ${bookmark.commitSha} " +
                    "with fresh complete CI ${report.runUrl}. Local review only, no source " +
                    "write, canonical merge, signed release, device install or acceptance.",
                maxOf(now(), plan.updatedAtMillis))
            store.save(done, plan.revision)
            "Developer plan ${plan.id}: LOCAL final review acknowledged at revision " +
                "${done.revision}, verified commit ${bookmark.commitSha.take(12)}. " +
                "No write approval, canonical merge, signed APK, in-app release or " +
                "device-installed success was inferred."
        } catch (_: Exception) {
            "Saved Developer review expired or the bookmark, CI, plan or cancellation " +
                "state changed. Consent consumed; review again. No completion was assumed."
        }
    }
}
