package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Per-installation Live Chat participation preferences. This file is re-read by
 * the separate :wake process on each utterance; cached SharedPreferences cannot
 * reliably deliver updates between the UI and microphone processes.
 */
data class LiveChatCompanionSettings(
    val style: String = "companion",
    val initiative: Int = 55,
    val interruptionsEnabled: Boolean = true,
    val contextEnabled: Boolean = true,
    val replyToOtherSpeakers: Boolean = false,
    val keepListeningUntilStopped: Boolean = true,
    val experimentalPcmStream: Boolean = false
) {
    fun normalized() = copy(
        style = style.takeIf { it in STYLES } ?: "companion",
        initiative = initiative.coerceIn(0, 100)
    )

    fun toJson() = JSONObject()
        .put("style", normalized().style)
        .put("initiative", initiative.coerceIn(0, 100))
        .put("interruptionsEnabled", interruptionsEnabled)
        .put("contextEnabled", contextEnabled)
        .put("replyToOtherSpeakers", replyToOtherSpeakers)
        .put("keepListeningUntilStopped", keepListeningUntilStopped)
        .put("experimentalPcmStream", experimentalPcmStream)

    companion object {
        private const val FILE_NAME = "ambient-companion-settings.json"
        const val ACTION_CHANGED = "com.assistant.core.action.AMBIENT_COMPANION_SETTINGS_CHANGED"
        val STYLES = setOf("quiet", "companion", "active")

        fun fromJson(json: JSONObject) = LiveChatCompanionSettings(
            style = json.optString("style", "companion"),
            initiative = json.optInt("initiative", 55),
            interruptionsEnabled = json.optBoolean("interruptionsEnabled", true),
            contextEnabled = json.optBoolean("contextEnabled", true),
            replyToOtherSpeakers = json.optBoolean("replyToOtherSpeakers", false),
            keepListeningUntilStopped = json.optBoolean("keepListeningUntilStopped", true),
            experimentalPcmStream = json.optBoolean("experimentalPcmStream", false)
        ).normalized()

        fun load(context: Context): LiveChatCompanionSettings {
            return runCatching {
                val raw = AtomicFile(File(context.filesDir, FILE_NAME)).readFully()
                fromJson(JSONObject(String(raw, Charsets.UTF_8)))
            }.getOrElse {
                // Respect the pre-existing Personal Jarvis configuration when
                // upgrading; do not silently overwrite its approved defaults.
                val old = context.getSharedPreferences("jarvis_live_companion", Context.MODE_PRIVATE)
                LiveChatCompanionSettings(
                    style = old.getString("style", "companion") ?: "companion",
                    initiative = old.getInt("initiative", 55),
                    interruptionsEnabled = old.getBoolean("interruptions_enabled", true),
                    contextEnabled = old.getBoolean("context_enabled", true)
                ).normalized()
            }
        }

        fun save(context: Context, settings: LiveChatCompanionSettings) {
            val file = AtomicFile(File(context.filesDir, FILE_NAME))
            val stream = file.startWrite()
            try {
                stream.write(settings.normalized().toJson().toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
        }
    }
}
