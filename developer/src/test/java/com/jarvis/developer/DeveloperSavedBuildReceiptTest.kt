package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DeveloperSavedBuildReceiptTest {
    private val path = "feature/jarvis-developer-task/sample-" + "a".repeat(12)
    private val entry = RepairHistoryStore.Bookmark(PatchEngine.Target.DEVELOPER, path, "b".repeat(40))
    @Test fun reportsOnlyReadOnlyCurrentWorkflowState() {
        val report = BuildDiagnostics.parse(entry.commitSha, entry.branch, 12, 1, "completed", "success", "")
        val text = DeveloperSavedBuildReceipt.render(entry, report)
        assertTrue(text.contains("CI: success"))
        assertTrue(text.contains("Evidence incomplete"))
        assertTrue(text.contains("No write approval"))
        assertFalse(text.contains("installed"))
    }
    @Test fun missingBuildAndWrongProductOrSourceAreNeverAcceptedAsSuccess() {
        val missing = BuildDiagnostics.parse(entry.commitSha, entry.branch, 0, 1, "missing", null, "")
        assertTrue(DeveloperSavedBuildReceipt.render(entry, missing).contains("no matching run"))
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperSavedBuildReceipt.render(entry,
                BuildDiagnostics.parse("c".repeat(40), entry.branch, 12, 1, "completed", "success", ""))
        }
        val personal = RepairHistoryStore.Bookmark(PatchEngine.Target.PERSONAL,
            "feature/jarvis-personal-repair/sample-" + "a".repeat(12), "b".repeat(40))
        assertThrows(IllegalArgumentException::class.java) {
            DeveloperSavedBuildReceipt.render(personal, missing)
        }
    }
}
