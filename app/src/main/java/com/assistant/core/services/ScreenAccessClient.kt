package com.assistant.core.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class ScreenAccessClient(private val context: Context) {
    private fun call(method: String, text: String? = null) = context.contentResolver.call(
        Uri.parse("content://${context.packageName}.screen-access"), method, text, null)
    fun connected(): Boolean = runCatching { call("connected")?.getBoolean("connected") == true }.getOrDefault(false)
    data class TaskState(val active: Boolean = false, val awaitingInput: Boolean = false, val contextual: Boolean = false)
    fun taskState(): TaskState = runCatching { call("task_state")?.let {
        TaskState(it.getBoolean("active"), it.getBoolean("awaiting_input"), it.getBoolean("contextual"))
    } ?: TaskState() }.getOrDefault(TaskState())
    fun accepts(text: String) = ScreenSession.recognizes(text) ||
        (ScreenTaskCommands.contextual(text) != null && taskState().contextual)
    fun discard() { runCatching { call("discard") } }
    fun reply(text: String): String {
        if (!accepts(text)) return "No supported screen command was requested."
        if (text.length > 400) { discard(); return "Keep screen requests short and bounded. " + ScreenTaskCommands.help() }
        val command = ScreenControls.parse(text)
        if (command?.kind == ScreenControls.Kind.SETUP) {
            discard()
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra("android.intent.extra.COMPONENT_NAME",
                ComponentName(context, JarvisAccessibilityService::class.java).flattenToString())
            val opened = runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
                .getOrElse { runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) }
            return (if (opened) "Requested Android Accessibility settings. " else "Open Android Accessibility settings manually. ") + ScreenControls.setup()
        }
        return runCatching { call("command", text)?.getString("reply") ?: error("Bridge unavailable") }
            .getOrElse { "The private Accessibility bridge could not verify this request. Check screen control status and Android Accessibility access before trying again; no automatic retry was made." }
    }
}
