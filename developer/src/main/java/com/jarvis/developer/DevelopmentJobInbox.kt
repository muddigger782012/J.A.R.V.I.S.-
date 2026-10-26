package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Admission/status metadata for cross-app reviews; existing source-plan stores remain authoritative. */
class DevelopmentJobInbox(directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private val checkpoint = PrivateCheckpointFile(directory, "development-inbox.bin", 32, 1_048_576)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun reject(code: String): Nothing = throw Wire.Rejected(code)
    private fun load(): MutableList<JSONObject> {
        val bytes = checkpoint.read() ?: return mutableListOf()
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val payload = root.getString("payload")
        require(root.getString("digest") == hash(payload))
        val rows = JSONArray(payload); require(rows.length() <= 64)
        val jobs = (0 until rows.length()).map { rows.getJSONObject(it) }.toMutableList()
        require(jobs.map { it.getString("id") }.distinct().size == jobs.size)
        for (job in jobs) {
            require(job.getString("caller") in Wire.assistantPackages)
            Wire.decode(job.getString("request"))
            require(job.getString("state") in setOf("QUEUED", "PAUSED", "CANCELLED"))
            require(job.getLong("revision") in 0..1_000_000 && job.getInt("attempts") in 1..3)
            require(job.getJSONArray("receipts").length() <= 100)
            require(job.getJSONObject("verification").keys().asSequence().toSet() == Wire.Stage.values().map { it.name }.toSet())
            for (stage in Wire.Stage.values()) require(job.getJSONObject("verification").getString(stage.name) in
                setOf(Wire.Verification.PENDING.name, Wire.Verification.BLOCKED.name))
        }
        return jobs
    }
    private fun save(jobs: List<JSONObject>) {
        val payload = JSONArray(jobs).toString()
        val bytes = JSONObject().put("version", 1).put("digest", hash(payload)).put("payload", payload).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 1_048_576) reject("QUEUE_FULL")
        checkpoint.write(bytes)
    }
    private fun view(job: JSONObject) = JSONObject().put("id", job.getString("id")).put("state", job.getString("state"))
        .put("mode", Wire.decode(job.getString("request")).mode).put("revision", job.getLong("revision"))
        .put("attempts", job.getInt("attempts")).put("verification", JSONObject(job.getJSONObject("verification").toString()))
        .put("sourceVerified", false).put("deviceVerified", false)

    fun handle(caller: String, receiverPersonal: Boolean, request: Wire.Request): JSONObject = checkpoint.transaction {
        Wire.validate(request)
        if (caller !in Wire.assistantPackages) reject("UNAUTHORIZED")
        if (!Wire.permitsMode(request.mode, receiverPersonal, Wire.personalPackage(caller))) reject("POLICY_REQUIRED")
        val jobs = load()
        if (request.kind == "task.list.request") return@transaction JSONObject().put("jobs", JSONArray(jobs.filter {
            it.getString("caller") == caller
        }.takeLast(8).map(::view))).put("total", jobs.count { it.getString("caller") == caller })
        val fingerprint = Wire.fingerprint(request)
        val owned = jobs.filter { it.getString("caller") == caller }
        for (job in owned) {
            val receipts = job.getJSONArray("receipts")
            for (index in 0 until receipts.length()) {
                val receipt = receipts.getJSONObject(index)
                if (receipt.getString("id") == request.requestId) {
                    if (!Wire.permitsMode(Wire.decode(job.getString("request")).mode, receiverPersonal, Wire.personalPackage(caller))) reject("POLICY_REQUIRED")
                    if (receipt.getString("fingerprint") != fingerprint) reject("CONFLICT")
                    return@transaction view(job).put("duplicate", true)
                }
            }
        }
        if (request.kind == "diagnostics.review.request") {
            if (jobs.size >= 64) reject("QUEUE_FULL")
            val verification = JSONObject()
            Wire.Stage.values().forEach { verification.put(it.name, Wire.Verification.PENDING.name) }
            val job = JSONObject().put("id", "job_" + hash(caller + "\u0000" + request.requestId))
                .put("caller", caller).put("request", Wire.encode(request)).put("state", "QUEUED").put("revision", 0L)
                .put("attempts", 1).put("created", now()).put("verification", verification).put("receipts", JSONArray()
                    .put(JSONObject().put("id", request.requestId).put("fingerprint", fingerprint)
                        .put("kind", request.kind).put("mode", request.mode).put("revision", 0L).put("at", now())))
            require(job.getLong("created") >= 0)
            jobs.add(job); save(jobs)
            return@transaction view(job).put("duplicate", false)
        }
        val job = owned.singleOrNull { it.getString("id") == request.jobId } ?: reject("NOT_FOUND")
        if (!Wire.permitsMode(Wire.decode(job.getString("request")).mode, receiverPersonal, Wire.personalPackage(caller))) reject("POLICY_REQUIRED")
        if (request.kind == "task.status.request") return@transaction view(job)
        if (request.expectedRevision != job.getLong("revision")) reject("CONFLICT")
        if (request.kind in setOf("task.resume.request", "task.retry.request") &&
            request.mode != Wire.decode(job.getString("request")).mode) reject("POLICY_REQUIRED")
        val state = job.getString("state")
        val next = when (request.kind) {
            "task.cancel.request" -> "CANCELLED"
            "task.pause.request" -> if (state == "QUEUED") "PAUSED" else reject("CONFLICT")
            "task.resume.request" -> if (state == "PAUSED") "QUEUED" else reject("CONFLICT")
            "task.retry.request" -> {
                if (state != "CANCELLED" || job.getInt("attempts") >= 3) reject("RETRY_BLOCKED")
                job.put("attempts", job.getInt("attempts") + 1); "QUEUED"
            }
            else -> reject("INVALID_REQUEST")
        }
        val receipts = job.getJSONArray("receipts")
        if (receipts.length() >= 100) reject("AUDIT_FULL")
        job.put("state", next).put("revision", job.getLong("revision") + 1L)
        for (stage in Wire.Stage.values()) job.getJSONObject("verification").put(stage.name,
            if (next == "CANCELLED") Wire.Verification.BLOCKED.name else Wire.Verification.PENDING.name)
        receipts.put(JSONObject().put("id", request.requestId).put("fingerprint", fingerprint).put("kind", request.kind)
            .put("mode", request.mode).put("from", state).put("to", next).put("revision", job.getLong("revision")).put("at", now()))
        save(jobs)
        view(job).put("duplicate", false)
    }

    fun pendingReviews(): List<Pair<String, String>> = checkpoint.transaction {
        load().filter { it.getString("state") == "QUEUED" }.map {
            it.getString("id") to Wire.decode(it.getString("request")).body
        }
    }
    fun localSnapshot(): List<JSONObject> = checkpoint.transaction {
        load().map { view(it).put("caller", it.getString("caller")) }
    }
}
