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
            if (current.optString("state") == "generating" &&
                current.optString("generation_process") == process) return@synchronized false
            write(id, base(id, task, target).put("state", "saved").apply {
                remove("generation_request"); remove("generation_process")
                remove("response"); remove("response_description"); remove("response_target")
            })
        }.getOrDefault(false)
    }

    fun begin(id: String, task: String, target: Int): Ticket? = synchronized(lock) {
        runCatching {
            val current = read(id)
            if (current.optString("state") == "generating" &&
                current.optString("generation_process") == process) return@synchronized null
            val ticket = Ticket(id, UUID.randomUUID().toString())
            if (write(id, base(id, task, target).put("state", "generating")
                    .put("generation_request", ticket.request).put("generation_process", process))) ticket else null
        }.getOrNull()
    }

    fun finish(ticket: Ticket, state: String, response: String? = null): Result = synchronized(lock) {
        require(state in setOf("generated", "generation_failed", "generation_cancelled", "generation_interrupted"))
        require((state == "generated") == (response != null))
        runCatching {
            val record = read(ticket.project)
            if (record.optString("state") != "generating" || record.optString("generation_request") != ticket.request)
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

    private fun base(id: String, task: String, target: Int): JSONObject {
        require(task.isNotBlank() && target in 0..1)
        return read(id).apply { if (!has("response")) put("response", "") }
            .put("id", id).put("description", task).put("target", target)
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
