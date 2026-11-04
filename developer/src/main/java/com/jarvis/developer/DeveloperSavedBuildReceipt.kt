package com.jarvis.developer

/**
 * Read-only CI report after an explicit saved bookmark recheck.
 * This is NOT an approval receipt or a record of a local source write.
 */
internal object DeveloperSavedBuildReceipt {
    fun render(bookmark: RepairHistoryStore.Bookmark, evidence: BuildDiagnostics.Report): String {
        RepairHistoryStore.validate(bookmark)
        BuildDiagnostics.validateReport(evidence)
        require(bookmark.target == PatchEngine.Target.DEVELOPER &&
            evidence.target == bookmark.target && evidence.branch == bookmark.branch &&
            evidence.commitSha == bookmark.commitSha)
        val state = when (evidence.status) {
            "completed" -> when (evidence.conclusion) {
                "success" -> "success (GitHub workflow only)"
                "failure" -> "failed"
                else -> "completed with ${evidence.conclusion ?: "unknown"}"
            }
            "missing" -> "no matching run"
            else -> evidence.status
        }
        val tests = evidence.totals?.let {
            "JUnit evidence: ${it.tests} tests, ${it.failures} failures, ${it.errors} errors.\n"
        }.orEmpty()
        val limitations = if (evidence.evidenceComplete) "Complete bounded evidence."
            else "Evidence incomplete or limited; a passing test suite was not inferred."
        return "Saved Developer build (read-only)\n" +
            "Commit: ${bookmark.commitSha.take(12)}\n" +
            "CI: $state\n" + tests + limitations + "\n" +
            "This is a bookmark and current GitHub observation, not proof of an approved " +
            "source edit. No write approval, source scope, merge, signed release or installation " +
            "was restored."
    }
}
