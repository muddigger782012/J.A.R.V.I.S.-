package com.jarvis.developer

import android.content.Context
import androidx.work.*
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Optional process-memory credentials. Never part of a checkpoint, WorkRequest or IPC payload. */
internal object DevelopmentEngineeringCredentials {
    private val values = ConcurrentHashMap<String, String>()
    fun remember(job: String, token: String) {
        require(token.length <= 4096 && token.none { it.isISOControl() } && (values.containsKey(job) || values.size < 64))
        if (token.isBlank()) values.remove(job) else values[job] = token
    }
    fun token(job: String) = values[job].orEmpty()
    fun forget(job: String) { values.remove(job) }
}

/** Restartable GET-only verification. A persisted job can never restore a writer or approval. */
class DevelopmentEngineeringWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val cancellation = PatchPreviewCancellation()
    override fun doWork(): Result {
        val job = inputData.getString("job") ?: return Result.failure()
        val inbox = DevelopmentJobInbox(File(applicationContext.noBackupFilesDir.canonicalFile, "development-inbox"))
        val token = DevelopmentEngineeringCredentials.token(job)
        val retry = DevelopmentEngineeringRead.run(inbox, job, Wire.personalPackage(applicationContext.packageName), cancellation,
            inspect = { PatchPublisher.inspectReceipt(token, it, cancellation) },
            feedback = { receipt, commit, stop -> BuildFeedback.read(token, receipt.branch, commit, receipt.target, stop) })
        if (!retry) DevelopmentEngineeringCredentials.forget(job)
        return if (retry && !isStopped) Result.retry() else Result.success()
    }
    override fun onStopped() { cancellation.cancel(); super.onStopped() }
}

internal object DevelopmentEngineeringRead {
    fun run(inbox: DevelopmentJobInbox, job: String, personal: Boolean, cancellation: PatchPreviewCancellation,
        inspect: (PatchPublisher.OutcomeReceipt) -> PatchPublisher.CommitResult? = { PatchPublisher.inspectReceipt("", it, cancellation) },
        feedback: (PatchPublisher.OutcomeReceipt, String, PatchPreviewCancellation) -> BuildDiagnostics.Report = { receipt, commit, stop ->
            BuildFeedback.read("", receipt.branch, commit, receipt.target, stop)
        }): Boolean {
        val read = inbox.claimEngineeringRead(job, personal) ?: return false
        return try {
            cancellation.check()
            val commit = read.commit ?: run {
                val result = inspect(read.receipt)
                cancellation.check()
                if (result == null) return inbox.engineeringReadFailed(read, absent = true)
                if (!inbox.observedEngineering(read, result)) return false
                result.commitSha
            }
            val report = feedback(read.receipt, commit, cancellation)
            cancellation.check()
            inbox.recordEngineeringBuild(read, report)
        } catch (_: Exception) {
            inbox.engineeringReadFailed(read)
        }
    }
}

internal object DevelopmentEngineeringScheduler {
    private fun name(job: String) = "approved-engineering-evidence-$job"
    fun accepted(context: Context, data: org.json.JSONObject) {
        if (data.getString("state") in setOf("PAUSED", "CANCELLED")) {
            DevelopmentEngineeringCredentials.forget(data.getString("id"))
            WorkManager.getInstance(context).cancelUniqueWork(name(data.getString("id")))
        } else recover(context)
    }
    fun recover(context: Context) {
        val inbox = DevelopmentJobInbox(File(context.noBackupFilesDir.canonicalFile, "development-inbox"))
        inbox.recoverableEngineeringJobs().forEach { job ->
            val work = OneTimeWorkRequestBuilder<DevelopmentEngineeringWorker>()
                .setInputData(workDataOf("job" to job))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(job), ExistingWorkPolicy.KEEP, work)
        }
    }
}
