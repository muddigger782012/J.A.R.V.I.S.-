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
        changed: () -> Unit, services: Services = Services(), githubToken: () -> String = { "" }, forget: Button? = null): Handle {
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
            val lookupTask = discussion.lookupTask(task)
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
                    val context = source.withDiscussion(task, discussion)
                    require(context.target == productTarget)
                    context.data(task) // Reject a stale or mismatched fixture before any provider request.
                    sourceSummary = context.summary()
                    activity.runOnUiThread { if (current(run)) status.text = activity.getString(
                        if (context.verified) R.string.project_generation_context_ready else R.string.project_generation_context_unavailable, providerName) }
                    val text = services.ask(providerName, secret, modelName, task, run.cancellation, context)
                    run.cancellation.check()
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
                inputs(true); changed(); status.setText(R.string.project_generation_start_failed)
            }
        }
        return Handle(::stop)
    }
}
