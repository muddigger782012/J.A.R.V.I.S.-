package com.assistant.core.services

import org.json.JSONObject
import org.json.JSONArray

/** An imported static report is untrusted evidence, never a source-write approval. */
object SelfDiagnosticsReport {
    const val MAX_BYTES = 512 * 1024
    const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
    data class Rule(val title: String, val priority: Int, val explanation: String)
    val rules = mapOf(
        "credential-literal" to Rule("Potential embedded credential", 1, "Inspect the cited line locally. Confirm placeholders before removing or rotating any secret. Never share the value."),
        "unsafe-shell" to Rule("Shell trust boundary", 1, "Review whether structured process arguments can replace shell interpretation. A marker alone does not prove exploitability."),
        "compile-failure" to Rule("Reported compilation failure", 2, "Reproduce the compiler failure; the first reported error may be a downstream symptom."),
        "test-failure" to Rule("Reported test failure", 2, "Reproduce the failing behavior while preserving existing assertions. Logs do not establish the root cause."),
        "static-analysis-failure" to Rule("Reported static-analysis failure", 2, "Review the analysis finding against the unchanged baseline. Existing lint debt does not prove a regression from this change."),
        "crash" to Rule("Reported Android crash", 2, "Reproduce the exception against the installed version. The imported log may be synthetic or stale."),
        "memory-pressure" to Rule("Reported memory exhaustion", 2, "Inspect allocation lifetime under a bounded workload. Device evidence is needed to establish the cause."),
        "application-not-responding" to Rule("Reported unresponsive application", 2, "Inspect main-thread blocking and system load; reproduce the stall before proposing a repair."),
        "duplicate-function" to Rule("Repeated Python function body", 5, "Consider one tested helper only if both contracts can share behavior. Repeated fixtures may be intentional."),
        "large-source" to Rule("Large source file", 5, "Review separation of responsibilities while preserving working features. Size alone is not a defect."),
        "incomplete-feature" to Rule("Unimplemented-code marker", 6, "Check reachability and actual supported behavior. Abstract methods and fixtures may be intentional."),
        "todo-marker" to Rule("Outstanding review marker", 6, "Triage the cited TODO or FIXME. A stale comment does not prove broken behavior."),
        "large-file" to Rule("File outside read limit", 7, "This file was not analyzed. Use a separately bounded, authorized review."),
        "unreadable" to Rule("Unreadable source", 7, "Check access and encoding. No conclusion can be drawn from unreadable content.")
    )
    data class Finding(val id: String, val rule: String, val path: String, val line: Int?, val evidenceKind: String)
    data class Report(val id: String, val snapshot: String, val revision: String?, val complete: Boolean, val scanned: Int, val findings: List<Finding>)
    fun details(finding: Finding): String {
        val rule = rules.getValue(finding.rule)
        val benefit = when (rule.priority) { 1 -> "Reduce potential credential disclosure or shell interpretation risk."
            2 -> "Restore dependable behavior after reproducing the reported failure."
            5 -> "Reduce maintenance cost while preserving existing behavior."
            6 -> "Clarify incomplete or outdated implementation work."
            else -> "Make the uninspected coverage explicit before planning." }
        val risk = when (rule.priority) { 1 -> "A false positive or removal of a valid configuration can break an integration."
            2 -> "A symptom-based repair can alter behavior or hide a regression."
            5 -> "Refactoring can change independent contracts and callers."
            6 -> "Intentional stubs and comments can be valid; verify requirements."
            else -> "Expanded inspection needs explicit scope and resource bounds." }
        val effort = when (finding.rule) { "large-source", "memory-pressure", "application-not-responding" -> "high"
            "todo-marker", "large-file", "unreadable" -> "low"; else -> "medium" }
        return "${rule.explanation}\n\nExpected benefit: $benefit\nRisk: $risk\nEstimated difficulty: $effort."
    }
    private fun hash(value: String, size: Int = 64) = value.matches(Regex("[a-f0-9]{$size}"))
    private fun path(value: String): Boolean = value == "." || (value.length in 1..240 &&
        value.matches(Regex("[A-Za-z0-9_./-]+")) && value.split('/').none { it in setOf("", ".", "..") } &&
        !Regex("AIza[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}").containsMatchIn(value))
    private fun integer(json: JSONObject, key: String, range: IntRange): Int {
        val value = json.get(key)
        require(value is Int || value is Long)
        val number = (value as Number).toLong(); require(number in range.first.toLong()..range.last.toLong())
        return number.toInt()
    }
    private fun boundedJson(text: String) {
        var depth = 0; var quoted = false; var escaped = false; var stringLength = 0
        for (c in text) {
            if (quoted) {
                require(++stringLength <= 4096)
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> { quoted = true; stringLength = 0 }
                '{', '[' -> require(++depth <= 12)
                '}', ']' -> require(--depth >= 0)
            }
        }
        require(!quoted && depth == 0)
    }
    fun parse(text: String): Report = run {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        boundedJson(text)
        val root = JSONObject(text)
        require(integer(root, "schema_version", 2..2) == 2 && root.get("mode") == "read-only" && root.get("repository") == REPOSITORY)
        val auth = root.getJSONObject("authorization")
        require(listOf("source_writes", "merge", "publish", "install").all { auth.get(it) == false })
        require(root.getJSONObject("tests").get("state") == "not-run")
        val id = root.getString("report_id"); val snapshot = root.getString("snapshot")
        require(hash(id) && hash(snapshot))
        val revision = if (root.isNull("revision")) null else root.getString("revision").also { require(hash(it, 40)) }
        require(root.get("complete") is Boolean)
        val scanned = integer(root, "scanned_files", 0..5000)
        val items = root.getJSONArray("findings"); require(items.length() <= 300)
        val findings = (0 until items.length()).map { index ->
            val row = items.getJSONObject(index)
            val rule = row.getString("rule"); val ref = row.getString("id"); val file = row.getString("path")
            require(rule in rules && hash(ref) && path(file))
            val line = if (row.isNull("line")) null else integer(row, "line", 1..1_000_000)
            val kind = row.getJSONObject("evidence").getString("kind")
            require(kind in setOf("source", "build-log", "crash-log"))
            Finding(ref, rule, file, line, kind)
        }
        require(findings.map { it.id }.distinct().size == findings.size)
        Report(id, snapshot, revision, root.getBoolean("complete"), scanned,
            findings.sortedWith(compareBy<Finding> { rules.getValue(it.rule).priority }.thenBy { it.path }.thenBy { it.line ?: 0 }))
    }
    /** Only validated references and app-owned wording enter the other app's draft. */
    fun developerDraft(report: Report, finding: Finding): String {
        require(finding in report.findings)
        require(hash(report.id) && hash(report.snapshot) && (report.revision == null || hash(report.revision, 40)))
        require(finding.rule in rules && path(finding.path) && (finding.line == null || finding.line in 1..1_000_000))
        require(finding.evidenceKind in setOf("source", "build-log", "crash-log"))
        return "Review a static diagnostic for $REPOSITORY.\n" +
            "Imported report and claimed revision are not independently verified. Re-read authorized source before planning.\n" +
            "Review signal: ${rules.getValue(finding.rule).title}.\n" +
            "Source reference: ${finding.path}${finding.line?.let { ":$it" } ?: ""} (${finding.evidenceKind}).\n" +
            "Claimed revision: ${report.revision ?: "unspecified"}. Source inventory: ${report.snapshot}.\n" +
            "${rules.getValue(finding.rule).explanation}\n" +
            "This is a review draft. No source edit, branch write, merge, publication or installation is authorized. " +
            "Use Developer's separate source-bound proposal and owner approval before any write."
    }
    /** Persist only validated references; drop every imported prose or unknown field. */
    fun encode(report: Report): String = JSONObject().put("schema_version", 2).put("mode", "read-only")
        .put("repository", REPOSITORY).put("report_id", report.id).put("snapshot", report.snapshot)
        .put("revision", report.revision ?: JSONObject.NULL).put("complete", report.complete).put("scanned_files", report.scanned)
        .put("tests", JSONObject().put("state", "not-run"))
        .put("authorization", JSONObject().put("source_writes", false).put("merge", false).put("publish", false).put("install", false))
        .put("findings", JSONArray(report.findings.map { f -> JSONObject().put("id", f.id).put("rule", f.rule)
            .put("path", f.path).put("line", f.line ?: JSONObject.NULL).put("evidence", JSONObject().put("kind", f.evidenceKind)) })).toString()
}
