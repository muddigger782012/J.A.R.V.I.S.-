package com.jarvis.developer

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** Uses the existing project records. Request receipts authorize no source or release operation. */
internal class ProjectGenerationStore(private val preferences: SharedPreferences) {
    data class Ticket(val project: String, val request: String)
    enum class Result { SAVED, STALE, FAILED }

    fun saveLocal(id: String, task: String, target: Int): Boolean = synchronized(lock) {
        runCatching {
            val current = read(id)
            if (current.optString("state") == "generating") return@synchronized false
            write(id, base(id, task, target).put("state", "saved").apply {
                remove("generation_request"); remove("generation_process")
                remove("response"); remove("response_description"); remove("response_target")
            })
        }.getOrDefault(false)
    }

    fun begin(id: String, task: String, target: Int): Ticket? = synchronized(lock) {
        runCatching {
            val current = read(id)
            if (current.optString("state") == "generating") return@synchronized null
            val ticket = Ticket(id, UUID.randomUUID().toString())
            if (write(id, base(id, task, target).put("state", "generating")
                    .put("generation_request", ticket.request).put("generation_process", process).apply {
                        remove("response"); remove("response_description"); remove("response_target")
                    })) ticket else null
        }.getOrNull()
    }

    fun finish(ticket: Ticket, state: String, response: String? = null): Result = synchronized(lock) {
        require(state in setOf("generated", "generation_failed", "generation_cancelled", "generation_interrupted"))
        require((state == "generated") == (response != null))
        if (response != null && response.length > 256_000) {
            // Do not strand the workbench in generating when a provider exceeds the storage limit.
            val recorded = finish(ticket, "generation_failed")
            return@synchronized if (recorded == Result.STALE) Result.STALE else Result.FAILED
        }
        runCatching {
            val record = read(ticket.project)
            if (record.optString("state") != "generating" || record.optString("generation_request") != ticket.request ||
                record.optString("generation_process") != process)
                return@synchronized Result.STALE
            record.put("state", state).remove("generation_request")
            record.remove("generation_process")
            if (response != null) record.put("response", response)
                .put("response_description", record.getString("description")).put("response_target", record.getInt("target"))
            else {
                record.remove("response"); record.remove("response_description"); record.remove("response_target")
            }
            if (write(ticket.project, record)) Result.SAVED else Result.FAILED
        }.getOrDefault(Result.FAILED)
    }

    /** Return stable project IDs for the Projects page and workstream selection. */
    fun projectIds(): List<String> = synchronized(lock) {
        preferences.all.keys.asSequence()
            .filter { it.startsWith("project_") }
            .map { it.removePrefix("project_") }
            .filter { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
            .filter { runCatching { read(it).has("id") }.getOrDefault(false) }
            .sorted()
            .toList()
    }

    /** Persist a user-authored development note without changing job approval or source files. */
    fun saveNote(id: String, note: String): Boolean = synchronized(lock) {
        if (note.length > 8_000) return@synchronized false
        runCatching {
            val record = read(id)
            if (!record.has("id")) return@synchronized false
            record.put("developer_note", note)
            write(id, record)
        }.getOrDefault(false)
    }

    fun note(id: String): String = synchronized(lock) {
        runCatching { read(id).optString("developer_note") }.getOrDefault("")
    }

    /** Export a reviewable, credential-free snapshot; never export provider keys or approvals. */
    fun exportProject(id: String): String? = synchronized(lock) {
        runCatching {
            val record = read(id)
            if (!record.has("id")) return@synchronized null
            JSONObject()
                .put("schema", 1)
                .put("id", id)
                .put("description", record.optString("description"))
                .put("target", record.optInt("target"))
                .put("developer_note", record.optString("developer_note"))
                .put("state", stateLabel(record))
                .toString(2)
        }.getOrNull()
    }

    /** Import safe project context only; never import approvals or execution state. */
    fun importProject(snapshot: String): Boolean = synchronized(lock) {
        if (snapshot.length > 32_000) return@synchronized false
        runCatching {
            val json = JSONObject(snapshot)
            if (json.optInt("schema", -1) != 1) return@synchronized false
            val id = json.getString("id")
            val task = json.getString("description")
            val target = json.getInt("target")
            val note = json.optString("developer_note")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) ||
                task.isBlank() || task.length > 16_000 || target !in 0..1 ||
                note.length > 8_000 || read(id).has("id")) return@synchronized false
            write(id, JSONObject().put("id", id).put("description", task)
                .put("target", target).put("developer_note", note).put("state", "saved"))
        }.getOrDefault(false)
    }

    /** Recover orphaned requests across every stored project without touching live requests. */
    fun recoverAllInterrupted(): Int = synchronized(lock) {
        preferences.all.keys.asSequence()
            .filter { it.startsWith("project_") }
            .map { it.removePrefix("project_") }
            .filter { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) }
            .count { recoverInterrupted(it) }
    }

    /** Turn a prior-process in-flight request into a durable, retryable interruption. */
    fun recoverInterrupted(id: String): Boolean = synchronized(lock) {
        runCatching {
            val record = read(id)
            if (record.optString("state") != "generating" ||
                record.optString("generation_process") == process) return@synchronized false
            record.put("state", "generation_interrupted")
            listOf("generation_request", "generation_process", "response",
                "response_description", "response_target").forEach { record.remove(it) }
            write(id, record)
        }.getOrDefault(false)
    }

    private fun base(id: String, task: String, target: Int): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(task.isNotBlank() && task.length <= 16_000 && target in 0..1)
        // A newly saved or pending project has no AI reply until generation succeeds.
        return read(id).put("id", id).put("description", task).put("target", target)
    }
    private fun read(id: String): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val value = preferences.getString("project_$id", null) ?: return JSONObject()
        return JSONObject(value).also { require(it.optString("id", id) == id) }
    }
    private fun write(id: String, value: JSONObject): Boolean = preferences.edit()
        .putString("project_$id", value.put("updated_at", System.currentTimeMillis()).toString()).commit()

    companion object {
        private val lock = Any()
        private val process = UUID.randomUUID().toString()
        fun replyMatches(record: JSONObject): Boolean? {
            if (!record.has("response_description") || !record.has("response_target")) return null
            return record.optString("response_description") == record.optString("description") &&
                record.optInt("response_target", -1) == record.optInt("target", -2)
        }
        fun stateLabel(record: JSONObject): String = when (record.optString("state")) {
            "saved" -> "Saved locally"
            "generated" -> "AI reply received"
            "generation_failed" -> "AI request failed"
            "generation_cancelled" -> "AI request cancelled"
            "generation_interrupted" -> "Interrupted; retry when ready"
            "generating" -> if (record.optString("generation_process") == process) "AI reply requested"
                else "Interrupted; retry when ready"
            else -> "Saved project"
        }
    }
}
