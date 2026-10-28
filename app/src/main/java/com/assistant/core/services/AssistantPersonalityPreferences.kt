package com.assistant.core.services

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Read on each turn, including from the foreground voice process. */
object AssistantPersonalityPreferences {
    fun instructions(context: Context): String = load(context).instructions(solutionFirst = isPersonal(context.packageName))

    fun behaviorProfile(context: Context): JSONObject = load(context).toJson().put("solution_first", isPersonal(context.packageName))

    internal fun isPersonal(packageName: String) = packageName == "com.assistant.core"

    fun load(context: Context) = runCatching {
        AssistantPersonality.fromJson(JSONObject(String(AtomicFile(File(context.filesDir, "assistant-personality.json")).readFully(), Charsets.UTF_8)))
    }.getOrDefault(AssistantPersonality())

    fun save(context: Context, value: AssistantPersonality) {
        val file = AtomicFile(File(context.filesDir, "assistant-personality.json"))
        val stream = file.startWrite()
        try {
            stream.write(AssistantPersonality.fromJson(value.toJson()).toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
