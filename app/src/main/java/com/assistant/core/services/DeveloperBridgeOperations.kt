package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentPeerIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Typed development requests stay separate from AI interpretations, command learning and approvals. */
class DeveloperBridgeOperations(private val context: Context) {
    private val outbox = DevelopmentReviewOutbox(PersonalStateFile(context, "developer-outbox.json"))
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
            (if (reply.optBoolean("publicSourceAnalysis")) "Queued public source inspection is available with bounded, partial context. " else "") +
            (if (reply.optBoolean("approvedEngineeringJobs")) "In Developer, you can stage a source review, approve its complete diff and track the verified commit, build and test evidence. Interrupted writes are checked without automatically repeating them. "
            else "This Developer version handles read-only reviews. ") +
            (if (reply.optBoolean("verifiedArtifactReview")) "Developer can inspect an APK from the job's exact source and request a separately approved Android installation. " else "") +
            (if (reply.optBoolean("artifactReviewHandoff")) "You can ask me to open this job's verified APK review in Developer. " else "") +
            "This connection cannot grant source approval or install an application. Android privileges are unchanged."
    }
    fun submit(body: String): String {
        return submitRetained(outbox.prepare(body, selectedMode().wireValue))
    }
    fun analyzeSource(personal: Boolean): String = submitRetained(outbox.prepare(
        if (personal) "Inspect Personal Jarvis voice recognition, microphone lifecycle and AI connection reliability. Identify source dependencies and evidence; do not modify source."
        else "Inspect Jarvis Developer task execution, approval enforcement and build reliability. Identify source dependencies and evidence; do not modify source.",
        selectedMode().wireValue, if (personal) "source.personal.analysis.request" else "source.developer.analysis.request"))
    private fun submitRetained(request: Wire.Request): String {
        if (request.mode != selectedMode().wireValue) throw Wire.Rejected("POLICY_REQUIRED")
        val response = try { DevelopmentBridgeClient(context).request(request) }
            catch (failure: Exception) { outbox.record(request, null); throw failure }
        outbox.record(request, response)
        if (response.getString("code") != "OK") throw Wire.Rejected(response.getString("code"))
        return "Developer acknowledged the queued read-only request. " + render(response.getJSONObject("data"))
    }
    fun retryPending(): String {
        val queued = outbox.pending(selectedMode().wireValue)
        if (queued.isEmpty()) return "No unacknowledged reviews match the current approval mode."
        val results = queued.take(3).map { request ->
            runCatching { submitRetained(request) }.fold({ "A retained review was acknowledged." }, { failure(it) })
        }
        return results.joinToString("\n") + if (queued.size > 3) "\nFurther retained reviews remain for the next retry." else ""
    }
    fun status(): String = render(call(Wire.Request(UUID.randomUUID().toString(), "task.status.request",
        mode = selectedMode().wireValue, jobId = outbox.latestJob())))
    fun reviewApk(isCurrent: () -> Boolean = { true }): String = reviewApkForJob(outbox.latestJob(), isCurrent)
    internal fun reviewApkForJob(job: String, isCurrent: () -> Boolean = { true }): String {
        if (!isCurrent()) throw Wire.Rejected("CANCELLED")
        val mode = selectedMode().wireValue
        val state = call(Wire.Request(UUID.randomUUID().toString(), "task.status.request", mode = mode, jobId = job))
        if (!isCurrent()) throw Wire.Rejected("CANCELLED")
        val review = call(Wire.Request(UUID.randomUUID().toString(), Wire.ARTIFACT_REVIEW, mode = mode,
            jobId = job, expectedRevision = state.getLong("revision")))
        val delivery = review.getJSONObject("artifactReview")
        val ticket = delivery.getString("ticket")
        if (review.getString("id") != job || !ticket.matches(Regex("[a-f0-9-]{36}")) ||
            delivery.getLong("expires") - System.currentTimeMillis() !in 1..120_000L) throw Wire.Rejected("CONFLICT")
        if (!isCurrent() || selectedMode().wireValue != mode) throw Wire.Rejected("CANCELLED")
        val uid = context.packageManager.getApplicationInfo(Wire.DEVELOPER_PACKAGE, 0).uid
        if (DevelopmentPeerIdentity.packageForUid(context, uid, setOf(Wire.DEVELOPER_PACKAGE)) == null)
            throw Wire.Rejected("UNAUTHORIZED")
        context.startActivity(Intent(Wire.ARTIFACT_REVIEW_ACTION)
            .setComponent(ComponentName(Wire.DEVELOPER_PACKAGE, "com.jarvis.developer.MainActivity"))
            .putExtra(Wire.ARTIFACT_REVIEW_EXTRA, ticket)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        return "I requested Developer's verified APK review for this job. Review the APK there before approving installation. Android's permission and confirmation are still required; no installation was requested."
    }
    fun list(): String {
        val data = call(Wire.Request(UUID.randomUUID().toString(), "task.list.request", mode = selectedMode().wireValue))
        val jobs = data.getJSONArray("jobs")
        return "${data.getInt("total")} Developer reviews recorded.\n" + (0 until jobs.length()).joinToString("\n\n") { render(jobs.getJSONObject(it)) }
    }
    fun control(kind: String): String {
        require(kind in setOf("task.cancel.request", "task.pause.request", "task.resume.request", "task.retry.request"))
        val jobId = outbox.latestJob()
        val state = call(Wire.Request(UUID.randomUUID().toString(), "task.status.request", mode = selectedMode().wireValue, jobId = jobId))
        return render(call(Wire.Request(UUID.randomUUID().toString(), kind, mode = selectedMode().wireValue,
            jobId = jobId, expectedRevision = state.getLong("revision"))))
    }
    private fun render(value: JSONObject): String = "Task state: ${value.getString("state")}. " +
        "Attempt ${value.getInt("attempts")}." + when (value.optString("phase")) {
            "DISCOVERY" -> " Repository discovery started."
            "INDEXING" -> " Source indexing started."
            "SOURCE_READ" -> " Source retrieval started."
            "PLANNING" -> " Source analysis started."
            "REPORT" -> " Source report preparation started."
            else -> ""
        } + "\n" + Wire.Stage.values().joinToString("\n") {
            "${it.name.replace('_', ' ')}: ${value.getJSONObject("verification").getString(it.name)}"
        } + (if (value.optJSONObject("artifactReview")?.optBoolean("consumed") == true)
            "\nDeveloper acknowledged this job's APK review handoff. Installation approval and device acceptance remain separate." else "") +
        (value.optJSONObject("analysis")?.let { "\nRead-only source context: ${it.getInt("inspected")} files inspected, ${it.getInt("planned")} references planned. Coverage is partial; inspect the report in Developer." } ?: "") +
        (if (value.optString("problem") == "SOURCE_READ_UNVERIFIED") "\nThe public source read could not be verified. Check Developer's internet connection and repository access, then retry the task explicitly." else "") +
        (if (value.optString("problem") == "SOURCE_OUTCOME_UNKNOWN") "\nThe approved write outcome is unverified. Developer can check the expected branch using read-only requests; the old approval cannot repeat the write." else "") +
        (if (value.getString("state") == "CANCELLED" && value.optJSONObject("engineering")?.optBoolean("approvalConsumed") == true)
            "\nCancellation stops later steps. An approved commit request may already have reached GitHub; cancellation does not undo it." else "") +
        (if (value.optString("problem") == "ENGINEERING_EVIDENCE_UNAVAILABLE") "\nBuild or test evidence remains unverified. Check repository access and the read budget in Developer. No additional source write was authorized." else "") +
        (if (value.optString("problem") == "SOURCE_ANALYSIS_INTERNAL") "\nDeveloper's source inspection encountered an internal failure. Review its diagnostic category before an explicit retry; no repair or build was completed." else "") +
        (value.optJSONObject("artifact")?.let { artifact -> "\nAPK inspection: " +
            (if (artifact.optBoolean("stale")) "Earlier artifact; inspect the current verified build in Developer before another installation request. " else "") +
            (if (artifact.has("versionCode")) "${artifact.getString("packageName")} version ${artifact.getLong("versionCode")}, ${artifact.getString("signing")} signing. " else "No verified APK recorded. ") +
            when (artifact.optString("installation")) {
                "PACKAGE_PRESENT" -> "The installed package matches the requested APK. Device acceptance remains unverified."
                "PENDING_USER_ACTION" -> "Android is waiting for installation confirmation in Developer."
                "PREPARING", "SUBMITTED" -> "An owner-approved Android installation request is in progress; completion is unverified."
                "OUTCOME_UNKNOWN" -> "The installation outcome is unknown. Developer can read the installed package without repeating installation."
                "FAILED" -> "Android installation failed. Review the build and recorded status in Developer."
                "CANCELLED" -> "Installation tracking was cancelled; this does not undo an operation Android already completed."
                else -> "No Android installation has been verified."
            } + " " + Wire.installationDiagnostic(artifact.optInt("androidStatus", Int.MIN_VALUE)) } ?: "") +
        "\nA queued request or source analysis is not a verified source change, successful build, publication or installation."

    companion object {
        fun failure(failure: Throwable): String = when ((failure as? Wire.Rejected)?.code) {
            "COMPANION_UNAVAILABLE", "UNSUPPORTED_VERSION", "UNSUPPORTED_OPERATION" -> "The compatible Developer bridge is unavailable. Install or open the independent Developer application and retry. A queued request is not a completed repair."
            "UNAUTHORIZED", "POLICY_REQUIRED" -> "Developer identity or approval policy could not be verified. No development operation was authorized."
            "TIMEOUT", "BUSY" -> "Developer has not acknowledged this request. Retrying the same retained review reuses its request ID; acceptance and completion remain unverified."
            "NOT_FOUND" -> "No acknowledged Developer job is available for this command. Submit a review first."
            "CONFLICT" -> "The Developer job or request changed. Review its current status before issuing another control."
            "UNVERIFIED_ARTIFACT" -> "This job does not yet have a current verified APK. Finish its build and APK inspection in Developer, then ask me to open its APK review."
            "CANCELLED" -> "The development handoff was cancelled. No installation was requested."
            "QUEUE_FULL", "AUDIT_FULL" -> "The bounded development history is full. Review it in Developer; no existing job was discarded."
            "TOO_LARGE" -> "This review exceeds the bounded transfer size. Select a smaller diagnostic review; no queue acknowledgement was verified."
            else -> "Development status could not be verified. No build, source change or installation success is inferred."
        }
    }
}
