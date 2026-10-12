package com.assistant.core.services

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

object AssistantPhrasePreferences {
    fun load(context: Context) = runCatching {
        AssistantPhrases.fromJson(JSONObject(String(AtomicFile(File(context.filesDir, "assistant-phrases.json")).readFully(), Charsets.UTF_8)))
    }.getOrDefault(AssistantPhrases())
    fun save(context: Context, phrases: AssistantPhrases) {
        require(phrases.introduction.length <= 500 && phrases.wakeReplies.length <= 1000)
        val file = AtomicFile(File(context.filesDir, "assistant-phrases.json"))
        val stream = file.startWrite()
        try { stream.write(phrases.toJson().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
