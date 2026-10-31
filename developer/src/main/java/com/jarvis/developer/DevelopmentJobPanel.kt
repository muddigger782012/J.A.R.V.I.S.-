package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Durable, explicit-approval workflow. Does not pretend to execute remote builds. */
object DevelopmentJobPanel {
    fun attach(activity: Activity, parent: LinearLayout) {
        val prefs = activity.getSharedPreferences("developer_jobs_v1", Context.MODE_PRIVATE)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF0C202C.toInt())
        }
        parent.addView(box)
        fun field(hintText: String): EditText = EditText(activity).apply {
            hint = hintText
            setSingleLine(true)
            box.addView(this)
        }
        val heading = TextView(activity).apply {
            text = "DEVELOPMENT JOB CONTROL"
            setTextColor(0xFF4EDDF3.toInt())
            textSize = 16f
        }
        box.addView(heading, 0)
        val project = field("Project ID").apply { setText("jarvis-developer") }
        val instruction = field("Development instruction (max 4000 characters)")
        val proposal = field("Reviewed proposal ID")
        val run = field("Actual GitHub build run ID")
        val state = TextView(activity).apply { setTextColor(Color.WHITE) }
        box.addView(state)
        var current: DevelopmentJobController.Job? = null
        fun display() {
            val job = current
            state.text = if (job == null) "No active job" else
                "Project: ${job.projectId} | Stage: ${job.stage} | Run: ${job.buildRunId ?: "none"}"
        }
        fun save(job: DevelopmentJobController.Job) {
            val prefix = job.projectId
            prefs.edit().putString("active", prefix)
                .putString("$prefix.instruction", job.instruction)
                .putString("$prefix.stage", job.stage.name)
                .putString("$prefix.proposal", job.approvedProposalId)
                .putLong("$prefix.run", job.buildRunId ?: 0L).apply()
            current = job
            display()
        }
        fun button(title: String, onClick: () -> Unit) {
            box.addView(Button(activity).apply { text = title; setOnClickListener { onClick() } })
        }
        button("Create or load project job") {
            val id = project.text.toString().trim()
            try {
                val existing = prefs.getString("$id.instruction", null)
                if (existing == null) save(DevelopmentJobController.Job(id, instruction.text.toString()))
                else {
                    val stage = DevelopmentJobController.Stage.valueOf(
                        prefs.getString("$id.stage", "INTAKE") ?: "INTAKE")
                    save(DevelopmentJobController.Job(id, existing, stage,
                        prefs.getString("$id.proposal", null),
                        prefs.getLong("$id.run", 0L).takeIf { it > 0 }))
                }
            } catch (e: IllegalArgumentException) { state.text = "Invalid job: ${e.message}" }
        }
        val events = listOf(
            "Analyze instruction" to DevelopmentJobController.Event.ANALYZED,
            "Accept design" to DevelopmentJobController.Event.DESIGN_ACCEPTED,
            "Source proposal prepared" to DevelopmentJobController.Event.SOURCE_PREPARED,
            "Approve reviewed source" to DevelopmentJobController.Event.SOURCE_APPROVED,
            "Record started GitHub build" to DevelopmentJobController.Event.BUILD_STARTED,
            "Record successful build" to DevelopmentJobController.Event.BUILD_PASSED,
            "Record failed build" to DevelopmentJobController.Event.BUILD_FAILED,
            "Prepare repair proposal" to DevelopmentJobController.Event.REPAIR_PROPOSED,
            "Approve reviewed repair" to DevelopmentJobController.Event.REPAIR_APPROVED,
            "Abort job" to DevelopmentJobController.Event.ABORT
        )
        events.forEach { (title, event) ->
            button(title) {
                try {
                    val job = requireNotNull(current) { "Create or load a job first" }
                    save(DevelopmentJobController.advance(job, event,
                        proposalId = proposal.text.toString().trim().ifBlank { null },
                        runId = run.text.toString().toLongOrNull()))
                } catch (e: IllegalArgumentException) {
                    state.text = "Transition blocked: ${e.message}"
                }
            }
        }
        display()
    }
}
