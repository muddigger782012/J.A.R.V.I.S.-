package com.jarvis.developer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import java.io.File

/** Persistent public-repository reads only. No provider, source writer, token or release API. */
class DevelopmentAnalysisWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    private val cancellation = PatchPreviewCancellation()
    override fun doWork(): Result {
        val jobId = inputData.getString("job") ?: return Result.failure()
        val inbox = DevelopmentJobInbox(File(applicationContext.noBackupFilesDir.canonicalFile, "development-inbox"))
        val lease = runCatching { inbox.claimAnalysis(jobId, Wire.personalPackage(applicationContext.packageName)) }
            .getOrNull() ?: return Result.failure()
        return try {
            cancellation.check()
            val target = if (lease.request.kind == "source.personal.analysis.request") PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
            val (inventory, plan) = RepositoryIntelligence.analyzeAuthorizedSource(lease.request.body, target, cancellation)
            cancellation.check()
            require(inventory.commitSha == plan.commitSha && plan.files.all { file -> file.source in inventory.files })
            if (inbox.finishAnalysis(lease, plan)) Result.success() else Result.failure()
        } catch (_: Exception) {
            runCatching { if (isStopped) inbox.interruptAnalysis(lease) else inbox.failAnalysis(lease) }
            Result.failure()
        }
    }
    override fun onStopped() { cancellation.cancel(); super.onStopped() }
}

internal object DevelopmentAnalysisScheduler {
    private fun name(job: String) = "public-source-analysis-$job"
    fun accepted(context: Context, result: org.json.JSONObject) {
        val job = result.getString("id")
        if (result.getString("state") in setOf("CANCELLED", "PAUSED")) {
            WorkManager.getInstance(context).cancelUniqueWork(name(job))
        } else if (result.getString("state") in setOf("QUEUED", "RUNNING")) {
            recover(context)
        }
    }
    fun recover(context: Context) {
        val inbox = DevelopmentJobInbox(File(context.noBackupFilesDir.canonicalFile, "development-inbox"))
        val manager = WorkManager.getInstance(context)
        inbox.recoverableAnalysisJobs().forEach { job ->
            val work = OneTimeWorkRequestBuilder<DevelopmentAnalysisWorker>()
                .setInputData(workDataOf("job" to job))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniqueWork(name(job), ExistingWorkPolicy.KEEP, work)
        }
    }
}
