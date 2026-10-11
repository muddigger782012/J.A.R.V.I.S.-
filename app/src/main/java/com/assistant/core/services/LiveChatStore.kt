package com.assistant.core.services

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic files let the UI and :wake process share state without stale preference caches. */
object LiveChatStore {
    private fun read(context: Context, name: String) = runCatching {
        String(AtomicFile(File(context.filesDir, name)).readFully(), Charsets.UTF_8)
    }.getOrNull()
    private fun write(context: Context, name: String, text: String) {
        val file = AtomicFile(File(context.filesDir, name))
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun state(context: Context): JSONObject {
        val state = runCatching { JSONObject(read(context, "live-chat-state.json") ?: "{}") }.getOrDefault(JSONObject())
        if (System.currentTimeMillis() - state.optLong("updated") > 180000L) state.put("active", false)
        return state
    }
    fun state(context: Context, active: Boolean, phase: VoiceInteractionState, message: String) {
        write(context, "live-chat-state.json", JSONObject().put("active", active).put("phase", phase.name)
            .put("message", message).put("updated", System.currentTimeMillis()).toString())
    }
    fun messages(context: Context) = runCatching { JSONArray(read(context, "live-chat-messages.json") ?: "[]") }.getOrDefault(JSONArray())
    fun append(context: Context, conversation: String, text: String) {
        val entries = messages(context)
        entries.put(JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("conversation", conversation).put("text", text))
        while (entries.length() > 2000) entries.remove(0)
        write(context, "live-chat-messages.json", entries.toString())
    }
}
