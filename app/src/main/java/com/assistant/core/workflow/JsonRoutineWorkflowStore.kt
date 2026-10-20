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
        val raw = read() ?: "[]"
        require(raw.length <= MAX_STORAGE_CHARS) { "Workflow journal is oversized." }
        val entries = JSONArray(raw)
        require(entries.length() <= RoutineWorkflowLimits.MAX_RETAINED_WORKFLOWS) { "Too many stored workflows." }
        return (0 until entries.length()).map { index ->
            val json = entries.getJSONObject(index)
            require(json.getInt("version") == 1) { "Unsupported workflow journal version." }
            val keys = json.getJSONArray("requestKeys")
            val steps = json.getJSONArray("steps")
            require(keys.length() in 1..RoutineWorkflowLimits.MAX_REQUEST_KEYS_PER_WORKFLOW) { "Invalid stored invocation keys." }
            val requestKeys = (0 until keys.length()).map { keys.getString(it) }
            require(requestKeys.distinct().size == requestKeys.size && requestKeys.all { it.isNotBlank() && it.length <= 200 })
            require(json.getString("id").isNotBlank() && json.getString("id").length <= 200)
            require(json.getString("phrase").length in 3..120)
            require(steps.length() in 1..3) { "Invalid stored workflow step count." }
            RoutineWorkflowCheckpoint(
                id = json.getString("id"),
                requestKeys = requestKeys.toSet(),
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
        require(retained.size <= RoutineWorkflowLimits.MAX_RETAINED_WORKFLOWS)
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
        val serialized = output.toString()
        require(serialized.length <= MAX_STORAGE_CHARS)
        // Validate the same bounded structure before committing it to the production journal.
        JsonRoutineWorkflowStore({ serialized }, { false }).load()
        write(serialized)
    }.getOrDefault(false)

    private companion object {
        const val MAX_STORAGE_CHARS = 2_000_000
    }
}
