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
    private data class Journal(val entries: List<RoutineWorkflowCheckpoint>, val retired: Set<String>)

    private fun journal(): Journal {
        val raw = read() ?: "[]"
        require(raw.length <= MAX_STORAGE_CHARS) { "Workflow journal is oversized." }
        // Read installed version-one arrays without rewriting them on inspection.
        val root = if (raw.trimStart().startsWith("[")) null else JSONObject(raw)
        if (root != null) require(root.getInt("version") == 2) { "Unsupported workflow journal version." }
        val retiredRows = root?.getJSONArray("retired") ?: JSONArray()
        require(retiredRows.length() <= RoutineWorkflowLimits.MAX_RETIRED_FINGERPRINTS)
        val retired = (0 until retiredRows.length()).map { retiredRows.getString(it) }
        require(retired.distinct().size == retired.size && retired.all { Regex("[0-9a-f]{64}").matches(it) })
        val retiredSet = retired.toSet()
        val entries = root?.getJSONArray("entries") ?: JSONArray(raw)
        require(entries.length() <= RoutineWorkflowLimits.MAX_RETAINED_WORKFLOWS) { "Too many stored workflows." }
        val checkpoints = (0 until entries.length()).map { index ->
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
        require(checkpoints.distinctBy { it.id }.size == checkpoints.size)
        val keys = checkpoints.flatMap { it.requestKeys }
        require(keys.distinct().size == keys.size)
        require(checkpoints.none { RoutineHistory.fingerprints(it).any { key -> key in retiredSet } })
        return Journal(checkpoints, retiredSet)
    }

    @Synchronized override fun load(): List<RoutineWorkflowCheckpoint> = journal().entries
    @Synchronized override fun isRetiredRequest(key: String) = RoutineHistory.requestFingerprint(key) in journal().retired
    @Synchronized override fun retiredFingerprintCount() = journal().retired.size

    @Synchronized override fun save(checkpoint: RoutineWorkflowCheckpoint): Boolean = runCatching {
        val saved = journal()
        require(RoutineHistory.fingerprints(checkpoint).none { it in saved.retired })
        persist(saved.copy(entries = saved.entries.filter { it.id != checkpoint.id } + checkpoint))
    }.getOrDefault(false)

    @Synchronized override fun archiveCompleted(reviewed: List<RoutineWorkflowCheckpoint>): Boolean = runCatching {
        val saved = journal()
        require(reviewed.isNotEmpty() && RoutineHistory.candidates(saved.entries) == reviewed)
        val retired = saved.retired + reviewed.flatMap(RoutineHistory::fingerprints)
        require(retired.size <= RoutineWorkflowLimits.MAX_RETIRED_FINGERPRINTS)
        persist(Journal(saved.entries.filterNot { entry -> reviewed.any { it.id == entry.id } }, retired))
    }.getOrDefault(false)

    private fun persist(journal: Journal): Boolean {
        val retained = journal.entries
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
        val serialized = if (journal.retired.isEmpty()) output.toString() else JSONObject().put("version", 2)
            .put("entries", output).put("retired", JSONArray(journal.retired.toList())).toString()
        require(serialized.length <= MAX_STORAGE_CHARS)
        // Validate the same bounded structure before committing it to the production journal.
        JsonRoutineWorkflowStore({ serialized }, { false }).load()
        return write(serialized)
    }

    private companion object {
        const val MAX_STORAGE_CHARS = 2_000_000
    }
}
