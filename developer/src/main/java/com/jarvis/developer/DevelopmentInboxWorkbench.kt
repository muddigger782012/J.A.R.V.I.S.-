package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** An owner-visible view of actual admission metadata, not a simulated engineering runner. */
object DevelopmentInboxWorkbench {
    fun attach(activity: Activity, parent: LinearLayout, settings: LinearLayout,
        patch: PatchWorkbench.Handle? = null, githubToken: () -> String = { "" }, openReview: (String) -> Unit) {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun label(text: String) = TextView(activity).apply {
            this.text = text; setTextColor(activity.getColor(R.color.developer_secondary)); textSize = 15f
            setPadding(8, 12, 8, 12)
        }
        fun button(text: String, action: () -> Unit) = Button(activity).apply {
            this.text = text; isAllCaps = false
            setTextColor(activity.getColor(R.color.developer_primary))
            backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.developer_panel))
            setOnClickListener { action() }
        }
        val preferences = activity.getSharedPreferences("development_approval", Activity.MODE_PRIVATE)
        val personal = Wire.personalPackage(activity.packageName)
        val modes = DevelopmentApprovalPolicy(personal).availableModes
        settings.addView(label("Development approval mode"))
        val picker = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, modes.map { Wire.modeLabel(it.wireValue) })
            setSelection(modes.indexOfFirst { it.wireValue == preferences.getString("mode", "supervised") }.coerceAtLeast(0))
        }
        settings.addView(picker)
        val modeStatus = label("Mode selection does not grant Android privileges or approval to edit source. Existing exact-proposal approvals remain in force.")
        settings.addView(modeStatus)
        settings.addView(button("Save development mode") {
            val mode = DevelopmentApprovalPolicy(personal).requireAllowed(modes[picker.selectedItemPosition])
            val success = runCatching {
                val audit = JSONArray(preferences.getString("audit", "[]")); check(audit.length() < 100)
                audit.put(JSONObject().put("mode", mode.wireValue).put("at", System.currentTimeMillis()))
                preferences.edit().putString("mode", mode.wireValue).putString("audit", audit.toString()).commit()
            }.getOrDefault(false)
            modeStatus.text = if (success) "Saved ${Wire.modeLabel(mode.wireValue)}. No source scope or Android privilege was granted."
                else "The mode or audit record could not be saved. No approval change was confirmed."
        })
        fun refresh() {
            panel.removeAllViews(); panel.addView(label("Assistant development reviews — checking private queue"))
            thread(name = "developer-inbox-read", isDaemon = true) {
                val inbox = DevelopmentJobInbox(java.io.File(activity.noBackupFilesDir.canonicalFile, "development-inbox"))
                val result = runCatching {
                    val jobs = inbox.localSnapshot()
                    Triple(jobs, inbox.pendingReviews().toMap(), jobs.takeLast(8).mapNotNull { job ->
                        inbox.localAnalysisReport(job.getString("id"))?.let { job.getString("id") to it }
                    }.toMap()) to jobs.takeLast(8).associate { job -> job.getString("id") to
                        if (job.getString("state") in setOf("REVIEW_READY", "AWAITING_APPROVAL"))
                            runCatching { inbox.engineeringPaths(job.getString("id")) to inbox.engineeringGoal(job.getString("id")) }
                                .getOrDefault(emptyList<String>() to "") else emptyList<String>() to "" }
                }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    panel.removeAllViews()
                    result.onFailure { panel.addView(label("Review history could not be read. No records were reset or completion inferred.")) }
                        .onSuccess { (snapshot, scopes) ->
                            val (jobs, drafts, reports) = snapshot
                            panel.addView(label("${jobs.size} stored jobs. Source, build, tests, signing and installation have separate verification gates."))
                            jobs.takeLast(8).forEachIndexed { index, job ->
                                val stages = job.getJSONObject("verification")
                                panel.addView(label("Review ${index + 1}: ${job.getString("state")} · ${Wire.modeLabel(job.getString("mode"))}\n" +
                                    Wire.Stage.values().joinToString("\n") { "${it.name.replace('_', ' ')}: ${stages.getString(it.name)}" }))
                                drafts[job.getString("id")]?.let { draft -> panel.addView(button("Open this unapproved review") {
                                    openReview(draft.lines().joinToString("\n", transform = RepositoryRedaction::line))
                                }) }
                                reports[job.getString("id")]?.let { report -> panel.addView(button("Open verified-source analysis (partial context)") {
                                    openReview(report)
                                }) }
                                val (paths, savedGoal) = scopes[job.getString("id")] ?: (emptyList<String>() to "")
                                DevelopmentArtifactWorkbench.attach(activity, panel, inbox, job, githubToken)
                                if (job.getBoolean("sourceVerified")) panel.addView(button("Review this verified commit on GitHub") {
                                    runCatching {
                                        val url = inbox.engineeringReviewUrl(job.getString("id")) ?: error("No verified commit")
                                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }.onFailure { android.widget.Toast.makeText(activity,
                                        "The recorded commit or browser could not be opened. Refresh this job; no source or approval was changed.", android.widget.Toast.LENGTH_LONG).show() }
                                })
                                if ((job.optJSONObject("engineering")?.optJSONObject("build")?.optLong("run", 0) ?: 0L) > 0L)
                                    panel.addView(button("Open recorded build diagnostics") {
                                        runCatching {
                                            openReview(inbox.engineeringDiagnostics(job.getString("id")) ?: error("No recorded diagnostics"))
                                        }.onFailure { android.widget.Toast.makeText(activity,
                                            "Recorded diagnostics are unavailable. Refresh or recheck this job; no approval was granted.", android.widget.Toast.LENGTH_LONG).show() }
                                    })
                                if (job.optJSONObject("engineering")?.optBoolean("approvalConsumed") == true &&
                                    job.getString("state") in setOf("READY_FOR_REVIEW", "REVIEW_REQUIRED", "OUTCOME_UNKNOWN")) {
                                    val resultLabel = label("Build checks use your current in-memory GitHub connection when available; approvals are never restored.")
                                    panel.addView(button("Recheck this job's outcome and build (read only)") {
                                        val checked = runCatching {
                                            inbox.refreshEngineeringEvidence(job.getString("id"), job.getLong("revision"), Wire.personalPackage(activity.packageName))
                                            DevelopmentEngineeringCredentials.remember(job.getString("id"), githubToken())
                                            DevelopmentEngineeringScheduler.recover(activity)
                                        }
                                        resultLabel.text = if (checked.isSuccess) "Read-only verification queued. Refresh for recorded results."
                                            else "Refresh this changed job and check the GitHub connection. No write or approval was restored."
                                    })
                                    panel.addView(resultLabel)
                                }
                                if (patch != null && paths.isNotEmpty()) {
                                    val goal = EditText(activity).apply {
                                        hint = "Describe the change you want Developer to make"
                                        contentDescription = "Requested engineering change"
                                        setText(savedGoal)
                                        minLines = 2
                                    }
                                    val scope = EditText(activity).apply {
                                        hint = "Exact analyzed paths for this task (one per line)"
                                        contentDescription = "Engineering task source scope"
                                        setText(paths.joinToString("\n")); minLines = 2
                                    }
                                    val outcome = label("Staging does not grant approval. Review every proposed change in the existing coding engine.")
                                    panel.addView(goal); panel.addView(scope)
                                    panel.addView(button("Prepare this job for exact diff review") {
                                        val result = runCatching {
                                            val selected = scope.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                                            val draft = inbox.stageEngineering(job.getString("id"), job.getLong("revision"), selected,
                                                Wire.personalPackage(activity.packageName), goal.text.toString().trim())
                                            check(patch.prepareEngineering(draft))
                                        }
                                        outcome.text = if (result.isSuccess) "Task staged in the coding engine. Generate a diff, review it, then approve the isolated commit explicitly."
                                            else "Describe the requested change, choose analyzed paths, and refresh if the job changed or the coding engine is busy. No write was approved."
                                    })
                                    panel.addView(outcome)
                                }
                            }
                        }
                }
            }
        }
        val inboxSection = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        inboxSection.addView(label("Assistant development inbox"))
        inboxSection.addView(button("Refresh development reviews", ::refresh))
        panel.addView(label("Refresh to read persisted review states and verification gates."))
        inboxSection.addView(panel)
        // Retain the existing final action and scroll-to-bottom behavior on short screens.
        parent.addView(inboxSection, minOf(2, parent.childCount))
    }
}
