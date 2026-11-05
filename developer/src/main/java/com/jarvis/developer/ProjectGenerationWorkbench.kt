package com.jarvis.developer

import android.app.Activity
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.view.View
import kotlin.concurrent.thread

/** A cancellable text reply, independent of source review, builds and development-job approvals. */
internal object ProjectGenerationWorkbench {
    internal class Services(
        val worker: (() -> Unit) -> Unit = { work -> thread(name = "DeveloperCodingAssistant", isDaemon = true) { work() }; Unit },
        val ask: (String, String, String, String, PatchPreviewCancellation, RepositoryCodingContext.Context) -> String = CodingAssistantProvider::ask,
        val context: (String, PatchEngine.Target, String, String, PatchPreviewCancellation) -> RepositoryCodingContext.Context = RepositoryIntelligence::codingContext,
        val progress: (Activity) -> String = DeveloperProgressReport::read
    )
    class Handle internal constructor(private val stop: (Boolean) -> Unit) {
        fun cancel() = stop(false)
        fun dispose() = stop(true)
    }
    fun attach(activity: Activity, send: Button, cancel: Button, provider: Spinner, key: EditText, model: EditText,
        prompt: EditText, output: TextView, status: TextView, target: () -> Int, targetView: () -> View?,
        selected: () -> String?, select: (String) -> Unit, store: ProjectGenerationStore,
        changed: () -> Unit, services: Services = Services(), githubToken: () -> String = { "" }, forget: Button? = null,
        requestPatch: () -> Boolean = { false },
        stageReview: (String, String, List<PatchEngine.Edit>, String) -> Boolean = { _, _, _, _ -> false },
        requestDeveloperRepair: () -> Boolean = { false },
        requestDeveloperBuild: () -> Boolean = { false },
        saveDeveloperTask: () -> Boolean = { false },
        readSavedDeveloperBuild: () -> Boolean = { false },
        approveDeveloperDiff: (String) -> Boolean = { false },
        showDeveloperDiff: () -> String? = { null }): Handle {
        data class Running(val ticket: ProjectGenerationStore.Ticket, val cancellation: PatchPreviewCancellation)
        var running: Running? = null
        var localProgressRead: PatchPreviewCancellation? = null
        var disposed = false
        fun inputs(enabled: Boolean) {
            listOf(send, provider, key, model, prompt).forEach { it.isEnabled = enabled }
            targetView()?.isEnabled = enabled
            cancel.isEnabled = !enabled
            forget?.isEnabled = enabled
        }
        fun alive() = !disposed && !activity.isFinishing && !activity.isDestroyed
        fun current(run: Running) = alive() && running === run && !run.cancellation.isCancelled
        fun stop(interrupted: Boolean) {
            if (interrupted) disposed = true
            val read = localProgressRead
            if (read != null) {
                localProgressRead = null
                read.cancel()
                if (alive()) {
                    inputs(true)
                    status.text = "Local development progress read cancelled. No task or approval was changed."
                }
                return
            }
            val run = running ?: return
            running = null; run.cancellation.cancel()
            val result = store.finish(run.ticket, if (interrupted) "generation_interrupted" else "generation_cancelled")
            if (alive()) {
                inputs(true); changed()
                status.setText(when (result) {
                    ProjectGenerationStore.Result.SAVED -> R.string.project_generation_cancelled
                    ProjectGenerationStore.Result.STALE -> R.string.project_generation_stopped
                    ProjectGenerationStore.Result.FAILED -> R.string.project_generation_cancel_save_failed
                })
            }
        }
        cancel.isEnabled = false
        cancel.setOnClickListener { stop(false) }
        forget?.setOnClickListener {
            if (!alive() || running != null) return@setOnClickListener
            val project = selected()
            status.setText(if (project == null || store.forgetDiscussion(project)) R.string.project_generation_discussion_cleared
                else R.string.project_generation_discussion_clear_failed)
        }
        send.setOnClickListener {
            if (!alive() || running != null || localProgressRead != null) return@setOnClickListener
            val task = prompt.text.toString().trim()
            if (task.isEmpty()) { status.setText(R.string.project_generation_describe); prompt.requestFocus(); return@setOnClickListener }
            val secret = key.text.toString().trim()
            val readToken = githubToken().trim()
            if ((secret.isNotEmpty() && task.contains(secret)) || (readToken.isNotEmpty() && task.contains(readToken))) {
                status.setText(R.string.project_generation_remove_key); return@setOnClickListener
            }
            // Full exact-diff review is read directly from verified in-memory proposal
            // state. The chat provider, saved discussion, and GitHub are not touched.
            if (DeveloperDiffChatCommand.isRequest(task)) {
                if (target() != 0) {
                    output.text = "Developer code review is not available for the Personal Jarvis target."
                    status.text = "No Developer diff was exposed or approved."
                } else {
                    val report = runCatching { showDeveloperDiff() }.getOrNull()
                    output.text = report ?: "No complete, current, source-verified Developer diff is ready. " +
                        "Generate a scoped code proposal first; no approval or write occurred."
                    status.text = if (report != null)
                        "Full source-verified Developer diff displayed for explicit chat review. No write started."
                    else "No current Developer diff available for chat review."
                }
                return@setOnClickListener
            }
            // Only a complete owner-typed 16-hex proposal fingerprint can approve a
            // new isolated task-branch write. Partial commands are refused locally.
            if (DeveloperDiffApprovalCommand.isAttempt(task)) {
                val prefix = DeveloperDiffApprovalCommand.parse(task)
                if (target() != 0) {
                    output.text = "Developer diff approval is unavailable for the Personal Jarvis target."
                    status.text = "No Developer source write started."
                } else if (prefix == null) {
                    output.text = "First type Show Developer diff and review the full change, then use the exact 16-character fingerprint. " +
                        "Example: Approve Developer diff 0123456789abcdef. No write started."
                    status.text = "Invalid exact Developer approval command."
                } else {
                    val started = runCatching { approveDeveloperDiff(prefix) }.getOrDefault(false)
                    output.text = if (started)
                        "Exact Developer diff approved. Creating only its isolated task branch. " +
                            "CI results will appear separately. No merge, signed update or installation was approved."
                    else "Approval refused: the fingerprint is not the currently reviewed, unconsumed " +
                        "source-verified Developer diff, or the commit gate is unavailable. No write started."
                    status.text = if (started) "Exact user-approved task branch commit started."
                        else "Developer diff approval refused; review current pinned proposal."
                }
                return@setOnClickListener
            }
            val savedBuildCommand = DeveloperSavedBuildChatCommand.match(task)
            if (savedBuildCommand != null) {
                if (target() != 0) {
                    output.text = "Developer task history is unavailable for the Personal Jarvis target."
                    status.text = "No Developer history operation was started."
                } else {
                    val started = runCatching {
                        when (savedBuildCommand) {
                            DeveloperSavedBuildChatCommand.Action.SAVE -> saveDeveloperTask()
                            DeveloperSavedBuildChatCommand.Action.READ -> readSavedDeveloperBuild()
                        }
                    }.getOrDefault(false)
                    output.text = if (started)
                        if (savedBuildCommand == DeveloperSavedBuildChatCommand.Action.SAVE)
                            "Saving only the current verified Developer task's branch and commit " +
                                "in private local history. No credentials, code or approval are saved."
                        else "Reading the latest explicitly saved Developer task's exact CI evidence. " +
                            "This cannot restore approval or start a source write."
                    else "No eligible Developer task or saved history operation is ready. " +
                        "No approval, source change, or passing CI was assumed."
                    status.text = if (started) "Developer history operation started from chat"
                        else "Developer history operation not started"
                }
                return@setOnClickListener
            }
            // Read-only exact-task CI follow-up. A queued first lookup can be refreshed
            // from chat without another menu, model call or source write.
            if (DeveloperBuildChatCommand.isRequest(task)) {
                if (target() != 0) {
                    output.text = "Developer build checks are unavailable for the Personal Jarvis target."
                    status.text = "Switch to Developer to inspect a Developer task's build."
                } else {
                    val started = runCatching { requestDeveloperBuild() }.getOrDefault(false)
                    output.text = if (started)
                        "Checking the exact Developer task commit's current CI status. " +
                            "Results and any verified failure diagnostics will appear here. " +
                            "No AI request or source write was started."
                    else "No completed Developer task commit is available for a build check, " +
                        "or its GitHub connection is unavailable. No CI result was assumed."
                    status.text = if (started) "Exact Developer task CI read started from chat."
                        else "Developer build check not started; no source or approval changed."
                }
                return@setOnClickListener
            }
            // Direct, narrowly matched chat repair request. No stored discussion, AI
            // request, or write is initiated here. A separate source-verified build
            // failure must already exist in PatchWorkbench before any preview starts.
            if (DeveloperRepairChatCommand.isRequest(task)) {
                if (target() != 0) {
                    output.text = "Developer compiler repair is not available for the Personal Jarvis target."
                    status.text = "Switch to Developer before requesting a Developer repair; no work started."
                } else {
                    val started = runCatching { requestDeveloperRepair() }.getOrDefault(false)
                    output.text = if (started)
                        "Preparing a NEW pinned-source repair diff below this chat. " +
                            "Review every change and explicitly approve it before any source write. " +
                            "No prior approval was transferred."
                    else "No verified, in-scope Developer compiler failure is ready for a repair. " +
                        "Review the exact task's build evidence before requesting a new repair. " +
                        "No provider request, approval or source write was started."
                    status.text = if (started) "Developer repair preparation started from chat; exact diff review pending."
                        else "Chat repair unavailable: a verified failed Developer source build is required."
                }
                return@setOnClickListener
            }
            // A direct chat request for progress reads only verified local storage.
            // It never starts the AI provider, changes task checkpoints, or grants approval.
            if (DeveloperProgressReport.isRequest(task)) {
                val cancellation = PatchPreviewCancellation()
                localProgressRead = cancellation
                inputs(false)
                status.text = "Reading local development plans and jobs; no network or source write."
                val work: () -> Unit = {
                    val report = runCatching {
                        cancellation.check()
                        services.progress(activity).also { cancellation.check() }
                    }
                    activity.runOnUiThread {
                        if (!alive() || localProgressRead !== cancellation || cancellation.isCancelled) return@runOnUiThread
                        localProgressRead = null
                        inputs(true)
                        report.onSuccess {
                            output.text = it
                            status.text = "Read-only local development progress loaded. CI and installed APK are not inferred."
                        }.onFailure {
                            output.text = "Saved development status could not be verified. No empty-progress fallback was used."
                            status.text = "Local progress read failed. Repair storage or inspect Track; no records were changed."
                        }
                    }
                }
                runCatching { services.worker(work) }.onFailure {
                    cancellation.cancel()
                    localProgressRead = null
                    inputs(true)
                    status.text = "Local progress worker could not start; no records were changed."
                }
                return@setOnClickListener
            }
            // An explicit Developer code-change request is already authorization to
            // ask the configured provider for a source-verified PROPOSAL, not to write.
            // Do not require a separate hidden code-mode toggle for these requests.
            val directCodeRequest = DeveloperCodeChangeIntent.isRequest(task)
            val wantsPatch = requestPatch() || directCodeRequest
            if (wantsPatch && target() != 0) {
                status.text = "Code patch proposals are limited to the Developer target. Switch to Developer."
                output.text = "Developer source changes require the Developer target and a verified source review. " +
                    "No AI coding request, approval or repository write was started."
                return@setOnClickListener
            }
            val project = selected() ?: java.util.UUID.randomUUID().toString()
            val product = target()
            if (secret.isEmpty()) {
                if (store.saveLocal(project, task, product)) {
                    select(project); changed(); status.setText(R.string.project_generation_saved_offline)
                    output.setText(R.string.project_generation_not_started)
                } else status.setText(R.string.project_generation_save_failed)
                return@setOnClickListener
            }
            val secrets = listOf(secret, readToken)
            val discussion = runCatching { store.discussion(project, product, secrets) }.getOrElse {
                status.setText(R.string.project_generation_save_failed); return@setOnClickListener
            }
            val instruction = if (!wantsPatch) task else task +
                "\n\nCODE PATCH MODE (proposal only, never execute): Return ONLY a JSON object with summary (string) and edits (array of path and content strings). " +
                "Every edit must be a complete replacement Kotlin or Java source file under developer/src/main/java/com/jarvis/developer/ or developer/src/test/java/com/jarvis/developer/. Existing files may be modified only when their current contents were provided in verified repository context. " +
                "Do not modify protected components, build configuration, permissions, credentials, or releases. Split work into further requests when it exceeds the provider response budget. Explicit user approval is required separately. No Markdown fences."
            val lookupTask = discussion.lookupTask(instruction)
            val ticket = store.begin(project, task, product)
            if (ticket == null) { status.setText(R.string.project_generation_save_failed); return@setOnClickListener }
            select(project); changed()
            val run = Running(ticket, PatchPreviewCancellation()).also { running = it }
            val providerName = provider.selectedItem.toString(); val modelName = model.text.toString().trim()
            inputs(false); status.setText(R.string.project_generation_reading_context)
            val productTarget = if (product == 1) PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
            val work: () -> Unit = {
                var sourceSummary = ""
                var nextDiscussion: ProjectGenerationDiscussion? = null
                var reviewScope: Triple<String, List<PatchEngine.Edit>, String>? = null
                val reply = runCatching {
                    run.cancellation.check()
                    val source = try { services.context(lookupTask, productTarget, readToken, secret, run.cancellation) }
                        catch (_: Exception) { run.cancellation.check(); RepositoryCodingContext.unavailable(lookupTask, productTarget) }
                    run.cancellation.check()
                    // A patch must be grounded in verified repository contents. A missing
                    // GitHub token, failed source read, or unavailable branch must never
                    // silently turn a code-change request into an ungrounded AI proposal.
                    if (wantsPatch && !source.verified) {
                        throw IllegalStateException("Repository source could not be verified. Connect GitHub and retry before requesting code changes.")
                    }
                    val context = source.withDiscussion(instruction, discussion)
                    require(context.target == productTarget)
                    // A longer conversation may exhaust the bounded context budget and
                    // remove the source excerpts. Never generate a patch without them.
                    if (wantsPatch && (!context.verified || context.paths.isEmpty())) {
                        throw IllegalStateException("Repository source could not be verified. Connect GitHub and retry before requesting code changes.")
                    }
                    context.data(instruction) // Context must bind to the exact provider request, including patch-mode instructions.
                    sourceSummary = context.summary()
                    activity.runOnUiThread { if (current(run)) status.text = activity.getString(
                        if (context.verified) R.string.project_generation_context_ready else R.string.project_generation_context_unavailable, providerName) }
                    val text = services.ask(providerName, secret, modelName, instruction, run.cancellation, context)
                    run.cancellation.check()
                    if (wantsPatch) {
                        // Reject free-form prose and unsafe edits before saving a proposal.
                        // This is validation only; publication still requires explicit approval.
                        val proposal = runCatching { org.json.JSONObject(text.trim()) }.getOrElse {
                            throw IllegalArgumentException("Code proposal must be a JSON object.")
                        }
                        require(proposal.optString("summary").isNotBlank()) { "Code proposal has no summary." }
                        val edits = proposal.optJSONArray("edits")
                            ?: throw IllegalArgumentException("Code proposal has no edits array.")
                        require(edits.length() > 0) { "Code proposal must contain at least one file." }
                        require(edits.length() <= PatchEngine.MAX_FILES) { "Code proposal exceeds the review engine file limit (${PatchEngine.MAX_FILES}). Request a smaller scoped repair." }
                        require(text.toByteArray(Charsets.UTF_8).size <= 512_000) { "Code proposal exceeds the response byte budget." }
                        val seen = mutableSetOf<String>()
                        for (i in 0 until edits.length()) {
                            val edit = edits.optJSONObject(i)
                                ?: throw IllegalArgumentException("Invalid code proposal edit.")
                            val path = edit.optString("path")
                            require((path.startsWith("developer/src/main/java/com/jarvis/developer/") ||
                                path.startsWith("developer/src/test/java/com/jarvis/developer/")) &&
                                PatchEngine.allowedPath(path, PatchEngine.Target.DEVELOPER)) {
                                "Code proposal contains an out-of-scope path."
                            }
                            require(!path.contains("..") && !path.contains(92.toChar()) &&
                                (path.endsWith(".kt") || path.endsWith(".java")) && seen.add(path)) {
                                "Code proposal contains an unsafe or duplicate path."
                            }
                            require(edit.optString("content").isNotBlank()) { "Code proposal contains an empty file." }
                            require(path in context.paths) { "Proposal path was not among the verified repository files. Select existing source files through repository inspection; do not invent new paths." }
                        }
                        reviewScope = Triple(proposal.getString("summary"), (0 until edits.length()).map { index ->
                            val edit = edits.getJSONObject(index)
                            PatchEngine.Edit(edit.getString("path"), edit.getString("content"))
                        }, requireNotNull(context.commit))
                    }
                    nextDiscussion = discussion.append(task, text, secrets)
                    sourceSummary + "\n\n" + text
                }
                if (!run.cancellation.isCancelled) {
                    val saved = store.finish(ticket, if (reply.isSuccess) "generated" else "generation_failed", reply.getOrNull(),
                        nextDiscussion.takeIf { reply.isSuccess })
                    activity.runOnUiThread {
                        if (!current(run)) return@runOnUiThread
                        running = null; inputs(true); changed()
                        if (selected() != project || saved == ProjectGenerationStore.Result.STALE) {
                            status.setText(R.string.project_generation_changed)
                        } else reply.onSuccess { text ->
                            val scope = reviewScope
                            if (scope != null && saved != ProjectGenerationStore.Result.SAVED) {
                                // A source-bound review must not outlive a failed local task receipt.
                                // Discard the proposal and require a fresh, traceable request.
                                output.text = "The generated code proposal could not be saved. Its unapproved review was discarded; retry the task. No files were changed."
                                status.text = "Code proposal not staged: project save failed."
                            } else if (scope != null) {
                                val staged = stageReview(task, scope.first, scope.second, scope.third)
                                val names = scope.second.joinToString("\n") { "• " + it.path.substringAfterLast('/') }
                                output.text = buildString {
                                    append("Jarvis proposes:\n").append(scope.first)
                                    append("\n\nFiles (").append(scope.second.size).append("):\n").append(names)
                                    append("\n\nSource revision: ").append(scope.third.take(12))
                                    append(if (staged) "\n\nCode changes staged for verified diff review. Nothing committed." else
                                        "\n\nReview staging unavailable. No source files changed.")
                                }
                                status.text = if (staged) "Proposal staged for review; explicit approval required." else
                                    "Review staging rejected. No repository write requested."
                            } else output.text = text
                            if (scope == null) status.text = if (saved == ProjectGenerationStore.Result.SAVED)
                                activity.getString(R.string.project_generation_received, providerName)
                                else activity.getString(R.string.project_generation_response_unsaved)
                        }.onFailure { failure ->
                            status.setText(R.string.project_generation_failed)
                            val reason = when (failure) {
                                is CodingAssistantProvider.ProviderFailure -> failure.message.orEmpty()
                                is IllegalArgumentException -> "AI request or response format validation failed. Check the selected model and request limits."
                                is IllegalStateException -> failure.message?.takeIf { it.startsWith("Repository source could not be verified.") }
                                    ?: "AI response processing failed. Check model compatibility and output limits."
                                else -> activity.getString(R.string.project_generation_check_connection)
                            }
                            val detail = if (sourceSummary.isBlank()) reason else sourceSummary + "\n\n" + reason
                            output.text = activity.getString(R.string.project_generation_failure_detail, detail,
                                activity.getString(R.string.project_generation_description_kept))
                        }
                    }
                }
            }
            runCatching { services.worker(work) }.onFailure {
                run.cancellation.cancel(); running = null
                store.finish(ticket, "generation_failed")
                inputs(true); changed(); status.setText(R.string.project_generation_start_failed)
            }
        }
        return Handle(::stop)
    }
}
