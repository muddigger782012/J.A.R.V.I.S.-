package com.jarvis.developer

/** Defense in depth for source previews; credential files are excluded by the reader policy. */
internal object RepositoryRedaction {
    private val namedLiteralStart = Regex(
        "(?i)(?<![a-z0-9_-])([\"']?[a-z0-9_-]*(?:api[_-]?key|token|password|secret|authorization)[\"']?\\s*[=:]\\s*)([\"'])"
    )
    private val knownToken = Regex(
        "\\b(?:gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,})\\b"
    )

    fun line(source: String): String {
        val literals = StringBuilder(source.length)
        var cursor = 0
        for (match in namedLiteralStart.findAll(source)) {
            if (match.range.first < cursor) continue
            val quote = match.groupValues[2].single()
            val valueStart = match.range.last + 1
            var end = valueStart
            while (end < source.length) {
                if (source[end] == '\\') { end = minOf(end + 2, source.length); continue }
                if (source[end] == quote) break
                end++
            }
            literals.append(source, cursor, valueStart).append("[REDACTED]")
            if (end < source.length) literals.append(quote)
            cursor = minOf(end + 1, source.length)
        }
        literals.append(source, cursor, source.length)
        return knownToken.replace(literals, "[REDACTED]")
    }
}
