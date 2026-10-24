package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/** Inline, explicit review/approval controls. Attaching this view performs no network operation. */
object PatchWorkbench {
    /** Test-only injection is internal; production callers always use the existing bounded engines. */
    internal class Services(
        val inventory: (String, String, PatchEngine.Target) -> RepositoryAnalysis.Inventory = RepositoryIntelligence::inventory,
        val readSource: (String, RepositoryAnalysis.Inventory, String) -> String = RepositoryIntelligence::readSource,
        val propose: (String, PatchEngine.Request) -> PatchEngine.Proposal = PatchModel::propose,
        val commit: (String, PatchEngine.ApprovedProposal) -> PatchPublisher.CommitResult = PatchPublisher::commit,
        val inspectOutcome: (String, PatchEngine.Proposal) -> PatchPublisher.CommitResult? = PatchPublisher::inspectOutcome,
        val ci: (String, PatchPublisher.CommitResult) -> PatchPublisher.CiStatus = PatchPublisher::ci,
        val feedback: (String, PatchPublisher.CommitResult) -> BuildDiagnostics.Report = { token, result ->
            BuildFeedback.read(token, result.branch, result.commitSha, target = result.target)
        },
        val followUpSource: (String, PatchPublisher.CommitResult, BuildDiagnostics.Report, String) -> PersonalRepair.Review =
            RepositoryIntelligence::personalFollowUp,
        val worker: (() -> Unit) -> Unit = { operation -> thread(name = "DeveloperPatchOperation") { operation() }; Unit }
    )

    interface RepairObserver {
        fun committed(ticket: RepairEngine.Ticket, result: PatchPublisher.CommitResult)
        fun failed(ticket: RepairEngine.Ticket, stage: String)
    }

    /** Transfers reviewed queue scope into a fresh preview; it carries no write approval. */
    class Handle internal constructor(
        private val prepareAction: (String, String, List<String>, String, String) -> Boolean,
        private val repairAction: (RepairEngine.Ticket, RepairObserver) -> Boolean,
        private val invalidateAction: () -> Boolean,
        private val personalAction: (PersonalRepair.Review) -> Boolean = { false }
    ) {
        fun prepare(taskId: String, task: String, paths: List<String>, baseCommitSha: String,
            baseBranch: String = "feature/jarvis-developer"): Boolean =
            prepareAction(taskId, task, paths, baseCommitSha, baseBranch)
        fun prepareRepair(ticket: RepairEngine.Ticket, observer: RepairObserver): Boolean = repairAction(ticket, observer)
        fun invalidate(): Boolean = invalidateAction()
        fun preparePersonal(review: PersonalRepair.Review): Boolean = personalAction(review)
    }

    fun attach(
        activity: Activity,
        root: LinearLayout,
        task: EditText,
        aiKey: EditText,
        provider: Spinner,
        model: EditText,
        githubToken: EditText,
        output: TextView,
        status: TextView,
        canWrite: () -> Boolean
    ): Handle = attach(activity, root, task, aiKey, provider, model, githubToken, output, status, canWrite, Services())

