package com.assistant.core.services

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** A token acknowledgement from :wake proves its microphone was released before practice. */
object VoicePracticeMicrophone {
    fun acknowledge(context: Context, token: String, available: Boolean) {
        val file = AtomicFile(File(context.filesDir, "voice-practice-microphone.json"))
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("token", token).put("available", available).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun available(context: Context, token: String): Boolean? = runCatching {
        val state = JSONObject(String(AtomicFile(File(context.filesDir, "voice-practice-microphone.json")).readFully(), Charsets.UTF_8))
        if (state.optString("token") == token) state.optBoolean("available") else null
    }.getOrNull()
}
