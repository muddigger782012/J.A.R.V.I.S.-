package com.jarvis.developer

import android.app.Activity
import org.json.JSONObject
import java.io.File

/**
 * Safe read-only eligibility for one-tap Developer source proposals.
 *
 * A ready plan alone is insufficient: another persisted coding job may still
 * have an unresolved write, review, analysis lease, or build outcome. Never
 * use a local checkpoint as live GitHub CI or as owner approval.
 */
internal object DeveloperTaskAdvance {
    internal data class Offer(val diagnosis: String, val packet: DeveloperPlanContinuation.Packet?)
    private val unresolved = setOf(
        "QUEUED", "RUNNING", "REVIEW_READY", "AWAITING_APPROVAL",
        "COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING",
        "READY_FOR_REVIEW", "REVIEW_REQUIRED"
    )

    fun read(activity: Activity): Offer {
        val root = activity.noBackupFilesDir.canonicalFile
        val plans = TaskPlanStore(File(root, "developer-task-plans")).loadAll()
        val jobs = DevelopmentJobInbox(File(root, "development-inbox")).localSnapshot()
        return evaluate(plans, jobs)
    }

    /** All evidence comes from validated, bounded local snapshots. */
    fun evaluate(plans: List<TaskPlanner.Plan>, jobs: List<JSONObject>): Offer {
        val diagnosis = DeveloperTaskTriage.summarize(plans, jobs)
        val next = DeveloperPlanContinuation.suggest(plans)
        // Check the whole bounded inbox. A newer cancelled job does not
        // make an older uncertain commit safe to ignore.
        val pending = jobs.asReversed().firstOrNull {
            it.optString("state") in unresolved
        }?.getString("state")
        if (pending != null) return Offer(
            diagnosis + "\n\nUnresolved Developer job: $pending. Review or " +
                "reconcile it in Track before another source preview can start. " +
                "No old approval may be reused.",
            null
        )
        return Offer(diagnosis, next.packet)
    }
}
