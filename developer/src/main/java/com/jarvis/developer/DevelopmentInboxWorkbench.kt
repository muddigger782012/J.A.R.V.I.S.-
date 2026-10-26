package com.jarvis.developer

import android.app.Activity
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** An owner-visible view of actual admission metadata, not a simulated engineering runner. */
object DevelopmentInboxWorkbench {
    fun attach(activity: Activity, parent: LinearLayout, settings: LinearLayout, openReview: (String) -> Unit) {
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
                val result = runCatching { inbox.localSnapshot() to inbox.pendingReviews().toMap() }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    panel.removeAllViews()
                    result.onFailure { panel.addView(label("Review history could not be read. No records were reset or completion inferred.")) }
                        .onSuccess { (jobs, drafts) ->
                            panel.addView(label("${jobs.size} stored reviews. These are unverified admission records, not completed repairs."))
                            jobs.takeLast(8).forEachIndexed { index, job ->
                                val stages = job.getJSONObject("verification")
                                panel.addView(label("Review ${index + 1}: ${job.getString("state")} · ${Wire.modeLabel(job.getString("mode"))}\n" +
                                    Wire.Stage.values().joinToString("\n") { "${it.name.replace('_', ' ')}: ${stages.getString(it.name)}" }))
                                drafts[job.getString("id")]?.let { draft -> panel.addView(button("Open this unapproved review") {
                                    openReview(draft.lines().joinToString("\n", transform = RepositoryRedaction::line))
                                }) }
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
