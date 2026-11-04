package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperFailureRepairDraftTest {
    private val path = "developer/src/main/java/com/jarvis/developer/VoiceLatency.kt"
    private val another = "developer/src/main/java/com/jarvis/developer/VoiceService.kt"
    private val proposalId = "a".repeat(64)
    private val sha = "b".repeat(40)
    private val branch = PatchEngine.taskBranch("voice-task", proposalId, PatchEngine.Target.DEVELOPER)
    private val result = PatchPublisher.CommitResult("voice-task", "OpenAI", "gpt-4.1-mini",
        proposalId, listOf(path), "c".repeat(40), sha, branch, "",
        "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
    private val ci = PatchPublisher.CiStatus(PatchPublisher.CiState.FAILED,
        "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/23", "failure")
    private fun evidence(log: String, revision: String = sha, sourceBranch: String = branch,
                         run: Long = 23): BuildDiagnostics.Report =
        BuildDiagnostics.parse(revision, sourceBranch, run, 1, "completed", "failure", log,
            PatchEngine.Target.DEVELOPER)
    private fun compiler(p: String = path) = "e: $p:12:3 Unresolved reference: private-raw-log-content\n"

    @Test fun compilerFailureInEditedSourceCreatesStrictNewSourceBoundRequest() {
        val candidate = DeveloperFailureRepairDraft.from(result, ci, evidence(compiler()))
        assertNotNull(candidate)
        assertEquals(listOf(path), candidate!!.paths)
        assertEquals(sha, candidate.base)
        assertEquals(branch, candidate.branch)
        assertTrue(candidate.task.contains("VoiceLatency.kt:12"))
        assertTrue(candidate.task.contains("NEW complete scoped source replacement"))
        assertFalse(candidate.task.contains("private-raw-log-content"))
        assertFalse(candidate.task.contains("HTTP"))
    }

    @Test fun outOfScopeCompilerAndTestOrInfrastructureErrorsCannotBecomeARepair() {
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence(compiler(another))))
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence("SomeTest > fails FAILED\n")))
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence("Could not GET dependency: HTTP 503\n" + compiler())))
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence("e: invalid-location:1:1 syntax\n")))
    }

    @Test fun sourceAndWorkflowMismatchCannotPrepareAnApprovableRepair() {
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence(compiler(), revision = "d".repeat(40))))
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence(compiler(), sourceBranch = "feature/jarvis-developer")))
        assertNull(DeveloperFailureRepairDraft.from(result, ci, evidence(compiler(), run = 99)))
        assertNull(DeveloperFailureRepairDraft.from(result, ci.copy(conclusion = "timed_out"), evidence(compiler())))
        assertNull(DeveloperFailureRepairDraft.from(result, ci.copy(state = PatchPublisher.CiState.RUNNING), evidence(compiler())))
        val personal = result.copy(target = PatchEngine.Target.PERSONAL)
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperFailureRepairDraft.from(personal, ci, evidence(compiler()))
        }
    }

    @Test fun partialEvidenceAndNonCompilerFailuresDoNotMakeSuggestions() {
        val report = evidence(compiler())
        assertNull(DeveloperFailureRepairDraft.from(result, ci,
            BuildDiagnostics.incomplete(report, "Build evidence was incomplete")))
        assertNull(DeveloperFailureRepairDraft.from(result, ci,
            evidence("FAILURE: Build failed\n")))
    }
}
