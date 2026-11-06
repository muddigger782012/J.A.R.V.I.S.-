package com.jarvis.developer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeveloperTaskTriageTest {
    // An ordinary editable source path; MainActivity is intentionally protected.
    private val path = "developer/src/main/java/com/jarvis/developer/ChatTimeline.kt"

    private fun plan() = TaskPlanner.Plan(
        id = "plan-1",
        task = "Repair Developer chat",
        baseCommitSha = "a".repeat(40),
        analysisId = "b".repeat(64),
        scope = listOf(path),
        steps = listOf(
            TaskPlanner.Step("analyze", "Review analysis", "Review exact scope", emptyList(), emptyList()),
            TaskPlanner.Step("implement-1", "Implement ChatTimeline", "Scoped review only",
                listOf(path), listOf("analyze")),
            TaskPlanner.Step("validate", "Validate exact build", "Verify CI", emptyList(), listOf("implement-1")),
            TaskPlanner.Step("review", "Review evidence", "Owner review", emptyList(), listOf("validate"))
        ),
        decisions = listOf(TaskPlanner.Decision(1, null, "Created local checkpoint", 1L)),
        revision = 0L,
        paused = false,
        updatedAtMillis = 1L
    )

    private fun job(state: String, build: String = "PENDING", signing: String = "PENDING") =
        JSONObject().put("state", state).put("summary",
            "DO NOT ECHO PRIVATE TOKEN = ghp_sensitive_example_12345")
            .put("verification", JSONObject()
                .put("SOURCE_COMMITTED", if (build == "PASSED") "PASSED" else "PENDING")
                .put("BUILD_SUCCESSFUL", build)
                .put("TESTS_PASSED", "PENDING")
                .put("SIGNED_APK", signing)
                .put("DEVICE_INSTALLED", "BLOCKED"))

    @Test fun commonNaturalQuestionsAreReadOnlyAndExact() {
        assertTrue(DeveloperTaskTriage.isRequest("What's blocking Developer?"))
        assertTrue(DeveloperTaskTriage.isRequest("What should Developer do next?"))
        assertTrue(DeveloperTaskTriage.isRequest("Diagnose Developer workflow"))
        assertFalse(DeveloperTaskTriage.isRequest("Fix Developer workflow"))
        assertFalse(DeveloperTaskTriage.isRequest("Approve Developer diff"))
        assertFalse(DeveloperTaskTriage.isRequest("Publish Developer update"))
    }

    @Test fun emptyLocalRecordsDoNotInventQueuedBuildsOrCompletedWork() {
        val text = DeveloperTaskTriage.summarize(emptyList(), emptyList())
        assertTrue(text.contains("Latest job: NONE"))
        assertTrue(text.contains("Latest plan: NONE"))
        assertTrue(text.contains("No live GitHub CI"))
        assertFalse(text.contains("PASSED"))
        assertFalse(text.contains("Source committed"))
    }

    @Test fun failedJobExplainsSafeRecoveryAndKeepsUnverifiedChecksVisible() {
        val text = DeveloperTaskTriage.summarize(listOf(plan()), listOf(job("FAILED", "FAILED")))
        assertTrue(text.contains("Latest job: FAILED"))
        assertTrue(text.contains("Build: FAILED"))
        assertTrue(text.contains("Tests: PENDING"))
        assertTrue(text.contains("Prepare Developer repair"))
        assertTrue(text.contains("Review Developer plan"))
        assertFalse(text.contains("ghp_sensitive"))
        assertFalse(text.contains("published successfully"))
    }

    @Test fun passingSignedArtifactDoesNotBecomeInstalledOrPublicRelease() {
        val text = DeveloperTaskTriage.summarize(emptyList(), listOf(
            job("READY_FOR_REVIEW", "PASSED", "PASSED")))
        assertTrue(text.contains("Signing: PASSED"))
        assertTrue(text.contains("Device: BLOCKED"))
        assertTrue(text.contains("does not establish publication or installation"))
    }

    @Test fun unknownOrOversizedEvidenceFailsClosedInsteadOfInventingEmptyHistory() {
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperTaskTriage.summarize(emptyList(), listOf(job("SUPER_SUCCESS")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperTaskTriage.summarize(emptyList(), List(65) { job("QUEUED") })
        }
    }

    @Test fun nextStepUsesVerifiedPlanCheckpointsButDoesNotClaimCiWasRun() {
        val initial = plan()
        TaskPlanner.validate(initial)
        val next = TaskPlanner.transition(initial, "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed analysis", 2L)
        val text = DeveloperTaskTriage.summarize(listOf(next), emptyList())
        assertTrue(text.contains("1/4 locally reported checkpoints"))
        assertTrue(text.contains("Continue Developer plan"))
        assertTrue(text.contains("No live GitHub CI"))
        assertFalse(text.contains("Build: PASSED"))
    }

    @Test fun uncertainCommitAlwaysWarnsAgainstBlindRetries() {
        val text = DeveloperTaskTriage.summarize(listOf(plan()), listOf(job("OUTCOME_UNKNOWN")))
        assertTrue(text.contains("outcome is uncertain"))
        assertTrue(text.contains("before any retry"))
        assertFalse(text.contains("retrying now"))
    }
}
