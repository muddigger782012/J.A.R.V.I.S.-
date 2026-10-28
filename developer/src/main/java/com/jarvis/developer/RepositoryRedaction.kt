package com.jarvis.developer

/** Defense in depth for source previews; credential files are excluded by the reader policy. */
internal object RepositoryRedaction {
    private val valueStart = Regex("(?i)(?<![a-z0-9_-])[\"']?[a-z0-9_.-]*(?:api[_-]?key|token|password|secret|authorization)[\"']?\\s*(?::\\s*[a-z0-9_?.<>]+)?\\s*[=:]\\s*")
    private val privateKey = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|\\z)")
    private val xmlSecret = Regex("(?i)<(?:string|item)\\b[^>]*\\bname=[\"'][^\"']*(?:api[_-]?key|token|password|secret|authorization)[^\"']*[\"'][^>]*>[\\s\\S]*?(?:</(?:string|item)>|\\z)")
    private val manifestSecret = Regex("(?i)<meta-data\\b[^>]*\\b(?:android:)?name=[\"'][^\"']*(?:api[_-]?key|token|password|secret|authorization)[^\"']*[\"'][^>]*>")
    private val auth = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9+/_=.-]{8,}")
    private val userInfo = Regex("https?://[^\\s/:@]+:[^\\s/@]+@")

    /** Whole-file sanitation preserves line numbers, including multiline literals and PEM blocks. */
    fun source(source: String, secrets: List<String> = emptyList()): String {
        val ranges = mutableListOf<IntRange>()
        privateKey.findAll(source).forEach { ranges.add(it.range) }
        xmlSecret.findAll(source).forEach { ranges.add(it.range) }
        manifestSecret.findAll(source).forEach { ranges.add(it.range) }
        for (match in valueStart.findAll(source)) {
            val start = match.range.last + 1
            if (start >= source.length) continue
            val triple = source.startsWith("\"\"\"", start) || source.startsWith("'''", start)
            val quote = source[start].takeIf { it == '\"' || it == '\'' }
            var end = start
            if (triple) {
                val closing = source.indexOf(source.substring(start, start + 3), start + 3)
                end = if (closing < 0) source.length else closing + 3
            } else if (quote != null) {
                end++
                while (end < source.length) {
                    if (source[end] == '\\') { end = minOf(source.length, end + 2); continue }
                    if (source[end++] == quote) break
                }
            } else {
                while (end < source.length && source[end] !in "\r\n;<") end++
            }
            if (end > start) ranges.add(start until end)
        }
        val redacted = StringBuilder(); var cursor = 0
        for (range in ranges.sortedBy { it.first }) {
            val start = maxOf(cursor, range.first); val end = range.last + 1
            if (end <= start) continue
            redacted.append(source, cursor, start)
            redacted.append(source.substring(start, end).split('\n').joinToString("\n") { "[REDACTED]" })
            cursor = end
        }
        redacted.append(source, cursor, source.length)
        var result = redacted.toString()
        secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[REDACTED]") }
        result = userInfo.replace(auth.replace(result, "[REDACTED]"), "https://[REDACTED]@")
        return result.lineSequence().joinToString("\n") { line(it) }.map { character ->
            if ((character.isISOControl() && character != '\n' && character != '\t') ||
                character in '\u202a'..'\u202e' || character in '\u2066'..'\u2069') ' ' else character
        }.joinToString("")
    }
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
