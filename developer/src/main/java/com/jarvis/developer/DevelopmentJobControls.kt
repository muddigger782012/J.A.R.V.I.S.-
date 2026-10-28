package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject
import kotlin.concurrent.thread

/** Same read cancellation and recovery effects for authenticated IPC and local owner controls. */
internal object DevelopmentJobControlEffects {
    fun accepted(context: Context, inbox: DevelopmentJobInbox, result: JSONObject) {
        if (result.getString("state") in setOf("CANCELLED", "PAUSED")) {
            DevelopmentArtifactReads.cancel(result.getString("id"))
            inbox.installationRecord(result.getString("id"))?.let { JarvisPackageInstaller.abandon(context, it) }
        }
        DevelopmentAnalysisScheduler.accepted(context, result)
        DevelopmentEngineeringScheduler.accepted(context, result)
    }
}

/** Local controls reuse the durable state machine. They never submit a source diff or install. */
internal object DevelopmentJobControls {
    internal class Services(
        val accepted: (Context, DevelopmentJobInbox, JSONObject) -> Unit = DevelopmentJobControlEffects::accepted,
        val credentials: (JSONObject, String) -> Unit = DevelopmentEngineeringCredentials::rememberForRead,
        val worker: (() -> Unit) -> Unit = { work -> thread(name = "developer-job-control", isDaemon = true) { work() }; Unit }
    )
    fun attach(activity: Activity, parent: LinearLayout, inbox: DevelopmentJobInbox, job: JSONObject,
        actions: List<DevelopmentJobInbox.LocalControl>, mode: () -> String,
        isCurrent: () -> Boolean, changed: () -> Unit, services: Services = Services(), githubToken: () -> String = { "" }) {
        if (actions.isEmpty()) return
        val status = TextView(activity).apply {
            text = "Controls stop local work and monitoring. Remote operations already submitted may finish. Source-write and installation approvals are never restored."
            contentDescription = "Task control status"
            setTextColor(activity.getColor(R.color.developer_secondary))
            setPadding(8, 12, 8, 12)
        }
        val buttons = mutableListOf<Button>()
        var pending = false
        actions.forEach { action ->
            val button = Button(activity).apply {
                text = action.label; isAllCaps = false
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setTextColor(activity.getColor(R.color.developer_primary))
                backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.developer_panel))
            }
            buttons.add(button); parent.addView(button)
            button.setOnClickListener {
                if (pending || !isCurrent()) return@setOnClickListener
                pending = true; buttons.forEach { it.isEnabled = false }
                val selectedMode = mode()
                val readToken = if (action in setOf(DevelopmentJobInbox.LocalControl.RESUME, DevelopmentJobInbox.LocalControl.RETRY)) githubToken() else ""
                status.text = "Saving task control…"
                services.worker {
                    val result = runCatching { inbox.controlLocal(job.getString("id"), job.getLong("revision"),
                        selectedMode, Wire.personalPackage(activity.packageName), action) }
                    val scheduled = result.mapCatching { state ->
                        services.credentials(state, readToken)
                        services.accepted(activity.applicationContext, inbox, state)
                    }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) return@runOnUiThread
                        pending = false
                        result.onSuccess {
                            status.text = when (action) {
                                DevelopmentJobInbox.LocalControl.PAUSE -> "Task paused locally. Remote commits or builds already submitted may finish."
                                DevelopmentJobInbox.LocalControl.RESUME -> "Task resumed. Source changes still require a fresh exact-diff approval."
                                DevelopmentJobInbox.LocalControl.CANCEL -> "Task cancelled locally. Use the read-only recheck to inspect remote work already submitted."
                                DevelopmentJobInbox.LocalControl.RETRY -> "Read-only work queued. No source write or installation was approved."
                            }
                            if (scheduled.isFailure) status.text = "Task state saved. Background recovery could not be scheduled; refresh this task."
                            if (scheduled.isSuccess) changed()
                        }.onFailure { failure ->
                            status.text = when ((failure as? Wire.Rejected)?.code) {
                                "CONFLICT" -> "This task changed. Refresh before controlling it again."
                                "POLICY_REQUIRED" -> "The selected development mode does not permit this action. Check Settings, then refresh."
                                "RETRY_BLOCKED" -> "The retry limit or an uncertain source outcome requires review. No write was repeated."
                                else -> "Task state could not be saved. Refresh before trying again."
                            }
                            // A failed/stale click requires a fresh snapshot, not a hidden retry.
                        }
                    }
                }
            }
        }
        parent.addView(status)
    }
}
