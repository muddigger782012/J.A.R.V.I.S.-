package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
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
    /** Transfers reviewed queue scope into a fresh preview; it carries no write approval. */
    class Handle internal constructor(
        private val prepareAction: (String, String, List<String>, String) -> Boolean,
        private val invalidateAction: () -> Boolean
    ) {
        fun prepare(taskId: String, task: String, paths: List<String>, baseCommitSha: String): Boolean =
            prepareAction(taskId, task, paths, baseCommitSha)
        fun invalidate(): Boolean = invalidateAction()
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
        val ci = Button(context).apply {
            text = "Check CI for this exact task commit (read-only)"
            isEnabled = false
        }
        root.addView(ci)

        var busy = false
        var revision = 0L
        var plannedBaseCommitSha: String? = null
        var queuedContextNeedsRefresh = false
        var settingQueuedInputs = false
        var proposal: PatchEngine.Proposal? = null
        var proposalMetadata: Metadata? = null
        var proposalReport: String? = null
        var lastCommit: PatchPublisher.CommitResult? = null
        // An uncertain write outcome is never retried using the same approval in this session.
        val attemptedProposalIds = HashSet<String>()

        fun alive() = !activity.isFinishing && !activity.isDestroyed
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
                    plannedBaseCommitSha.orEmpty())), plannedBaseCommitSha)
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
            ci.isEnabled = !busy && lastCommit != null
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
        fun inputChanges(changesScope: Boolean) = object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
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
                    invalidate()
                }
                priorSelectionListener?.onItemSelected(parent, view, position, id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedProvider = ""
                invalidate()
                priorSelectionListener?.onNothingSelected(parent)
            }
        }
        approve.setOnCheckedChangeListener { _, _ -> refresh() }
        fun clearQueuedSource() {
            plannedBaseCommitSha = null
            queuedContextNeedsRefresh = false
            queuedSource.text = "Manual coding task: a new preview reads the current development branch."
            invalidate()
        }
        manual.setOnClickListener { if (!busy) clearQueuedSource() }

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
                !frozen.allowedPaths.all(PatchEngine::allowedPath) ->
                    "The allowlist contains a protected or unsupported path. Use permitted Developer Kotlin, Java, XML or existing Gradle files."
                else -> null
            }
            if (invalid != null) {
                invalidate()
                status.text = invalid
                return@setOnClickListener
            }
            invalidate()
            val startedRevision = revision
            busy = true
            refresh()
            review.text = "Reading only the explicit source scope at one immutable commit, then requesting a reviewable diff…"
            status.text = "Preparing a scoped diff preview"
            thread(name = "DeveloperPatchPreview") {
                var stage = "pinned repository inventory"
                try {
                    val inventory = RepositoryIntelligence.inventory(token)
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
                            RepositoryIntelligence.readSource(token, inventory, path))
                    }
                    val request = PatchEngine.Request(frozen.taskId, frozen.task, inventory.commitSha,
                        frozen.allowedPaths, sources, frozen.provider, frozen.model)
                    stage = "source scope validation"
                    PatchEngine.validateRequest(request)
                    stage = "selected AI provider proposal"
                    val generated = PatchModel.propose(key, request)
                    stage = "complete diff validation"
                    PatchEngine.revalidate(generated)
                    val report = renderProposal(generated, frozen.fingerprint)
                    activity.runOnUiThread {
                        if (alive()) {
                            if (revision != startedRevision || metadata() != frozen) {
                                review.text = "The preview completed after its task, provider, model or source scope changed. Its result was discarded; generate a new preview."
                                status.text = "Stale preview discarded; no repository write requested"
                            } else if (generated.id in attemptedProposalIds) {
                                review.text = "This exact proposal has already had a commit attempt. Check its task branch before proceeding. Use a new task ID and fresh review for a separate attempt."
                                status.text = "Previously consumed approval cannot be reused"
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
                                    "The development branch moved after this plan was created. Create a new plan from fresh analysis, or explicitly switch to a manual task. The old plan's scope and decisions remain saved. No provider call or repository write was requested."
                                else "Preview stopped during $failedStage. Check the explicit file scope, source access, provider key/model and source limits before trying a new preview. No repository write was requested."
                                status.text = "Scoped diff preview unavailable"
                            }
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
                PatchEngine.approve(selected, selected.id)
            } catch (_: Exception) {
                invalidate()
                status.text = "The exact diff could not be approved. Generate and review a fresh preview."
                return@setOnClickListener
            }
            attemptedProposalIds.add(selected.id)
            proposal = null
            proposalMetadata = null
            proposalReport = null
            approve.isChecked = false
            busy = true
            refresh()
            status.text = "Creating only the approved isolated task branch"
            thread(name = "DeveloperPatchCommit") {
                try {
                    val result = PatchPublisher.commit(token, approved)
                    val report = renderCommit(result, null)
                    activity.runOnUiThread {
                        if (alive()) {
                            lastCommit = result
                            review.text = report
                            output.text = commitAudit(result) + "\nCI has not been checked."
                            status.text = "Task branch committed; CI status is unverified"
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
                                "\n\nCOMMIT ATTEMPT STOPPED\n$detail\nApproval was consumed. Unreferenced objects or a task branch may exist; check GitHub before a separate attempt. Rollback is not assumed and no retry will run automatically."
                            output.text = "Task: ${selected.request.taskId}\nBase SHA: ${selected.request.baseCommitSha}\nProposal: ${selected.id}\nExpected branch: ${PatchEngine.branchName(selected)}\n$detail\nApproval consumed; inspect GitHub before a new attempt."
                            status.text = "Commit stopped; review the recorded outcome before proceeding"
                        }
                    }
                } finally {
                    activity.runOnUiThread {
                        if (alive()) { busy = false; refresh() }
                    }
                }
            }
        }

        ci.setOnClickListener {
            val result = lastCommit ?: return@setOnClickListener
            if (busy) return@setOnClickListener
            val token = githubToken.text.toString().trim()
            if (token.isBlank()) {
                status.text = "Enter an in-memory GitHub token to check this exact task commit."
                return@setOnClickListener
            }
            // Checking a build replaces any later preview, so its old approval is invalidated.
            invalidate()
            busy = true
            refresh()
            review.text = renderCommit(result, null) + "\n\nChecking the exact commit's current CI status…"
            status.text = "Checking CI for ${result.commitSha}"
            thread(name = "DeveloperPatchCI") {
                try {
                    val build = PatchPublisher.ci(token, result)
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
        refresh()
        return Handle(prepareAction = { id, description, paths, base ->
            val safe = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) &&
                description.isNotBlank() && description.length <= 12_000 &&
                paths.size in 1..PatchEngine.MAX_FILES && paths.distinct().size == paths.size &&
                paths.all(PatchEngine::allowedPath) && base.matches(Regex("[a-f0-9]{40}")) &&
                (listOf(id, description) + paths).all {
                    RepositoryRedaction.line(it) == it && !it.contains("PRIVATE KEY-----") &&
                        it.none { char -> char == '\u0000' || char == '\u007f' ||
                            (char < ' ' && char !in "\n\r\t") }
                }
            if (busy || !safe) false else {
                invalidate()
                settingQueuedInputs = true
                try {
                    taskId.setText(id)
                    task.setText(description)
                    allowedPaths.setText(paths.joinToString("\n"))
                } finally { settingQueuedInputs = false }
                // Text watchers may invalidate old queue state while fields change. Pin only
                // after every field has been filled, so those synchronous callbacks cannot unpin it.
                plannedBaseCommitSha = base
                queuedContextNeedsRefresh = false
                queuedSource.text = "Queued task source: $base\nA moved development branch requires a new plan and fresh diff review. No approval was restored."
                review.text = "Queued scope loaded. Generate a fresh diff, review every change and approve it explicitly."
                status.text = "Queued step prepared for a new diff; no provider call or Git write requested"
                refresh()
                true
            }
        }, invalidateAction = {
            if (busy) false else {
                invalidate()
                if (plannedBaseCommitSha != null) {
                    queuedContextNeedsRefresh = true
                    queuedSource.text = "Queued context changed. Prepare the selected step again, or explicitly clear the source pin for a manual task.\nPinned source: $plannedBaseCommitSha"
                }
                true
            }
        })
    }

    private data class Metadata(
        val taskId: String,
        val task: String,
        val provider: String,
        val model: String,
        val allowedPaths: List<String>,
        val fingerprint: String,
        val expectedBaseCommitSha: String?
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
        append("\nPublication and installation require separate approval.")
    }
}
