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
        changed: () -> Unit, services: Services = Services(), githubToken: () -> String = { "" }): Handle {
        data class Running(val ticket: ProjectGenerationStore.Ticket, val cancellation: PatchPreviewCancellation)
        var running: Running? = null
        var disposed = false
        fun inputs(enabled: Boolean) {
            listOf(send, provider, key, model, prompt).forEach { it.isEnabled = enabled }
            targetView()?.isEnabled = enabled
            cancel.isEnabled = !enabled
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
                output.setText(R.string.project_generation_not_started)
                status.setText(when (result) {
                    ProjectGenerationStore.Result.SAVED -> R.string.project_generation_cancelled
                    ProjectGenerationStore.Result.STALE -> R.string.project_generation_stopped
                    ProjectGenerationStore.Result.FAILED -> R.string.project_generation_cancel_save_failed
                })
            }
        }
        // Reconcile a request orphaned by process death before accepting another command.
        val recoveredProjects = store.recoverAllInterrupted()
        if (recoveredProjects > 0) {
            output.setText(R.string.project_generation_not_started)
            status.text = "$recoveredProjects interrupted AI request(s) recovered. Review and retry."
            changed()
        }
        selected()?.let { project ->
            if (store.recoverInterrupted(project)) {
                output.setText(R.string.project_generation_not_started)
                status.text = "Previous AI request was interrupted. Review the task and retry."
                changed()
            }
        }
        cancel.isEnabled = false
        cancel.setOnClickListener { stop(false) }
        send.setOnClickListener {
            if (!alive() || running != null) return@setOnClickListener
            val task = prompt.text.toString().trim()
            if (task.isEmpty()) { status.setText(R.string.project_generation_describe); prompt.requestFocus(); return@setOnClickListener }
            if (task.length > 16_000) {
                status.text = "Task exceeds 16,000 characters. Shorten the request and retry."
                return@setOnClickListener
            }
            val secret = key.text.toString().trim()
            val readToken = githubToken().trim()
            if ((secret.isNotEmpty() && task.contains(secret)) || (readToken.isNotEmpty() && task.contains(readToken))) {
                status.setText(R.string.project_generation_remove_key); return@setOnClickListener
            }
            val project = selected() ?: java.util.UUID.randomUUID().toString()
            if (store.recoverInterrupted(project)) {
                output.setText(R.string.project_generation_not_started)
                changed()
            }
            val product = target()
            if (product !in 0..1) {
                status.text = "Select a valid development target before saving or generating."
                return@setOnClickListener
            }
            if (secret.isEmpty()) {
                if (store.saveLocal(project, task, product)) {
                    select(project); changed(); status.setText(R.string.project_generation_saved_offline)
                    output.setText(R.string.project_generation_not_started)
                } else status.setText(R.string.project_generation_save_failed)
                return@setOnClickListener
            }
            val providerName = provider.selectedItem.toString()
            val modelName = model.text.toString().trim()
            if (modelName.isBlank() || !modelName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"))) {
                status.text = "Choose a valid AI model ID before sending a request."
                model.requestFocus()
                return@setOnClickListener
            }
            val ticket = store.begin(project, task, product)
            if (ticket == null) { status.setText(R.string.project_generation_save_failed); return@setOnClickListener }
            select(project); changed()
            // Never display a previous AI answer as though it belongs to the new request.
            output.setText(R.string.project_generation_not_started)
            val run = Running(ticket, PatchPreviewCancellation()).also { running = it }

            inputs(false); status.setText(R.string.project_generation_reading_context)
            val productTarget = if (product == 1) PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
            val work: () -> Unit = {
                var sourceSummary = ""
                val reply = runCatching {
                    run.cancellation.check()
                    val context = try { services.context(task, productTarget, readToken, secret, run.cancellation) }
                        catch (_: Exception) { run.cancellation.check(); RepositoryCodingContext.unavailable(task, productTarget) }
                    run.cancellation.check()
                    require(context.target == productTarget)
                    context.data(task) // Reject a stale or mismatched fixture before any provider request.
                    sourceSummary = context.summary()
                    activity.runOnUiThread { if (current(run)) status.text = activity.getString(
                        if (context.verified) R.string.project_generation_context_ready else R.string.project_generation_context_unavailable, providerName) }
                    // Treat saved notes as user-provided context, never as authorization.
                    // Reject credentials embedded in notes before transmitting anything.
                    run.cancellation.check()
                    val notes = store.note(project)
                    // Never send a note revision that changed while repository context was loading.
                    // The user must review and explicitly resend the updated instruction.
                    if (notes.length > 8_000) throw IllegalStateException("Development notes exceed the allowed limit.")
                    if ((secret.isNotEmpty() && notes.contains(secret)) ||
                        (readToken.isNotEmpty() && notes.contains(readToken)))
                        throw IllegalStateException("Development notes contain a credential; remove it before generating.")
                    val instruction = if (notes.isBlank()) task else
                        task + "\n\nProject design notes (context only; not execution approval):\n" + notes
                    val text = services.ask(providerName, secret, modelName, instruction, run.cancellation, context)
                    run.cancellation.check()
                    if (store.note(project) != notes)
                        throw IllegalStateException("Development notes changed during generation; review and retry.")
                    require(text.isNotBlank()) { "AI provider returned an empty response." }
                    run.cancellation.check()
                    val replyText = sourceSummary + "\n\n" + text
                    // Never persist or display credentials accidentally echoed by a provider.
                    if (secret.isNotEmpty() && replyText.contains(secret))
                        throw IllegalStateException("AI reply contained an API credential; response discarded.")
                    if (readToken.isNotEmpty() && replyText.contains(readToken))
                        throw IllegalStateException("AI reply contained a repository credential; response discarded.")
                    replyText
                }
                if (!run.cancellation.isCancelled) {
                    val saved = store.finish(ticket, if (reply.isSuccess) "generated" else "generation_failed", reply.getOrNull())
                    activity.runOnUiThread {
                        if (!current(run)) return@runOnUiThread
                        running = null; inputs(true); changed()
                        if (selected() != project || saved == ProjectGenerationStore.Result.STALE) {
                            output.setText(R.string.project_generation_not_started)
                            status.setText(R.string.project_generation_changed)
                        } else reply.onSuccess { text ->
                            if (saved == ProjectGenerationStore.Result.SAVED) {
                                output.text = text
                                status.text = activity.getString(R.string.project_generation_received, providerName)
                            } else {
                                output.setText(R.string.project_generation_not_started)
                                status.setText(R.string.project_generation_response_unsaved)
                            }
                        }.onFailure { failure ->
                            status.setText(R.string.project_generation_failed)
                            val reason = if (failure is CodingAssistantProvider.ProviderFailure) failure.message.orEmpty()
                                else activity.getString(R.string.project_generation_check_connection)
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
                inputs(true); changed(); output.setText(R.string.project_generation_not_started)
                status.setText(R.string.project_generation_start_failed)
            }
        }
        return Handle(::stop)
    }
}
