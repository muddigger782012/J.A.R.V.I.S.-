package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID
import kotlin.concurrent.thread

/** Explicit source planning and metadata-only promotion review; attaching performs no operation. */
object SelfDevelopmentWorkbench {
    fun attach(activity: Activity, root: LinearLayout, task: EditText, githubToken: EditText,
        output: TextView, status: TextView, patches: PatchWorkbench.Handle) {
        Controller(activity, root, task, githubToken, output, status, patches).attach()
    }

    private class Controller(
        private val activity: Activity,
        private val root: LinearLayout,
        private val sharedTask: EditText,
        private val githubToken: EditText,
        private val output: TextView,
        private val status: TextView,
        private val patches: PatchWorkbench.Handle
    ) {
        private val context = root.context
        private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val taskId = input("Self-development task ID", 48, true)
        private val goal = input("Own Developer improvement goal (maximum 4,000 characters)", 4_000)
        private val scope = input("1–8 existing permitted Developer production paths, one per line", 2_000)
        private val analyze = button("Read source and propose bounded own-code improvements (GET only)")
        private val stage = button("Stage this exact source scope for a NEW diff review")
        private val baselineBranch = input("Successful baseline branch", 120, true)
        private val baselineSha = input("Successful baseline exact source SHA", 40, true)
        private val baselineRun = input("Optional baseline debug workflow run ID", 18, true)
        private val readBaseline = button("Read exact baseline debug CI (GET only)")
        private val candidateBranch = input("Isolated candidate task branch", 140, true)
        private val candidateSha = input("Candidate exact source SHA", 40, true)
        private val candidateRun = input("Optional candidate debug workflow run ID", 18, true)
        private val readCandidate = button("Read exact candidate debug CI (GET only)")
        private val controllerSha = input("Trusted evaluation controller exact source SHA", 40, true)
        private val signedRun = input("Completed candidate evaluation workflow run ID", 18, true)
        private val readSigned = button("Read signed candidate and recovery evidence (GET only)")
        private val evaluate = button("Compare baseline, candidate and verified recovery evidence")
        private val confirmation = input("Enter exact eligible review SHA-256", 64, true)
        private val approve = CheckBox(context).apply {
            text = "I reviewed this exact eligible candidate and approve recording its promotion request"
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        private val confirm = button("Record exact approved promotion request (metadata only)")
        private val dashboard = TextView(context).apply { setTextIsSelectable(true) }
        private val planInputs = listOf(taskId, goal, scope)
        private val baselineInputs = listOf(baselineBranch, baselineSha, baselineRun)
        private val candidateInputs = listOf(candidateBranch, candidateSha, candidateRun, controllerSha, signedRun)
        private val inputs = planInputs + baselineInputs + candidateInputs + confirmation
        private val buttons = listOf(analyze, stage, readBaseline, readCandidate, readSigned, evaluate, confirm)
        private var proposal: SelfDevelopment.Proposal? = null
        private var baseline: BuildDiagnostics.Report? = null
        private var candidate: BuildDiagnostics.Report? = null
        private var evidence: CandidateEvaluation.Evidence? = null
        private var review: CandidateEvaluation.Review? = null
        private var recordedAudit: String? = null
        private var busy = false
        private var rendering = false
        private var revision = 0L

        private data class SourceInputs(val id: String, val goal: String, val paths: List<String>)
        private data class CiInputs(val branch: String, val sha: String, val runId: Long?)

        fun attach() {
            root.addView(container)
            container.addView(label("Controlled self-development — source proposals and candidate review"))
            container.addView(label("Suggestions use immutable analyzed own-code metadata and an explicit existing production scope. Existing tests, build configuration, coding controls, secrets, normal Jarvis and Beta remain read-only here. No automatic model call, patch, retry, CI dispatch, signing, publication or installation occurs. Source staging uses the separate complete-diff approval controls."))
            taskId.setText("self-" + UUID.randomUUID().toString().replace("-", "").take(16))
            goal.setText(sharedTask.text.toString().take(4_000))
            baselineBranch.setText("feature/jarvis-developer")
            listOf<View>(taskId, goal, scope, analyze, stage, baselineBranch, baselineSha, baselineRun,
                readBaseline, candidateBranch, candidateSha, candidateRun, readCandidate, controllerSha,
                signedRun, readSigned, evaluate, dashboard, confirmation, approve, confirm).forEach(container::addView)
            container.addView(label("Read baseline CI, candidate CI and the separately completed evaluation workflow explicitly. Passing compilation is not recovery verification. Missing, mismatched or unverifiable previous-release bytes, immutable source, signer identity or higher-version recovery blocks promotion. Recording an approved request retains metadata only in this screen; it does not publish, install, merge, dispatch a workflow or restore any approval after restart."))
            inputs.forEach(::watch)
            approve.setOnCheckedChangeListener { _, _ -> refresh() }
            analyze.setOnClickListener { analyzeSource() }
            stage.setOnClickListener { stageSource() }
            readBaseline.setOnClickListener { readCi(true) }
            readCandidate.setOnClickListener { readCi(false) }
            readSigned.setOnClickListener { readEvidence() }
            evaluate.setOnClickListener { evaluateCandidate() }
            confirm.setOnClickListener { confirmReview() }
            refresh()
        }

        private fun input(hintText: String, maximum: Int, single: Boolean = false) = EditText(context).apply {
            hint = hintText
            filters = arrayOf(InputFilter.LengthFilter(maximum))
            if (single) setSingleLine(true) else {
                minLines = 2
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        private fun button(text: String) = Button(context).apply { this.text = text }
        private fun label(text: String) = TextView(context).apply { this.text = text }
        private fun alive() = !activity.isFinishing && !activity.isDestroyed

        private fun clearReview() {
            review = null
            recordedAudit = null
            approve.isChecked = false
            rendering = true
            try { confirmation.setText("") } finally { rendering = false }
        }

        private fun watch(input: EditText) {
            var before = input.text.toString()
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    if (!rendering) before = s?.toString().orEmpty()
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (rendering) return
                    if (busy || !patches.invalidate()) {
                        rendering = true
                        try { input.setText(before); input.setSelection(input.text.length) } finally { rendering = false }
                        status.text = "Finish the active operation before changing self-development inputs."
                        return
                    }
                    revision++
                    if (input == confirmation) {
                        approve.isChecked = false
                        recordedAudit = null
                    } else {
                        clearReview()
                        if (input in planInputs) proposal = null
                        if (input in baselineInputs) baseline = null
                        if (input in candidateInputs) { candidate = null; evidence = null }
                    }
                    refresh()
                }
            })
        }

        /** Every explicit operation invalidates any earlier patch approval and promotion review. */
        private fun begin(clear: Boolean = true): Boolean {
            if (busy || !patches.invalidate()) {
                status.text = "Finish the active operation before changing self-development state."
                return false
            }
            revision++
            if (clear) clearReview()
            return true
        }

        private fun sourceInputs(): SourceInputs {
            val result = SourceInputs(taskId.text.toString().trim(), goal.text.toString().trim(),
                scope.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
            require(result.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) &&
                result.goal.isNotBlank() && result.goal.length <= 4_000 && result.paths.size in 1..8 &&
                result.paths.distinct().size == result.paths.size && result.paths.all(SelfDevelopment::allowedPath))
            require(RepositoryRedaction.line(result.goal) == result.goal && !result.goal.contains("PRIVATE KEY-----"))
            return result
        }

        private fun ciInputs(baselineRead: Boolean): CiInputs {
            val branch = (if (baselineRead) baselineBranch else candidateBranch).text.toString().trim()
            val sha = (if (baselineRead) baselineSha else candidateSha).text.toString().trim()
            val rawRun = (if (baselineRead) baselineRun else candidateRun).text.toString().trim()
            require(BuildDiagnostics.validBranch(branch) && sha.matches(Regex("[a-f0-9]{40}")))
            require(rawRun.isBlank() || rawRun.matches(Regex("[0-9]{1,18}")))
            return CiInputs(branch, sha, if (rawRun.isBlank()) null else rawRun.toLong().also { require(it > 0) })
        }

        private fun analyzeSource() {
            if (!begin()) return
            proposal = null
            val frozen = try { sourceInputs() } catch (_: Exception) {
                status.text = "Enter a bounded goal, task ID and 1–8 unique permitted existing own production paths."
                refresh(); return
            }
            val started = revision
            val token = githubToken.text.toString().trim()
            busy = true; refresh()
            status.text = "Reading immutable source for deterministic scoped self-development suggestions."
            thread(name = "JarvisDeveloper-SelfAnalysis") {
                val result = runCatching {
                    val snapshot = RepositoryIntelligence.analyzeSnapshot(token, frozen.goal)
                    SelfDevelopment.propose(frozen.id, snapshot.second, frozen.paths)
                }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    busy = false
                    if (started != revision || !patches.invalidate()) {
                        status.text = "Discarded stale self-development analysis; no approval or write was created."
                    } else result.fold(onSuccess = {
                        proposal = it
                        output.text = SelfDevelopment.render(it)
                        status.text = "Source-grounded proposal ready. Stage it explicitly for a fresh complete diff review."
                    }, onFailure = {
                        status.text = "Analysis could not establish every scoped source path. Check permitted paths, source access and task relevance; refine the goal before rereading."
                    })
                    refresh()
                }
            }
        }

