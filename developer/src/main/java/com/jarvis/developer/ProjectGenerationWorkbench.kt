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
        val ask: (String, String, String, String, PatchPreviewCancellation) -> String = { provider, key, model, task, cancellation ->
            CodingAssistantProvider.ask(provider, key, model, task, cancellation)
        }
    )
    class Handle internal constructor(private val stop: (Boolean) -> Unit) {
        fun cancel() = stop(false)
        fun dispose() = stop(true)
    }
    fun attach(activity: Activity, send: Button, cancel: Button, provider: Spinner, key: EditText, model: EditText,
        prompt: EditText, output: TextView, status: TextView, target: () -> Int, targetView: () -> View?,
        selected: () -> String?, select: (String) -> Unit, store: ProjectGenerationStore,
        changed: () -> Unit, services: Services = Services()): Handle {
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
                status.setText(when (result) {
                    ProjectGenerationStore.Result.SAVED -> R.string.project_generation_cancelled
                    ProjectGenerationStore.Result.STALE -> R.string.project_generation_stopped
                    ProjectGenerationStore.Result.FAILED -> R.string.project_generation_cancel_save_failed
                })
            }
        }
        cancel.isEnabled = false
        cancel.setOnClickListener { stop(false) }
        send.setOnClickListener {
            if (!alive() || running != null) return@setOnClickListener
            val task = prompt.text.toString().trim()
            if (task.isEmpty()) { status.setText(R.string.project_generation_describe); prompt.requestFocus(); return@setOnClickListener }
            val secret = key.text.toString().trim()
            if (secret.isNotEmpty() && task.contains(secret)) {
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
            val ticket = store.begin(project, task, product)
            if (ticket == null) { status.setText(R.string.project_generation_save_failed); return@setOnClickListener }
            select(project); changed()
            val run = Running(ticket, PatchPreviewCancellation()).also { running = it }
            val providerName = provider.selectedItem.toString(); val modelName = model.text.toString().trim()
            inputs(false); status.text = activity.getString(R.string.project_generation_connecting, providerName)
            val work: () -> Unit = {
                val reply = runCatching {
                    run.cancellation.check()
                    services.ask(providerName, secret, modelName, task, run.cancellation).also { run.cancellation.check() }
                }
                if (!run.cancellation.isCancelled) {
                    val saved = store.finish(ticket, if (reply.isSuccess) "generated" else "generation_failed", reply.getOrNull())
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
                            output.text = reason + "\n" + activity.getString(R.string.project_generation_description_kept)
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
