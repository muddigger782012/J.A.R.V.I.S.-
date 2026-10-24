package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** Explicit, bounded repair sessions. Reading feedback never starts a model or repository write. */
object RepairWorkbench {
    fun attach(
        activity: Activity,
        root: LinearLayout,
        task: EditText,
        githubToken: EditText,
        output: TextView,
        status: TextView,
        patches: PatchWorkbench.Handle
    ) {
        attach(activity, root, task, githubToken, output, status, patches, Services())
    }

    /** GET-only seams for widget/lifecycle checks; no provider or publisher operation is exposed. */
    internal class Services(
        val read: (String, String, String, Long?) -> BuildDiagnostics.Report = { token, branch, sha, run ->
            BuildFeedback.read(token, branch, sha, run)
        },
        val worker: (() -> Unit) -> Unit = { operation ->
            thread(name = "JarvisDeveloper-RepairFeedback") { operation() }; Unit
        }
    )

    internal fun attach(activity: Activity, root: LinearLayout, task: EditText, githubToken: EditText,
        output: TextView, status: TextView, patches: PatchWorkbench.Handle, services: Services) {
        Controller(activity, root, task, githubToken, output, status, patches, services).attach()
    }

    private class Controller(
        private val activity: Activity,
        private val root: LinearLayout,
        private val sharedTask: EditText,
        private val githubToken: EditText,
        private val output: TextView,
        private val status: TextView,
        private val patches: PatchWorkbench.Handle,
        private val services: Services
    ) {
        private val context = root.context
        private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val baselineBranch = input("Successful baseline branch", true)
        private val baselineSha = input("Successful baseline: exact 40-character commit SHA", true, 40)
        private val baselineRun = input("Optional baseline workflow run ID", true, 18)
        private val readBaseline = button("Read successful baseline CI and regression expectations (GET only)")
        private val failedBranch = input("Failing / latest repair branch", true)
        private val failedSha = input("Failing / latest repair: exact 40-character commit SHA", true, 40)
        private val failedRun = input("Optional failing / latest workflow run ID", true, 18)
        private val readFailed = button("Read exact failing / latest repair CI (GET only)")
        private val sessionId = input("New repair session ID (1–39 letters, digits, hyphens or underscores)", true, 39)
        private val scope = input("Repair allowlist: 1–8 exact permitted Developer paths, one per line", max = 2_000)
        private val goal = input("Repair goal and acceptance criteria (maximum 4,000 characters)", max = 4_000)
        private val start = button("Start NEW session: reset to 2 attempts and a 30-minute budget")
        private val prepare = button("Reserve ONE repair attempt and stage a NEW diff review")
        private val abandon = button("Abandon staged repair approval (consumes this reserved attempt)")
        private val dashboard = TextView(context).apply { setTextIsSelectable(true) }
        private val savedPlanId = input("Optional existing local plan ID for repair audit evidence", true, 48)
        private val saveAudit = button("Save repair audit as a local plan note")
        private val identityInputs = listOf(baselineBranch, baselineSha, baselineRun,
            failedBranch, failedSha, failedRun)
        private val inputs = identityInputs + listOf(sessionId, scope, goal, savedPlanId)
        private val buttons = listOf(readBaseline, readFailed, start, prepare, abandon, saveAudit)
        private var baseline: BuildDiagnostics.Report? = null
        private var failed: BuildDiagnostics.Report? = null
        private var session: RepairEngine.Session? = null
        private var ticket: RepairEngine.Ticket? = null
        private var awaiting: PatchPublisher.CommitResult? = null
        private var canRetry = false
        private var sessionInputs: SessionInputs? = null
        private var busy = false
        private var rendering = false
        private var revision = 0L

        private data class SessionInputs(val id: String, val paths: List<String>, val task: String)
        private data class ReadInputs(val branch: String, val sha: String, val runId: Long?)

        private fun input(hintText: String, single: Boolean = false, max: Int = 120) = EditText(context).apply {
            hint = hintText
            filters = arrayOf(InputFilter.LengthFilter(max))
            if (single) setSingleLine(true) else {
                minLines = 2
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        private fun button(label: String) = Button(context).apply { text = label }
        private fun label(text: String) = TextView(context).apply { this.text = text }
        private fun alive() = !activity.isFinishing && !activity.isDestroyed

        fun attach() {
            root.addView(container)
            container.addView(label("Testing and debugging — exact-source feedback and bounded repairs"))
            container.addView(label("Read a complete successful baseline before starting a repair. Its passing tests become regression expectations. Each session permits at most two attempts within 30 minutes; its execution budget stays in memory. Starting a NEW session explicitly resets that budget. No model request, retry, CI dispatch, signing, publication or installation starts automatically."))
            baselineBranch.setText("feature/jarvis-developer")
            failedBranch.setText("feature/jarvis-developer")
            sessionId.setText("repair-" + UUID.randomUUID().toString().replace("-", "").take(16))
            goal.setText(sharedTask.text.toString().take(4_000))
            listOf<View>(baselineBranch, baselineSha, baselineRun, readBaseline,
                failedBranch, failedSha, failedRun, readFailed, sessionId, scope, goal,
                start, prepare, abandon, dashboard, savedPlanId, saveAudit).forEach(container::addView)
            container.addView(label("Changing repair inputs or using another repair action invalidates any staged approval and consumes its reserved attempt. Staging only fills the separate coding controls: generate, review the complete diff and explicitly approve that fresh proposal before committing. After a commit, read its exact CI explicitly; no result is assumed. Saving audit metadata requires an existing Phase 3 local plan and does not mark a step complete or retain source, diffs, keys or raw logs."))
            inputs.forEach(::watch)
            readBaseline.setOnClickListener { read(true) }
            readFailed.setOnClickListener { read(false) }
            start.setOnClickListener { startSession() }
            prepare.setOnClickListener { prepareAttempt() }
            abandon.setOnClickListener {
                if (begin()) {
                    status.text = "Staged repair approval abandoned. Its reserved attempt remains consumed."
                    refresh()
                }
            }
            saveAudit.setOnClickListener { saveNote() }
            refresh()
        }

        private fun watch(input: EditText) {
            var previous = input.text.toString()
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    if (!rendering) previous = s?.toString().orEmpty()
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (rendering) return
                    if (busy || !patches.invalidate()) {
                        rendering = true
                        try { input.setText(previous); input.setSelection(input.text.length) }
                        finally { rendering = false }
                        status.text = "Finish the active operation before changing repair inputs."
                        return
                    }
                    cancelTicket()
                    revision++
                    if (input in listOf(baselineBranch, baselineSha, baselineRun)) baseline = null
                    if (input in listOf(failedBranch, failedSha, failedRun)) failed = null
                    refresh()
                }
            })
        }

