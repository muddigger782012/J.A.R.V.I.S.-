package com.jarvis.developer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeveloperProgressReportTest {
    private val sourcePath = "developer/src/main/java/com/jarvis/developer/VoiceLatency.kt"

    private fun plan(paused: Boolean = false): TaskPlanner.Plan {
        val steps = listOf(
            TaskPlanner.Step("analyze", "Analyze repository", "Read source only", emptyList(), emptyList()),
            TaskPlanner.Step("implement", "Implement voice fix", "Review a scoped diff", listOf(sourcePath), listOf("analyze")),
            TaskPlanner.Step("validate", "Verify", "Run exact-source checks", emptyList(), listOf("implement"))
        )
        return TaskPlanner.Plan("voice-task", "Improve voice", "a".repeat(40), "b".repeat(64),
            listOf(sourcePath), steps, listOf(TaskPlanner.Decision(1, null, "Created read-only plan", 1L)),
            0, paused, 1L).also(TaskPlanner::validate)
    }

    @Test fun chatRecognizesExplicitProgressRequestsOnly() {
        assertTrue(DeveloperProgressReport.isRequest("Show development progress"))
        assertTrue(DeveloperProgressReport.isRequest("What is the status of my development tasks?"))
        assertFalse(DeveloperProgressReport.isRequest("Build a development progress dashboard"))
        assertFalse(DeveloperProgressReport.isRequest("Proceed to the next milestone"))
    }

    @Test fun summarizesRealPendingPlansAndReviewJobsWithoutClaimingBuildSuccess() {
        val job = JSONObject().put("id", "local-job").put("state", "REVIEW_READY")
            .put("kind", "source.developer.analysis.request")
        val report = DeveloperProgressReport.summarize(listOf(plan()), listOf(job))
        assertTrue(report.contains("Task plans: 1"))
        assertTrue(report.contains("0/3 reported done"))
        assertTrue(report.contains("next: Analyze repository"))
        assertTrue(report.contains("requiring review: 1"))
        assertTrue(report.contains("Developer source analysis: REVIEW_READY"))
        assertTrue(report.contains("not confirmed"))
        assertFalse(report.contains("APK ready to install"))
    }

    @Test fun pausedTaskNeverPresentsAnExecutableNextStep() {
        val report = DeveloperProgressReport.summarize(listOf(plan(paused = true)), emptyList())
        assertTrue(report.contains("Paused — review before resuming"))
        assertTrue(report.contains("No saved development jobs"))
    }

    @Test fun corruptionCannotBeSilentlyReportedAsNoProgress() {
        val invalid = plan().copy(baseCommitSha = "bad")
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperProgressReport.summarize(listOf(invalid), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperProgressReport.summarize(emptyList(), listOf(JSONObject().put("state", "FAKE_SUCCESS")))
        }
    }
}
