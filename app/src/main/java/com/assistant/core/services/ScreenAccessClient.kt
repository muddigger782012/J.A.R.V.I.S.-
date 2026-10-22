package com.assistant.core.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class ScreenAccessClient(private val context: Context) {
    private fun call(method: String, text: String? = null, extras: android.os.Bundle? = null) = context.contentResolver.call(
        Uri.parse("content://${context.packageName}.screen-access"), method, text, extras)
    fun connected(): Boolean = runCatching { call("connected")?.getBoolean("connected") == true }.getOrDefault(false)
    data class TaskState(val active: Boolean = false, val awaitingInput: Boolean = false, val contextual: Boolean = false, val contextKey: String = "")
    fun taskState(): TaskState = runCatching { call("task_state")?.let {
        TaskState(it.getBoolean("active"), it.getBoolean("awaiting_input"), it.getBoolean("contextual"), it.getString("context_key").orEmpty())
    } ?: TaskState() }.getOrDefault(TaskState())
    fun accepts(text: String) = ScreenSession.recognizes(text) ||
        (ScreenTaskCommands.contextual(text) != null && taskState().contextual)
    data class Prepared internal constructor(val text: String, internal val reviewToken: String?, internal val taskKey: String? = null)
    fun prepare(text: String): Prepared {
        val confirm = ScreenControls.parse(text)?.kind == ScreenControls.Kind.CONFIRM ||
            ScreenTaskCommands.parse(text)?.kind == ScreenTaskCommands.Kind.CONFIRM_STEP
        val token = if (confirm) runCatching { call("review_state")?.getString("token").orEmpty() }.getOrDefault("") else null
        val continues = ScreenTaskCommands.parse(text, active = true)?.kind == ScreenTaskCommands.Kind.CONTINUE
        val task = if (continues) runCatching { call("task_state")?.getString("task_key").orEmpty() }.getOrDefault("") else null
        return Prepared(text, token, task)
    }
    /** Capture before microphone input: a delayed utterance must not approve a different review. */
    fun voiceScope(): String = taskState().contextKey + ":" + runCatching { call("review_state")?.getString("token").orEmpty() }.getOrDefault("")
    data class Response(val text: String, val contextKey: String = "")
    fun discard() { runCatching { call("discard") } }
    /** Notification cancellation is bound to its original task, never a later replacement. */
    fun cancelTask(taskKey: String) { runCatching { call("cancel_task", taskKey) } }
    fun reply(text: String): String = reply(prepare(text))
    fun reply(prepared: Prepared): String = replyWithContext(prepared).text
    fun replyWithContext(prepared: Prepared): Response {
        val text = prepared.text
        if (!accepts(text)) return Response("No supported screen command was requested.")
        if (text.length > 400) { discard(); return Response("Keep screen requests short and bounded. " + ScreenTaskCommands.help()) }
        val command = ScreenControls.parse(text)
        if (command?.kind == ScreenControls.Kind.SETUP) {
            discard()
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra("android.intent.extra.COMPONENT_NAME",
                ComponentName(context, JarvisAccessibilityService::class.java).flattenToString())
            val opened = runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                .getOrElse { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) }
            return Response((if (opened) "Requested Android Accessibility settings. " else "Open Android Accessibility settings manually. ") + ScreenControls.setup())
        }
        val extras = prepared.reviewToken?.let { token -> android.os.Bundle().apply { putString("review_token", token) } }
            ?: prepared.taskKey?.let { key -> android.os.Bundle().apply { putString("task_key", key) } }
        return runCatching {
            var response = call("command", text, extras) ?: error("Bridge unavailable")
            val step = response.getInt("accepted_step")
            val taskKey = response.getString("task_key").orEmpty()
            if (step > 0 && taskKey.isNotBlank() && android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                val observed = runCatching {
                    var next: android.os.Bundle? = null
                    for (delay in listOf(200L, 350L)) {
                        Thread.sleep(delay)
                        val scope = android.os.Bundle().apply { putString("task_key", taskKey) }
                        next = call("command", "continue screen task", scope) ?: error("Observation unavailable")
                        if (!next.getBoolean("observation_pending")) break
                    }
                    response = next ?: error("Observation unavailable")
                    response.getString("reply") ?: error("Observation unavailable")
                }.getOrElse { "The result could not be verified yet. Check the app, then continue the task to inspect again. No tap will be repeated." }
                Response("Android accepted step $step. $observed", response.getString("context_key").orEmpty())
            } else Response(response.getString("reply") ?: error("Bridge unavailable"), response.getString("context_key").orEmpty())
        }
            .getOrElse { Response("The private Accessibility bridge could not verify this request. Check screen control status and Android Accessibility access before trying again; no automatic retry was made.") }
    }
}
