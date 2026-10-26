package com.jarvis.developer

import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Admission/status metadata for cross-app reviews; existing source-plan stores remain authoritative. */
class DevelopmentJobInbox(directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private companion object { val processIdentity = java.util.UUID.randomUUID().toString() }
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
            val state = job.getString("state")
            require(state in setOf("QUEUED", "PAUSED", "CANCELLED", "RUNNING", "REVIEW_READY", "FAILED",
                "AWAITING_APPROVAL", "COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING", "READY_FOR_REVIEW", "REVIEW_REQUIRED"))
            if (state in setOf("RUNNING", "REVIEW_READY", "FAILED")) require(Wire.decode(job.getString("request")).kind in Wire.sourceAnalysisKinds)
            require(job.getLong("revision") in 0..1_000_000 && job.getInt("attempts") in 1..3)
            require(job.getJSONArray("receipts").length() <= 100)
            require(job.getJSONObject("verification").keys().asSequence().toSet() == Wire.Stage.values().map { it.name }.toSet())
            val engineering = job.optJSONObject("engineering")
            if (state in setOf("AWAITING_APPROVAL", "COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING", "READY_FOR_REVIEW", "REVIEW_REQUIRED")) require(engineering != null)
            if (engineering != null) {
                require(Wire.decode(job.getString("request")).kind in Wire.sourceAnalysisKinds)
                require(engineering.getInt("polls") in 0..20)
                val paths = engineering.getJSONArray("paths")
                require(paths.length() in 1..8 && (0 until paths.length()).map { paths.getString(it) }.distinct().size == paths.length())
                if (engineering.has("commit")) require(engineering.getString("commit").matches(Regex("[a-f0-9]{40}")))
            }
            engineering?.optJSONObject("receipt")?.let(DevelopmentEngineering::decode)
            engineering?.optJSONObject("artifact")?.let { artifact ->
                artifact.optJSONObject("record")?.let { raw ->
                    val record = DevelopmentArtifact.record(raw)
                    require(record.commit == engineering.getString("commit") &&
                        record.packageName == DevelopmentArtifact.packageName(DevelopmentEngineering.decode(engineering.getJSONObject("receipt")).target))
                    require(artifact.getString("buildFingerprint") == engineering.getJSONObject("build").getString("fingerprint"))
                }
                artifact.optJSONObject("installation")?.let { install ->
                    require(artifact.has("record") && install.getString("status") in DevelopmentArtifact.installStates)
                    require(install.getString("nonce").matches(Regex("[a-f0-9-]{36}")) && install.getInt("session") >= -1)
                }
            }
            for (stage in Wire.Stage.values()) {
                val value = job.getJSONObject("verification").getString(stage.name)
                require(value in Wire.Verification.values().map { it.name })
                if (engineering == null || stage == Wire.Stage.DEVICE_INSTALLED && value == "PASSED")
                    require(value in setOf("PENDING", "BLOCKED"))
                if (stage == Wire.Stage.SIGNED_APK && value == "PASSED") require(engineering?.optJSONObject("artifact")?.has("record") == true)
                if (stage == Wire.Stage.SIGNED_APK && value == "PASSED") require(listOf("SOURCE_COMMITTED", "BUILD_SUCCESSFUL", "TESTS_PASSED").all {
                    job.getJSONObject("verification").getString(it) == "PASSED"
                })
                if (stage == Wire.Stage.SIGNED_APK && value == "RUNNING") require(engineering?.optJSONObject("artifact")?.has("readLease") == true)
                if (stage == Wire.Stage.DEVICE_INSTALLED && value in setOf("RUNNING", "FAILED"))
                    require(engineering?.optJSONObject("artifact")?.has("installation") == true)
                if (value == "PASSED") require(engineering?.has("receipt") == true &&
                    engineering.optString("commit").matches(Regex("[a-f0-9]{40}")))
            }
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
        .put("sourceVerified", job.getJSONObject("verification").getString("SOURCE_COMMITTED") == "PASSED")
        .put("deviceVerified", false).also { result ->
            if (job.has("phase")) result.put("phase", job.getString("phase")).put("phaseElapsedMillis", job.optLong("phaseElapsedMillis"))
            job.optJSONObject("analysis")?.let { result.put("analysis", JSONObject(it.toString()).also { it.remove("paths") }) }
            job.optJSONObject("engineering")?.let { e ->
                result.put("engineering", JSONObject().put("approvalConsumed", e.has("receipt"))
                    .put("commit", e.optString("commit")).put("polls", e.optInt("polls"))
                    .put("build", e.optJSONObject("build") ?: JSONObject()))
                e.optJSONObject("artifact")?.let { a ->
                    result.put("artifact", JSONObject().put("checking", a.has("readLease"))
                        .put("problem", a.optString("problem")).put("installation", a.optJSONObject("installation")?.optString("status").orEmpty()).also { summary ->
                            a.optJSONObject("installation")?.takeIf { it.has("androidStatus") }?.let { summary.put("androidStatus", it.getInt("androidStatus")) }
                            a.optJSONObject("record")?.let { record ->
                                for (key in listOf("packageName", "versionCode", "versionName", "signing", "run", "artifact")) summary.put(key, record.get(key))
                            }
                        })
                }
            }
            job.optString("problem").takeIf { it.isNotEmpty() }?.let { result.put("problem", it) }
        }

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
                if (receipt.optString("id") == request.requestId) {
                    if (!Wire.permitsMode(Wire.decode(job.getString("request")).mode, receiverPersonal, Wire.personalPackage(caller))) reject("POLICY_REQUIRED")
                    if (receipt.getString("fingerprint") != fingerprint) reject("CONFLICT")
                    return@transaction view(job).put("duplicate", true)
                }
            }
        }
        if (request.kind == "diagnostics.review.request" || request.kind in Wire.sourceAnalysisKinds) {
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
        val engineering = job.optJSONObject("engineering")
        val next = when (request.kind) {
            "task.cancel.request" -> "CANCELLED"
            "task.pause.request" -> if (state in setOf("QUEUED", "RUNNING", "AWAITING_APPROVAL", "COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING")) "PAUSED" else reject("CONFLICT")
            "task.resume.request" -> if (state == "PAUSED") {
                if (engineering == null) "QUEUED" else if (engineering.has("commit")) "VERIFYING"
                else if (engineering.has("receipt")) "OUTCOME_UNKNOWN" else "AWAITING_APPROVAL"
            } else reject("CONFLICT")
            "task.retry.request" -> {
                if (state !in setOf("CANCELLED", "FAILED", "REVIEW_REQUIRED") || job.getInt("attempts") >= 3) reject("RETRY_BLOCKED")
                if (engineering != null && !engineering.has("commit")) reject("RETRY_BLOCKED")
                engineering?.put("polls", 0)
                job.put("attempts", job.getInt("attempts") + 1)
                if (engineering == null) "QUEUED" else "VERIFYING"
            }
            else -> reject("INVALID_REQUEST")
        }
        val receipts = job.getJSONArray("receipts")
        if (receipts.length() >= 100) reject("AUDIT_FULL")
        job.put("state", next).put("revision", job.getLong("revision") + 1L)
        job.remove("lease"); job.remove("problem")
        engineering?.remove("readLease")
        engineering?.optJSONObject("artifact")?.let { artifact ->
            artifact.remove("readLease")
            artifact.optJSONObject("installation")?.takeIf { it.optString("status") in DevelopmentArtifact.activeInstalls }
                ?.put("status", "CANCELLED")
            if (job.getJSONObject("verification").getString("SIGNED_APK") == "RUNNING")
                job.getJSONObject("verification").put("SIGNED_APK", "BLOCKED")
            job.getJSONObject("verification").put("DEVICE_INSTALLED", "BLOCKED")
        }
        if (engineering != null && !engineering.has("receipt")) engineering.remove("nonce")
        for (stage in Wire.Stage.values()) {
            val stages = job.getJSONObject("verification")
            if (engineering == null || stages.getString(stage.name) != "PASSED") stages.put(stage.name,
                if (next == "CANCELLED") "BLOCKED" else "PENDING")
        }
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
    internal data class AnalysisLease(val jobId: String, val token: String, val request: Wire.Request)
    internal fun claimAnalysis(jobId: String, receiverPersonal: Boolean): AnalysisLease? = checkpoint.transaction {
        val jobs = load(); val job = jobs.singleOrNull { it.getString("id") == jobId } ?: return@transaction null
        val request = Wire.decode(job.getString("request"))
        if (request.kind !in Wire.sourceAnalysisKinds || job.getString("state") !in setOf("QUEUED", "RUNNING")) return@transaction null
        if (!Wire.permitsMode(request.mode, receiverPersonal, Wire.personalPackage(job.getString("caller")))) reject("POLICY_REQUIRED")
        val token = java.util.UUID.randomUUID().toString()
        analysisTransition(job, "RUNNING", "analysis.claim")
        job.put("lease", token).put("phase", "DISCOVERY").put("phaseElapsedMillis", 0L)
        job.remove("analysis"); job.remove("report"); job.remove("problem")
        save(jobs)
        AnalysisLease(jobId, token, request)
    }
    private fun analysisTransition(job: JSONObject, next: String, event: String) {
        val receipts = job.getJSONArray("receipts")
        if (receipts.length() >= 100) reject("AUDIT_FULL")
        val old = job.getString("state")
        job.put("state", next).put("revision", job.getLong("revision") + 1L)
        receipts.put(JSONObject().put("kind", event).put("from", old).put("to", next)
            .put("revision", job.getLong("revision")).put("at", now()))
    }
    internal fun progressAnalysis(lease: AnalysisLease, phase: String, elapsedMillis: Long): Boolean = checkpoint.transaction {
        require(phase in setOf("INDEXING", "SOURCE_READ", "PLANNING", "REPORT") && elapsedMillis in 0..600_000)
        val jobs = load(); val job = jobs.singleOrNull { it.getString("id") == lease.jobId } ?: return@transaction false
        if (job.getString("state") != "RUNNING" || job.optString("lease") != lease.token) return@transaction false
        require(elapsedMillis >= job.optLong("phaseElapsedMillis"))
        if (job.optString("phase") == phase) return@transaction true
        analysisTransition(job, "RUNNING", "analysis.phase")
        job.put("phase", phase).put("phaseElapsedMillis", elapsedMillis)
        job.getJSONArray("receipts").getJSONObject(job.getJSONArray("receipts").length() - 1).put("phase", phase).put("elapsedMillis", elapsedMillis)
        save(jobs); true
    }
    internal fun finishAnalysis(lease: AnalysisLease, plan: RepositoryAnalysis.AnalysisPlan): Boolean = checkpoint.transaction {
        require(plan.commitSha.matches(Regex("[a-f0-9]{40}")) && plan.task == lease.request.body)
        val jobs = load(); val job = jobs.singleOrNull { it.getString("id") == lease.jobId } ?: return@transaction false
        if (job.getString("state") != "RUNNING" || job.optString("lease") != lease.token) return@transaction false
        require(plan.files.isNotEmpty()) { "No verified source evidence was retrieved" }
        analysisTransition(job, "REVIEW_READY", "analysis.ready")
        val rendered = RepositoryAnalysis.render(plan).lines().joinToString("\n", transform = RepositoryRedaction::line).toByteArray(Charsets.UTF_8)
        val shortened = rendered.size > 8000
        val report = if (shortened) String(rendered.copyOf(8000), Charsets.UTF_8) + "\nReport display truncated; context remains incomplete." else String(rendered, Charsets.UTF_8)
        job.put("report", report).put("analysis", JSONObject().put("commit", plan.commitSha)
            .put("inspected", plan.inspectedPaths.size).put("planned", plan.files.size).put("partial", true)
            .put("paths", JSONArray(plan.files.map { it.path }))
            .put("reportTruncated", shortened))
        job.remove("lease"); save(jobs); true
    }
    internal fun failAnalysis(lease: AnalysisLease, problem: String = "SOURCE_READ_UNVERIFIED"): Boolean = checkpoint.transaction {
        require(problem in setOf("SOURCE_READ_UNVERIFIED", "SOURCE_ANALYSIS_INTERNAL"))
        val jobs = load(); val job = jobs.singleOrNull { it.getString("id") == lease.jobId } ?: return@transaction false
        if (job.getString("state") != "RUNNING" || job.optString("lease") != lease.token) return@transaction false
        analysisTransition(job, "FAILED", "analysis.failed")
        job.remove("lease"); job.put("problem", problem)
        save(jobs); true
    }
    internal fun interruptAnalysis(lease: AnalysisLease): Boolean = checkpoint.transaction {
        val jobs = load(); val job = jobs.singleOrNull { it.getString("id") == lease.jobId } ?: return@transaction false
        if (job.getString("state") != "RUNNING" || job.optString("lease") != lease.token) return@transaction false
        analysisTransition(job, "QUEUED", "analysis.interrupted")
        job.remove("lease"); save(jobs); true
    }
    internal fun recoverableAnalysisJobs(): List<String> = checkpoint.transaction {
        load().filter { it.getString("state") in setOf("QUEUED", "RUNNING") &&
            !it.has("engineering") && Wire.decode(it.getString("request")).kind in Wire.sourceAnalysisKinds }.map { it.getString("id") }
    }
    fun localAnalysisReport(jobId: String): String? = checkpoint.transaction {
        load().singleOrNull { it.getString("id") == jobId && it.getString("state") == "REVIEW_READY" }?.getString("report")
    }
    private fun engineeringPolicy(job: JSONObject, personal: Boolean) {
        val request = Wire.decode(job.getString("request"))
        if (!Wire.permitsMode(request.mode, personal, Wire.personalPackage(job.getString("caller")))) reject("POLICY_REQUIRED")
        require(request.kind in Wire.sourceAnalysisKinds)
    }
    internal fun engineeringPaths(jobId: String): List<String> = checkpoint.transaction {
        val job = load().single { it.getString("id") == jobId }
        val target = if (Wire.decode(job.getString("request")).kind == "source.personal.analysis.request")
            PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
        val paths = job.optJSONObject("engineering")?.optJSONArray("paths")
            ?: job.getJSONObject("analysis").optJSONArray("paths") ?: return@transaction emptyList()
        require(paths.length() <= 8)
        (0 until paths.length()).map { paths.getString(it) }.filter { PatchEngine.allowedPath(it, target) }
    }
    internal fun engineeringGoal(jobId: String): String = checkpoint.transaction {
        load().single { it.getString("id") == jobId }.optJSONObject("engineering")?.optString("task").orEmpty()
    }
    internal fun stageEngineering(jobId: String, revision: Long, paths: List<String>, personal: Boolean,
        goal: String? = null): DevelopmentEngineering.Draft = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == jobId }
        engineeringPolicy(job, personal)
        require(job.getLong("revision") == revision && job.getString("state") in setOf("REVIEW_READY", "AWAITING_APPROVAL"))
        require(job.optJSONObject("engineering")?.has("receipt") != true)
        val request = Wire.decode(job.getString("request"))
        val task = goal ?: request.body.lines().joinToString("\n", transform = RepositoryRedaction::line)
        require(task.isNotBlank() && task.length <= 4_000 && task.lines().all { RepositoryRedaction.line(it) == it } &&
            task.none { it.isISOControl() && it !in "\n\r\t" })
        val target = if (request.kind == "source.personal.analysis.request") PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
        val known = job.getJSONObject("analysis").getJSONArray("paths")
        require(paths.size in 1..8 && paths.distinct().size == paths.size && paths.all { path ->
            PatchEngine.allowedPath(path, target) && (0 until known.length()).any { known.getString(it) == path }
        })
        val draft = DevelopmentEngineering.Draft(jobId, java.util.UUID.randomUUID().toString(), "bridge-" + hash(jobId).take(24),
            task,
            job.getJSONObject("analysis").getString("commit"), if (target == PatchEngine.Target.PERSONAL) "main" else "feature/jarvis-developer",
            target, paths.sorted())
        job.put("engineering", JSONObject().put("nonce", draft.nonce).put("paths", JSONArray(draft.paths)).put("task", task).put("polls", 0))
        analysisTransition(job, "AWAITING_APPROVAL", "engineering.staged")
        save(jobs); draft
    }
    internal fun claimEngineering(draft: DevelopmentEngineering.Draft, approved: PatchEngine.ApprovedProposal,
        personal: Boolean): DevelopmentEngineering.Write = checkpoint.transaction {
        PatchEngine.validateApproval(approved)
        val jobs = load(); val job = jobs.single { it.getString("id") == draft.job }
        engineeringPolicy(job, personal)
        val e = job.getJSONObject("engineering")
        require(job.getString("state") == "AWAITING_APPROVAL" && e.optString("nonce") == draft.nonce && !e.has("receipt"))
        val request = Wire.decode(job.getString("request"))
        val target = if (request.kind == "source.personal.analysis.request") PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
        val proposal = approved.proposal.request
        val scope = e.getJSONArray("paths"); val paths = (0 until scope.length()).map { scope.getString(it) }
        require(proposal.taskId == "bridge-" + hash(draft.job).take(24) && proposal.task == e.getString("task") &&
            proposal.baseCommitSha == job.getJSONObject("analysis").getString("commit") && proposal.allowedPaths.sorted() == paths &&
            proposal.target == target && proposal.baseBranch == if (target == PatchEngine.Target.PERSONAL) "main" else "feature/jarvis-developer")
        val receipt = PatchPublisher.outcomeReceipt(approved)
        val token = java.util.UUID.randomUUID().toString()
        e.put("receipt", DevelopmentEngineering.encode(receipt)).put("writeToken", token).put("writerProcess", processIdentity); e.remove("nonce")
        analysisTransition(job, "COMMITTING", "engineering.approval.consumed")
        job.getJSONObject("verification").put("SOURCE_COMMITTED", "RUNNING")
        save(jobs); DevelopmentEngineering.Write(draft.job, token, receipt)
    }
    internal fun finishEngineering(write: DevelopmentEngineering.Write, result: PatchPublisher.CommitResult): Boolean = checkpoint.transaction {
        PatchPublisher.validateReceiptOutcome(write.receipt, result)
        val jobs = load(); val job = jobs.single { it.getString("id") == write.job }; val e = job.getJSONObject("engineering")
        if (e.optString("writeToken") != write.token || DevelopmentEngineering.decode(e.getJSONObject("receipt")) != write.receipt) return@transaction false
        if (e.has("commit")) { require(e.getString("commit") == result.commitSha); return@transaction true }
        recordCommit(job, result.commitSha); save(jobs); true
    }
    private fun recordCommit(job: JSONObject, commit: String) {
        require(commit.matches(Regex("[a-f0-9]{40}")))
        job.getJSONObject("engineering").put("commit", commit)
        job.remove("problem")
        job.getJSONObject("verification").put("SOURCE_COMMITTED", "PASSED")
        analysisTransition(job, if (job.getString("state") in setOf("CANCELLED", "PAUSED")) job.getString("state") else "VERIFYING", "engineering.source.verified")
    }
    internal fun uncertainEngineering(write: DevelopmentEngineering.Write): Boolean = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == write.job }; val e = job.getJSONObject("engineering")
        if (e.optString("writeToken") != write.token || e.has("commit")) return@transaction false
        analysisTransition(job, if (job.getString("state") in setOf("CANCELLED", "PAUSED")) job.getString("state") else "OUTCOME_UNKNOWN", "engineering.outcome.unknown")
        job.put("problem", "SOURCE_OUTCOME_UNKNOWN"); job.getJSONObject("verification").put("SOURCE_COMMITTED", "BLOCKED")
        save(jobs); true
    }
    internal fun claimEngineeringRead(jobId: String, personal: Boolean): DevelopmentEngineering.Read? = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == jobId }
        try { engineeringPolicy(job, personal) } catch (_: Wire.Rejected) {
            analysisTransition(job, "REVIEW_REQUIRED", "engineering.policy.blocked")
            job.put("problem", "POLICY_REQUIRED")
            for (stage in listOf("SOURCE_COMMITTED", "BUILD_SUCCESSFUL", "TESTS_PASSED"))
                if (job.getJSONObject("verification").getString(stage) != "PASSED") job.getJSONObject("verification").put(stage, "BLOCKED")
            save(jobs); return@transaction null
        }
        if (job.getString("state") !in setOf("COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING")) return@transaction null
        val e = job.getJSONObject("engineering")
        if (e.optInt("polls") >= 20) {
            analysisTransition(job, "REVIEW_REQUIRED", "engineering.read.budget")
            for (stage in listOf("SOURCE_COMMITTED", "BUILD_SUCCESSFUL", "TESTS_PASSED"))
                if (job.getJSONObject("verification").getString(stage) != "PASSED") job.getJSONObject("verification").put(stage, "BLOCKED")
            job.put("problem", "ENGINEERING_EVIDENCE_UNAVAILABLE"); save(jobs); return@transaction null
        }
        val token = java.util.UUID.randomUUID().toString()
        e.put("readLease", token).put("polls", e.optInt("polls") + 1)
        save(jobs)
        DevelopmentEngineering.Read(jobId, token, DevelopmentEngineering.decode(e.getJSONObject("receipt")), e.optString("commit").takeIf { it.isNotEmpty() })
    }
    private fun currentRead(job: JSONObject, read: DevelopmentEngineering.Read) = job.getString("state") in setOf("COMMITTING", "OUTCOME_UNKNOWN", "VERIFYING") &&
        job.getJSONObject("engineering").optString("readLease") == read.token &&
        DevelopmentEngineering.decode(job.getJSONObject("engineering").getJSONObject("receipt")) == read.receipt
    internal fun observedEngineering(read: DevelopmentEngineering.Read, result: PatchPublisher.CommitResult): Boolean = checkpoint.transaction {
        PatchPublisher.validateReceiptOutcome(read.receipt, result)
        val jobs = load(); val job = jobs.single { it.getString("id") == read.job }
        if (!currentRead(job, read)) return@transaction false
        recordCommit(job, result.commitSha); save(jobs); true
    }
    internal fun recordEngineeringBuild(read: DevelopmentEngineering.Read, report: BuildDiagnostics.Report): Boolean = checkpoint.transaction {
        BuildDiagnostics.validateReport(report)
        val jobs = load(); val job = jobs.single { it.getString("id") == read.job }; val e = job.getJSONObject("engineering")
        if (!currentRead(job, read)) return@transaction false
        require(report.target == read.receipt.target && report.branch == read.receipt.branch && report.commitSha == e.getString("commit"))
        val old = e.optJSONObject("build")
        if (old != null && (report.runId < old.optLong("run") || report.runId == old.optLong("run") && report.runAttempt < old.optInt("attempt"))) return@transaction false
        e.put("build", JSONObject().put("run", report.runId).put("attempt", report.runAttempt).put("url", report.runUrl)
            .put("status", report.status).put("conclusion", report.conclusion ?: "").put("complete", report.evidenceComplete)
            .put("fingerprint", report.fingerprint).put("tests", report.totals?.tests ?: 0))
        val rendered = BuildDiagnostics.render(report).toByteArray(Charsets.UTF_8)
        e.put("diagnostics", String(rendered.copyOf(minOf(rendered.size, 4_096)), Charsets.UTF_8) +
            if (rendered.size > 4_096) "\nDisplay truncated; review the recorded workflow for full evidence." else "")
        e.remove("readLease"); job.remove("problem")
        val stages = job.getJSONObject("verification")
        val complete = report.status == "completed"
        val success = complete && report.conclusion == "success"
        stages.put("BUILD_SUCCESSFUL", if (complete) { if (success) "PASSED" else "FAILED" } else if (report.status == "missing") "PENDING" else "RUNNING")
        val testsPass = success && report.evidenceComplete && report.totals?.let { it.tests > 0 && it.failures == 0 && it.errors == 0 && it.skipped == 0 } == true
        stages.put("TESTS_PASSED", if (testsPass) "PASSED" else if (!complete) "PENDING"
            else if (report.tests.values.any { it in setOf(BuildDiagnostics.TestState.FAILED, BuildDiagnostics.TestState.ERROR) }) "FAILED" else "BLOCKED")
        analysisTransition(job, if (!complete) "VERIFYING" else if (testsPass) "READY_FOR_REVIEW" else "REVIEW_REQUIRED", "engineering.build.observed")
        save(jobs); !complete
    }
    internal fun engineeringReadFailed(read: DevelopmentEngineering.Read, absent: Boolean = false) = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == read.job }
        if (!currentRead(job, read)) return@transaction false
        job.getJSONObject("engineering").remove("readLease")
        job.put("problem", if (absent) "SOURCE_OUTCOME_UNKNOWN" else "ENGINEERING_EVIDENCE_UNAVAILABLE")
        if (absent) {
            analysisTransition(job, "REVIEW_REQUIRED", "engineering.outcome.unverified")
            job.getJSONObject("verification").put("SOURCE_COMMITTED", "BLOCKED")
        }
        save(jobs); !absent
    }
    internal fun recoverableEngineeringJobs(): List<String> = checkpoint.transaction {
        load().filter { it.has("engineering") && (it.getString("state") in setOf("OUTCOME_UNKNOWN", "VERIFYING") ||
            it.getString("state") == "COMMITTING" && it.getJSONObject("engineering").optString("writerProcess") != processIdentity) }
            .map { it.getString("id") }
    }
    internal fun engineeringReviewUrl(jobId: String): String? = checkpoint.transaction {
        val job = load().single { it.getString("id") == jobId }
        if (job.getJSONObject("verification").getString("SOURCE_COMMITTED") != "PASSED") return@transaction null
        val commit = job.getJSONObject("engineering").getString("commit")
        require(commit.matches(Regex("[a-f0-9]{40}")))
        "https://github.com/muddigger782012/J.A.R.V.I.S.-/commit/$commit"
    }
    internal fun engineeringDiagnostics(jobId: String): String? = checkpoint.transaction {
        load().single { it.getString("id") == jobId }.optJSONObject("engineering")?.optString("diagnostics")?.takeIf { it.isNotEmpty() }
    }
    internal fun refreshEngineeringEvidence(jobId: String, revision: Long, personal: Boolean) = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == jobId }
        engineeringPolicy(job, personal)
        val e = job.getJSONObject("engineering")
        require(job.getLong("revision") == revision && e.has("receipt") &&
            job.getString("state") in setOf("READY_FOR_REVIEW", "REVIEW_REQUIRED", "OUTCOME_UNKNOWN"))
        require(e.optJSONObject("artifact")?.optJSONObject("installation")?.optString("status") !in DevelopmentArtifact.activeInstalls)
        e.remove("artifact")
        job.getJSONObject("verification").put("SIGNED_APK", "PENDING").put("DEVICE_INSTALLED", "PENDING")
        e.put("polls", 0); e.remove("readLease"); job.remove("problem")
        analysisTransition(job, if (e.has("commit")) "VERIFYING" else "OUTCOME_UNKNOWN", "engineering.read.requested")
        for (stage in listOf("BUILD_SUCCESSFUL", "TESTS_PASSED")) job.getJSONObject("verification").put(stage, "PENDING")
        save(jobs)
    }
    private fun artifactReady(job: JSONObject) {
        require(job.getString("state") == "READY_FOR_REVIEW")
        require(listOf("SOURCE_COMMITTED", "BUILD_SUCCESSFUL", "TESTS_PASSED").all {
            job.getJSONObject("verification").getString(it) == "PASSED"
        })
    }
    internal fun beginArtifact(jobId: String, revision: Long, personal: Boolean): DevelopmentArtifact.Source = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == jobId }
        engineeringPolicy(job, personal); artifactReady(job); require(job.getLong("revision") == revision)
        val e = job.getJSONObject("engineering"); val build = e.getJSONObject("build")
        val artifact = e.optJSONObject("artifact") ?: JSONObject().also { e.put("artifact", it) }
        require(artifact.optJSONObject("installation")?.optString("status") !in DevelopmentArtifact.activeInstalls)
        val nonce = java.util.UUID.randomUUID().toString(); artifact.put("readLease", nonce); artifact.remove("problem")
        val receipt = DevelopmentEngineering.decode(e.getJSONObject("receipt"))
        val source = DevelopmentArtifact.Source(jobId, nonce, e.getString("commit"), receipt.branch, receipt.target,
            build.getLong("run"), build.getInt("attempt"), build.getString("fingerprint"))
        DevelopmentArtifact.validateSource(source)
        job.getJSONObject("verification").put("SIGNED_APK", "RUNNING").put("DEVICE_INSTALLED", "PENDING")
        analysisTransition(job, job.getString("state"), "artifact.read.started"); save(jobs); source
    }
    private fun currentArtifact(job: JSONObject, source: DevelopmentArtifact.Source): Boolean {
        val e = job.getJSONObject("engineering")
        return job.getString("state") == "READY_FOR_REVIEW" && e.optJSONObject("artifact")?.optString("readLease") == source.lease &&
            e.getString("commit") == source.commit && e.getJSONObject("build").getString("fingerprint") == source.buildFingerprint
    }
    internal fun recordArtifact(source: DevelopmentArtifact.Source, record: DevelopmentArtifact.Record): Boolean = checkpoint.transaction {
        DevelopmentArtifact.validateSource(source); DevelopmentArtifact.validate(record)
        require(record.commit == source.commit && record.packageName == DevelopmentArtifact.packageName(source.target))
        val jobs = load(); val job = jobs.single { it.getString("id") == source.job }
        if (!currentArtifact(job, source)) return@transaction false
        artifactReady(job)
        job.getJSONObject("engineering").put("artifact", JSONObject().put("record", record.json())
            .put("buildFingerprint", source.buildFingerprint))
        job.getJSONObject("verification").put("SIGNED_APK", "PASSED").put("DEVICE_INSTALLED", "PENDING")
        analysisTransition(job, job.getString("state"), "artifact.apk.verified"); save(jobs); true
    }
    internal fun failArtifact(source: DevelopmentArtifact.Source, problem: DevelopmentArtifact.Problem) = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == source.job }
        if (!currentArtifact(job, source)) return@transaction false
        job.getJSONObject("engineering").getJSONObject("artifact").apply { remove("readLease"); put("problem", problem.name) }
        job.getJSONObject("verification").put("SIGNED_APK", if (problem == DevelopmentArtifact.Problem.CANCELLED) "BLOCKED" else "FAILED")
        analysisTransition(job, job.getString("state"), "artifact.read.failed"); save(jobs); true
    }
    internal fun artifactRecord(jobId: String): DevelopmentArtifact.Record? = checkpoint.transaction {
        val job = load().single { it.getString("id") == jobId }
        job.optJSONObject("engineering")?.optJSONObject("artifact")?.optJSONObject("record")?.let(DevelopmentArtifact::record)
    }
    internal fun beginInstallation(jobId: String, revision: Long, apkSha256: String, personal: Boolean): DevelopmentArtifact.Installation = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == jobId }
        engineeringPolicy(job, personal); artifactReady(job)
        require(job.getLong("revision") == revision && job.getJSONObject("verification").getString("SIGNED_APK") == "PASSED")
        require(jobs.none { it.optJSONObject("engineering")?.optJSONObject("artifact")?.optJSONObject("installation")?.optString("status") in DevelopmentArtifact.activeInstalls })
        val artifact = job.getJSONObject("engineering").getJSONObject("artifact")
        val record = DevelopmentArtifact.record(artifact.getJSONObject("record")); require(record.apkSha256 == apkSha256)
        val nonce = java.util.UUID.randomUUID().toString()
        artifact.put("installation", JSONObject().put("nonce", nonce).put("session", -1).put("status", "PREPARING"))
        job.getJSONObject("verification").put("DEVICE_INSTALLED", "RUNNING")
        analysisTransition(job, job.getString("state"), "installation.owner.requested"); save(jobs)
        DevelopmentArtifact.Installation(jobId, nonce, record)
    }
    private fun currentInstallation(job: JSONObject, request: DevelopmentArtifact.Installation): JSONObject? {
        if (job.getString("state") != "READY_FOR_REVIEW") return null
        val artifact = job.getJSONObject("engineering").optJSONObject("artifact") ?: return null
        val install = artifact.optJSONObject("installation") ?: return null
        return install.takeIf { it.getString("nonce") == request.nonce && it.getInt("session") == request.session &&
            it.getString("status") in DevelopmentArtifact.activeInstalls && DevelopmentArtifact.record(artifact.getJSONObject("record")) == request.record }
    }
    internal fun bindInstallation(request: DevelopmentArtifact.Installation, session: Int): DevelopmentArtifact.Installation = checkpoint.transaction {
        require(session >= 0 && request.session == -1)
        val jobs = load(); val job = jobs.single { it.getString("id") == request.job }
        val install = requireNotNull(currentInstallation(job, request)); require(install.getString("status") == "PREPARING")
        install.put("session", session)
        analysisTransition(job, job.getString("state"), "installation.session.recorded"); save(jobs); request.copy(session = session)
    }
    internal fun submitInstallation(request: DevelopmentArtifact.Installation): Boolean = checkpoint.transaction {
        val jobs = load(); val job = jobs.single { it.getString("id") == request.job }
        val install = currentInstallation(job, request) ?: return@transaction false
        require(request.session >= 0 && install.getString("status") == "PREPARING")
        install.put("status", "SUBMITTED")
        analysisTransition(job, job.getString("state"), "installation.commit.requested"); save(jobs); true
    }
    internal fun installationStatus(request: DevelopmentArtifact.Installation, status: String, androidStatus: Int? = null): Boolean = checkpoint.transaction {
        require(status in DevelopmentArtifact.installStates && status !in setOf("PREPARING", "SUBMITTED"))
        require(androidStatus == null || androidStatus in -1..7)
        val jobs = load(); val job = jobs.single { it.getString("id") == request.job }
        val install = currentInstallation(job, request) ?: return@transaction false
        if (install.getString("status") == status) return@transaction false
        install.put("status", status)
        if (androidStatus != null) install.put("androidStatus", androidStatus)
        // Android success or matching installed bytes is still not an acceptance-test result.
        job.getJSONObject("verification").put("DEVICE_INSTALLED", if (status == "FAILED") "FAILED" else if (status in setOf("PACKAGE_PRESENT", "CANCELLED", "OUTCOME_UNKNOWN")) "BLOCKED" else "RUNNING")
        analysisTransition(job, job.getString("state"), "installation.${status.lowercase()}"); save(jobs); true
    }
    internal fun pendingInstallations(): List<DevelopmentArtifact.Installation> = checkpoint.transaction {
        load().mapNotNull { job ->
            val artifact = job.optJSONObject("engineering")?.optJSONObject("artifact") ?: return@mapNotNull null
            val install = artifact.optJSONObject("installation") ?: return@mapNotNull null
            if (install.getString("status") !in DevelopmentArtifact.activeInstalls || job.getString("state") != "READY_FOR_REVIEW") return@mapNotNull null
            DevelopmentArtifact.Installation(job.getString("id"), install.getString("nonce"),
                DevelopmentArtifact.record(artifact.getJSONObject("record")), install.getInt("session"))
        }
    }
    internal fun installationRecord(jobId: String): DevelopmentArtifact.Installation? = checkpoint.transaction {
        val job = load().single { it.getString("id") == jobId }
        val artifact = job.optJSONObject("engineering")?.optJSONObject("artifact") ?: return@transaction null
        val install = artifact.optJSONObject("installation") ?: return@transaction null
        DevelopmentArtifact.Installation(jobId, install.getString("nonce"), DevelopmentArtifact.record(artifact.getJSONObject("record")), install.getInt("session"))
    }
    fun localSnapshot(): List<JSONObject> = checkpoint.transaction {
        load().map { view(it).put("caller", it.getString("caller")) }
    }
}
