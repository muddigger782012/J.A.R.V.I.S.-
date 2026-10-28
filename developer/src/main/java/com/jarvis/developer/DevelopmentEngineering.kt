package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject

/** Local typed identities. Neither a staged draft nor a persisted receipt grants write approval. */
internal object DevelopmentEngineering {
    data class Draft(val job: String, val nonce: String, val taskId: String, val task: String,
        val base: String, val branch: String, val target: PatchEngine.Target, val paths: List<String>)
    data class Write(val job: String, val token: String, val receipt: PatchPublisher.OutcomeReceipt)
    data class Read(val job: String, val token: String, val receipt: PatchPublisher.OutcomeReceipt, val commit: String?)

    fun encode(r: PatchPublisher.OutcomeReceipt): JSONObject {
        PatchPublisher.validateReceipt(r)
        return JSONObject().put("task", r.taskId).put("provider", r.provider).put("model", r.model)
            .put("proposal", r.proposalId).put("base", r.base).put("branch", r.baseBranch).put("target", r.target.name)
            .put("edits", JSONArray(r.edits.map { JSONObject().put("path", it.path)
                .put("before", it.before ?: JSONObject.NULL).put("after", it.after) }))
    }
    fun decode(value: JSONObject): PatchPublisher.OutcomeReceipt {
        val rows = value.getJSONArray("edits")
        require(rows.length() in 1..PatchEngine.MAX_FILES)
        return PatchPublisher.OutcomeReceipt(value.getString("task"), value.getString("provider"), value.getString("model"),
            value.getString("proposal"), value.getString("base"), value.getString("branch"),
            PatchEngine.Target.valueOf(value.getString("target")), (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                PatchPublisher.BlobReceipt(row.getString("path"), if (row.isNull("before")) null else row.getString("before"), row.getString("after"))
            }).also(PatchPublisher::validateReceipt)
    }
}