        /** Called on the UI thread before every action; a busy patch cannot lose its context. */
        private fun begin(): Boolean {
            if (busy || !patches.invalidate()) {
                status.text = "Finish the active operation before changing repair state."
                return false
            }
            cancelTicket()
            revision++
            return true
        }

        private fun cancelTicket() {
            val active = ticket ?: return
            val current = session
            ticket = null
            if (current == null) return
            try {
                session = RepairEngine.recordFailure(current, active, "approval", System.currentTimeMillis())
                canRetry = session?.stopReason == null
            } catch (_: Exception) { disableSession() }
        }

        private fun disableSession() {
            session = null
            ticket = null
            awaiting = null
            canRetry = false
            sessionInputs = null
            status.text = "Repair session stopped because its state could not be verified. Start a new explicit session after reviewing CI."
        }

        private fun currentInputs() = SessionInputs(sessionId.text.toString().trim(),
            scope.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
            goal.text.toString().trim())

        private fun readInputs(baselineRead: Boolean): ReadInputs {
            val branch = (if (baselineRead) baselineBranch else failedBranch).text.toString().trim()
            val sha = (if (baselineRead) baselineSha else failedSha).text.toString().trim()
            val rawRun = (if (baselineRead) baselineRun else failedRun).text.toString().trim()
            require(BuildDiagnostics.validBranch(branch) && Regex("[a-f0-9]{40}").matches(sha))
            require(rawRun.isEmpty() || Regex("[0-9]{1,18}").matches(rawRun))
            val runId = if (rawRun.isEmpty()) null else rawRun.toLong().also { require(it > 0) }
            return ReadInputs(branch, sha, runId)
        }

