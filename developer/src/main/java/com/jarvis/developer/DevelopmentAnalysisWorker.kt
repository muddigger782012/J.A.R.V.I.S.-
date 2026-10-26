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
        val completed = DevelopmentAnalysisExecution.run(inbox, jobId, Wire.personalPackage(applicationContext.packageName),
            cancellation, { isStopped }, { android.os.SystemClock.elapsedRealtime() },
            RepositoryIntelligence::analyzeAuthorizedSource) { category ->
                android.util.Log.w("DevelopmentBridge", "analysis.failure $category")
            }
        return if (completed) Result.success() else Result.failure()
    }
    override fun onStopped() { cancellation.cancel(); super.onStopped() }
}

/** The worker and deterministic regressions use this same execution boundary. */
internal object DevelopmentAnalysisExecution {
    fun run(inbox: DevelopmentJobInbox, jobId: String, personal: Boolean,
        cancellation: PatchPreviewCancellation, stopped: () -> Boolean, elapsed: () -> Long,
        read: (String, PatchEngine.Target, PatchPreviewCancellation, (String) -> Unit) -> Pair<RepositoryAnalysis.Inventory, RepositoryAnalysis.AnalysisPlan>,
        log: (String) -> Unit = {}): Boolean {
        val lease = runCatching { inbox.claimAnalysis(jobId, personal) }.getOrNull() ?: return false
        val started = elapsed()
        fun progress(phase: String) {
            if (!inbox.progressAnalysis(lease, phase, elapsed() - started)) cancellation.cancel()
            cancellation.check()
        }
        return try {
            cancellation.check()
            val target = if (lease.request.kind == "source.personal.analysis.request") PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
            val (inventory, plan) = read(lease.request.body, target, cancellation, ::progress)
            cancellation.check()
            require(inventory.commitSha == plan.commitSha && plan.files.all { file -> file.source in inventory.files })
            progress("REPORT")
            inbox.finishAnalysis(lease, plan)
        } catch (failure: Throwable) {
            val category = when (failure) {
                is StackOverflowError -> "STACK_LIMIT"
                is OutOfMemoryError -> "MEMORY_LIMIT"
                is LinkageError -> "PLATFORM_LINKAGE"
                is Error -> "INTERNAL_ERROR"
                else -> "SOURCE_READ_UNVERIFIED"
            }
            runCatching { log(category) }
            runCatching { if (stopped()) inbox.interruptAnalysis(lease)
                else inbox.failAnalysis(lease, if (failure is Error) "SOURCE_ANALYSIS_INTERNAL" else "SOURCE_READ_UNVERIFIED") }
            if (failure is VirtualMachineError && failure !is StackOverflowError || failure is ThreadDeath) throw failure
            false
        }
    }
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
