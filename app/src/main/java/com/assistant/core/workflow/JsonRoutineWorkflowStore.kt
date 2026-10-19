package com.assistant.core.workflow

import com.assistant.core.services.LearnableDeviceAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable storage through injected local I/O, e.g. SharedPreferences.getString and commit.
 * A malformed or unreadable journal throws on load, rather than silently losing deduplication.
 * Writes must be atomic; use commit, not asynchronous apply, for the write-ahead boundary.
 * One live WorkflowEngine must own this journal (including voice and typed-chat dispatch).
 */
class JsonRoutineWorkflowStore(
    private val read: () -> String?,
    private val write: (String) -> Boolean
) : RoutineWorkflowStore {
    @Synchronized override fun load(): List<RoutineWorkflowCheckpoint> {
        val entries = JSONArray(read() ?: "[]")
        return (0 until entries.length()).map { index ->
            val json = entries.getJSONObject(index)
            require(json.getInt("version") == 1) { "Unsupported workflow journal version." }
            val keys = json.getJSONArray("requestKeys")
            val steps = json.getJSONArray("steps")
            require(steps.length() in 1..3) { "Invalid stored workflow step count." }
            RoutineWorkflowCheckpoint(
                id = json.getString("id"),
                requestKeys = (0 until keys.length()).map { keys.getString(it) }.toSet(),
                phrase = json.getString("phrase"),
                steps = (0 until steps.length()).map { stepIndex ->
                    val state = steps.getJSONObject(stepIndex)
                    val target = if (state.has("target")) state.getJSONObject("target").let { action ->
                        val parameters = action.getJSONObject("parameters")
                        LearnableDeviceAction(action.getString("capability"), parameters.keys().asSequence().associateWith { parameters.getString(it) })
                    } else null
                    RoutineStepCheckpoint(
                        step = RoutineWorkflowStep(state.getInt("index"), state.getString("action"), target),
                        status = RoutineStepStatus.valueOf(state.getString("status")),
                        attempts = state.getInt("attempts"),
                        detail = state.getString("detail")
                    )
                },
                status = RoutineWorkflowStatus.valueOf(json.getString("status")),
                approved = json.getBoolean("approved"),
                cancellationRequested = json.getBoolean("cancelled"),
                detail = json.getString("detail")
            )
        }
    }

    @Synchronized override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean = runCatching {
        val retained = load().filter { it.id != checkpoint.id } + checkpoint
        val output = JSONArray()
        retained.forEach { entry ->
            val steps = JSONArray()
            entry.steps.forEach { state ->
                val json = JSONObject().put("index", state.step.index).put("action", state.step.actionId)
                    .put("status", state.status.name).put("attempts", state.attempts).put("detail", state.detail)
                state.step.target?.let { target ->
                    json.put("target", JSONObject().put("capability", target.capabilityId).put("parameters", JSONObject(target.parameters)))
                }
                steps.put(json)
            }
            output.put(JSONObject().put("version", 1).put("id", entry.id).put("phrase", entry.phrase)
                .put("requestKeys", JSONArray(entry.requestKeys.toList())).put("steps", steps)
                .put("status", entry.status.name).put("approved", entry.approved)
                .put("cancelled", entry.cancellationRequested).put("detail", entry.detail))
        }
        write(output.toString())
    }.getOrDefault(false)
}
