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
        val inventory: (String, String, PatchEngine.Target, PatchPreviewCancellation) -> RepositoryAnalysis.Inventory = RepositoryIntelligence::inventory,
        val readSource: (String, RepositoryAnalysis.Inventory, String, PatchPreviewCancellation) -> String = RepositoryIntelligence::readSource,
        val propose: (String, PatchEngine.Request, PatchPreviewCancellation) -> PatchEngine.Proposal = PatchModel::propose,
        val commit: (String, PatchEngine.ApprovedProposal) -> PatchPublisher.CommitResult = PatchPublisher::commit,
        val inspectOutcome: (String, PatchEngine.Proposal) -> PatchPublisher.CommitResult? = PatchPublisher::inspectOutcome,
        val ci: (String, PatchPublisher.CommitResult) -> PatchPublisher.CiStatus = PatchPublisher::ci,
        val feedback: (String, PatchPublisher.CommitResult, PatchPreviewCancellation) -> BuildDiagnostics.Report = { token, result, cancellation ->
            BuildFeedback.read(token, result.branch, result.commitSha, result.target, cancellation)
        },
        val followUpSource: (String, PatchPublisher.CommitResult, BuildDiagnostics.Report, String, PatchPreviewCancellation) -> PersonalRepair.Review =
            RepositoryIntelligence::personalFollowUp,
        val history: RepairHistoryWorkbench.Services = RepairHistoryWorkbench.Services(),
        val inbox: (Activity) -> DevelopmentJobInbox = { DevelopmentJobInbox(java.io.File(it.noBackupFilesDir.canonicalFile, "development-inbox")) },
        val engineeringRecovery: (Activity) -> Unit = DevelopmentEngineeringScheduler::recover,
        val worker: (() -> Unit) -> Unit = { operation -> thread(name = "DeveloperPatchOperation") { operation() }; Unit },
        val chatStatus: (String) -> Unit = { }
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
        private val personalAction: (PersonalRepair.Review) -> Boolean = { false },
        private val cancelPreviewAction: () -> Unit = {},
        private val disposeAction: () -> Unit = cancelPreviewAction,
        private val engineeringAction: (DevelopmentEngineering.Draft) -> Boolean = { false },
        private val guidedAction: (String, String, List<String>, String, PatchEngine.Target) -> Boolean = { _, _, _, _, _ -> false },
        private val importAction: (String, String, List<PatchEngine.Edit>, String) -> Boolean = { _, _, _, _ -> false },
        private val developerRepairAction: () -> Boolean = { false },
        private val exactDeveloperCiAction: () -> Boolean = { false },
        private val saveDeveloperBookmarkAction: () -> Boolean = { false },
        private val readDeveloperBookmarkAction: () -> Boolean = { false },
        private val approveDeveloperDiffAction: (String) -> Boolean = { false },
        private val showDeveloperDiffAction: () -> String? = { null },
        private val continueDeveloperPlanAction: (String, String, List<String>, String) -> Boolean = { _, _, _, _ -> false }
    ) {
        fun prepare(taskId: String, task: String, paths: List<String>, baseCommitSha: String,
            baseBranch: String = "feature/jarvis-developer"): Boolean =
            prepareAction(taskId, task, paths, baseCommitSha, baseBranch)
        fun prepareRepair(ticket: RepairEngine.Ticket, observer: RepairObserver): Boolean = repairAction(ticket, observer)
        /** User-initiated chat/inline repair handoff: preview only, never approval or write. */
        fun prepareDeveloperRepair(): Boolean = developerRepairAction()
        /** User-initiated exact task CI refresh: GET-only, no provider or repository writes. */
        fun refreshDeveloperCi(): Boolean = exactDeveloperCiAction()
        /** Saves only commit identity, never source, credentials or approval. */
        fun saveDeveloperBookmark(): Boolean = saveDeveloperBookmarkAction()
        /** Explicit bounded GET-only read after Activity recreation. Never revives a writer. */
        fun readSavedDeveloperBuild(): Boolean = readDeveloperBookmarkAction()
        /** Explicit code-specific user authorization, not implicit chat approval. */
        fun approveDeveloperDiff(prefix: String): Boolean = approveDeveloperDiffAction(prefix)
        /** Returns a currently verified, complete Developer diff; no network call or write. */
        fun showDeveloperDiff(): String? = showDeveloperDiffAction()
        /** Owner-requested next plan step: source-verified preview, never an automatic write. */
        fun continueDeveloperPlan(id: String, task: String, paths: List<String>, base: String): Boolean =
            continueDeveloperPlanAction(id, task, paths, base)
        fun invalidate(): Boolean = invalidateAction()
        fun preparePersonal(review: PersonalRepair.Review): Boolean = personalAction(review)
        internal fun importChatProposal(task: String, summary: String, edits: List<PatchEngine.Edit>, base: String) =
            importAction(task, summary, edits, base)
        fun cancelPreview() = cancelPreviewAction()
        fun dispose() = disposeAction()
        internal fun prepareEngineering(draft: DevelopmentEngineering.Draft) = engineeringAction(draft)
        internal fun prepareGuided(id: String, task: String, paths: List<String>, base: String, target: PatchEngine.Target) =
            guidedAction(id, task, paths, base, target)
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

    /** Reports audited development outcomes to the primary conversation, not to a second coding engine. */
    fun attach(activity: Activity, root: LinearLayout, task: EditText, aiKey: EditText,
        provider: Spinner, model: EditText, githubToken: EditText, output: TextView,
        status: TextView, canWrite: () -> Boolean, onChatStatus: (String) -> Unit): Handle =
        attach(activity, root, task, aiKey, provider, model, githubToken, output, status,
            canWrite, Services(chatStatus = onChatStatus))

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
        val cancelPreview = Button(context).apply {
            text = "Cancel source / proposal preview"
            isEnabled = false
        }
        root.addView(cancelPreview)
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
        val prepareDeveloperRepair = Button(context).apply {
            text = "Prepare Developer repair from verified failure"
            isEnabled = false
        }
        root.addView(prepareDeveloperRepair)
        val cancelRead = Button(context).apply { text = "Cancel task diagnostics / source read"; isEnabled = false }
        root.addView(cancelRead)
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
        var disposed = false
        var activePreview: PatchPreviewCancellation? = null
        var activeRead: PatchPreviewCancellation? = null
        var revision = 0L
        var plannedBaseCommitSha: String? = null
        var plannedBaseBranch = "feature/jarvis-developer"
        var plannedTarget = PatchEngine.Target.DEVELOPER
        var activeRepair: Pair<RepairEngine.Ticket, RepairObserver>? = null
        var activeEngineering: DevelopmentEngineering.Draft? = null
        var interruptedEngineering: DevelopmentEngineering.Write? = null
        var queuedContextNeedsRefresh = false
        var settingQueuedInputs = false
        var chatImport: Pair<String, List<PatchEngine.Edit>>? = null
        var proposal: PatchEngine.Proposal? = null
        var proposalMetadata: Metadata? = null
        var proposalReport: String? = null
        var chatReviewedProposalId: String? = null
        var lastCommit: PatchPublisher.CommitResult? = null
        var interruptedAttempt: PatchEngine.Proposal? = null
        var lastFeedback: Pair<PatchPublisher.CommitResult, BuildDiagnostics.Report>? = null
        var developerRepairCandidate: DeveloperFailureRepairDraft.Candidate? = null
        var followUpPaths = emptyList<String>()
        var followUpReview: PersonalRepair.Review? = null
        var selectedFollowUp: String? = null
        // An uncertain write outcome is never retried using the same approval in this session.
        val attemptedProposalIds = HashSet<String>()

        fun alive() = !disposed && !activity.isFinishing && !activity.isDestroyed
        fun selectedFollowUpPath() = followUpFile.selectedItem?.toString()?.takeIf { it in followUpPaths }
        fun metadata(): Metadata {
            val providerName = provider.selectedItem?.toString().orEmpty()
            val rawModel = model.text.toString()
            val selectedModel = rawModel.trim().ifBlank {
                if (providerName == "Gemini") CodingAssistantProvider.DEFAULT_GEMINI_MODEL else "gpt-4.1-mini"
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
            cancelPreview.isEnabled = busy && activePreview?.isCancelled == false
            cancelRead.isEnabled = busy && activeRead?.isCancelled == false
            manual.isEnabled = !busy && plannedBaseCommitSha != null
            approve.isEnabled = !busy && exact
            commit.isEnabled = !busy && exact && approve.isChecked
            inspectOutcome.isEnabled = !busy && interruptedAttempt != null
            ci.isEnabled = !busy && lastCommit != null
            feedback.isEnabled = !busy && lastCommit != null
            prepareDeveloperRepair.isEnabled = !busy && developerRepairCandidate != null
            followUpFile.isEnabled = !busy && followUpPaths.isNotEmpty()
            readFollowUp.isEnabled = !busy && selectedFollowUpPath() != null
            stageFollowUp.isEnabled = !busy && followUpReview != null
            openReview.isEnabled = !busy && lastCommit != null
        }
        fun cancelledRead(finished: Boolean) {
            followUpReview = null
            val message = if (finished)
                "Task diagnostic read cancelled. Its result was discarded. Choose an explicit fresh read; no source was staged, approval restored or write retried."
            else "Task diagnostic cancellation requested. Waiting for the bounded reader to close; its result will be discarded. No source was staged, approval restored or write retried."
            review.text = message
            output.text = message
            status.text = message
        }
        fun invalidate() {
            activePreview?.cancel()
            activeRead?.let { it.cancel(); if (alive()) cancelledRead(false) }
            revision++
            val hadPreview = proposal != null
            proposal = null
            proposalMetadata = null
            proposalReport = null
            chatReviewedProposalId = null
            approve.isChecked = false
            if (hadPreview) {
                review.text = "The task, model or explicit source scope changed. Generate and review a fresh diff before approving a write."
                status.text = "Previous diff approval invalidated"
            }
            refresh()
        }
        fun setFeedbackContext(value: Pair<PatchPublisher.CommitResult, BuildDiagnostics.Report>?) {
            developerRepairCandidate = null
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
        fun stopPreview() {
            val pending = activePreview ?: return
            pending.cancel()
            failRepair(activeRepair, "cancelled")
            invalidate()
            review.text = "Preview cancellation requested. Active connections are closing and late results will be discarded. A provider request may already have reached the selected provider. No repository write was requested."
            status.text = "Cancelling preview; waiting for its bounded request to close"
            refresh()
        }
        cancelPreview.setOnClickListener { stopPreview() }
        fun stopRead() {
            val pending = activeRead ?: return
            pending.cancel()
            if (alive()) { cancelledRead(false); refresh() }
        }
        cancelRead.setOnClickListener { stopRead() }
        val readCredentialChanges = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { stopRead() }
        }
        githubToken.addTextChangedListener(readCredentialChanges)
        fun inputChanges(changesScope: Boolean) = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                if (changesScope && !settingQueuedInputs) {
                    activeEngineering = null
                    chatImport = null // Imported replacement files belong to the original task and scope only.
                }
                if (!settingQueuedInputs) failRepair(activeRepair, "approval")
                if (changesScope && plannedBaseCommitSha != null && !settingQueuedInputs) {
                    queuedContextNeedsRefresh = true
                    queuedSource.text = "Queued task or scope changed. Prepare the selected step again, or explicitly clear the source pin for a manual task.\nPinned source: $plannedBaseCommitSha"
                }
                invalidate()
            }
        }
        listOf(taskId, task, allowedPaths).forEach { it.addTextChangedListener(inputChanges(true)) }
        model.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                chatImport = null
                activeEngineering = null
            }
        })
        model.addTextChangedListener(inputChanges(false))
        var selectedProvider = provider.selectedItem?.toString().orEmpty()
        val priorSelectionListener = provider.onItemSelectedListener
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = provider.selectedItem?.toString().orEmpty()
                if (selected != selectedProvider) {
                    selectedProvider = selected
                    chatImport = null
                    activeEngineering = null
                    failRepair(activeRepair, "approval")
                    invalidate()
                }
                priorSelectionListener?.onItemSelected(parent, view, position, id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedProvider = ""
                chatImport = null
                activeEngineering = null
                failRepair(activeRepair, "approval")
                invalidate()
                priorSelectionListener?.onNothingSelected(parent)
            }
        }
        approve.setOnCheckedChangeListener { _, _ -> refresh() }
        fun clearQueuedSource() {
            activeEngineering = null
            failRepair(activeRepair, "approval")
            plannedBaseCommitSha = null
            plannedBaseBranch = "feature/jarvis-developer"
            plannedTarget = PatchEngine.Target.DEVELOPER
            activeRepair = null
            queuedContextNeedsRefresh = false
            queuedSource.text = "Manual coding task: a new preview reads the current development branch."
            invalidate()
        }
        manual.setOnClickListener { if (!busy) { chatImport = null; clearQueuedSource() } }
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
            if (busy) return@setOnClickListener
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
                    "The allowlist contains a protected or unsupported path for this product. Use Home analysis to select ordinary logic, or open repository issues for a supervised pull request covering protected controllers. No source request or approval was made."
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
            val cancellation = PatchPreviewCancellation()
            activePreview = cancellation
            busy = true
            refresh()
            review.text = "Reading only the explicit source scope at one immutable commit, then requesting a reviewable diff…"
            status.text = "Preparing a scoped diff preview"
            services.worker {
                var stage = "pinned repository inventory"
                try {
                    cancellation.check()
                    val inventory = services.inventory(token, frozen.baseBranch, frozen.target, cancellation)
                    cancellation.check()
                    if (frozen.expectedBaseCommitSha != null &&
                        inventory.commitSha != frozen.expectedBaseCommitSha) {
                        stage = "queued source revision change"
                        error("Queued source changed; create a new plan from fresh analysis")
                    }
                    stage = "explicit source retrieval"
                    val indexed = inventory.files.associateBy { it.path }
                    val sources = frozen.allowedPaths.map { path ->
                        cancellation.check()
                        val source = indexed[path]
                        if (source == null) PatchEngine.SourceFile(path, null, null)
                        else PatchEngine.SourceFile(path, source.sha,
                            services.readSource(token, inventory, path, cancellation)).also { cancellation.check() }
                    }
                    val request = PatchEngine.Request(frozen.taskId, frozen.task, inventory.commitSha,
                        frozen.allowedPaths, sources, frozen.provider, frozen.model, frozen.baseBranch, frozen.target)
                    stage = "source scope validation"
                    PatchEngine.validateRequest(request)
                    stage = "selected AI provider proposal"
                    cancellation.check()
                    val imported = chatImport
                    val generated = if (imported != null) {
                        require(imported.second.map { it.path }.toSet() == request.allowedPaths.toSet())
                        val payload = org.json.JSONObject().put("summary", imported.first).put("edits",
                            org.json.JSONArray(imported.second.map { edit ->
                                org.json.JSONObject().put("path", edit.path).put("content", edit.content)
                            }))
                        PatchEngine.parse(request, payload.toString())
                    } else services.propose(key, request, cancellation)
                    cancellation.check()
                    stage = "complete diff validation"
                    PatchEngine.revalidate(generated)
                    val report = renderProposal(generated, frozen.fingerprint)
                    activity.runOnUiThread {
                        if (alive()) {
                            if (cancellation.isCancelled) {
                                review.text = "Preview cancelled. Its result was discarded; generate and review a new diff before any write."
                                status.text = "Preview cancelled; no repository write requested"
                                failRepair(repair, "cancelled")
                            } else if (revision != startedRevision || metadata() != frozen) {
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
                                runCatching { services.chatStatus(DeveloperChatBuildReceipt.preview(
                                    generated.request.taskId, generated.edits.size, generated.id)) }
                            }
                        }
                    }
                } catch (_: Exception) {
                    val failedStage = stage
                    activity.runOnUiThread {
                        if (alive()) {
                            if (cancellation.isCancelled) {
                                review.text = "Preview cancelled. Its result was discarded; generate and review a new diff before any write. A provider request already sent cannot be recalled."
                                status.text = "Preview cancelled; no repository write requested"
                            } else if (revision != startedRevision || metadata() != frozen) {
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
                            failRepair(repair, if (cancellation.isCancelled) "cancelled" else outcome)
                        }
                    }
                } finally {
                    activity.runOnUiThread {
                        if (alive() && activePreview === cancellation) {
                            activePreview = null
                            busy = false
                            refresh()
                        }
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
            var engineeringWrite: DevelopmentEngineering.Write? = null
            val approved = try {
                // Approval binds the current checkbox, frozen inputs and complete fingerprint.
                check(metadata() == frozen && review.text.toString() == proposalReport)
                PatchEngine.approve(selected, selected.id).also { approval ->
                    activeRepair?.first?.claimCommit(approval, System.currentTimeMillis())
                    activeEngineering?.let { draft -> engineeringWrite = services.inbox(activity).claimEngineering(draft, approval,
                        com.jarvis.integration.DevelopmentBridgeContract.personalPackage(activity.packageName))
                        DevelopmentEngineeringCredentials.remember(draft.job, token)
                    }
                }
            } catch (_: Exception) {
                failRepair(activeRepair, "approval")
                invalidate()
                status.text = "The exact diff could not be approved. Generate and review a fresh preview."
                return@setOnClickListener
            }
            val repair = activeRepair
            activeEngineering = null
            interruptedEngineering = engineeringWrite
            attemptedProposalIds.add(selected.id)
            interruptedAttempt = selected
            lastCommit = null
            setFeedbackContext(null)
            proposal = null
            proposalMetadata = null
            proposalReport = null
            chatReviewedProposalId = null
            approve.isChecked = false
            busy = true
            refresh()
            status.text = "Creating only the approved isolated task branch"
            services.worker {
                try {
                    val result = services.commit(token, approved)
                    PatchPublisher.validateOutcome(selected, result)
                    // Persist real outcomes even when Back/recreation has destroyed the UI.
                    engineeringWrite?.let { write ->
                        check(services.inbox(activity).finishEngineering(write, result))
                        services.engineeringRecovery(activity)
                    }
                    // After an approved, validated commit perform one exact-source CI read.
                    // If that run failed, perform one bounded GET-only build-log read and
                    // display its sanitized diagnostics in the same chat/review response.
                    // Neither read can retry a write, start a repair, or restore approval.
                    val checked = if (alive()) runCatching { services.ci(token, result) }.getOrNull() else null
                    val ciText = checked?.let(::ciSummary) ?:
                        "CI status could not be verified for this exact commit. Use Check CI to retry the read-only lookup."
                    if (checked?.state == PatchPublisher.CiState.FAILED) {
                        activity.runOnUiThread {
                            if (alive()) {
                                status.text = "Task branch committed; checking bounded failure evidence for the exact CI run."
                                output.text = commitAudit(result) + "\n" + ciText + "\nReading failure details; no repair or additional write."
                            }
                        }
                    }
                    val failureReport = if (checked?.state == PatchPublisher.CiState.FAILED && alive()) {
                        runCatching {
                            val cancellation = PatchPreviewCancellation()
                            val evidence = services.feedback(token, result, cancellation)
                            BuildDiagnostics.validateReport(evidence)
                            require(evidence.target == result.target && evidence.branch == result.branch &&
                                evidence.commitSha == result.commitSha && evidence.status == "completed" &&
                                evidence.conclusion == checked.conclusion && evidence.conclusion != "success")
                            // A later workflow attempt must not be mistaken for the CI run just checked.
                            require(checked.runUrl != null &&
                                evidence.runUrl.startsWith(checked.runUrl + "/attempts/"))
                            evidence
                        }.getOrNull()
                    } else null
                    val repairDraft = if (checked != null && failureReport != null)
                        runCatching { DeveloperFailureRepairDraft.from(result, checked, failureReport) }.getOrNull()
                    else null
                    val repairHint = if (checked?.state != PatchPublisher.CiState.FAILED) "" else
                        if (repairDraft != null)
                            "\n\nCompiler-only follow-up available. Select Prepare Developer repair from verified failure. " +
                                "A fresh pinned-source diff will be generated for your review; no prior approval is reused."
                        else "\n\nNo source-scoped compiler repair could be safely inferred. " +
                            "Review the verified tests/infrastructure diagnostics before starting another task."
                    val failureText = if (checked?.state != PatchPublisher.CiState.FAILED) "" else
                        if (failureReport != null)
                            "\n\nExact-run failure diagnostics (untrusted build evidence):\n" +
                                BuildDiagnostics.render(failureReport) +
                                "\nNo repair, provider request, source write or retry was started."
                        else "\n\nFailed build details could not be verified for this CI run. " +
                            "Use Read exact task build to retry the bounded diagnostic read. " +
                            "No repair or source write was started."
                    val report = renderCommit(result, checked) +
                        (if (checked == null) "\n\n$ciText" else "") + failureText + repairHint
                    activity.runOnUiThread {
                        if (alive()) {
                            lastCommit = result
                            developerRepairCandidate = repairDraft
                            interruptedAttempt = null
                            interruptedEngineering = null
                            review.text = report
                            output.text = commitAudit(result) + "\n" + ciText + failureText + repairHint
                            runCatching { services.chatStatus(DeveloperChatBuildReceipt.commit(
                                result, checked, failureReport, repairDraft != null)) }
                            status.text = when (checked?.state) {
                                PatchPublisher.CiState.PASSED -> "Task branch committed; exact-source debug CI passed"
                                PatchPublisher.CiState.FAILED -> if (failureReport != null)
                                    "Task branch committed; exact-run failure diagnostics ready for review."
                                else "Task branch committed; CI failed, but detailed evidence remains unverified."
                                PatchPublisher.CiState.RUNNING -> "Task branch committed; CI running. Use Check CI to refresh."
                                PatchPublisher.CiState.QUEUED -> "Task branch committed; CI queued. Use Check CI to refresh."
                                PatchPublisher.CiState.MISSING -> "Task branch committed; no matching CI run yet. No tests are assumed."
                                null -> "Task branch committed; CI lookup unavailable. Commit recorded; tests unverified."
                            }
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
                    engineeringWrite?.let { write ->
                        runCatching { services.inbox(activity).uncertainEngineering(write) }
                        runCatching { services.engineeringRecovery(activity) }
                    }
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
                            runCatching { services.chatStatus(DeveloperChatBuildReceipt.uncertain()) }
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
                    if (result != null) interruptedEngineering?.let { write ->
                        check(services.inbox(activity).finishEngineering(write, result))
                        services.engineeringRecovery(activity)
                    }
                    activity.runOnUiThread {
                        if (alive()) {
                            if (result == null) {
                                review.text = "GitHub did not return the expected task branch for this interrupted attempt. It may be absent, removed or inaccessible. This does not undo previous writes or make the old approval reusable. Inspect access and GitHub before preparing a separate task."
                                status.text = "Interrupted branch not observed; approval remains consumed"
                            } else {
                                lastCommit = result
                                interruptedAttempt = null
                                interruptedEngineering = null
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
            if (busy || !alive()) return@setOnClickListener
            setFeedbackContext(null)
            failRepair(activeRepair, "approval")
            invalidate()
            val startedRevision = revision
            val cancellation = PatchPreviewCancellation()
            activeRead = cancellation
            val token = githubToken.text.toString().trim()
            busy = true
            refresh()
            status.text = "Reading bounded build diagnostics for the exact task commit; no repair is started"
            review.text = renderCommit(result, null) + "\n\nBuild diagnostics are being read; previous feedback is no longer current."
            output.text = commitAudit(result) + "\nBuild diagnostics are being read; previous feedback is no longer current."
            services.worker {
                try {
                    cancellation.check()
                    val report = services.feedback(token, result, cancellation)
                    cancellation.check()
                    BuildDiagnostics.validateReport(report)
                    require(report.target == result.target && report.branch == result.branch && report.commitSha == result.commitSha)
                    val rendered = BuildDiagnostics.render(report)
                    cancellation.check()
                    activity.runOnUiThread {
                        if (alive()) {
                            if (cancellation.isCancelled || revision != startedRevision) cancelledRead(true)
                            else {
                                // Publishing the verified result can trigger selector callbacks, not another read.
                                activeRead = null
                                setFeedbackContext(result to report)
                                review.text = commitAudit(result) + "\n\n" + rendered +
                                    "\n\nComplete approved diff:\n" + result.diff +
                                    "\n\nDiagnostics do not approve changes or start a repair. Generate and review a fresh exact diff before any separate writing attempt."
                                output.text = commitAudit(result) + "\n" + rendered
                                status.text = "Exact task build diagnostics ready; review evidence limits before proceeding"
                            }
                        }
                    }
                } catch (failure: Exception) {
                    activity.runOnUiThread {
                        if (alive()) {
                            if (cancellation.isCancelled || revision != startedRevision) cancelledRead(true)
                            else {
                                val detail = BuildFeedback.explainFailure(failure) ?: "Build diagnostics could not be verified for this product, branch and exact commit. Check GitHub access and retained logs; no repair, provider call or write was started."
                                review.text = renderCommit(result, null) + "\n\n" + detail
                                output.text = commitAudit(result) + "\n" + detail
                                status.text = "Exact task build diagnostics remain unverified"
                            }
                        }
                    }
                } finally {
                    activity.runOnUiThread { if (alive()) { activeRead = null; busy = false; refresh() } }
                }
            }
        }

        readFollowUp.setOnClickListener {
            val sourceContext = lastFeedback ?: return@setOnClickListener
            val path = selectedFollowUpPath() ?: return@setOnClickListener
            if (busy || !alive()) return@setOnClickListener
            followUpReview = null
            failRepair(activeRepair, "approval")
            invalidate()
            val startedRevision = revision
            val cancellation = PatchPreviewCancellation()
            activeRead = cancellation
            val token = githubToken.text.toString().trim()
            busy = true; refresh()
            status.text = "Re-reading the failed Personal source at its pinned commit; no provider call or approval"
            review.text = "Reading selected failed source; previous source review is no longer current."
            output.text = review.text
            services.worker {
                try {
                    cancellation.check()
                    val sourceReview = services.followUpSource(token, sourceContext.first, sourceContext.second, path, cancellation)
                    cancellation.check()
                    PersonalRepair.validateFollowUp(sourceReview, sourceContext.first, sourceContext.second, path)
                    activity.runOnUiThread {
                        if (alive()) {
                            if (cancellation.isCancelled || revision != startedRevision || lastFeedback !== sourceContext || selectedFollowUpPath() != path) {
                                cancelledRead(true)
                            } else {
                                activeRead = null
                                followUpReview = sourceReview
                                output.text = PersonalRepair.render(sourceReview)
                                review.text = PersonalRepair.render(sourceReview)
                                status.text = "Follow-up source verified. Stage explicitly, then generate and approve a new complete diff."
                            }
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        if (alive()) {
                            if (cancellation.isCancelled || revision != startedRevision) cancelledRead(true)
                            else status.text = "Follow-up source could not be verified. Start a new source analysis if the branch moved, or check source access. Nothing was staged or approved."
                        }
                    }
                } finally {
                    activity.runOnUiThread { if (alive()) { activeRead = null; busy = false; refresh() } }
                }
            }
        }

        // Reused by the explicit Check CI button and the primary chat command.
        // A task may have been QUEUED/MISSING at the one-time post-commit lookup,
        // so subsequent owner-requested checks must be able to refresh the exact run.
        fun startExactCiCheck(): Boolean {
            val result = lastCommit ?: return false
            if (busy || !alive()) return false
            val token = githubToken.text.toString().trim()
            if (token.isBlank()) {
                status.text = "Enter a GitHub token to verify this exact task CI. No read was started."
                return false
            }
            setFeedbackContext(null)
            // A CI check invalidates an uncommitted preview; it cannot reuse approval.
            failRepair(activeRepair, "approval")
            invalidate()
            busy = true
            refresh()
            review.text = renderCommit(result, null) + "\n\nReading current CI for this exact task commit."
            status.text = "Checking exact task CI status; no provider or source write"
            val launched = runCatching {
                services.worker {
                    try {
                        val checked = services.ci(token, result)
                        // A failed result is evidence of failure, not necessarily proof that
                        // the logs belong to this particular attempt or commit.
                        val failureReport = if (checked.state == PatchPublisher.CiState.FAILED)
                            runCatching {
                                val evidence = services.feedback(token, result, PatchPreviewCancellation())
                                BuildDiagnostics.validateReport(evidence)
                                require(evidence.target == result.target &&
                                    evidence.branch == result.branch &&
                                    evidence.commitSha == result.commitSha &&
                                    evidence.status == "completed" &&
                                    checked.conclusion == evidence.conclusion &&
                                    evidence.conclusion != "success" &&
                                    checked.runUrl != null &&
                                    evidence.runUrl.startsWith(checked.runUrl + "/attempts/"))
                                evidence
                            }.getOrNull()
                        else null
                        val candidate = if (failureReport != null)
                            runCatching {
                                DeveloperFailureRepairDraft.from(result, checked, failureReport)
                            }.getOrNull()
                        else null
                        val receipt = DeveloperChatBuildReceipt.commit(result, checked, failureReport,
                            candidate != null)
                        activity.runOnUiThread {
                            if (alive()) {
                                developerRepairCandidate = candidate
                                val diagnostics = failureReport?.let {
                                    "\n\nVerified exact-run diagnostics:\n" + BuildDiagnostics.render(it)
                                } ?: if (checked.state == PatchPublisher.CiState.FAILED)
                                    "\n\nDetailed build evidence could not be verified for this run."
                                else ""
                                review.text = renderCommit(result, checked) + diagnostics
                                output.text = commitAudit(result) + "\n" + ciSummary(checked) + diagnostics
                                runCatching { services.chatStatus(receipt) }
                                status.text = when (checked.state) {
                                    PatchPublisher.CiState.PASSED -> "Exact task debug CI passed; signed release not verified"
                                    PatchPublisher.CiState.FAILED -> if (failureReport != null)
                                        "Exact task CI failed; bounded verified diagnostics available"
                                    else "Exact task CI failed; detailed evidence remains unverified"
                                    PatchPublisher.CiState.MISSING -> "No matching exact-task CI run found yet"
                                    PatchPublisher.CiState.QUEUED -> "Exact task CI queued; check again later"
                                    PatchPublisher.CiState.RUNNING -> "Exact task CI running; check again later"
                                }
                            }
                        }
                    } catch (_: Exception) {
                        activity.runOnUiThread {
                            if (alive()) {
                                review.text = renderCommit(result, null) +
                                    "\n\nCI status could not be verified. Check GitHub access and retry this read."
                                output.text = commitAudit(result) + "\nCI status unverified."
                                runCatching {
                                    services.chatStatus(DeveloperChatBuildReceipt.commit(result, null, null, false))
                                }
                                status.text = "CI remains unverified for the exact task commit"
                            }
                        }
                    } finally {
                        activity.runOnUiThread {
                            if (alive()) { busy = false; refresh() }
                        }
                    }
                }
            }.isSuccess
            if (!launched) {
                busy = false
                refresh()
                status.text = "CI worker could not start. No source change or retry occurred."
            }
            return launched
        }
        ci.setOnClickListener { startExactCiCheck() }
        fun saveDeveloperTaskBookmark(): Boolean {
            val result = lastCommit?.takeIf { it.target == PatchEngine.Target.DEVELOPER } ?: return false
            if (busy || !alive()) return false
            val bookmark = runCatching { RepairHistoryStore.bookmark(result) }.getOrNull() ?: return false
            busy = true
            refresh()
            val started = runCatching {
                services.worker {
                    // The owner explicitly requested this local write. Never save
                    // source, diff, token or approval, even if the Activity dies.
                    val saved = runCatching {
                        RepairHistoryStore(services.history.directory(activity)).save(bookmark)
                    }.isSuccess
                    activity.runOnUiThread {
                        if (alive()) {
                            val message = if (saved)
                                "Developer task bookmark saved privately (branch + commit only). " +
                                    "After reopening Developer, say 'Check saved Developer build' " +
                                    "to read its exact CI evidence. No approval or write access was saved."
                            else "Developer task bookmark could not be saved. No persistence or " +
                                "future recovery has been assumed; check private storage."
                            runCatching { services.chatStatus(message) }
                            status.text = if (saved) "Developer task bookmark saved; no build or release action"
                                else "Developer task bookmark save failed"
                            busy = false
                            refresh()
                        }
                    }
                }
            }.isSuccess
            if (!started) {
                busy = false
                refresh()
                status.text = "Bookmark worker could not start. No bookmark was assumed saved."
            }
            return started
        }

        fun readSavedDeveloperBuild(): Boolean {
            if (busy || !alive()) return false
            val cancellation = PatchPreviewCancellation()
            val token = githubToken.text.toString().trim()
            activeRead = cancellation
            busy = true
            refresh()
            status.text = "Reading the latest explicitly saved Developer task; no source change"
            val started = runCatching {
                services.worker {
                    val message = runCatching {
                        cancellation.check()
                        val bookmark = RepairHistoryStore(services.history.directory(activity))
                            .load().firstOrNull { it.target == PatchEngine.Target.DEVELOPER }
                            ?: return@runCatching "No explicitly saved Developer task bookmark is available. " +
                                "Finish an approved task and say 'Save Developer task' before restarting. " +
                                "No CI, source or installation result was inferred."
                        RepairHistoryStore.validate(bookmark)
                        cancellation.check()
                        val evidence = services.history.feedback(token, bookmark, cancellation)
                        cancellation.check()
                        BuildDiagnostics.validateReport(evidence)
                        require(evidence.target == bookmark.target &&
                            evidence.branch == bookmark.branch &&
                            evidence.commitSha == bookmark.commitSha)
                        DeveloperSavedBuildReceipt.render(bookmark, evidence)
                    }.getOrElse {
                        "Saved Developer task build could not be verified. " +
                            "The bookmark is not proof of an approved change or a successful build. " +
                            "Check GitHub access or saved history; no source write or retry was started."
                    }
                    activity.runOnUiThread {
                        if (alive()) {
                            if (!cancellation.isCancelled) {
                                runCatching { services.chatStatus(message) }
                                status.text = "Saved Developer build read complete; no approval restored"
                            } else status.text = "Saved build read cancelled; evidence discarded"
                            activeRead = null
                            busy = false
                            refresh()
                        }
                    }
                }
            }.isSuccess
            if (!started) {
                activeRead = null
                busy = false
                refresh()
                status.text = "Saved build reader could not start. No result was inferred."
            }
            return started
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
            if (busy || !alive() || !safe) return false
            activeEngineering = null
            chatImport = null
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
        fun startDeveloperRepair(): Boolean {
            val candidate = developerRepairCandidate ?: return false
            val committed = lastCommit
            if (!alive() || busy || committed == null ||
                committed.commitSha != candidate.base || committed.branch != candidate.branch) {
                developerRepairCandidate = null
                status.text = "Failed-build context changed. Re-read the exact task evidence before preparing a repair."
                refresh()
                return false
            }
            val taskId = "repair-" + UUID.randomUUID().toString().replace("-", "").take(16)
            if (!prepareInputs(taskId, candidate.task, candidate.paths, candidate.base, candidate.branch)) {
                status.text = "Repair could not be prepared from the pinned source. No new approval or write occurred."
                return false
            }
            developerRepairCandidate = null
            // A user-initiated button or explicit chat request is a preview action only.
            // The failed build's consumed approval cannot authorize a second Git write.
            preview.performClick()
            val started = busy && activePreview != null
            if (started) status.text = "Reading failed Developer source and requesting a NEW bounded repair diff. No write authorized."
            else status.text = "Repair preview could not be started. No approval or source write occurred."
            refresh()
            return started
        }
        prepareDeveloperRepair.setOnClickListener { startDeveloperRepair() }
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
        val disposeHistory = RepairHistoryWorkbench.attach(activity, root, githubToken,
            currentCommit = { if (busy) null else lastCommit },
            begin = {
                if (busy) false else {
                    setFeedbackContext(null)
                    failRepair(activeRepair, "approval")
                    invalidate()
                    busy = true
                    refresh()
                    true
                }
            }, end = { busy = false; refresh() }, services = services.history)

        fun showDeveloperDiffInChat(): String? {
            val selected = proposal ?: return null
            if (!alive() || busy || selected.request.target != PatchEngine.Target.DEVELOPER ||
                !current()) return null
            val complete = proposalReport ?: return null
            // Only a currently source-verified complete diff can be exposed.
            // Its exact fingerprint is now eligible for a distinct explicit chat approval.
            chatReviewedProposalId = selected.id
            return complete + "\n\nFor this exact reviewed diff only, type: Approve Developer diff " +
                selected.id.take(16) +
                "\nThis does not merge, publish a signed update, or install an APK."
        }

        fun continueDeveloperPlanStep(id: String, description: String, paths: List<String>, base: String): Boolean {
            // Do not replace an unreviewed, approved or uncertain in-session diff
            // merely because the owner asked which local plan step is next.
            if (!alive() || busy || proposal != null || interruptedAttempt != null || activeRead != null ||
                activePreview != null || activeRepair != null || activeEngineering != null) return false
            if (!prepareInputs(id, description, paths, base, "feature/jarvis-developer")) return false
            preview.performClick()
            return busy && activePreview != null
        }

        fun approveDeveloperDiffFromChat(prefix: String): Boolean {
            val selected = proposal ?: return false
            // A typed fingerprint must bind to the exact, current complete diff.
            // There is no chat-side AI interpretation of permission and no reused approval.
            if (!alive() || busy || selected.request.target != PatchEngine.Target.DEVELOPER ||
                !DeveloperDiffApprovalCommand.matches(prefix, selected.id) ||
                chatReviewedProposalId != selected.id ||
                !current() || !canWrite() || githubToken.text.toString().trim().isEmpty()) return false
            approve.isChecked = true
            refresh()
            if (!commit.isEnabled) {
                approve.isChecked = false
                refresh()
                return false
            }
            // Delegate to existing exact-SHA approval, audit and one-attempt commit.
            commit.performClick()
            val started = busy && selected.id in attemptedProposalIds
            if (!started && !busy) {
                approve.isChecked = false
                refresh()
            }
            return started
        }

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
        }, cancelPreviewAction = ::stopPreview, disposeAction = {
            stopPreview()
            activeRead?.cancel()
            disposed = true
            githubToken.removeTextChangedListener(readCredentialChanges)
            disposeHistory()
        }, guidedAction = { id, description, paths, base, target ->
            prepareInputs(id, description, paths, base,
                if (target == PatchEngine.Target.PERSONAL) "main" else "feature/jarvis-developer", target)
        }, importAction = { taskText, summary, edits, base ->
            if (edits.isEmpty() || edits.size > PatchEngine.MAX_FILES ||
                edits.any { !PatchEngine.allowedPath(it.path) } || edits.map { it.path }.distinct().size != edits.size) false
            else {
                val id = "chat-" + UUID.randomUUID().toString().replace("-", "").take(16)
                val ready = prepareInputs(id, taskText, edits.map { it.path }, base, "feature/jarvis-developer")
                if (!ready) false else {
                    // The owner already requested this proposal in chat. Preparing its
                    // exact diff is read-only and needs no additional menu action or AI call.
                    // The existing checkbox and commit button still require a separate
                    // explicit approval before any repository write.
                    chatImport = summary to edits.map { it.copy() }
                    preview.performClick()
                    val started = busy && activePreview != null
                    if (!started) {
                        chatImport = null
                        status.text = "Chat proposal could not start the pinned source validation. Review the source connection and retry; no repository write or approval occurred."
                    }
                    started
                }
            }
        }, developerRepairAction = ::startDeveloperRepair,
            exactDeveloperCiAction = {
                lastCommit?.target == PatchEngine.Target.DEVELOPER && startExactCiCheck()
            }, saveDeveloperBookmarkAction = ::saveDeveloperTaskBookmark,
            readDeveloperBookmarkAction = ::readSavedDeveloperBuild,
            approveDeveloperDiffAction = ::approveDeveloperDiffFromChat,
            showDeveloperDiffAction = ::showDeveloperDiffInChat,
            continueDeveloperPlanAction = ::continueDeveloperPlanStep,
            engineeringAction = { draft ->
            if (prepareInputs(draft.taskId, draft.task, draft.paths, draft.base, draft.branch, draft.target)) {
                activeEngineering = draft
                status.text = "Assistant job staged. Generate and approve its exact diff; progress will be recorded in the development inbox."
                true
            } else false
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
