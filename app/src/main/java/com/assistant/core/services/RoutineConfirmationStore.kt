package com.assistant.core.services

import android.content.Context
import com.assistant.core.learning.ApprovedRoutineRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Shared next-turn routine approval. Reviewing or saving never executes its steps. */
class RoutineConfirmationStore internal constructor(
    private val read: () -> String?, private val write: (String?) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences("jarvis_routine_confirmation", Context.MODE_PRIVATE).getString("pending", null) },
        { value ->
            val editor = context.applicationContext.getSharedPreferences("jarvis_routine_confirmation", Context.MODE_PRIVATE).edit()
            if (value == null) editor.remove("pending") else editor.putString("pending", value)
            editor.commit()
        }
    )
    data class Resolution(val routine: ApprovedRoutineRegistry.Routine, val approved: Boolean)
    fun propose(routine: ApprovedRoutineRegistry.Routine): Boolean = synchronized(lock) {
        val lesson = FallbackIntentLesson("CREATE_ROUTINE", .95, mapOf("phrase" to routine.phrase, "steps" to routine.actions.joinToString(",")), learnable = true)
        if (RoutineProposal.fromLesson(lesson) != routine) return@synchronized false
        runCatching { write(JSONObject().put("phrase", routine.phrase).put("steps", JSONArray(routine.actions)).put("created", now()).toString()) }.getOrDefault(false)
    }
    fun consume(input: String): Resolution? = synchronized(lock) {
        val raw = read() ?: return@synchronized null
        if (!write(null)) return@synchronized null
        val routine = runCatching {
            val json = JSONObject(raw)
            if (now() - json.getLong("created") !in 0..120_000) return@runCatching null
            val steps = json.getJSONArray("steps")
            RoutineProposal.fromLesson(FallbackIntentLesson("CREATE_ROUTINE", .95,
                mapOf("phrase" to json.getString("phrase"), "steps" to (0 until steps.length()).joinToString(",") { steps.getString(it) }), learnable = true))
        }.getOrNull() ?: return@synchronized null
        when (input.lowercase(Locale.ROOT).trim().trimEnd('.', '?', '!').trim()) {
            "confirm routine" -> Resolution(routine, true)
            "cancel routine" -> Resolution(routine, false)
            else -> null
        }
    }
    companion object { private val lock = Any() }
}
