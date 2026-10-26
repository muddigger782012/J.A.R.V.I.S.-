package com.assistant.core.services

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Reuses the existing atomic Personal state store. A timeout never allocates a replacement ID. */
class DevelopmentReviewOutbox(private val storage: PersonalStateFile) {
    private fun rows(raw: String?) = if (raw == null) JSONArray() else JSONArray(raw).also { require(it.length() <= 16) }
    fun prepare(body: String, mode: String, kind: String = "diagnostics.review.request"): Wire.Request = storage.access {
        require(kind == "diagnostics.review.request" || kind in Wire.sourceAnalysisKinds)
        val records = rows(read())
        for (index in 0 until records.length()) {
            val item = records.getJSONObject(index)
            val request = Wire.decode(item.getString("request"))
            if (request.body == body && request.mode == mode && request.kind == kind && item.getString("transport") in setOf("PENDING", "UNCONFIRMED"))
                return@access request
        }
        if (records.length() >= 16) throw Wire.Rejected("QUEUE_FULL")
        val request = Wire.Request(UUID.randomUUID().toString(), kind, body, mode)
        records.put(JSONObject().put("request", Wire.encode(request)).put("transport", "PENDING"))
        if (!write(records.toString())) throw Wire.Rejected("STORAGE_UNAVAILABLE")
        request
    }
    fun record(request: Wire.Request, response: JSONObject?) = storage.access {
        val records = rows(read())
        val row = (0 until records.length()).map { records.getJSONObject(it) }
            .singleOrNull { Wire.decode(it.getString("request")) == request }
            ?: throw Wire.Rejected("STORAGE_UNAVAILABLE")
        val acknowledged = response?.getString("code") == "OK"
        if (acknowledged) {
            require(response!!.getString("id") == request.requestId)
            val job = response.getJSONObject("data").getString("id")
            Wire.validate(Wire.Request("check-job", "task.status.request", jobId = job))
            row.put("job", job)
        }
        row.put("transport", if (acknowledged) "ACKNOWLEDGED" else "UNCONFIRMED")
        if (!write(records.toString())) throw Wire.Rejected("STORAGE_UNAVAILABLE")
    }
    fun latestJob(): String = storage.access {
        val records = rows(read())
        (records.length() - 1 downTo 0).map { records.getJSONObject(it) }.firstOrNull { it.has("job") }?.getString("job")
            ?: throw Wire.Rejected("NOT_FOUND")
    }
    fun pending(mode: String): List<Wire.Request> = storage.access {
        val records = rows(read())
        (0 until records.length()).map { records.getJSONObject(it) }
            .filter { it.getString("transport") in setOf("PENDING", "UNCONFIRMED") }
            .map { Wire.decode(it.getString("request")) }.filter { it.mode == mode }
    }
}
