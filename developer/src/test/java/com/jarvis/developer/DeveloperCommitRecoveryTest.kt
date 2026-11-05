package com.jarvis.developer

import android.app.Activity
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DeveloperCommitRecoveryTest {
    private val first = "developer/src/main/java/com/jarvis/developer/Latency.kt"
    private val second = "developer/src/main/java/com/jarvis/developer/Network.kt"
    private val failedSha = "c".repeat(40)
    private val repairedSha = "e".repeat(40)
    private val originalRun = "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/23"
    private val repairRun = "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/24"

    private fun committed(task: String, proposal: String, paths: List<String>,
        parent: String, sha: String, branch: String): PatchPublisher.CommitResult =
        PatchPublisher.CommitResult(task, "Gemini", "gemini-2.5-pro",
            proposal, paths, parent, sha,
            PatchEngine.taskBranch(task, proposal, PatchEngine.Target.DEVELOPER), "",
            branch, PatchEngine.Target.DEVELOPER)

    private val original by lazy {
        committed("original-task", "b".repeat(64), listOf(first, second),
            "a".repeat(40), failedSha, "feature/jarvis-developer")
    }
    private val child by lazy {
        committed("repair-new-task", "f".repeat(64), listOf(first),
            failedSha, repairedSha, original.branch)
    }

    private fun activity() = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun restartCanReverifyPassingOriginalCommitAndAdvanceOnlyItsPlan() {
        val a = activity()
        val dir = Files.createTempDirectory("recovery-passing-original-").toFile()
        DeveloperCommitRecoveryStore(dir).save(original)
        var reads = 0
        var checkpointed = 0
        val output = DeveloperCommitRecovery.recheck(a, "test-only", PatchPreviewCancellation(),
            directory = { dir }, ciRead = { _, result ->
                assertEquals(original.copy(diff = ""), result)
                reads++
                PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED, repairRun, "success")
            }, failureRead = { _, _, _ -> error("No failed CI report expected") },
            recordDirect = { _, result, status ->
                assertEquals(original.commitSha, result.commitSha)
                assertEquals(repairRun, status.runUrl)
                checkpointed++
                "Original plan exact debug CI recorded"
            }, recordRepair = { _, _, _, _ -> error("No repair lineage expected") })
        assertEquals(1, reads)
        assertEquals(1, checkpointed)
        assertTrue(output.contains("Original plan exact debug CI recorded"))
        assertTrue(output.contains("No old approval"))
        assertFalse(output.contains("NOT installed by this operation.\nOriginal plan") )
    }

    @Test fun restartRechecksFailedOriginalParentBeforeRecordingPassingRepair() {
        val a = activity()
        val dir = Files.createTempDirectory("recovery-repair-parent-").toFile()
        val store = DeveloperCommitRecoveryStore(dir)
        store.save(original)
        store.save(child)
        var polls = 0
        var diagnostics = 0
        var recorded = 0
        val output = DeveloperCommitRecovery.recheck(a, "test-only", PatchPreviewCancellation(),
            directory = { dir },
            ciRead = { _, receipt ->
                polls++
                when (receipt.commitSha) {
                    original.commitSha -> PatchPublisher.CiStatus(PatchPublisher.CiState.FAILED,
                        originalRun, "failure")
                    child.commitSha -> PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED,
                        repairRun, "success")
                    else -> error("Unexpected commit")
                }
            },
            failureRead = { _, receipt, cancellation ->
                cancellation.check()
                assertEquals(original.commitSha, receipt.commitSha)
                diagnostics++
                BuildDiagnostics.parse(original.commitSha, original.branch, 23, 1,
                    "completed", "failure",
                    "e: $first:2:1 Unresolved reference: fixture_symbol\n",
                    PatchEngine.Target.DEVELOPER)
            },
            recordDirect = { _, _, _ -> error("Never mark repaired child as a direct task") },
            recordRepair = { _, origin, repaired, passed ->
                assertEquals(original.commitSha, origin.original.commitSha)
                assertEquals(original.branch, repaired.baseBranch)
                assertEquals(original.commitSha, repaired.baseCommitSha)
                assertEquals(repairedSha, repaired.commitSha)
                assertEquals(repairRun, passed.runUrl)
                recorded++
                "Original task repaired CI recorded"
            })
        assertEquals(2, polls)
        assertEquals(1, diagnostics)
        assertEquals(1, recorded)
        assertTrue(output.contains("Original task repaired CI recorded"))
        assertTrue(output.contains("No old approval"))
    }

    @Test fun failedOrUnfinishedCiCannotRecordCompletionOrStartProviderWork() {
        val a = activity()
        val dir = Files.createTempDirectory("recovery-unfinished-").toFile()
        DeveloperCommitRecoveryStore(dir).save(original)
        var checkpointed = 0
        val result = DeveloperCommitRecovery.recheck(a, "test-only", PatchPreviewCancellation(),
            directory = { dir },
            ciRead = { _, _ -> PatchPublisher.CiStatus(PatchPublisher.CiState.RUNNING,
                originalRun) },
            failureRead = { _, _, _ -> error("No report before terminal CI") },
            recordDirect = { _, _, _ -> checkpointed++; "Unexpected completion" },
            recordRepair = { _, _, _, _ -> error("No repair") })
        assertEquals(0, checkpointed)
        assertTrue(result.contains("not verified PASSED"))
        assertTrue(result.contains("no plan checkpoint"))
    }

    @Test fun unrelatedSavedParentNeverCompletesARepairedTask() {
        val a = activity()
        val dir = Files.createTempDirectory("recovery-missing-parent-").toFile()
        DeveloperCommitRecoveryStore(dir).save(child)
        var reads = 0
        val result = DeveloperCommitRecovery.recheck(a, "test-only", PatchPreviewCancellation(),
            directory = { dir },
            ciRead = { _, receipt ->
                assertEquals(child.commitSha, receipt.commitSha)
                reads++
                PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED, repairRun, "success")
            },
            failureRead = { _, _, _ -> error("Missing parent must never read logs") },
            recordDirect = { _, _, _ -> error("No direct checkpoint") },
            recordRepair = { _, _, _, _ -> error("No repaired checkpoint") })
        assertEquals(1, reads)
        assertTrue(result.contains("no eligible, unchanged original"))
    }

    @Test fun cancelledRecoveryCannotReadNetworkOrTouchPlan() {
        val a = activity()
        val dir = Files.createTempDirectory("recovery-cancelled-").toFile()
        DeveloperCommitRecoveryStore(dir).save(original)
        val cancellation = PatchPreviewCancellation()
        cancellation.cancel()
        assertThrows(Exception::class.java) {
            DeveloperCommitRecovery.recheck(a, "test-only", cancellation,
                directory = { dir }, ciRead = { _, _ -> error("No network after cancellation") },
                recordDirect = { _, _, _ -> error("No plan change") })
        }
    }
}