        private fun read(baselineRead: Boolean) {
            if (!begin()) return
            val frozen = try { readInputs(baselineRead) } catch (_: Exception) {
                status.text = "Enter an allowed Developer branch, exact lowercase commit SHA and an optional positive run ID."
                refresh(); return
            }
            val readRevision = revision
            val readSession = session
            val token = githubToken.text.toString().trim()
            busy = true
            status.text = "Reading bounded GET-only feedback for ${frozen.branch} at ${frozen.sha}."
            output.text = "Reading exact-source CI feedback; previous results do not establish this run's outcome."
            refresh()
            services.worker {
                val result = runCatching { services.read(token, frozen.branch, frozen.sha, frozen.runId) }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    busy = false
                    if (readRevision != revision || session !== readSession || !patches.invalidate()) {
                        status.text = "Discarded feedback because repair inputs or the active coding operation changed."
                        refresh(); return@runOnUiThread
                    }
                    result.fold(onSuccess = { report ->
                        try {
                            BuildDiagnostics.validateReport(report)
                            require(report.target == PatchEngine.Target.DEVELOPER)
                            require(report.commitSha == frozen.sha && report.branch == frozen.branch)
                            require(frozen.runId == null || report.runId == frozen.runId)
                            if (baselineRead) {
                                require(report.status == "completed" && report.conclusion == "success" &&
                                    report.evidenceComplete && report.tests.isNotEmpty() &&
                                    report.tests.values.all { it == BuildDiagnostics.TestState.PASSED })
                                baseline = report
                                status.text = "Complete successful baseline retained in memory with ${report.tests.size} passing regression expectations."
                            } else {
                                val current = session
                                if (awaiting != null && current != null) {
                                    val checked = RepairEngine.assess(current, report, System.currentTimeMillis())
                                    session = checked.session
                                    canRetry = checked.canRetry
                                    if (report.status == "completed") awaiting = null
                                    status.text = checked.summary
                                } else {
                                    status.text = "Exact-source feedback read. Review diagnostics before explicitly starting or preparing repair work."
                                }
                                failed = report
                            }
                            output.text = BuildDiagnostics.render(report)
                        } catch (_: Exception) {
                            if (baselineRead) baseline = null else failed = null
                            canRetry = false
                            status.text = "Feedback was not eligible for this baseline or repair state. No new attempt or approval was created."
                        }
                    }, onFailure = { failure ->
                        if (baselineRead) baseline = null else failed = null
                        val detail = BuildFeedback.explainFailure(failure)
                        status.text = detail ?: "Could not read verified CI feedback. Check repository access and exact source/run identity; no write or retry occurred."
                        output.text = detail ?: "No verified feedback was retrieved for ${frozen.branch} at ${frozen.sha}."
                    })
                    refresh()
                }
            }
        }

        private fun startSession() {
            if (!begin()) return
            try {
                val frozen = currentInputs()
                require(frozen.task.isNotBlank() && frozen.task.length <= 4_000)
                val baselineReport = baseline ?: error("Baseline required")
                val failedReport = failed ?: error("Failure required")
                require(failedReport.status == "completed" && failedReport.conclusion == "failure")
                session = RepairEngine.start(frozen.id, frozen.paths, baselineReport, 2, System.currentTimeMillis())
                sessionInputs = frozen
                awaiting = null
                canRetry = true
                status.text = "New explicit repair session started: at most two attempts within 30 minutes. Prepare one attempt to inspect a fresh diff."
            } catch (_: Exception) {
                status.text = "Session not started. Read complete successful baseline and failed CI, then check session ID, repair goal and permitted 1–8-file scope."
            }
            refresh()
        }

        private fun prepareAttempt() {
            if (!begin()) return
            try {
                val current = session ?: error("Session required")
                require(awaiting == null && canRetry && currentInputs() == sessionInputs)
                val report = failed ?: error("Failure report required")
                val prepared = RepairEngine.prepare(current, report, currentInputs().task, System.currentTimeMillis())
                session = prepared.session
                ticket = prepared.ticket
                canRetry = false
                val capturedSession = prepared.session
                val capturedTicket = prepared.ticket
                val observer = object : PatchWorkbench.RepairObserver {
                    override fun committed(ticket: RepairEngine.Ticket, result: PatchPublisher.CommitResult) {
                        if (!alive() || ticket !== capturedTicket || this@Controller.ticket !== capturedTicket ||
                            session !== capturedSession) return
                        try {
                            session = RepairEngine.recordCommit(capturedSession, capturedTicket, result, System.currentTimeMillis())
                            this@Controller.ticket = null
                            awaiting = result
                            failed = null
                            canRetry = false
                            rendering = true
                            try {
                                failedBranch.setText(result.branch)
                                failedSha.setText(result.commitSha)
                                failedRun.setText("")
                            } finally { rendering = false }
                            status.text = "Repair commit recorded at ${result.commitSha}. Read its exact CI explicitly; no passing result, signing or publication is assumed."
                        } catch (_: Exception) { disableSession() }
                        refresh()
                    }
                    override fun failed(ticket: RepairEngine.Ticket, stage: String) {
                        if (!alive() || ticket !== capturedTicket || this@Controller.ticket !== capturedTicket ||
                            session !== capturedSession) return
                        try {
                            session = RepairEngine.recordFailure(capturedSession, capturedTicket, stage, System.currentTimeMillis())
                            this@Controller.ticket = null
                            canRetry = session?.stopReason == null
                            status.text = if (stage == "cancelled")
                                "Repair preview cancelled. Its attempt budget remains consumed; review the session before preparing another attempt."
                            else "Repair attempt stopped at $stage. Its budget remains consumed; review the session before preparing another attempt."
                        } catch (_: Exception) { disableSession() }
                        refresh()
                    }
                }
                if (!patches.prepareRepair(capturedTicket, observer)) {
                    // A refused stage never receives a callback; consume that reservation once.
                    if (ticket === capturedTicket) {
                        session = RepairEngine.recordFailure(capturedSession, capturedTicket, "preview", System.currentTimeMillis())
                        ticket = null
                        canRetry = session?.stopReason == null
                    }
                    status.text = "Repair staging refused. The reservation remains consumed; no model request or write started."
                } else {
                    status.text = "One attempt reserved. Use the coding controls to generate, review and explicitly approve its fresh scoped diff."
                }
            } catch (_: Exception) {
                // A failed handoff still closes a reservation; no invisible ticket can be reused.
                cancelTicket()
                status.text = "Repair preparation stopped. Review scope, exact failed CI, repeated failures, deadline and remaining budget. Edited session inputs require a new explicit session."
                canRetry = false
            }
            refresh()
        }

        private fun saveNote() {
            if (!begin()) return
            val current = session ?: run { status.text = "Start a repair session before saving audit evidence."; refresh(); return }
            val planId = savedPlanId.text.toString().trim()
            if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}").matches(planId)) {
                status.text = "Enter an existing local plan ID; create one in Task planning first if needed."
                refresh(); return
            }
            val audit = try { RepairEngine.audit(current) } catch (_: Exception) { disableSession(); refresh(); return }
            busy = true
            refresh()
            thread(name = "JarvisDeveloper-RepairAudit") {
                val result = runCatching {
                    val store = TaskPlanStore(File(context.noBackupFilesDir.canonicalFile, "developer-task-plans"))
                    val plan = store.loadAll().single { it.id == planId }
                    val noted = TaskPlanner.note(plan, audit, maxOf(System.currentTimeMillis(), plan.updatedAtMillis))
                    store.save(noted, plan.revision)
                }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    busy = false
                    status.text = if (result.isSuccess) "Repair audit saved as a local plan decision. Reload Task planning to view the note; no step was marked complete."
                        else "Audit note was not saved. Check the existing plan ID, checkpoint integrity, revision and decision budget, then reload local plans."
                    refresh()
                }
            }
        }

        private fun refresh() {
            inputs.forEach { it.isEnabled = !busy }
            buttons.forEach { it.isEnabled = !busy }
            readFailed.isEnabled = !busy && (baseline != null || session != null)
            start.isEnabled = !busy && baseline != null && failed != null && awaiting == null
            prepare.isEnabled = !busy && session != null && session?.stopReason == null &&
                ticket == null && awaiting == null && failed != null && canRetry && currentInputs() == sessionInputs
            abandon.isEnabled = !busy && ticket != null
            saveAudit.isEnabled = !busy && session != null
            dashboard.text = buildString {
                append("Baseline: ")
                val base = baseline
                if (base == null) append("read a complete successful CI run first.\n") else {
                    append("${base.branch} @ ${base.commitSha}\n${base.runUrl}\n")
                    append("${base.tests.size} passing test identities retained as regression expectations.\n")
                }
                val current = session
                if (current == null) append("No active repair session.\n") else {
                    append('\n').append(RepairEngine.render(current)).append('\n')
                    if (currentInputs() != sessionInputs) append("Session inputs changed: a new explicit session is required before staging.\n")
                }
                if (ticket != null) append("A reserved attempt is staged in the coding controls; its exact new diff still requires explicit approval.\n")
                if (awaiting != null) append("Awaiting an explicit exact-commit CI read; another attempt is blocked.\n")
                failed?.let { append("\nLatest feedback:\n").append(BuildDiagnostics.render(it)) }
                append("\nSession tickets and approvals are never persisted. Only explicit Save audit retains bounded source/CI references in an existing local plan.")
            }
        }
    }
}
