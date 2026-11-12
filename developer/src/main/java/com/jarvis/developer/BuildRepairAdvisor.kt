package com.jarvis.developer

/** Suggestions are diagnostic guidance only; no automatic write or retry is authorized. */
object BuildRepairAdvisor {
    data class Advice(val category: String, val recommendation: String, val needsApproval: Boolean = true)
    fun suggest(report: BuildDiagnostics.Report): List<Advice> {
        if (report.status != "completed" || report.conclusion == "success") return emptyList()
        return report.diagnostics.take(12).map { diagnostic ->
            when (diagnostic.kind) {
                BuildDiagnostics.Kind.COMPILER -> Advice("Compiler", "Inspect the reported source location and propose a minimal reviewed fix.")
                BuildDiagnostics.Kind.TEST -> Advice("Test", "Reproduce the failed test and compare the expected behavior before changing implementation.")
                BuildDiagnostics.Kind.INFRASTRUCTURE -> Advice("Infrastructure", "Verify runner, SDK and dependency availability before retrying without code changes.")
                BuildDiagnostics.Kind.BUILD -> Advice("Build", "Inspect the failing Gradle task and its configuration; request approval for any protected-file change.")
                BuildDiagnostics.Kind.UNKNOWN -> Advice("Unclassified", "Collect the full bounded build evidence; do not guess at a fix.")
            }
        }.distinct()
    }
}
