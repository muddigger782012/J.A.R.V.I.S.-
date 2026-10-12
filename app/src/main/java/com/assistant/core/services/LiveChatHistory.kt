package com.assistant.core.services

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LiveChatHistory {
    data class Entry(val id: String, val text: String, val createdAt: Long)
    data class Session(val id: String, val conversation: String, val entries: List<Entry>) {
        val startedAt get() = entries.firstOrNull { it.createdAt > 0 }?.createdAt ?: 0L
    }

    fun sessions(log: JSONArray): List<Session> {
        val entries = linkedMapOf<String, MutableList<Entry>>()
        val conversations = mutableMapOf<String, String>()
        for (i in 0 until log.length()) {
            val row = log.optJSONObject(i) ?: continue
            val text = row.optString("text")
            if (text.isBlank()) continue
            val conversation = row.optString("conversation")
            val session = row.optString("session").ifBlank { "legacy:$conversation" }
            entries.getOrPut(session) { mutableListOf() }.add(Entry(row.optString("id"), text, row.optLong("createdAt")))
            conversations[session] = conversation
        }
        return entries.map { (id, rows) -> Session(id, conversations[id].orEmpty(), rows) }.reversed()
    }

    fun time(timestamp: Long): String = if (timestamp <= 0) "Time unavailable" else
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(timestamp))

    fun debugText(sessions: List<Session>, version: String, device: String, options: VoiceResponseOptions): String = buildString {
        appendLine("J.A.R.V.I.S. live chat debug record")
        appendLine("App: $version")
        appendLine("Device: $device")
        appendLine("Time zone: ${TimeZone.getDefault().id}")
        appendLine("Current response settings: regular speech=${options.regularSpeech}; live speech=${options.liveSpeech}; live dialogue=${options.liveDialogue}")
        appendLine("These are the current settings; historical settings were not recorded.")
        appendLine("Retained dialogue only; entries without timestamps predate timestamp logging.")
        for (session in sessions.asReversed()) {
            appendLine()
            appendLine("Session: ${session.id}")
            appendLine("Conversation: ${session.conversation}")
            for (entry in session.entries) appendLine("[${time(entry.createdAt)}] ${entry.text}")
        }
    }
}