    internal fun attach(
        activity: Activity, root: LinearLayout, task: EditText, aiKey: EditText,
        provider: Spinner, model: EditText, githubToken: EditText, output: TextView,
        status: TextView, canWrite: () -> Boolean, services: Services
    ): Handle {
        val context = root.context
        root.addView(TextView(context).apply {
            text = "Scoped coding engine — preview, review and approve"
        })
        root.addView(TextView(context).apply {
            text = "Enter up to eight exact Developer source paths, one per line. Only this explicit scope is sent to the selected AI provider. Kotlin, Java, XML and existing Developer Gradle dependency changes are supported; protected files are rejected. Preview reads pinned source and calls the AI provider."
        })
        val taskId = EditText(context).apply {
            hint = "Task ID (letters, digits, hyphens or underscores)"
            setSingleLine(true)
            setText("task-" + UUID.randomUUID().toString().replace("-", "").take(16))
        }
        root.addView(taskId)
        val allowedPaths = EditText(context).apply {
            hint = "Exact allowed source paths, one per line (maximum eight)"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(allowedPaths)
        val queuedSource = TextView(context).apply {
            text = "Manual coding task: a new preview reads the current development branch."
        }
        root.addView(queuedSource)
        val manual = Button(context).apply { text = "Use manual task and clear queued source pin" }
        root.addView(manual)
        val preview = Button(context).apply { text = "Generate scoped multi-file diff preview" }
        root.addView(preview)
        val review = TextView(context).apply {
            setTextIsSelectable(true)
            text = "A complete diff, immutable base SHA and proposal fingerprint will appear here for review. GitHub source reads can use public access; creating a task branch requires your in-memory GitHub token."
        }
        root.addView(review)
        val approve = CheckBox(context).apply {
            text = "I reviewed this exact diff and approve creating its isolated task branch"
            isEnabled = false
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        root.addView(approve)
        val commit = Button(context).apply {
            text = "Create task branch and commit approved files (writes)"
            isEnabled = false
        }
        root.addView(commit)
        val inspectOutcome = Button(context).apply {
            text = "Verify interrupted commit outcome (read-only)"
            isEnabled = false
        }
        root.addView(inspectOutcome)
        val ci = Button(context).apply {
            text = "Check CI for this exact task commit (read-only)"
            isEnabled = false
        }
        root.addView(ci)
        val feedback = Button(context).apply {
            text = "Read exact task build diagnostics (GET only)"
            isEnabled = false
        }
        root.addView(feedback)
        root.addView(TextView(context).apply {
            text = "Personal compiler follow-up: choose a file from the failed repair's existing source scope, read it, then stage a new review. Different files or test-only/infrastructure failures need separate source analysis. Logs never authorize a retry."
        })
        val chooseFile = "Select a failed Personal source file"
        val followUpFile = Spinner(context).apply {
            contentDescription = "Failed Personal source file"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(chooseFile))
            isEnabled = false
        }
        root.addView(followUpFile)
        val readFollowUp = Button(context).apply { text = "Read selected failed Personal source (GET only)"; isEnabled = false }
        val stageFollowUp = Button(context).apply { text = "Stage follow-up for a NEW exact diff review"; isEnabled = false }
        root.addView(readFollowUp)
        root.addView(stageFollowUp)
        val openReview = Button(context).apply {
            text = "Open task branch for pull request review"
            isEnabled = false
        }
        root.addView(openReview)

        var busy = false
        var revision = 0L
        var plannedBaseCommitSha: String? = null
        var plannedBaseBranch = "feature/jarvis-developer"
        var plannedTarget = PatchEngine.Target.DEVELOPER
        var activeRepair: Pair<RepairEngine.Ticket, RepairObserver>? = null
        var queuedContextNeedsRefresh = false
        var settingQueuedInputs = false
        var proposal: PatchEngine.Proposal? = null
        var proposalMetadata: Metadata? = null
        var proposalReport: String? = null
        var lastCommit: PatchPublisher.CommitResult? = null
        var interruptedAttempt: PatchEngine.Proposal? = null
        var lastFeedback: Pair<PatchPublisher.CommitResult, BuildDiagnostics.Report>? = null
        var followUpPaths = emptyList<String>()
        var followUpReview: PersonalRepair.Review? = null
        var selectedFollowUp: String? = null
        // An uncertain write outcome is never retried using the same approval in this session.
        val attemptedProposalIds = HashSet<String>()

        fun alive() = !activity.isFinishing && !activity.isDestroyed
        fun selectedFollowUpPath() = followUpFile.selectedItem?.toString()?.takeIf { it in followUpPaths }
        fun metadata(): Metadata {
            val providerName = provider.selectedItem?.toString().orEmpty()
            val rawModel = model.text.toString()
            val selectedModel = rawModel.trim().ifBlank {
                if (providerName == "Gemini") "gemini-2.5-flash" else "gpt-4.1-mini"
            }
            val rawId = taskId.text.toString()
            val rawTask = task.text.toString()
            val rawPaths = allowedPaths.text.toString()
            return Metadata(rawId.trim(), rawTask.trim(), providerName, selectedModel,
                rawPaths.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                fingerprint(listOf(rawId, rawTask, providerName, rawModel, rawPaths,
                    plannedBaseCommitSha.orEmpty(), plannedBaseBranch, plannedTarget.name)), plannedBaseCommitSha, plannedBaseBranch, plannedTarget)
        }
        fun current(): Boolean {
            val candidate = proposal ?: return false
            val frozen = proposalMetadata ?: return false
            return metadata() == frozen && candidate.id !in attemptedProposalIds &&
                proposalReport != null && review.text.toString() == proposalReport
        }
        fun refresh() {
            val exact = current()
            preview.isEnabled = !busy
            manual.isEnabled = !busy && plannedBaseCommitSha != null
            approve.isEnabled = !busy && exact
            commit.isEnabled = !busy && exact && approve.isChecked
            inspectOutcome.isEnabled = !busy && interruptedAttempt != null
            ci.isEnabled = !busy && lastCommit != null
            feedback.isEnabled = !busy && lastCommit != null
            followUpFile.isEnabled = !busy && followUpPaths.isNotEmpty()
            readFollowUp.isEnabled = !busy && selectedFollowUpPath() != null
            stageFollowUp.isEnabled = !busy && followUpReview != null
            openReview.isEnabled = !busy && lastCommit != null
        }
        fun invalidate() {
            revision++
            val hadPreview = proposal != null
            proposal = null
            proposalMetadata = null
            proposalReport = null
            approve.isChecked = false
            if (hadPreview) {
                review.text = "The task, model or explicit source scope changed. Generate and review a fresh diff before approving a write."
                status.text = "Previous diff approval invalidated"
            }
            refresh()
        }
        fun setFeedbackContext(value: Pair<PatchPublisher.CommitResult, BuildDiagnostics.Report>?) {
            followUpReview = null
            selectedFollowUp = null
            lastFeedback = value
            followUpPaths = value?.let { runCatching { PersonalRepair.followUpPaths(it.first, it.second) }.getOrDefault(emptyList()) }
                ?: emptyList()
            followUpFile.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf(chooseFile) + followUpPaths)
            followUpFile.setSelection(0)
            refresh()
        }
        fun closeRepairContext() {
            queuedContextNeedsRefresh = true
            queuedSource.text = "The reserved repair attempt is closed. Prepare a fresh bounded attempt, or explicitly clear the source pin for a manual task.\nPinned source: $plannedBaseBranch @ $plannedBaseCommitSha"
        }
        fun failRepair(repair: Pair<RepairEngine.Ticket, RepairObserver>?, stage: String) {
            if (repair == null || activeRepair !== repair) return
            activeRepair = null
            closeRepairContext()
            try { repair.second.failed(repair.first, stage) } catch (_: Exception) {
                status.text = "Repair audit unavailable; stop and review the session manually."
            }
        }
        fun inputChanges(changesScope: Boolean) = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                if (!settingQueuedInputs) failRepair(activeRepair, "approval")
                if (changesScope && plannedBaseCommitSha != null && !settingQueuedInputs) {
                    queuedContextNeedsRefresh = true
                    queuedSource.text = "Queued task or scope changed. Prepare the selected step again, or explicitly clear the source pin for a manual task.\nPinned source: $plannedBaseCommitSha"
                }
                invalidate()
            }
        }
        listOf(taskId, task, allowedPaths).forEach { it.addTextChangedListener(inputChanges(true)) }
        model.addTextChangedListener(inputChanges(false))
        var selectedProvider = provider.selectedItem?.toString().orEmpty()
        val priorSelectionListener = provider.onItemSelectedListener
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = provider.selectedItem?.toString().orEmpty()
                if (selected != selectedProvider) {
                    selectedProvider = selected
                    failRepair(activeRepair, "approval")
                    invalidate()
                }
                priorSelectionListener?.onItemSelected(parent, view, position, id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedProvider = ""
                failRepair(activeRepair, "approval")
                invalidate()
                priorSelectionListener?.onNothingSelected(parent)
            }
        }
        approve.setOnCheckedChangeListener { _, _ -> refresh() }
        fun clearQueuedSource() {
            failRepair(activeRepair, "approval")
            plannedBaseCommitSha = null
            plannedBaseBranch = "feature/jarvis-developer"
            plannedTarget = PatchEngine.Target.DEVELOPER
            activeRepair = null
            queuedContextNeedsRefresh = false
            queuedSource.text = "Manual coding task: a new preview reads the current development branch."
            invalidate()
        }
        manual.setOnClickListener { if (!busy) clearQueuedSource() }
        followUpFile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val path = selectedFollowUpPath()
                if (path != selectedFollowUp) {
                    selectedFollowUp = path
                    followUpReview = null
                    failRepair(activeRepair, "approval")
                    invalidate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                val changed = selectedFollowUp != null || followUpReview != null
                selectedFollowUp = null; followUpReview = null
                if (changed) { failRepair(activeRepair, "approval"); invalidate() } else refresh()
            }
        }
        openReview.setOnClickListener {
            val result = lastCommit ?: return@setOnClickListener
            runCatching {
                activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(PatchPublisher.reviewUrl(result))))
            }.onFailure { status.text = "Unable to open GitHub review. Check the task branch in your browser; no pull request was created." }
        }

        preview.setOnClickListener {
            val frozen = metadata()
            val key = aiKey.text.toString().trim()
            val token = githubToken.text.toString().trim()
            // Reject protected paths before the first repository or provider request.
            val invalid = when {
                queuedContextNeedsRefresh ->
                    "The queued context changed. Prepare the selected step again, or explicitly switch to a manual task."
                !frozen.taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) ->
                    "Use a task ID of 1–48 letters, digits, hyphens or underscores."
                frozen.task.isBlank() || frozen.task.length > 12_000 ->
                    "Enter a task of 1–12,000 characters."
                frozen.provider !in setOf("Gemini", "OpenAI") -> "Select Gemini or OpenAI."
                key.isBlank() -> "Enter the selected provider's API key. It is used only in memory."
                frozen.allowedPaths.size !in 1..PatchEngine.MAX_FILES ->
                    "Enter 1–8 exact source paths, one per line."
                frozen.allowedPaths.distinct().size != frozen.allowedPaths.size ->
                    "Remove duplicate source paths from the allowlist."
                !frozen.allowedPaths.all { PatchEngine.allowedPath(it, frozen.target) } ->
                    "The allowlist contains a protected or unsupported path for this product. Personal repairs require an explicitly staged source review."
                else -> null
            }
            if (invalid != null) {
                invalidate()
                status.text = invalid
                return@setOnClickListener
            }
            val repair = activeRepair
            try { repair?.first?.claimPreview(System.currentTimeMillis()) } catch (_: Exception) {
                failRepair(repair, "preview")
                invalidate()
                status.text = "This repair preview was already used, expired or stopped. Review the repair session before preparing another attempt."
                return@setOnClickListener
            }
            invalidate()
            val startedRevision = revision
            busy = true
            refresh()
            review.text = "Reading only the explicit source scope at one immutable commit, then requesting a reviewable diff…"
            status.text = "Preparing a scoped diff preview"
            services.worker {
                var stage = "pinned repository inventory"
                try {
                    val inventory = services.inventory(token, frozen.baseBranch, frozen.target)
                    if (frozen.expectedBaseCommitSha != null &&
                        inventory.commitSha != frozen.expectedBaseCommitSha) {
                        stage = "queued source revision change"
                        error("Queued source changed; create a new plan from fresh analysis")
                    }
                    stage = "explicit source retrieval"
                    val indexed = inventory.files.associateBy { it.path }
                    val sources = frozen.allowedPaths.map { path ->
                        val source = indexed[path]
                        if (source == null) PatchEngine.SourceFile(path, null, null)
                        else PatchEngine.SourceFile(path, source.sha,
                            services.readSource(token, inventory, path))
                    }
                    val request = PatchEngine.Request(frozen.taskId, frozen.task, inventory.commitSha,
                        frozen.allowedPaths, sources, frozen.provider, frozen.model, frozen.baseBranch, frozen.target)
                    stage = "source scope validation"
                    PatchEngine.validateRequest(request)
                    stage = "selected AI provider proposal"
                    val generated = services.propose(key, request)
                    stage = "complete diff validation"
                    PatchEngine.revalidate(generated)
                    val report = renderProposal(generated, frozen.fingerprint)
                    activity.runOnUiThread {
                        if (alive()) {
                            if (revision != startedRevision || metadata() != frozen) {
                                review.text = "The preview completed after its task, provider, model or source scope changed. Its result was discarded; generate a new preview."
                                status.text = "Stale preview discarded; no repository write requested"
                                failRepair(repair, "preview")
                            } else if (generated.id in attemptedProposalIds) {
                                review.text = "This exact proposal has already had a commit attempt. Check its task branch before proceeding. Use a new task ID and fresh review for a separate attempt."
                                status.text = "Previously consumed approval cannot be reused"
                                failRepair(repair, "preview")
                            } else {
                                proposal = generated
                                proposalMetadata = frozen
                                proposalReport = report
                                review.text = report
                                output.text = "Scoped preview ready. Task: ${frozen.taskId}\nBase SHA: ${generated.request.baseCommitSha}\nProposal: ${generated.id}\nReview the full diff beside the approval controls."
                                status.text = "Diff ready for explicit review; no repository write requested"
                            }
                        }
                    }
                } catch (_: Exception) {
                    val failedStage = stage
                    activity.runOnUiThread {
                        if (alive()) {
                            if (revision != startedRevision || metadata() != frozen) {
                                review.text = "The task or source scope changed while previewing. Generate a fresh preview."
                                status.text = "Stale preview discarded"
                            } else {
                                review.text = if (failedStage == "queued source revision change")
                                    "The pinned source branch moved after this task was prepared. Create a new plan from fresh analysis, or explicitly switch to a manual task. The old plan's scope and decisions remain saved. No provider call or repository write was requested."
                                else "Preview stopped during $failedStage. Check the explicit file scope, source access, provider key/model and source limits before trying a new preview. No repository write was requested."
                                status.text = "Scoped diff preview unavailable"
                            }
                            val outcome = when (failedStage) {
                                "selected AI provider proposal" -> "provider"
                                "complete diff validation" -> "preview"
                                else -> "source"
                            }
                            failRepair(repair, outcome)
                        }
                    }
                } finally {
                    activity.runOnUiThread {
                        if (alive()) { busy = false; refresh() }
                    }
                }
            }
        }

        commit.setOnClickListener {
            val selected = proposal
            val frozen = proposalMetadata
            if (busy || selected == null || frozen == null || !current() || !approve.isChecked) {
                status.text = "Generate, review and explicitly approve the current exact diff first."
                refresh()
                return@setOnClickListener
            }
            if (!canWrite()) {
                status.text = "Confirm this installed Developer version works before creating a task branch."
                return@setOnClickListener
            }
            val token = githubToken.text.toString().trim()
            if (token.isBlank()) {
                status.text = "Enter an in-memory GitHub token with repository contents-write access."
                return@setOnClickListener
            }
            val approved = try {
                // Approval binds the current checkbox, frozen inputs and complete fingerprint.
                check(metadata() == frozen && review.text.toString() == proposalReport)
                PatchEngine.approve(selected, selected.id).also { approval ->
                    activeRepair?.first?.claimCommit(approval, System.currentTimeMillis())
                }
            } catch (_: Exception) {
                failRepair(activeRepair, "approval")
                invalidate()
                status.text = "The exact diff could not be approved. Generate and review a fresh preview."
                return@setOnClickListener
            }
            val repair = activeRepair
            attemptedProposalIds.add(selected.id)
            interruptedAttempt = selected
            lastCommit = null
            setFeedbackContext(null)
            proposal = null
            proposalMetadata = null
            proposalReport = null
            approve.isChecked = false
            busy = true
            refresh()
            status.text = "Creating only the approved isolated task branch"
            services.worker {
                try {
                    val result = services.commit(token, approved)
                    PatchPublisher.validateOutcome(selected, result)
                    val report = renderCommit(result, null)
                    activity.runOnUiThread {
                        if (alive()) {
                            lastCommit = result
                            interruptedAttempt = null
                            review.text = report
                            output.text = commitAudit(result) + "\nCI has not been checked."
                            status.text = "Task branch committed; CI status is unverified"
                            repair?.let { pair ->
                                activeRepair = null
                                closeRepairContext()
                                try { pair.second.committed(pair.first, result) } catch (_: Exception) {
                                    status.text = "Commit created; repair audit unavailable. Verify the recorded task branch before further work."
                                }
                            }
                        }
                    }
                } catch (failure: Exception) {
                    // Publisher failures have fixed stage/outcome text; never display raw HTTP bodies.
                    val detail = failure.message?.takeIf {
                        it.startsWith("Scoped commit stopped during ") && it.length <= 512 &&
                            !it.contains('\n') && !it.contains('\r')
                    } ?: "The commit operation stopped; its remote outcome could not be verified."
                    activity.runOnUiThread {
                        if (alive()) {
                            review.text = renderProposal(selected, frozen.fingerprint) +
                                "\n\nCOMMIT ATTEMPT STOPPED\n$detail\nApproval was consumed. Use Verify interrupted commit outcome for a read-only check of this exact proposal, or inspect GitHub manually. Rollback is not assumed and no retry will run automatically."
                            output.text = "Task: ${selected.request.taskId}\nBase SHA: ${selected.request.baseCommitSha}\nProposal: ${selected.id}\nExpected branch: ${PatchEngine.branchName(selected)}\n$detail\nApproval consumed; inspect GitHub before a new attempt."
                            status.text = "Commit stopped; review the recorded outcome before proceeding"
                            failRepair(repair, "commit")
                        }
                    }
                } finally {
                    activity.runOnUiThread {
                        if (alive()) { busy = false; refresh() }
                    }
                }
            }
        }

        inspectOutcome.setOnClickListener {
            val selected = interruptedAttempt ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            val token = githubToken.text.toString().trim()
            if (token.isBlank()) {
                status.text = "Enter an in-memory GitHub token to verify the interrupted attempt. No write will be retried."
                return@setOnClickListener
            }
            // Any newer diff loses its approval; the interrupted attempt's approval stays consumed.
            failRepair(activeRepair, "approval")
            invalidate()
            busy = true
            refresh()
            status.text = "Checking the interrupted proposal's branch, parent and complete tree using GET only"
            services.worker {
                try {
                    val result = services.inspectOutcome(token, selected)
                    if (result != null) PatchPublisher.validateOutcome(selected, result)
                    activity.runOnUiThread {
                        if (alive()) {
                            if (result == null) {
                                review.text = "GitHub did not return the expected task branch for this interrupted attempt. It may be absent, removed or inaccessible. This does not undo previous writes or make the old approval reusable. Inspect access and GitHub before preparing a separate task."
                                status.text = "Interrupted branch not observed; approval remains consumed"
                            } else {
                                lastCommit = result
                                interruptedAttempt = null
                                setFeedbackContext(null)
                                review.text = renderCommit(result, null) +
                                    "\n\nInterrupted commit verified against the exact proposal. No write was retried and no approval was restored. A prior repair session is not resumed; read CI and review its result explicitly."
                                output.text = commitAudit(result) + "\nRead-only outcome verified; CI not checked. Approval remains consumed."
                                status.text = "Interrupted commit verified; CI still needs an explicit check"
                            }
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        if (alive()) {
                            review.text = "The interrupted branch could not be verified against its exact approved source, parent, audit identity and complete file tree. Its outcome remains unverified. No write was retried, approval restored or rollback assumed. Inspect GitHub manually before a separate task."
                            status.text = "Interrupted commit outcome remains unverified; no write attempted"
                        }
                    }
                } finally {
                    activity.runOnUiThread { if (alive()) { busy = false; refresh() } }
                }
            }
        }

        feedback.setOnClickListener {
            val result = lastCommit ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            setFeedbackContext(null)
            failRepair(activeRepair, "approval")
            invalidate()
            val token = githubToken.text.toString().trim()
            busy = true
            refresh()
            status.text = "Reading bounded build diagnostics for the exact task commit; no repair is started"
            services.worker {
                try {
                    val report = services.feedback(token, result)
                    BuildDiagnostics.validateReport(report)
                    require(report.target == result.target && report.branch == result.branch && report.commitSha == result.commitSha)
                    val rendered = BuildDiagnostics.render(report)
                    activity.runOnUiThread {
                        if (alive()) {
                            setFeedbackContext(result to report)
                            review.text = commitAudit(result) + "\n\n" + rendered +
                                "\n\nComplete approved diff:\n" + result.diff +
                                "\n\nDiagnostics do not approve changes or start a repair. Generate and review a fresh exact diff before any separate writing attempt."
                            output.text = commitAudit(result) + "\n" + rendered
                            status.text = "Exact task build diagnostics ready; review evidence limits before proceeding"
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        if (alive()) {
                            review.text = renderCommit(result, null) +
                                "\n\nBuild diagnostics could not be verified for this product, branch and exact commit. Check GitHub access and retained logs; no repair, provider call or write was started."
                            status.text = "Exact task build diagnostics remain unverified"
                        }
                    }
                } finally {
                    activity.runOnUiThread { if (alive()) { busy = false; refresh() } }
                }
            }
        }

        readFollowUp.setOnClickListener {
            val sourceContext = lastFeedback ?: return@setOnClickListener
            val path = selectedFollowUpPath() ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            followUpReview = null
            failRepair(activeRepair, "approval")
            invalidate()
            val startedRevision = revision
            val token = githubToken.text.toString().trim()
            busy = true; refresh()
            status.text = "Re-reading the failed Personal source at its pinned commit; no provider call or approval"
            services.worker {
                try {
                    val sourceReview = services.followUpSource(token, sourceContext.first, sourceContext.second, path)
                    PersonalRepair.validateFollowUp(sourceReview, sourceContext.first, sourceContext.second, path)
                    activity.runOnUiThread {
                        if (alive()) {
                            if (revision != startedRevision || lastFeedback !== sourceContext || selectedFollowUpPath() != path) {
                                status.text = "Stale follow-up source review discarded. Select and read the current failed source again."
                            } else {
                                followUpReview = sourceReview
                                output.text = PersonalRepair.render(sourceReview)
                                review.text = PersonalRepair.render(sourceReview)
                                status.text = "Follow-up source verified. Stage explicitly, then generate and approve a new complete diff."
                            }
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        if (alive()) status.text = "Follow-up source could not be verified. Start a new source analysis if the branch moved, or check source access. Nothing was staged or approved."
                    }
                } finally {
                    activity.runOnUiThread { if (alive()) { busy = false; refresh() } }
                }
            }
        }

        ci.setOnClickListener {
            val result = lastCommit ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            setFeedbackContext(null)
            val token = githubToken.text.toString().trim()
            if (token.isBlank()) {
                status.text = "Enter an in-memory GitHub token to check this exact task commit."
                return@setOnClickListener
            }
            // Checking a build replaces any later preview, so its old approval is invalidated.
            failRepair(activeRepair, "approval")
            invalidate()
            busy = true
            refresh()
            review.text = renderCommit(result, null) + "\n\nChecking the exact commit's current CI status…"
            status.text = "Checking CI for ${result.commitSha}"
            services.worker {
                try {
                    val build = services.ci(token, result)
                    activity.runOnUiThread {
                        if (alive()) {
                            review.text = renderCommit(result, build)
                            output.text = commitAudit(result) + "\n" + ciSummary(build)
                            status.text = when (build.state) {
                                PatchPublisher.CiState.PASSED -> "Exact task commit: debug CI passed"
                                PatchPublisher.CiState.FAILED -> "Exact task commit: CI failed or did not succeed"
                                PatchPublisher.CiState.MISSING -> "No matching CI run found for the exact task commit"
                                PatchPublisher.CiState.QUEUED -> "Exact task commit: CI queued"
                                PatchPublisher.CiState.RUNNING -> "Exact task commit: CI running"
                            }
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        if (alive()) {
                            review.text = renderCommit(result, null) +
                                "\n\nCI status could not be verified. Check GitHub access and retry the read-only status check."
                            output.text = commitAudit(result) +
                                "\nCI status could not be verified. Check GitHub access and retry the read-only status check."
                            status.text = "CI status remains unverified for ${result.commitSha}"
                        }
                    }
                } finally {
                    activity.runOnUiThread {
                        if (alive()) { busy = false; refresh() }
                    }
                }
            }
        }
        fun prepareInputs(id: String, description: String, paths: List<String>, base: String,
            sourceBranch: String, target: PatchEngine.Target = PatchEngine.Target.DEVELOPER): Boolean {
            val safe = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) &&
                description.isNotBlank() && description.length <= 12_000 &&
                paths.size in 1..PatchEngine.MAX_FILES && paths.distinct().size == paths.size &&
                paths.all { PatchEngine.allowedPath(it, target) } && base.matches(Regex("[a-f0-9]{40}")) &&
                PatchEngine.validSourceBranch(sourceBranch, target) &&
                (listOf(id, description, sourceBranch) + paths).all {
                    RepositoryRedaction.line(it) == it && !it.contains("PRIVATE KEY-----") &&
                        it.none { char -> char == '\u0000' || char == '\u007f' ||
                            (char < ' ' && char !in "\n\r\t") }
                }
            if (busy || !safe) return false
            failRepair(activeRepair, "approval")
            invalidate()
            settingQueuedInputs = true
            try {
                taskId.setText(id)
                task.setText(description)
                allowedPaths.setText(paths.joinToString("\n"))
            } finally { settingQueuedInputs = false }
            // Pin after synchronous text watchers run; every preview checks this exact branch/SHA.
            plannedBaseCommitSha = base
            plannedBaseBranch = sourceBranch
            plannedTarget = target
            queuedContextNeedsRefresh = false
            queuedSource.text = "Queued ${target.name.lowercase()} task source: $sourceBranch @ $base\nA moved source branch requires fresh analysis and diff review. No approval was restored."
            review.text = "Queued scope loaded. Generate a fresh diff, review every change and approve it explicitly."
            status.text = "Queued step prepared for a new diff; no provider call or Git write requested"
            refresh()
            return true
        }
        stageFollowUp.setOnClickListener {
            val selected = followUpReview ?: return@setOnClickListener
            val sourceContext = lastFeedback ?: return@setOnClickListener
            val path = selectedFollowUpPath() ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            try {
                PersonalRepair.validateFollowUp(selected, sourceContext.first, sourceContext.second, path)
                require(prepareInputs(selected.taskId, selected.task, selected.scope, selected.sourceSha,
                    selected.branch, PatchEngine.Target.PERSONAL))
                followUpReview = null
                status.text = "Follow-up source staged for a NEW diff. The previous approval remains consumed; generate, review and explicitly approve the new proposal."
            } catch (_: Exception) {
                followUpReview = null
                status.text = "Follow-up staging refused. Read current failed source again; no provider call, retry or approval occurred."
            }
            refresh()
        }
        refresh()
        return Handle(prepareAction = { id, description, paths, base, branch -> prepareInputs(id, description, paths, base, branch) }, repairAction = { ticket, observer ->
            if (prepareInputs(ticket.taskId, ticket.task, ticket.scope, ticket.baseCommitSha, ticket.baseBranch)) {
                activeRepair = ticket to observer
                true
            } else false
        }, invalidateAction = {
            // Callers own repair cancellation. Invalidating a preview alone cannot consume a ticket.
            if (busy) false else {
                invalidate()
                if (plannedBaseCommitSha != null) {
                    queuedContextNeedsRefresh = true
                    queuedSource.text = "Queued context changed. Prepare the selected step again, or explicitly clear the source pin for a manual task.\nPinned source: $plannedBaseBranch @ $plannedBaseCommitSha"
                }
                true
            }
        }, personalAction = { selected ->
            try {
                PersonalRepair.validate(selected)
                prepareInputs(selected.taskId, selected.task, selected.scope, selected.sourceSha, selected.branch, PatchEngine.Target.PERSONAL)
            } catch (_: Exception) { false }
        })
    }

    private data class Metadata(
        val taskId: String,
        val task: String,
        val provider: String,
        val model: String,
        val allowedPaths: List<String>,
        val fingerprint: String,
        val expectedBaseCommitSha: String?,
        val baseBranch: String,
        val target: PatchEngine.Target
    )

    private fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun renderProposal(proposal: PatchEngine.Proposal, inputFingerprint: String): String = buildString {
        val request = proposal.request
        append("Task: ").append(request.taskId).append('\n')
        append("Provider: ").append(request.provider).append(" / ").append(request.model).append('\n')
        append("Source branch: ").append(request.baseBranch).append('\n')
        append("Product: ").append(request.target.name).append('\n')
        append("Base commit SHA: ").append(request.baseCommitSha).append('\n')
        append("Proposal SHA-256: ").append(proposal.id).append('\n')
        append("Input SHA-256: ").append(inputFingerprint).append('\n')
        append("Proposed task branch: ").append(PatchEngine.branchName(proposal)).append('\n')
        append("Explicit allowed paths:\n").append(request.allowedPaths.joinToString("\n")).append('\n')
        append("Changed files:\n").append(TaskFileSummary.describe(proposal.edits.map { it.path })).append('\n')
        append("Task request:\n").append(request.task).append('\n')
        append("Proposal summary:\n").append(proposal.summary).append('\n')
        append("\nComplete reviewable diff:\n").append(proposal.diff)
    }

    private fun commitAudit(result: PatchPublisher.CommitResult): String = buildString {
        append("Task: ").append(result.taskId).append('\n')
        append("Provider: ").append(result.provider).append(" / ").append(result.model).append('\n')
        append("Proposal SHA-256: ").append(result.proposalId).append('\n')
        append("Source branch: ").append(result.baseBranch).append('\n')
        append("Product: ").append(result.target.name).append('\n')
        append("Base commit SHA: ").append(result.baseCommitSha).append('\n')
        append("Task commit SHA: ").append(result.commitSha).append('\n')
        append("Task branch: ").append(result.branch).append('\n')
        append("Changed files:\n").append(TaskFileSummary.describe(result.paths))
    }

    private fun renderCommit(result: PatchPublisher.CommitResult, build: PatchPublisher.CiStatus?): String =
        commitAudit(result) + "\n" + (build?.let(::ciSummary) ?: "CI status: not checked") +
            "\n\nComplete approved diff:\n" + result.diff

    private fun ciSummary(build: PatchPublisher.CiStatus): String = buildString {
        append(when (build.state) {
            PatchPublisher.CiState.MISSING -> "CI status: no matching workflow run for this exact task commit."
            PatchPublisher.CiState.QUEUED -> "CI status: queued for this exact task commit."
            PatchPublisher.CiState.RUNNING -> "CI status: running for this exact task commit."
            PatchPublisher.CiState.PASSED -> "CI status: debug tests/build passed for this exact task commit. Signed-candidate verification and release promotion remain separate."
            PatchPublisher.CiState.FAILED -> "CI status: exact task commit did not pass (${build.conclusion ?: "unknown"})."
        })
        build.runUrl?.let { append("\nBuild URL: ").append(it) }
        append("\nIf no CI run exists, open a pull request for this task branch; no workflow is dispatched here.")
        append("\nMerge, publication and installation require separate approval.")
    }
}
