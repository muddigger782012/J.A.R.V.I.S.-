package com.jarvis.developer

import android.app.Activity
import java.io.File

/**
 * GET-only reattachment after activity recreation. A recovered receipt
 * is NOT a PatchEngine.ApprovedProposal, provider request, or write handle.
 * Any plan advancement uses fresh, exact commit and CI evidence and the
 * existing atomic plan checkpoint guards.
 */
internal object DeveloperCommitRecovery {
    fun recheck(activity: Activity, token: String,
        cancellation: PatchPreviewCancellation,
        directory: (Activity) -> File = {
            File(it.noBackupFilesDir.canonicalFile, "developer-commit-recovery")
        },
        ciRead: (String, PatchPublisher.CommitResult) -> PatchPublisher.CiStatus = PatchPublisher::ci,
        failureRead: (String, PatchPublisher.CommitResult, PatchPreviewCancellation) -> BuildDiagnostics.Report =
            { key, commit, cancel ->
                BuildFeedback.read(key, commit.branch, commit.commitSha, commit.target, cancel)
            },
        recordDirect: (Activity, PatchPublisher.CommitResult, PatchPublisher.CiStatus) -> String? =
            DeveloperPlanBuildCheckpoint::record,
        recordRepair: (Activity, DeveloperPlanRepairCheckpoint.Origin,
            PatchPublisher.CommitResult, PatchPublisher.CiStatus) -> String? =
            DeveloperPlanRepairCheckpoint::record
    ): String {
        cancellation.check()
        val receipts = DeveloperCommitRecoveryStore(directory(activity)).load()
        val latest = receipts.firstOrNull() ?: return "No automatically saved Developer commit " +
            "identity is available. Older manual bookmarks can still be checked. " +
            "No source write or approval was restored."
        cancellation.check()
        val current = ciRead(token, latest)
        cancellation.check()
        val summary = DeveloperChatBuildReceipt.commit(latest, current, null, false)
        if (current.state != PatchPublisher.CiState.PASSED ||
            current.conclusion != "success") {
            return "Recovered Developer task commit identity (READ ONLY).\n" + summary +
                "\nThe current run is not verified PASSED. Recheck later or investigate " +
                "the exact task run; no plan checkpoint, provider call or GitHub write occurred."
        }

        val checkpoint = if (latest.baseBranch == "feature/jarvis-developer") {
            cancellation.check()
            recordDirect(activity, latest, current)
        } else {
            // A repaired child can only finish the original task if the
            // immediately preceding failed parent was previously committed
            // in this same isolated Developer receipt chain.
            val parent = receipts.drop(1).singleOrNull {
                it.target == PatchEngine.Target.DEVELOPER &&
                    it.commitSha == latest.baseCommitSha &&
                    it.branch == latest.baseBranch &&
                    it.baseBranch == "feature/jarvis-developer"
            }
            if (parent == null) null
            else {
                cancellation.check()
                val parentCi = ciRead(token, parent)
                cancellation.check()
                if (parentCi.state != PatchPublisher.CiState.FAILED ||
                    parentCi.conclusion != "failure") null
                else {
                    val report = failureRead(token, parent, cancellation)
                    cancellation.check()
                    val origin = DeveloperPlanRepairCheckpoint.Origin(parent, parentCi, report)
                    // Recompute compiler-only repair eligibility from this
                    // fresh exact-run report instead of trusting a bookmark.
                    if (DeveloperFailureRepairDraft.from(parent, parentCi, report) == null) null
                    else recordRepair(activity, origin, latest, current)
                }
            }
        }
        cancellation.check()
        return "Recovered Developer task commit (READ ONLY).\n" + summary +
            (if (checkpoint == null)
                "\nFresh passing CI was observed, but no eligible, unchanged original " +
                    "plan checkpoint could be advanced. Review saved plan and provenance manually."
             else "\n$checkpoint") +
            "\nNo old approval, code diff, GitHub writer, signed release or device install was restored."
    }
}