        private fun stageSource() {
            if (!begin()) return
            try {
                val selected = proposal ?: error("Proposal required")
                SelfDevelopment.validate(selected)
                val current = sourceInputs()
                require(current.id == selected.id && current.goal == selected.goal && current.paths.sorted() == selected.scope)
                require(patches.prepare(selected.id, selected.task, selected.scope, selected.sourceCommitSha, selected.sourceBranch))
                output.text = SelfDevelopment.render(selected)
                status.text = "Exact source scope staged. Generate, review and explicitly approve its new diff using the coding controls."
            } catch (_: Exception) {
                status.text = "Self-development staging refused. Read fresh scoped source and finish any active coding operation first."
            }
            refresh()
        }

        private fun readCi(baselineRead: Boolean) {
            if (!begin()) return
            if (baselineRead) baseline = null else candidate = null
            val frozen = try { ciInputs(baselineRead) } catch (_: Exception) {
                status.text = "Enter an allowed branch, exact lowercase source SHA and optional positive debug run ID."
                refresh(); return
            }
            val started = revision
            val token = githubToken.text.toString().trim()
            busy = true; refresh()
            status.text = "Reading bounded exact-source debug CI feedback."
            thread(name = "JarvisDeveloper-SelfDebugFeedback") {
                val result = runCatching { BuildFeedback.read(token, frozen.branch, frozen.sha, frozen.runId) }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    busy = false
                    if (started != revision || !patches.invalidate()) {
                        status.text = "Discarded feedback because the active source or coding context changed."
                    } else result.fold(onSuccess = {
                        try {
                            BuildDiagnostics.validateReport(it)
                            require(it.branch == frozen.branch && it.commitSha == frozen.sha &&
                                (frozen.runId == null || frozen.runId == it.runId))
                            if (baselineRead) baseline = it else candidate = it
                            output.text = BuildDiagnostics.render(it)
                            status.text = "Exact-source feedback retained for an explicit baseline comparison; no passing candidate is assumed."
                        } catch (_: Exception) { status.text = "Feedback identity could not be verified; candidate review remains blocked." }
                    }, onFailure = { status.text = "Could not verify debug feedback; check source identity and read access." })
                    refresh()
                }
            }
        }

        private fun readEvidence() {
            if (!begin()) return
            evidence = null
            val frozen: CiInputs
            val controller: String
            val run: Long
            try {
                frozen = ciInputs(false)
                controller = controllerSha.text.toString().trim()
                val rawRun = signedRun.text.toString().trim()
                require(controller.matches(Regex("[a-f0-9]{40}")) && rawRun.matches(Regex("[0-9]{1,18}")))
                run = rawRun.toLong().also { require(it > 0) }
            } catch (_: Exception) {
                status.text = "Enter exact candidate branch/SHA, trusted controller SHA and completed evaluation run ID."
                refresh(); return
            }
            val started = revision
            val token = githubToken.text.toString().trim()
            busy = true; refresh()
            status.text = "Reading candidate signing, backup integrity and recovery evidence without dispatching a workflow."
            thread(name = "JarvisDeveloper-CandidateEvidence") {
                val result = runCatching { CandidateFeedback.read(token, frozen.branch, frozen.sha, run, controller) }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    busy = false
                    if (started != revision || !patches.invalidate()) {
                        status.text = "Discarded candidate evidence because the active source or coding context changed."
                    } else result.fold(onSuccess = {
                        evidence = it
                        status.text = "Candidate evidence retained. Compare it explicitly with baseline and exact candidate debug CI."
                    }, onFailure = {
                        status.text = "Signed candidate or recovery evidence could not be verified. Promotion remains blocked."
                    })
                    refresh()
                }
            }
        }

        private fun evaluateCandidate() {
            if (!begin()) return
            try {
                val result = CandidateEvaluation.assess(baseline ?: error("Baseline required"),
                    candidate ?: error("Candidate required"), evidence ?: error("Evidence required"), System.currentTimeMillis())
                review = result
                output.text = CandidateEvaluation.render(result)
                status.text = if (result.eligible) "All evaluation gates passed. Review the exact fingerprint before approving metadata recording."
                    else "Promotion blocked by the recorded baseline, candidate or recovery evidence."
            } catch (_: Exception) {
                status.text = "Read verified baseline, candidate and signed/recovery evidence before evaluating. No promotion request was created."
            }
            refresh()
        }

        private fun confirmReview() {
            if (!begin(clear = false)) return
            val selected = review ?: run { status.text = "Evaluate current evidence first."; refresh(); return }
            val exact = confirmation.text.toString().trim()
            val explicit = approve.isChecked
            review = null
            approve.isChecked = false
            try {
                val request = CandidateEvaluation.confirm(selected, exact, explicit, System.currentTimeMillis())
                recordedAudit = CandidateEvaluation.audit(request)
                output.text = recordedAudit
                status.text = "Exact approved promotion request recorded as in-memory metadata only. No release, install, merge or workflow dispatch occurred."
            } catch (_: Exception) {
                recordedAudit = null
                status.text = "Promotion confirmation refused or expired. Evaluate fresh evidence and review its exact fingerprint again."
            }
            refresh()
        }

        private fun refresh() {
            inputs.forEach { it.isEnabled = !busy }
            buttons.forEach { it.isEnabled = !busy }
            stage.isEnabled = !busy && proposal != null
            evaluate.isEnabled = !busy && baseline != null && candidate != null && evidence != null
            approve.isEnabled = !busy && review?.eligible == true
            confirm.isEnabled = !busy && review?.eligible == true && approve.isChecked &&
                confirmation.text.toString().trim() == review?.fingerprint
            dashboard.text = buildString {
                proposal?.let { appendLine(SelfDevelopment.render(it)) }
                appendLine("Baseline debug feedback: ${baseline?.commitSha ?: "not read"}")
                appendLine("Candidate debug feedback: ${candidate?.commitSha ?: "not read"}")
                appendLine("Signed candidate/recovery evidence: ${if (evidence == null) "not read" else "read; evaluate gates explicitly"}")
                review?.let { appendLine(CandidateEvaluation.render(it)) }
                recordedAudit?.let { appendLine(it) }
                appendLine("Publication and installation stay separate approval gates. Historical missing or failed backup checks do not become verified through a successful compile.")
            }
        }
    }
}
