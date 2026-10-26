package com.assistant.core.services

import android.content.Context
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import com.jarvis.integration.DevelopmentBridgeClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Typed development requests stay separate from AI interpretations, command learning and approvals. */
class DeveloperBridgeOperations(private val context: Context) {
    private val storage = PersonalStateFile(context, "developer-outbox.json")
    private val preferences get() = context.getSharedPreferences("development_approval", Context.MODE_PRIVATE)
    val modes get() = DevelopmentApprovalPolicy(context.packageName == "com.assistant.core").availableModes
    fun selectedMode(): DevelopmentApprovalMode {
        val mode = DevelopmentApprovalMode.fromWire(preferences.getString("mode", "supervised").orEmpty())
            ?: throw Wire.Rejected("POLICY_REQUIRED")
        return DevelopmentApprovalPolicy(context.packageName == "com.assistant.core").requireAllowed(mode)
    }
    fun selectMode(mode: DevelopmentApprovalMode): Boolean {
        DevelopmentApprovalPolicy(context.packageName == "com.assistant.core").requireAllowed(mode)
        val audit = JSONArray(preferences.getString("audit", "[]"))
        if (audit.length() >= 100) throw Wire.Rejected("AUDIT_FULL")
        audit.put(JSONObject().put("mode", mode.wireValue).put("at", System.currentTimeMillis()))
        return preferences.edit().putString("mode", mode.wireValue).putString("audit", audit.toString()).commit()
    }
    private fun rows(raw: String?) = if (raw == null) JSONArray() else JSONArray(raw).also { require(it.length() <= 16) }
    private fun pending(body: String): Wire.Request = storage.access {
        val records = rows(read()); val mode = selectedMode().wireValue
        for (index in 0 until records.length()) {
            val item = records.getJSONObject(index)
            val request = Wire.decode(item.getString("request"))
            if (request.body == body && request.mode == mode && item.getString("transport") in setOf("PENDING", "UNCONFIRMED"))
                return@access request
        }
        if (records.length() >= 16) throw Wire.Rejected("QUEUE_FULL")
        val request = Wire.Request(UUID.randomUUID().toString(), "diagnostics.review.request", body, mode)
        records.put(JSONObject().put("request", Wire.encode(request)).put("transport", "PENDING"))
        if (!write(records.toString())) throw Wire.Rejected("STORAGE_UNAVAILABLE")
        request
    }
    private fun record(request: Wire.Request, response: JSONObject?) = storage.access {
        val records = rows(read())
        val row = (0 until records.length()).map { records.getJSONObject(it) }
            .singleOrNull { Wire.decode(it.getString("request")).requestId == request.requestId }
            ?: throw Wire.Rejected("STORAGE_UNAVAILABLE")
        row.put("transport", if (response?.optString("code") == "OK") "ACKNOWLEDGED" else "UNCONFIRMED")
        if (response?.optString("code") == "OK") row.put("job", response.getJSONObject("data").getString("id"))
        if (!write(records.toString())) throw Wire.Rejected("STORAGE_UNAVAILABLE")
    }
    private fun call(request: Wire.Request): JSONObject {
        val response = DevelopmentBridgeClient(context).request(request)
        if (response.getString("code") != "OK") throw Wire.Rejected(response.getString("code"))
        return response.getJSONObject("data")
    }
    fun capabilities(): String {
        val reply = call(Wire.Request(UUID.randomUUID().toString(), "capabilities.request", mode = selectedMode().wireValue))
        Wire.negotiate(reply, Wire.Request("capability-check", "diagnostics.review.request", "Read-only review", selectedMode().wireValue))
        return "Developer's identity and connection are verified. Review queuing, task status, pause, resume and cancellation are available. " +
            "Current approval mode: ${Wire.modeLabel(selectedMode().wireValue)}. " +
            "This bridge currently handles read-only reviews; code execution and installation require later verified integrations and their approvals. Android privileges are unchanged."
    }
    fun submit(body: String): String {
        val request = pending(body)
        val response = try { DevelopmentBridgeClient(context).request(request) }
            catch (failure: Exception) { record(request, null); throw failure }
        record(request, response)
        if (response.getString("code") != "OK") throw Wire.Rejected(response.getString("code"))
        return "Developer acknowledged the read-only review. " + render(response.getJSONObject("data"))
    }
    private fun latestJob(): String = storage.access {
        val records = rows(read())
        (records.length() - 1 downTo 0).map { records.getJSONObject(it) }.firstOrNull { it.has("job") }?.getString("job")
            ?: throw Wire.Rejected("NOT_FOUND")
    }
    fun status(): String = render(call(Wire.Request(UUID.randomUUID().toString(), "task.status.request",
        mode = selectedMode().wireValue, jobId = latestJob())))
    fun list(): String {
        val data = call(Wire.Request(UUID.randomUUID().toString(), "task.list.request", mode = selectedMode().wireValue))
        val jobs = data.getJSONArray("jobs")
        return "${data.getInt("total")} Developer reviews recorded.\n" + (0 until jobs.length()).joinToString("\n\n") { render(jobs.getJSONObject(it)) }
    }
    fun control(kind: String): String {
        require(kind in setOf("task.cancel.request", "task.pause.request", "task.resume.request", "task.retry.request"))
        val jobId = latestJob()
        val state = call(Wire.Request(UUID.randomUUID().toString(), "task.status.request", mode = selectedMode().wireValue, jobId = jobId))
        return render(call(Wire.Request(UUID.randomUUID().toString(), kind, mode = selectedMode().wireValue,
            jobId = jobId, expectedRevision = state.getLong("revision"))))
    }
    private fun render(value: JSONObject): String = "Review state: ${value.getString("state")}. " +
        "Attempt ${value.getInt("attempts")}.\n" + Wire.Stage.values().joinToString("\n") {
            "${it.name.replace('_', ' ')}: ${value.getJSONObject("verification").getString(it.name)}"
        } + "\nQueued review is not verified source, a successful build, publication or installation."

    companion object {
        fun failure(failure: Throwable): String = when ((failure as? Wire.Rejected)?.code) {
            "COMPANION_UNAVAILABLE", "UNSUPPORTED_VERSION", "UNSUPPORTED_OPERATION" -> "The compatible Developer bridge is unavailable. Install or open the independent Developer application and retry. A queued request is not a completed repair."
            "UNAUTHORIZED", "POLICY_REQUIRED" -> "Developer identity or approval policy could not be verified. No development operation was authorized."
            "TIMEOUT", "BUSY" -> "Developer has not acknowledged this request. Retrying the same retained review reuses its request ID; acceptance and completion remain unverified."
            "NOT_FOUND" -> "No acknowledged Developer job is available for this command. Submit a review first."
            "CONFLICT" -> "The Developer job or request changed. Review its current status before issuing another control."
            "QUEUE_FULL", "AUDIT_FULL" -> "The bounded development history is full. Review it in Developer; no existing job was discarded."
            "TOO_LARGE" -> "This review exceeds the bounded transfer size. Select a smaller diagnostic review; no queue acknowledgement was verified."
            else -> "Development status could not be verified. No build, source change or installation success is inferred."
        }
    }
}
