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
    fun currentConversation(context: Context) = read(context, "live-chat-conversation.txt").orEmpty()
    fun currentConversation(context: Context, id: String) = write(context, "live-chat-conversation.txt", id)
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
    fun lastReply(context: Context) = runCatching { JSONObject(read(context, "live-chat-reply.json") ?: "{}") }.getOrDefault(JSONObject())
    fun lastReply(context: Context, reply: JSONObject) = write(context, "live-chat-reply.json", reply.toString())
    fun dismissedReply(context: Context) = read(context, "live-chat-dismissed.txt").orEmpty()
    fun dismissReply(context: Context, id: String) = write(context, "live-chat-dismissed.txt", id)
    fun append(context: Context, conversation: String, text: String, session: String = ""): JSONObject {
        val entries = messages(context)
        val entry = JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("conversation", conversation)
            .put("text", text).put("session", session).put("createdAt", System.currentTimeMillis())
        entries.put(entry)
        while (entries.length() > 2000) entries.remove(0)
        write(context, "live-chat-messages.json", entries.toString())
        return entry
    }
}
