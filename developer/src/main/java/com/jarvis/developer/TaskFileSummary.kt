package com.jarvis.developer

/** A deterministic review summary; it never reads or changes source files. */
object TaskFileSummary {
    fun describe(paths: List<String>): String {
        val files = paths.distinct().sorted()
        return (listOf("${files.size} ${if (files.size == 1) "file" else "files"}") + files).joinToString("\n")
    }
}
