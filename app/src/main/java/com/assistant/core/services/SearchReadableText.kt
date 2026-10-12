package com.assistant.core.services

/** Small text-only Markdown subset; never loads HTML, remote images or model-written URLs. */
object SearchReadableText {
    data class Emphasis(val start: Int, val end: Int)
    data class Result(val text: String, val emphasis: List<Emphasis>)
    fun format(raw: String): Result {
        val output = StringBuilder()
        val emphasis = mutableListOf<Emphasis>()
        raw.take(12000).lines().forEach { original ->
            var line = original.trimEnd()
            val heading = Regex("^\\s{0,3}#{1,6}\\s+").find(line)
            if (heading != null) line = line.substring(heading.range.last + 1)
            line = line.replace(Regex("^\\s*[-*+]\\s+"), "• ")
                .replace(Regex("!?\\[([^]\\n]+)]\\([^\\n)]*\\)"), "$1")
                .replace(Regex("`([^`\\n]+)`"), "$1")
            if (line.isBlank() && (output.isEmpty() || output.endsWith("\n"))) return@forEach
            if (output.isNotEmpty()) output.append('\n')
            val start = output.length
            var cursor = 0
            Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").findAll(line).forEach { match ->
                output.append(line.substring(cursor, match.range.first))
                val boldStart = output.length
                output.append(match.groupValues[1].ifEmpty { match.groupValues[2] })
                emphasis.add(Emphasis(boldStart, output.length))
                cursor = match.range.last + 1
            }
            output.append(line.substring(cursor))
            if (heading != null && output.length > start) emphasis.add(Emphasis(start, output.length))
        }
        return Result(output.toString().trimEnd(), emphasis)
    }
    fun excerpt(raw: String): String {
        val text = format(raw).text.replace(Regex("\\s+"), " ").trim()
        return if (text.length > 220) text.take(217).trimEnd() + "…" else text
    }
}
