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
        val context: (String, PatchEngine.Target, String, String, PatchPreviewCancellation) -> RepositoryCodingContext.Context = RepositoryIntelligence::codingContext
    )
    class Handle internal constructor(private val stop: (Boolean) -> Unit) {
        fun cancel() = stop(false)
        fun dispose() = stop(true)
    }
    fun attach(activity: Activity, send: Button, cancel: Button, provider: Spinner, key: EditText, model: EditText,
        prompt: EditText, output: TextView, status: TextView, target: () -> Int, targetView: () -> View?,
        selected: () -> String?, select: (String) -> Unit, store: ProjectGenerationStore,
        changed: () -> Unit, services: Services = Services(), githubToken: () -> String = { "" }, forget: Button? = null,
        requestPatch: () -> Boolean = { false }): Handle {
        data class Running(val ticket: ProjectGenerationStore.Ticket, val cancellation: PatchPreviewCancellation)
        var running: Running? = null
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
            if (!alive() || running != null) return@setOnClickListener
            val task = prompt.text.toString().trim()
            if (task.isEmpty()) { status.setText(R.string.project_generation_describe); prompt.requestFocus(); return@setOnClickListener }
            val secret = key.text.toString().trim()
            val readToken = githubToken().trim()
            if ((secret.isNotEmpty() && task.contains(secret)) || (readToken.isNotEmpty() && task.contains(readToken))) {
                status.setText(R.string.project_generation_remove_key); return@setOnClickListener
            }
            val wantsPatch = requestPatch()
            if (wantsPatch && target() != 0) {
                status.text = "Code patch proposals are limited to the Developer target."
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
                "Every edit must be a complete NEW Kotlin or Java source file under developer/src/main/java/com/jarvis/developer/ or developer/src/test/java/com/jarvis/developer/. " +
                "Do not modify existing files, protected components, build configuration, permissions, credentials, or releases. Maximum 8 files. Explicit user approval is required separately. No Markdown fences."
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
                        require(edits.length() in 1..8) { "Code proposal must contain 1 to 8 files." }
                        val seen = mutableSetOf<String>()
                        for (i in 0 until edits.length()) {
                            val edit = edits.optJSONObject(i)
                                ?: throw IllegalArgumentException("Invalid code proposal edit.")
                            val path = edit.optString("path")
                            require(path.startsWith("developer/src/main/java/com/jarvis/developer/") ||
                                path.startsWith("developer/src/test/java/com/jarvis/developer/")) {
                                "Code proposal contains an out-of-scope path."
                            }
                            require(!path.contains("..") && !path.contains(92.toChar()) &&
                                (path.endsWith(".kt") || path.endsWith(".java")) && seen.add(path)) {
                                "Code proposal contains an unsafe or duplicate path."
                            }
                            require(edit.optString("content").isNotBlank()) { "Code proposal contains an empty file." }
                        }
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
                            output.text = text
                            status.text = if (saved == ProjectGenerationStore.Result.SAVED)
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
