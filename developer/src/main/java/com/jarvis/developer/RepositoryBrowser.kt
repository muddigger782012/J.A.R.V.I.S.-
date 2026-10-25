package com.jarvis.developer

/** Formatting for the read-only browser. Source bytes must come from the pinned reader. */
object RepositoryBrowser {
    private const val MAX_LINES = 200
    private const val MAX_CHARACTERS = 24_000

    fun inventory(snapshot: RepositoryAnalysis.Inventory, prefix: String): String {
        require(prefix.matches(Regex("[A-Za-z0-9_./-]*")) && !prefix.contains("..")) {
            "Use a repository-relative path or directory prefix."
        }
        val selected = snapshot.files.filter { it.path.startsWith(prefix) }
        return buildString {
            append("READ-ONLY SOURCE INVENTORY\nCommit: ${snapshot.commitSha}\n")
            append("${selected.size} allowed files matching '${prefix.ifBlank { "all" }}'.\n\n")
            selected.take(500).forEach { append("${it.path} (${it.size} bytes)\n") }
            if (selected.size > 500) append("\nList limited to 500 paths; narrow the directory prefix.\n")
            if (selected.isEmpty()) append("No allowed source paths match.\n")
            append("\nOnly allowlisted text sources up to 200 KB are shown; secrets and binary assets are excluded.")
        }
    }

    fun source(snapshot: RepositoryAnalysis.Inventory, path: String, source: String, range: String): String {
        val file = snapshot.files.firstOrNull { it.path == path }
            ?: error("Choose an exact path from the pinned inventory.")
        val match = Regex("([1-9][0-9]{0,5})-([1-9][0-9]{0,5})").matchEntire(range)
            ?: error("Enter a line range such as 1-120.")
        val start = match.groupValues[1].toInt()
        val requestedEnd = match.groupValues[2].toInt()
        require(requestedEnd >= start && requestedEnd - start < MAX_LINES) {
            "Select at most $MAX_LINES lines in ascending order."
        }
        val lines = source.lineSequence().drop(start - 1).take(requestedEnd - start + 1).toList()
        require(lines.isNotEmpty()) { "The start line is past the end of this source." }
        val visible = ArrayList<String>()
        var remaining = MAX_CHARACTERS
        var clipped = false
        for ((index, line) in lines.withIndex()) {
            val prefix = "${start + index}: "
            val separator = if (visible.isEmpty()) 0 else 1
            if (remaining - separator < prefix.length) { clipped = true; break }
            val numbered = prefix + RepositoryRedaction.line(line)
            val shown = numbered.take(remaining - separator)
            visible += shown
            remaining -= shown.length + separator
            if (shown.length < numbered.length) { clipped = true; break }
        }
        if (visible.size < lines.size) clipped = true
        val end = start + visible.size - 1
        val body = visible.joinToString("\n")
        return buildString {
            append("READ-ONLY SOURCE\nPath: $path\nCommit: ${snapshot.commitSha}\nBlob: ${file.sha}\n")
            append("Lines: $start-$end\n")
            append("https://github.com/muddigger782012/J.A.R.V.I.S.-/blob/${snapshot.commitSha}/$path#L$start-L$end\n\n")
            append(body)
            append("\n\nRecognized credential literals are redacted in this preview.")
            if (clipped) append("\n\nExcerpt clipped at $MAX_CHARACTERS characters; the last displayed line may be partial. Request a narrower line range.")
        }
    }
}
