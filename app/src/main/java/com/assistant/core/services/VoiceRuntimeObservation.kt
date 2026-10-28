package com.assistant.core.services

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Fixed-state heartbeat shared with the :wake process. No transcript or audio crosses this boundary. */
object VoiceRuntimeObservation {
    enum class Owner { LOCAL, FOREGROUND }
    data class Reading(val wake: VoiceAssistantService.WakeState, val phase: VoiceInteractionState,
        val timings: List<VoiceSessionDiagnostics.Event> = emptyList())
    private fun file(context: Context, owner: Owner) = AtomicFile(File(context.filesDir, "voice-runtime-${owner.name.lowercase()}.json"))
    fun write(context: Context, owner: Owner, wake: VoiceAssistantService.WakeState, phase: VoiceInteractionState) {
        runCatching {
            val store = file(context, owner)
            val bytes = JSONObject().put("wake", wake.name).put("phase", phase.name)
                .put("elapsed", SystemClock.elapsedRealtime()).put("pid", android.os.Process.myPid())
                .put("timings", org.json.JSONArray(VoiceSessionDiagnostics.runtimeSnapshot().map {
                    JSONObject().put("stage", it.stage.name).put("since", it.sinceStartMillis).put("delta", it.sincePreviousMillis)
                        .put("error", it.errorCode ?: JSONObject.NULL)
                })).toString().toByteArray()
            val output = store.startWrite()
            try { output.write(bytes); store.finishWrite(output) } catch (failure: Exception) { store.failWrite(output); throw failure }
        }
    }
    fun read(context: Context, owner: Owner): Reading? = runCatching {
        val bytes = file(context, owner).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= 4096); output.write(buffer, 0, size) }
            output.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(SystemClock.elapsedRealtime() - json.getLong("elapsed") in 0..45_000)
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        require(manager.runningAppProcesses.orEmpty().any { it.pid == json.getInt("pid") && it.uid == android.os.Process.myUid() })
        val array = json.optJSONArray("timings") ?: org.json.JSONArray()
        require(array.length() <= 16)
        val timings = (0 until array.length()).map {
            val event = array.getJSONObject(it)
            val since = event.getLong("since"); val delta = event.getLong("delta")
            require(since >= 0 && delta >= 0 && delta <= since)
            VoiceSessionDiagnostics.Event(VoiceSessionDiagnostics.Stage.valueOf(event.getString("stage")), since, delta,
                if (event.isNull("error")) null else event.getInt("error").also { code -> require(code in 1..100) })
        }
        Reading(VoiceAssistantService.WakeState.valueOf(json.getString("wake")), VoiceInteractionState.valueOf(json.getString("phase")), timings)
    }.getOrNull()
}
