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
        val askScreenshot: (String, String, String, String, PatchPreviewCancellation, RepositoryCodingContext.Context, DeveloperScreenshot) -> String =
            CodingAssistantProvider::askScreenshot,
        val context: (String, PatchEngine.Target, String, String, PatchPreviewCancellation) -> RepositoryCodingContext.Context = RepositoryIntelligence::codingContext,
        val progress: (Activity) -> String = DeveloperProgressReport::read,
        val releaseReadiness: (String) -> DeveloperReleaseControl.Candidate =
            DeveloperReleaseControl.Client()::inspect,
        val releaseDispatch: (String, String) -> DeveloperReleaseControl.Candidate =
            DeveloperReleaseControl.Client()::publish,
        val nextPlan: (Activity) -> DeveloperPlanContinuation.Suggestion = DeveloperPlanContinuation::suggest,
        val planAnalysis: DeveloperPlanAnalysisReview = DeveloperPlanAnalysisReview(),
        val completion: DeveloperPlanCompletionReview = DeveloperPlanCompletionReview(),
        val savedCompletion: DeveloperSavedCompletionReview = DeveloperSavedCompletionReview(),
        val ci: (String, PatchPublisher.CommitResult) -> PatchPublisher.CiStatus = PatchPublisher::ci
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
        stageReview: (String, String, List<PatchEngine.Edit>, String, PatchEngine.Target) -> Boolean = { _, _, _, _, _ -> false },
        requestDeveloperRepair: () -> Boolean = { false },
        requestDeveloperBuild: () -> Boolean = { false },
        saveDeveloperTask: () -> Boolean = { false },
        readSavedDeveloperBuild: () -> Boolean = { false },
        approveDeveloperDiff: (String) -> Boolean = { false },
        showDeveloperDiff: () -> String? = { null },
        continueDeveloperPlan: (String, String, List<String>, String) -> Boolean = { _, _, _, _ -> false },
        currentDeveloperCommit: () -> PatchPublisher.CommitResult? = { null },
        mayReadSavedCompletion: () -> Boolean = { false },
        onUserMessage: (String) -> Unit = {},
        screenshot: () -> DeveloperScreenshot? = { null },
        screenshotLoading: () -> Boolean = { false },
        onScreenshotSent: (DeveloperScreenshot) -> Unit = {},
        requestDeveloperSourceAnalysis: () -> Boolean = { false }): Handle {
        data class Running(val ticket: ProjectGenerationStore.Ticket, val cancellation: PatchPreviewCancellation)
        var running: Running? = null
        var localProgressRead: PatchPreviewCancellation? = null
        var releaseReadySha: String? = null
        var releaseDispatchMayHaveStarted = false
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
                    status.text = if (releaseDispatchMayHaveStarted)
                        "Signed release dispatch may have reached GitHub. Check Developer Actions before trying again; cancellation cannot undo a queued workflow."
                    else "Read-only operation cancelled. No source, release or approval was changed."
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
            if (screenshotLoading()) {
                status.text = "Screenshot import in progress. Send after the preview appears."
                return@setOnClickListener
            }
            val selectedScreenshot = screenshot()
            if (selectedScreenshot != null && DeveloperDiffApprovalCommand.isAttempt(task)) {
                output.text = "Remove the screenshot before approving a source diff. " +
                    "Image analysis never approves a code write."
                status.text = "Source approval kept separate from screenshot analysis."
                return@setOnClickListener
            }
            val secret = key.text.toString().trim()
            val readToken = githubToken().trim()
            if ((secret.isNotEmpty() && task.contains(secret)) || (readToken.isNotEmpty() && task.contains(readToken))) {
                status.setText(R.string.project_generation_remove_key); return@setOnClickListener
            }
            // The Home conversation is presentation only. Existing workbench and
            // exact scoped approval mechanisms remain the sole executors.
            runCatching { onUserMessage(task) }
            // Explicit read-only source inspection stays in the existing GitHub
            // inventory/analysis engine. No AI call, diff approval, or source write.
            if (DeveloperSourceChatCommand.isRequest(task)) {
                if (target() != 0) {
                    output.text = "Developer repository inspection requires the Developer target. " +
                        "No Personal source was read and no code change was requested."
                    status.text = "Select Developer before inspecting its repository."
                } else {
                    val started = runCatching { requestDeveloperSourceAnalysis() }.getOrDefault(false)
                    if (started) {
                        status.text = "Reading Developer repository (read-only). " +
                            "Eligible source may be staged for a separate exact diff review."
                    } else {
                        output.text = "Repository inspection could not start, or another source read " +
                            "is active. Retry after it finishes. No source or approval was changed."
                        status.text = "Developer source inspection not started."
                    }
                }
                return@setOnClickListener
            }
            // Release promotion is a two-step, owner-controlled chat operation.
            // First verify the canonical source and exact successful CI, then
            // explicitly type its entire SHA. No provider, source write, merge,
            // local signing, APK installation, or implicit approval is involved.
            val releaseCheck = DeveloperReleaseChatCommand.check(task)
            val releaseAttempt = DeveloperReleaseChatCommand.publishAttempt(task)
            if (releaseCheck || releaseAttempt) {
                if (target() != 0) {
                    output.text = "Signed Developer publication is unavailable for the Personal Jarvis target."
                    status.text = "Developer release command rejected."
                    return@setOnClickListener
                }
                val publishSha = if (releaseAttempt) DeveloperReleaseChatCommand.publishSha(task) else null
                if (releaseAttempt && (publishSha == null || publishSha != releaseReadySha)) {
                    output.text = "Publication requires a fresh passing exact-commit check. " +
                        "First type 'Check Developer release readiness', then send exactly " +
                        "'Publish Developer update' followed by the 40-character approved SHA. " +
                        "No release was requested."
                    status.text = "Signed update approval missing or stale."
                    return@setOnClickListener
                }
                if (readToken.isBlank()) {
                    output.text = "Connect GitHub in Settings. Release readiness requires read access; " +
                        "publication additionally requires GitHub Actions write permission."
                    status.text = "GitHub release connection unavailable."
                    return@setOnClickListener
                }
                val cancellation = PatchPreviewCancellation()
                localProgressRead = cancellation
                releaseDispatchMayHaveStarted = false
                inputs(false)
                status.text = if (publishSha == null)
                    "Verifying canonical Developer SHA and all required isolated CI."
                else "Rechecking exact source before requesting a signed Developer workflow."
                if (publishSha != null) releaseReadySha = null // One-use owner approval.
                val work: () -> Unit = {
                    val result = runCatching {
                        cancellation.check()
                        if (publishSha == null) services.releaseReadiness(readToken)
                        else {
                            cancellation.check()
                            releaseDispatchMayHaveStarted = true
                            services.releaseDispatch(readToken, publishSha)
                        }
                    }
                    activity.runOnUiThread {
                        if (!alive() || localProgressRead !== cancellation || cancellation.isCancelled)
                            return@runOnUiThread
                        localProgressRead = null
                        releaseDispatchMayHaveStarted = false
                        inputs(true)
                        result.onSuccess { verified ->
                            if (publishSha == null) {
                                releaseReadySha = verified.sha
                                output.text = "Canonical Developer source: ${verified.sha}\n" +
                                    "Verified successful isolated CI run: ${verified.runId}.\n\n" +
                                    "To authorize ONLY a signed workflow dispatch, type exactly:\n" +
                                    "Publish Developer update ${verified.sha}\n\n" +
                                    "No publication, merge, APK download or installation has occurred."
                                status.text = "Release candidate verified; exact owner authorization required."
                            } else {
                                output.text = "GitHub accepted a signed Developer release workflow request for " +
                                    "${verified.sha}. Follow Actions for the final signing and publication " +
                                    "result. The APK was NOT installed on your device."
                                status.text = "Signed release workflow queued; publication not yet verified."
                            }
                        }.onFailure {
                            if (publishSha == null) releaseReadySha = null
                            output.text = if (publishSha == null)
                                "Developer release readiness is blocked: canonical CI or source " +
                                    "could not be verified as complete and green. Inspect Developer " +
                                    "Actions and retry after resolving the failure. No release was requested."
                            else "Signed Developer workflow was not confirmed. Check GitHub Actions " +
                                "before trying again; do not assume it failed or was published."
                            status.text = "Developer release gate blocked or unverified."
                        }
                    }
                }
                runCatching { services.worker(work) }.onFailure {
                    localProgressRead = null
                    releaseReadySha = null
                    inputs(true)
                    status.text = "Release verification worker unavailable; no result verified."
                }
                return@setOnClickListener
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
                    output.text = "First type Show Developer diff and review the full change, " +
                        "then send exactly: Approve Developer diff. Extra text cannot authorize a write."
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
            // A separately requested, fully displayed metadata review is a prerequisite
            // for the first local analysis checkpoint. Confirmation marks only that
            // user-reported checkpoint; it cannot approve source edits or tests.
            if (DeveloperPlanAnalysisChatCommand.match(task) != null) {
                if (target() != 0) {
                    output.text = "Developer plan analysis review is unavailable for Personal Jarvis."
                    status.text = "No Developer plan or source operation started."
                } else {
                    val operation = DeveloperPlanAnalysisChatCommand.match(task)!!
                    if (operation == DeveloperPlanAnalysisChatCommand.Action.REVIEW) {
                        output.text = runCatching { services.planAnalysis.review(activity) }.getOrElse {
                            "Developer plan metadata could not be verified. No checkpoint was changed."
                        }
                        status.text = "Developer plan analysis metadata reviewed locally; no plan status changed."
                    } else {
                        // A successful owner analysis approval is enough to prepare a
                        // fresh, review-only code proposal. Avoid a redundant second
                        // "Continue" command, but never reuse this approval for writes.
                        val confirmation = runCatching {
                            services.planAnalysis.confirmWithReceipt(activity)
                        }.getOrElse {
                            DeveloperPlanAnalysisReview.Confirmation(
                                "Developer plan checkpoint could not be saved. No source preview or write started.")
                        }
                        val handoff = confirmation.planId?.let { approvedPlan ->
                            val suggestion = runCatching { services.nextPlan(activity) }.getOrNull()
                            val packet = suggestion?.packet
                            if (packet == null || packet.planId != approvedPlan)
                                "The just-approved plan's exact next step is unavailable or a different plan is selected. " +
                                    "Use Continue Developer plan after reviewing the saved state. No source preview was started."
                            else if (runCatching {
                                    continueDeveloperPlan(packet.id, packet.task, packet.paths, packet.base)
                                }.getOrDefault(false))
                                "Fresh source-verified Developer diff preview STARTED automatically for " +
                                    "the approved plan. Review the complete diff in chat before a separate " +
                                    "Approve Developer diff command. No source write is approved."
                            else
                                "Analysis was saved, but a coding review or preview is already active, or " +
                                    "the source-preparation gate is unavailable. No previous proposal was replaced. " +
                                    "Use Continue Developer plan when the coding slot is clear."
                        }.orEmpty()
                        output.text = confirmation.message +
                            if (handoff.isEmpty()) "" else "\n\n$handoff"
                        status.text = if (handoff.contains("STARTED automatically"))
                            "Plan analysis saved; read-only Developer code preview started."
                        else "Plan analysis result recorded; no automatic code write."
                    }
                }
                return@setOnClickListener
            }
            // Final review is a separate exact-commit CI read plus the owner's
            // explicit confirmation of the displayed local completion evidence.
            // It has no merge, signing, publishing or installation capability.
            val finalAction = DeveloperPlanCompletionChatCommand.match(task)
            if (finalAction != null) {
                if (target() != 0) {
                    output.text = "Developer completion review is unavailable for Personal Jarvis."
                    status.text = "No review or source operation started."
                    return@setOnClickListener
                }
                val commit = runCatching { currentDeveloperCommit() }.getOrNull()
                if (commit == null && !runCatching { mayReadSavedCompletion() }.getOrDefault(false)) {
                    output.text = "Saved completion review is unavailable while another coding " +
                        "preview, operation, or uncertain commit owns the Developer session. " +
                        "Finish or recover that operation before reviewing saved CI."
                    status.text = "Saved completion review blocked by active coding state."
                    return@setOnClickListener
                }
                if (readToken.isBlank()) {
                    output.text = "A GitHub token is needed for the exact-source CI read. " +
                        "No completion or release was assumed."
                    status.text = "Developer review requires a current CI read."
                    return@setOnClickListener
                }
                val cancellation = PatchPreviewCancellation()
                val projectAtStart = selected()
                localProgressRead = cancellation
                inputs(false)
                status.text = "Reading exact-task CI and local final review evidence; no source write."
                val work: () -> Unit = {
                    val response = runCatching {
                        cancellation.check()
                        if (commit == null) {
                            // After restart, a bookmark is NOT a recovered writer or
                            // consent. Re-read and fully validate its exact CI and
                            // current persisted plan before arming new local review.
                            if (finalAction == DeveloperPlanCompletionChatCommand.Action.REVIEW)
                                services.savedCompletion.prepare(activity, readToken, cancellation)
                            else services.savedCompletion.confirm(activity, readToken, cancellation)
                        } else {
                            val checked = services.ci(readToken, commit)
                            cancellation.check()
                            if (finalAction == DeveloperPlanCompletionChatCommand.Action.REVIEW)
                                services.completion.prepare(activity, commit, checked)
                            else services.completion.confirm(activity, commit, checked)
                        }
                    }
                    activity.runOnUiThread {
                        if (!alive() || localProgressRead !== cancellation || cancellation.isCancelled) return@runOnUiThread
                        localProgressRead = null
                        inputs(true)
                        if (target() != 0 || selected() != projectAtStart) {
                            output.text = "Project or target changed; late completion evidence discarded."
                            status.text = "No completion status assumed."
                        } else response.onSuccess { value ->
                            when (value) {
                                is DeveloperPlanCompletionReview.Prepared -> {
                                    output.text = value.text
                                    services.completion.arm(value)
                                    status.text = "Current exact-source CI and review displayed; " +
                                        "no final checkpoint marked complete."
                                }
                                is DeveloperSavedCompletionReview.Prepared -> {
                                    output.text = value.text
                                    services.savedCompletion.arm(value)
                                    status.text = "Saved Developer commit reverified against CI; " +
                                        "no former approval was restored."
                                }
                                is String -> {
                                    output.text = value
                                    status.text = "Developer final review result recorded; no release requested."
                                }
                            }
                        }.onFailure {
                            output.text = "Completion review requires a still-current source-scoped " +
                                "plan and passing exact-commit CI. No milestone was marked complete."
                            status.text = "Developer completion review not verified."
                        }
                    }
                }
                runCatching { services.worker(work) }.onFailure {
                    cancellation.cancel()
                    localProgressRead = null
                    inputs(true)
                    status.text = "Completion review worker unavailable; no completion assumed."
                }
                return@setOnClickListener
            }
            // Continue the newest persisted task plan from its next eligible checkpoint.
            // Source inspection/model calls remain in the existing pinned preview engine.
            // No dependency or user-reported acceptance checkpoint is auto-completed.
            if (DeveloperPlanContinueChatCommand.isRequest(task)) {
                if (target() != 0) {
                    output.text = "Developer plan continuation is unavailable for Personal Jarvis."
                    status.text = "No Developer plan or source operation started."
                    return@setOnClickListener
                }
                val projectAtStart = selected()
                val cancellation = PatchPreviewCancellation()
                localProgressRead = cancellation
                inputs(false)
                status.text = "Reading private Developer plan checkpoints; no source write."
                val work: () -> Unit = {
                    val suggestion = runCatching {
                        cancellation.check()
                        services.nextPlan(activity).also { cancellation.check() }
                    }
                    activity.runOnUiThread {
                        if (!alive() || localProgressRead !== cancellation || cancellation.isCancelled) return@runOnUiThread
                        localProgressRead = null
                        inputs(true)
                        if (target() != 0 || selected() != projectAtStart) {
                            output.text = "The selected project or product changed. Stale plan continuation discarded."
                            status.text = "No plan staged or source changed."
                        } else suggestion.onSuccess { next ->
                            val packet = next.packet
                            val started = packet != null && runCatching {
                                continueDeveloperPlan(packet.id, packet.task, packet.paths, packet.base)
                            }.getOrDefault(false)
                            output.text = next.message +
                                if (packet == null) "" else if (started)
                                    "\n\nFresh pinned-source diff preview STARTED for this one eligible step. " +
                                    "Review the complete result and explicitly approve it before any write. " +
                                    "The step remains unfinished until its checkpoint is reviewed."
                                else "\n\nA current review or coding operation is active, or preview inputs are not available. " +
                                    "No previous proposal was replaced and no write was made."
                            status.text = if (started) "One eligible Developer plan step preview started."
                                else "Local plan examined; no implementation write started."
                        }.onFailure {
                            output.text = "Saved Developer plan state could not be verified. Existing checkpoint files were not changed."
                            status.text = "Plan continuation stopped: private state verification failed."
                        }
                    }
                }
                runCatching { services.worker(work) }.onFailure {
                    cancellation.cancel()
                    localProgressRead = null
                    inputs(true)
                    status.text = "Plan continuation worker unavailable; no plan or source change occurred."
                }
                return@setOnClickListener
            }
            // An explicit Developer code-change request is already authorization to
            // ask the configured provider for a source-verified PROPOSAL, not to write.
            // Do not require a separate hidden code-mode toggle for these requests.
            val developerCodeRequest = DeveloperCodeChangeIntent.isRequest(task)
            val personalCodeRequest = PersonalCodeChangeIntent.isRequest(task)
            val brainstorming = DevelopmentExplorationIntent.isRequest(task) &&
                !developerCodeRequest && !personalCodeRequest
            val product = target()
            // The checkbox may request source changes for either product. Pure
            // brainstorming first returns suggestions: no selected feature means
            // there is not yet a scoped change to review or approve.
            // Screenshot requests are diagnosis-only. An image is never parsed as a
            // proposed edit or used as approval, even if the prompt starts with Fix.
            val wantsPatch = selectedScreenshot == null && (developerCodeRequest || personalCodeRequest ||
                (requestPatch() && !brainstorming))
            if ((developerCodeRequest && product != 0) || (personalCodeRequest && product != 1)) {
                status.text = "Select the app named in the request before preparing its code."
                output.text = "The request targets a different application than the selected product. " +
                    "Choose the correct product, then retry. No provider code request, " +
                    "approval or repository write was started."
                return@setOnClickListener
            }
            val project = selected() ?: java.util.UUID.randomUUID().toString()
            if (secret.isEmpty()) {
                if (selectedScreenshot != null) {
                    status.text = "Connect a vision-capable AI provider in Settings."
                    output.text = "The selected screenshot remains on this device. " +
                        "Nothing was uploaded; connect Gemini or OpenAI and send again."
                    return@setOnClickListener
                }
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
            val allowedRoots = if (product == 1)
                "app/src/main/java/com/assistant/core/ or app/src/test/java/com/assistant/core/. " +
                    "Personal production edits must target existing, verified source files; protected " +
                    "controllers, permissions, voice-capture security, build, releases and installation are forbidden. "
            else
                "developer/src/main/java/com/jarvis/developer/ or developer/src/test/java/com/jarvis/developer/. "
            val instruction = if (selectedScreenshot != null)
                task + "\n\nAnalyze the attached failure screenshot. Identify visible messages, " +
                    "likely causes, next diagnostic checks, and any source evidence required. " +
                    "This is advice only: do not output a code patch or claim a repair."
            else if (!wantsPatch) task else task +
                "\n\nCODE PATCH MODE (proposal only, never execute): Return ONLY a JSON object with summary (string) and edits (array of path and content strings). " +
                "Every edit must be a complete replacement Kotlin or Java source file under " + allowedRoots +
                "Modify only exact eligible paths and existing contents from the verified repository context. " +
                "Do not modify protected components, build configuration, permissions, credentials, signing, or releases. " +
                "Split work into further requests when it exceeds the provider response budget. " +
                "Explicit user approval is required separately. No Markdown fences."
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
                    val text = if (selectedScreenshot == null)
                        services.ask(providerName, secret, modelName, instruction, run.cancellation, context)
                    else services.askScreenshot(providerName, secret, modelName, instruction,
                        run.cancellation, context, selectedScreenshot)
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
                            require(PatchEngine.allowedPath(path, productTarget)) {
                                "Code proposal contains an out-of-scope or protected path for the selected application."
                            }
                            require(productTarget != PatchEngine.Target.PERSONAL ||
                                !path.startsWith("app/src/main/") ||
                                path in context.paths) {
                                "Personal production changes require existing verified source."
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
                                val staged = stageReview(task, scope.first, scope.second, scope.third, productTarget)
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
                            if (scope == null) status.text = if (saved != ProjectGenerationStore.Result.SAVED)
                                activity.getString(R.string.project_generation_response_unsaved)
                                else if (selectedScreenshot != null) {
                                    runCatching { onScreenshotSent(selectedScreenshot) }
                                    "Screenshot analyzed with $providerName. No source changes or approval."
                                }
                                else if (brainstorming)
                                    "Ideas received. Choose one and say 'Implement [feature] in " +
                                        (if (product == 1) "Personal Jarvis" else "Jarvis Developer") +
                                        "' for a source-verified code proposal. Nothing was changed."
                                else activity.getString(R.string.project_generation_received, providerName)
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
