package com.jarvis.developer

// Reviewed source: bed69b8c65030d24871d75186075bfecc125f043
/** A deterministic review summary; it never reads or changes source files. */
object TaskFileSummary {
    fun describe(paths: List<String>): String {
        val files = paths.distinct().sorted()
        val counts = linkedMapOf("Kotlin" to 0, "Java" to 0, "XML" to 0, "Gradle" to 0, "Other" to 0)
        files.forEach { path ->
            val name = path.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
            val type = when {
                name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> "Gradle"
                name.endsWith(".kt") || name.endsWith(".kts") -> "Kotlin"
                name.endsWith(".java") -> "Java"
                name.endsWith(".xml") -> "XML"
                else -> "Other"
            }
            counts[type] = counts.getValue(type) + 1
        }
        val count = "${files.size} ${if (files.size == 1) "file" else "files"}"
        val types = "Types: " + counts.entries.joinToString(", ") { "${it.key} ${it.value}" }
        return (listOf(count, types) + files).joinToString("\n")
    }
}
