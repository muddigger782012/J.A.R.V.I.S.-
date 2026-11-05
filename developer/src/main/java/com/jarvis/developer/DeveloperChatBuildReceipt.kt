package com.jarvis.developer

/** Compact owner-facing receipts: never copy untrusted source or logs into primary chat. */
internal object DeveloperChatBuildReceipt {
    fun preview(taskId: String, files: Int, proposalId: String? = null): String {
        require(taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) && files in 1..PatchEngine.MAX_FILES)
        if (proposalId != null) require(proposalId.matches(Regex("[a-f0-9]{64}")))
        val approval = if (proposalId == null) "" else
            "\nAFTER reviewing the complete diff, approve it by typing: Approve Developer diff. " +
                "The exact proposal fingerprint is bound internally; no copy/paste required."
        return "Diff ready: $files scoped file(s). Type 'Show Developer diff' to read the " +
            "complete verified changes in chat before approving the isolated branch. " +
            "No source write, passing test, signed release or installation is implied." + approval
    }

    fun commit(result: PatchPublisher.CommitResult, ci: PatchPublisher.CiStatus?,
        report: BuildDiagnostics.Report?, repairAvailable: Boolean): String {
        PatchPublisher.validateResult(result)
        if (report != null) {
            BuildDiagnostics.validateReport(report)
            require(report.commitSha == result.commitSha && report.branch == result.branch &&
                report.target == result.target && ci?.state == PatchPublisher.CiState.FAILED &&
                report.conclusion == ci.conclusion && ci.runUrl != null &&
                report.runUrl.startsWith(ci.runUrl + "/attempts/"))
        }
        val ciLabel = when (ci?.state) {
            PatchPublisher.CiState.PASSED -> "passed for exact task commit (not signed release)"
            PatchPublisher.CiState.FAILED -> if (report == null) "failed; failure evidence unverified"
                else "failed; verified diagnostics ready"
            PatchPublisher.CiState.RUNNING -> "running; refresh later"
            PatchPublisher.CiState.QUEUED -> "queued; refresh later"
            PatchPublisher.CiState.MISSING -> "no matching run found"
            null -> "not verified"
        }
        val next = when {
            ci?.state == PatchPublisher.CiState.FAILED && repairAvailable &&
                result.target == PatchEngine.Target.DEVELOPER ->
                "Say 'Prepare Developer repair' to begin a NEW pinned-source diff. Approval is not reusable."
            ci?.state == PatchPublisher.CiState.FAILED ->
                "Review failed-build diagnostics before proposing another change."
            ci?.state == PatchPublisher.CiState.PASSED ->
                "Review branch integration and separately approved signed publication."
            ci?.state in setOf(PatchPublisher.CiState.RUNNING, PatchPublisher.CiState.QUEUED,
                PatchPublisher.CiState.MISSING) -> "Recheck exact task CI for an updated result."
            else -> "Use Check CI to verify this exact task."
        }
        val product = if (result.target == PatchEngine.Target.DEVELOPER) "Developer" else "Personal Jarvis"
        return "$product task branch committed.\nCommit: ${result.commitSha.take(12)}; " +
            "files: ${result.paths.size}\nCI: $ciLabel\n$next\n" +
            "NOT merged, NOT published as a signed in-app update, NOT installed by this operation."
    }

    fun uncertain(): String = "Commit attempt stopped; the remote outcome is uncertain. " +
        "Approval was consumed. Verify the exact task branch before retrying. " +
        "No retry, release, or installation was started automatically."
}
