package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperChatBuildReceiptTest {
    private val proposalId = "a".repeat(64)
    private val branch = PatchEngine.taskBranch("sample", proposalId, PatchEngine.Target.DEVELOPER)
    private val result = PatchPublisher.CommitResult("sample", "OpenAI", "gpt-4.1-mini", proposalId,
        listOf("developer/src/main/java/com/jarvis/developer/VoiceLatency.kt"),
        "b".repeat(40), "c".repeat(40), branch, "private source material", "feature/jarvis-developer")

    @Test fun fullSourceValidatedFingerprintIsShownForExplicitChatApproval() {
        val message = DeveloperChatBuildReceipt.preview("task", 2, "a".repeat(64))
        assertTrue(message.contains("Approve Developer diff " + "a".repeat(16)))
        assertTrue(message.contains("AFTER reviewing"))
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperChatBuildReceipt.preview("task", 2, "b".repeat(12))
        }
    }

    @Test fun passAndPreviewRemainSeparateFromReleaseClaims() {
        assertTrue(DeveloperChatBuildReceipt.preview("sample", 1).contains("No source write"))
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperChatBuildReceipt.preview("bad space", 1)
        }
        val message = DeveloperChatBuildReceipt.commit(result,
            PatchPublisher.CiStatus(PatchPublisher.CiState.PASSED), null, false)
        assertTrue(message.contains("CI: passed"))
        assertTrue(message.contains("NOT published"))
        assertFalse(message.contains("private source material"))
        assertFalse(message.contains("gpt-4.1-mini"))
    }

    @Test fun verifiedFailedRunSuggestsRepairOnlyWithNewApproval() {
        val ci = PatchPublisher.CiStatus(PatchPublisher.CiState.FAILED,
            "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/runs/23", "failure")
        val report = BuildDiagnostics.parse(result.commitSha, result.branch, 23, 1,
            "completed", "failure",
            "e: developer/src/main/java/com/jarvis/developer/VoiceLatency.kt:2:1 Unresolved reference: secretName\n")
        val message = DeveloperChatBuildReceipt.commit(result, ci, report, true)
        assertTrue(message.contains("Prepare Developer repair"))
        assertFalse(message.contains("secretName"))
        val unknown = DeveloperChatBuildReceipt.commit(result, ci, null, false)
        assertTrue(unknown.contains("failure evidence unverified"))
        assertFalse(unknown.contains("Prepare Developer repair"))
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperChatBuildReceipt.commit(result, ci,
                report.copy(commitSha = "d".repeat(40)), true)
        }
    }

    @Test fun noCiAndUncertainWriteCannotBeCalledInstalledOrPassed() {
        val message = DeveloperChatBuildReceipt.commit(result, null, null, false)
        assertTrue(message.contains("CI: not verified"))
        assertFalse(message.contains("CI: passed"))
        assertTrue(DeveloperChatBuildReceipt.uncertain().contains("outcome is uncertain"))
    }
}
