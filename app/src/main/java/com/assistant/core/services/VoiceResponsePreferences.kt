package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.assistant.core.FontPreferences
import com.assistant.core.ThemePreferences
import org.json.JSONObject
import java.io.File

/** Read from disk per response: the microphone service lives in the :wake process. */
object VoiceResponsePreferences {
    const val ACTION_CHANGED = "com.assistant.core.action.RESPONSE_OPTIONS_CHANGED"
    private fun readJson(context: Context, name: String) = runCatching {
        JSONObject(String(AtomicFile(File(context.filesDir, name)).readFully(), Charsets.UTF_8))
    }.getOrDefault(JSONObject())
    private fun writeJson(context: Context, name: String, json: JSONObject) {
        val file = AtomicFile(File(context.filesDir, name))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun load(context: Context) = VoiceResponseOptions.fromJson(readJson(context, "voice-response-options.json"))
    fun save(context: Context, options: VoiceResponseOptions) {
        writeJson(context, "voice-response-options.json", options.toJson())
        notifyChanged(context)
    }
    fun notifyChanged(context: Context) = context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    fun appearance(context: Context) = readJson(context, "live-dialogue-appearance.json")
    fun captureAppearance(context: Context) {
        val snapshot = JSONObject().put("theme", ThemePreferences.selected(context))
            .put("fontPercent", FontPreferences.percent(context)).put("fontColor", FontPreferences.colorHex(context))
        if (snapshot.toString() != appearance(context).toString()) {
            runCatching { writeJson(context, "live-dialogue-appearance.json", snapshot); notifyChanged(context) }
        }
    }
}
