package com.jarvis.developer

import java.util.Locale
import java.security.MessageDigest

/**
 * Bounded, deterministic source retrieval. The only capability supplied by the caller is reading
 * one inventoried file at an immutable revision. Repository text is data, never an instruction.
 * This is lexical analysis, not a compiler or a claim that a proposed change has been validated.
 */
object RepositoryAnalysis {
    const val MAX_INSPECTED_FILES = 160
    const val MAX_FILE_BYTES = 200_000L
    const val MAX_TOTAL_BYTES = 2_000_000L
    const val MAX_PLANNED_FILES = 8
    const val MAX_SNIPPET_LINES = 7
    const val MAX_SNIPPET_CHARACTERS = 1_200

    data class SourceFile(val path: String, val sha: String, val size: Long)
    data class Inventory(val files: List<SourceFile>, val truncated: Boolean, val commitSha: String)
    data class Symbol(val name: String, val kind: String, val line: Int)
    data class Dependency(
        val reference: String,
        val kind: String,
        val resolvedPaths: List<String>,
        val status: String
    )
    data class Citation(
        val path: String,
        val blobSha: String,
        val commitSha: String,
        val startLine: Int,
        val endLine: Int,
        val code: String,
        val shortened: Boolean = false
    )
    data class PlannedFile(
        val source: SourceFile,
        val reasons: List<String>,
        val proposedChange: String,
        val likelyImpact: String,
        val symbols: List<Symbol>,
        val dependencies: List<Dependency>,
        val citations: List<Citation>
    ) {
        val path: String get() = source.path
    }
    data class AnalysisPlan(
        val task: String,
        val commitSha: String,
        val files: List<PlannedFile>,
        val limitations: List<String>,
        val inspectedPaths: List<String>,
        val inventoryCount: Int
    ) {
        val analyzer: String = "deterministic lexical v1; no model/provider called"
        val analysisId: String = MessageDigest.getInstance("SHA-256")
            .digest("$commitSha\n$task".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Document(
        val source: SourceFile,
        val text: String,
        val codeText: String,
        val lines: List<String>,
        val packageName: String,
        val symbols: List<Symbol>,
        val terms: Set<String>,
        val pathTerms: Set<String>
    )
    private data class Query(val primary: Set<String>, val expanded: Set<String>)
    private val revision = Regex("[0-9a-fA-F]{40}")
    private val word = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val camelBoundary = Regex("([a-z0-9])([A-Z])")
    private val packagePattern = Regex("(?m)^[ \\t]*package[ \\t]+([\\w.]+)")
    private val importPattern = Regex("(?m)^[ \\t]*import[ \\t]+(?:static[ \\t]+)?([\\w.*]+)")
    private val qualifiedSymbol = Regex("\\b(?:[a-z_]\\w*\\.)+[A-Z]\\w*(?:\\.[A-Z]\\w*)*\\b")
    private val declaration = Regex("\\b(class|object|interface|typealias|fun|val|var|record|enum)\\s+(?:<[^>]+>\\s*)?([A-Za-z_]\\w*)")
    private val javaMethod = Regex("\\b(?:public|protected|private)\\s+(?:static\\s+)?[\\w<>?\\[\\].]+\\s+([A-Za-z_]\\w*)\\s*\\(")
    private val resourceCode = Regex("\\b(?:([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\.)?R\\.(layout|drawable|string|color|style|menu|id|dimen|xml|navigation)\\.([A-Za-z_]\\w*)")
    private val resourceXml = Regex("@(?:\\+)?(?:([A-Za-z_][\\w.]*):)?(layout|drawable|string|color|style|menu|id|dimen|xml|navigation)/([A-Za-z_]\\w*)")
    private val xmlResourceName = Regex("<(string|color|style|dimen|item)\\b[^<>]*\\bname=[\"']([^\"']+)[\"']")
    private val xmlClass = Regex("(?:android:name|class)=[\"']([A-Za-z_][\\w.]+)[\"']")
    private val projectReference = Regex("\\bproject\\s*\\(\\s*[\"'](:[\\w:-]+)[\"']\\s*\\)")
    private val artifactReference = Regex("\\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\\s*\\(?\\s*[\"']([^\"'\\r\\n]{1,512})[\"']")
    private val stop = setOf("a", "an", "the", "to", "of", "for", "and", "or", "in", "on", "with", "that", "this", "it", "is", "be", "as", "from", "by", "new", "add", "change", "fix", "please", "implement", "make", "update", "want", "need", "task", "requested", "request", "can", "should", "would", "without", "into")
    // General vocabulary expansion changes retrieval weights, never selects a file or a canned plan.
    private val related = mapOf(
        "ai" to setOf("intelligence", "provider", "model"),
        "connect" to setOf("network", "provider", "client", "http", "connection"),
        "diagnos" to setOf("failure", "error", "test", "connection"),
        "appearance" to setOf("theme", "font", "layout"),
        "scroll" to setOf("scrollview", "height", "overflow", "nested"),
        "menu" to setOf("navigation", "settings", "destination"),
        "chat" to setOf("conversation", "message", "history"),
        "context" to setOf("history", "conversation", "session"),
        "plan" to setOf("roadmap", "phase", "milestone"),
        "progress" to setOf("status", "completion", "evidence")
    )

    fun analyze(task: String, inventory: Inventory, readSource: (String) -> String): AnalysisPlan {
        require(revision.matches(inventory.commitSha)) { "Analysis requires a full immutable commit SHA" }
        require(task.isNotBlank() && task.length <= 4_000) { "Task must contain 1–4,000 characters" }
        require(inventory.files.size <= 20_000) { "Inventory exceeds analysis entry limit" }
        require(inventory.files.map { it.path }.distinct().size == inventory.files.size) { "Duplicate inventory paths" }
        require(inventory.files.all { revision.matches(it.sha) && it.size >= 0 && safePath(it.path) }) {
            "Inventory contains invalid SHA, size, unsafe path or unsupported source type"
        }
        val notes = linkedSetOf(
            "Lexical references are estimates; reflection, generated code, runtime routing and transitive dependencies require separate verification.",
            "Per file: at most 2,000 declarations considered, 12 shown, 40 dependencies shown and two excerpts of $MAX_SNIPPET_LINES lines/$MAX_SNIPPET_CHARACTERS characters."
        )
        if (inventory.truncated) notes += "Inventory is truncated; results cover only supplied files and cannot establish repository-wide impact."
        val query = query(task)
        if (query.primary.isEmpty()) return AnalysisPlan(task, inventory.commitSha, emptyList(), notes.toList() + "No specific searchable task terms were supplied.", emptyList(), inventory.files.size)
        var bytesRead = 0L
        var oversized = 0
        var budgetSkipped = 0
        var failed = 0
        val inspected = mutableListOf<String>()
        val documents = mutableListOf<Document>()
        val candidates = inventory.files.sortedWith(
            compareByDescending<SourceFile> { discoveryScore(it.path, query) }
                .thenBy { sourcePriority(it.path) }.thenBy { it.path }
        )
        for (source in candidates) {
            if (source.size > MAX_FILE_BYTES) { oversized++; continue }
            if (inspected.size >= MAX_INSPECTED_FILES || bytesRead + source.size > MAX_TOTAL_BYTES) { budgetSkipped++; continue }
            inspected += source.path
            // Reserve the advertised size even if reading fails. Bad metadata cannot enlarge budgets.
            bytesRead += source.size
            val text = try { readSource(source.path) } catch (_: Exception) { failed++; continue }
            val sourceBytes = text.toByteArray(Charsets.UTF_8)
            val actualSize = sourceBytes.size.toLong()
            if (actualSize > MAX_FILE_BYTES || actualSize != source.size) {
                notes += "Rejected ${source.path}: returned UTF-8 byte size differs from inventory or exceeds the per-file limit."
                continue
            }
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${sourceBytes.size}\u0000".toByteArray(Charsets.UTF_8))
            val returnedSha = digest.digest(sourceBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!returnedSha.equals(source.sha, ignoreCase = true)) {
                notes += "Rejected ${source.path}: returned content does not match inventoried Git blob SHA."
                continue
            }
            if ('\u0000' in text) { notes += "Rejected ${source.path}: binary content in source response."; continue }
            documents += document(source, text)
        }
        if (oversized > 0) notes += "$oversized files exceed the $MAX_FILE_BYTES-byte per-file limit and were not read."
        if (budgetSkipped > 0) notes += "$budgetSkipped files were not read because analysis is limited to $MAX_INSPECTED_FILES reads and $MAX_TOTAL_BYTES bytes."
        if (failed > 0) notes += "$failed source reads failed; no plan relies on their contents."
        val ranked = documents.map { it to contentScore(it, query) }
            .filter { (_, score) -> score > 0.0 }
            .sortedWith(compareByDescending<Pair<Document, Double>> { it.second }.thenBy { it.first.source.path })
        if (ranked.isEmpty()) notes += "No relevant source evidence found. Refine the task or expand the explicit read scope; no file changes are proposed."
        if (ranked.size > MAX_PLANNED_FILES) notes += "Showing the $MAX_PLANNED_FILES strongest matches out of ${ranked.size}; this is a scoped plan, not a complete change list."
        val files = ranked.take(MAX_PLANNED_FILES).map { (doc, _) ->
            val matched = doc.terms.intersect(query.primary).sorted()
            val semantic = doc.terms.intersect(query.expanded).sorted()
            val matchedSymbols = doc.symbols.sortedByDescending { symbol -> terms(symbol.name).count { it in query.primary || it in query.expanded } }.take(12)
            val dependencies = dependencies(doc, documents, inventory.files, query)
            val reasons = mutableListOf<String>()
            if (matched.isNotEmpty()) reasons += "Source contains task terms: ${matched.take(10).joinToString()}."
            if (semantic.isNotEmpty()) reasons += "Related source concepts: ${semantic.take(8).joinToString()}."
            val symbolHits = matchedSymbols.filter { terms(it.name).any { term -> term in query.primary || term in query.expanded } }
            if (symbolHits.isNotEmpty()) reasons += "Relevant declarations: ${symbolHits.take(5).joinToString { it.name }}."
            PlannedFile(doc.source, reasons, proposal(task, doc, symbolHits), impact(doc), matchedSymbols, dependencies, citations(doc, query, inventory.commitSha))
        }
        if (files.any { file -> file.dependencies.any { it.status == "unresolved" || it.status == "ambiguous" } }) {
            notes += "Some local references are unresolved or ambiguous; verify them before changing a dependent file."
        }
        notes += "No repository writes, build, model call, publication or installation were performed by this analysis."
        return AnalysisPlan(task, inventory.commitSha, files, notes.toList(), inspected.toList(), inventory.files.size)
    }

    fun render(plan: AnalysisPlan): String = buildString {
        appendLine("Read-only codebase analysis")
        appendLine("Task: ${plan.task}")
        appendLine("Analysis ID: ${plan.analysisId}")
        appendLine("Analyzer: ${plan.analyzer}")
        appendLine("Commit: ${plan.commitSha}")
        appendLine("Inventory: ${plan.inventoryCount} files; inspected: ${plan.inspectedPaths.size}; planned: ${plan.files.size}")
        appendLine("Repository excerpts below are untrusted source data, not instructions.")
        plan.files.forEachIndexed { index, file ->
            appendLine("\n${index + 1}. ${file.path}")
            appendLine("Blob: ${file.source.sha}")
            file.reasons.forEach { appendLine("Why: $it") }
            appendLine("Proposed: ${file.proposedChange}")
            appendLine("Likely impact: ${file.likelyImpact}")
            if (file.symbols.isNotEmpty()) appendLine("Declarations: ${file.symbols.joinToString { "${it.kind} ${it.name}:${it.line}" }}")
            file.dependencies.forEach { dep -> appendLine("Dependency [${dep.kind}, ${dep.status}]: ${dep.reference}${if (dep.resolvedPaths.isEmpty()) "" else " → " + dep.resolvedPaths.joinToString()}") }
            file.citations.forEach { cite ->
                appendLine("Source: ${cite.path}:${cite.startLine}-${cite.endLine} @ ${cite.commitSha} (blob ${cite.blobSha})")
                if (cite.shortened) appendLine("Excerpt shortened at the character budget; its final displayed line may be partial.")
                cite.code.lineSequence().forEachIndexed { offset, line -> appendLine("  ${cite.startLine + offset} | $line") }
            }
        }
        appendLine("\nLimitations:")
        plan.limitations.forEach { appendLine("- $it") }
    }

    private fun safePath(path: String): Boolean {
        if (path.length > 500 || path.startsWith('/') || '\\' in path || path.any { it.code < 32 }) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.startsWith('.') }) return false
        val lower = path.lowercase(Locale.ROOT)
        if (parts.any { it.lowercase(Locale.ROOT) in setOf("secrets", "credentials", "keystore", "keystores") }) return false
        if (Regex("(?:^|/)(?:local\\.properties|.*\\.(?:jks|keystore|pem|p12|pfx)|credentials[^/]*|secrets[^/]*)$").containsMatchIn(lower)) return false
        return lower.endsWith(".kt") || lower.endsWith(".java") || lower.endsWith(".xml") || lower.endsWith(".gradle") || lower.endsWith(".kts") || lower.endsWith(".md") ||
            lower == "gradle/libs.versions.toml" || lower == "gradle/wrapper/gradle-wrapper.properties" || lower == "gradle.properties"
    }

    private fun normalize(value: String): String = when {
        value.startsWith("connect") -> "connect"
        value.startsWith("diagnos") -> "diagnos"
        value.startsWith("scroll") && value != "scrollview" -> "scroll"
        value.endsWith("ies") && value.length > 5 -> value.dropLast(3) + "y"
        value.endsWith("ing") && value.length > 6 -> value.dropLast(3)
        value.endsWith("s") && value.length > 4 -> value.dropLast(1)
        else -> value
    }
    private fun terms(value: String): Set<String> = word.findAll(value.replace(camelBoundary, "$1 $2").replace('_', ' '))
        .map { normalize(it.value.lowercase(Locale.ROOT)) }.filter { it.length > 1 && it !in stop }.toSet()
    private fun query(task: String): Query {
        val primary = terms(task)
        return Query(primary, primary.flatMap { related[it].orEmpty() }.map(::normalize).toSet() - primary)
    }
    private fun pathScore(path: String, query: Query): Double {
        val basename = terms(path.substringAfterLast('/'))
        val directories = terms(path.substringBeforeLast('/', "")) - basename
        // Package/scope words occur in almost every path (e.g. "assistant"). A matching
        // declaration filename is much stronger evidence than a shared directory segment.
        return basename.intersect(query.primary).size * 5.0 + basename.intersect(query.expanded).size * 1.5 +
            directories.intersect(query.primary).size * 0.25 + directories.intersect(query.expanded).size * 0.1
    }
    private fun sourcePriority(path: String): Int = when {
        "/src/main/" in path -> 0
        "/test/" in path || "/androidTest/" in path -> 3
        path.endsWith(".md") -> 2
        else -> 1
    }
    private fun discoveryScore(path: String, query: Query): Double {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        // Conventional entry points often own routing/state despite a task-neutral filename.
        // This prior affects read ordering only; a plan still requires matching source evidence.
        val entryPoint = when {
            name == "MainActivity" || name == "Main" || name == "main" -> 6.0
            name == "Application" || name.endsWith("Application") -> 4.0
            else -> 0.0
        }
        val priority = sourcePriority(path)
        val supportWeight = when (priority) { 3 -> 0.25; 2 -> 0.65; else -> 1.0 }
        // Test filenames share generic words such as "test" with diagnostic requests. Reserve
        // the bounded read session for production evidence before supporting examples/docs.
        return (pathScore(path, query) + if (priority < 2) entryPoint else 0.0) * supportWeight
    }
    private fun document(source: SourceFile, text: String): Document {
        val lines = text.lines()
        val codeText = when {
            source.path.endsWith(".kt") || source.path.endsWith(".java") -> maskCommentsAndStrings(text)
            source.path.endsWith(".xml") -> maskXmlComments(text)
            source.path.endsWith(".gradle") || source.path.endsWith(".gradle.kts") -> maskCommentsAndStrings(text, false)
            else -> text
        }
        val symbols = mutableListOf<Symbol>()
        codeText.lines().forEachIndexed { index, line ->
            declaration.findAll(line).forEach { match -> symbols += Symbol(match.groupValues[2], match.groupValues[1], index + 1) }
            if (source.path.endsWith(".java")) javaMethod.findAll(line).forEach { match -> symbols += Symbol(match.groupValues[1], "method", index + 1) }
            if (source.path.endsWith(".md") && line.startsWith('#')) symbols += Symbol(line.trimStart('#', ' '), "heading", index + 1)
            if (source.path.endsWith(".xml")) xmlResourceName.findAll(line).forEach { match -> symbols += Symbol(match.groupValues[2], "resource:${match.groupValues[1]}", index + 1) }
        }
        return Document(source, text, codeText, lines, packagePattern.find(codeText)?.groupValues?.get(1).orEmpty(), symbols.distinct().take(2_000), terms(text), terms(source.path))
    }
    private fun contentScore(doc: Document, query: Query): Double {
        val direct = doc.terms.intersect(query.primary)
        val semantic = doc.terms.intersect(query.expanded)
        // A filename alone is insufficient. At least one task concept must exist in source text.
        if (direct.isEmpty() && semantic.isEmpty()) return 0.0
        val declarations = doc.symbols.flatMap { terms(it.name) }.toSet()
        val raw = direct.size * 7.0 + semantic.size * 1.2 + pathScore(doc.source.path, query) +
            declarations.intersect(query.primary).size * 4.0 + declarations.intersect(query.expanded).size
        // Quoted requests in tests/docs are useful supporting evidence but must not crowd out code.
        val multiplier = if (doc.source.path.endsWith(".md")) 0.7 else if ("/test/" in doc.source.path || "/androidTest/" in doc.source.path) 0.35 else 1.0
        return raw * multiplier
    }

    private fun dependencies(doc: Document, documents: List<Document>, inventory: List<SourceFile>, query: Query): List<Dependency> {
        val result = mutableListOf<Dependency>()
        fun add(reference: String, kind: String, paths: List<String>, external: Boolean = false) {
            val unique = paths.distinct().sorted()
            result += Dependency(reference, kind, unique, when { unique.size > 1 && kind != "resource" -> "ambiguous"; unique.isNotEmpty() -> "resolved"; external -> "external"; else -> "unresolved" })
        }
        fun resolveQualified(reference: String): List<String> {
            val exact = reference.replace('.', '/')
            return inventory.filter { it.path.endsWith("/$exact.kt") || it.path.endsWith("/$exact.java") }.map { it.path } +
                documents.filter { other -> other.symbols.any { symbol -> "${other.packageName}.${symbol.name}" == reference } }.map { it.source.path }
        }
        val imports = importPattern.findAll(doc.codeText).map { it.groupValues[1] }.toList()
        imports.forEach { reference ->
            val matches = if (reference.endsWith(".*")) documents.filter { it.packageName == reference.removeSuffix(".*") }.map { it.source.path } else resolveQualified(reference)
            val localPrefix = documents.map { it.packageName.substringBeforeLast('.', it.packageName) }.filter { it.isNotBlank() }.any { reference.startsWith("$it.") }
            add(reference, "import", matches, !localPrefix && !reference.startsWith("com.jarvis.") && !reference.startsWith("com.assistant."))
        }
        // Calls may use a fully qualified class without an import. Resolve actual code against
        // the pinned inventory; comments, strings and a filename coincidence cannot create edges.
        qualifiedSymbol.findAll(doc.codeText).map { it.value }.distinct().filter { it !in imports }.forEach { reference ->
            val matches = resolveQualified(reference)
            if (matches.isNotEmpty()) add(reference, "qualified symbol", matches)
        }
        // Resolve same-package declarations used by name. A filename coincidence never proves an edge.
        documents.filter { it.source.path != doc.source.path && it.packageName.isNotEmpty() && it.packageName == doc.packageName }.forEach { other ->
            other.symbols.filter { it.kind in setOf("class", "object", "interface", "typealias", "record", "enum") }.forEach { symbol ->
                if (Regex("\\b${Regex.escape(symbol.name)}\\b").containsMatchIn(doc.codeText)) add("${other.packageName}.${symbol.name}", "same-package symbol", listOf(other.source.path))
            }
        }
        val resources = resourceCode.findAll(doc.codeText).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) } +
            resourceXml.findAll(doc.codeText).map { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val currentModule = doc.source.path.substringBefore("/src/", "")
        val importedR = imports.firstOrNull { it.endsWith(".R") }
        val namespacePattern = Regex("\\bnamespace\\s*(?:=\\s*)?[\"']([^\"']+)[\"']")
        fun namespaceModule(namespace: String): String? = documents.firstOrNull { other ->
            (other.source.path.endsWith("/build.gradle") || other.source.path.endsWith("/build.gradle.kts")) &&
                namespacePattern.find(other.text)?.groupValues?.get(1) == namespace
        }?.source?.path?.substringBeforeLast('/')
        resources.distinct().forEach { (namespace, type, name) ->
            val resourceNamespace = namespace.ifBlank { importedR?.removeSuffix(".R").orEmpty() }
            val resourceModule = if (resourceNamespace.isBlank()) currentModule else namespaceModule(resourceNamespace)
            fun sameResourceModule(path: String): Boolean = !resourceModule.isNullOrBlank() && path.startsWith("$resourceModule/src/")
            val paths = inventory.filter { sameResourceModule(it.path) && Regex("/res/${Regex.escape(type)}(?:-[^/]+)?/${Regex.escape(name)}\\.xml$").containsMatchIn(it.path) }.map { it.path } +
                documents.filter { other -> sameResourceModule(other.source.path) && other.source.path.endsWith(".xml") && (
                    other.symbols.any { it.kind == "resource:$type" && it.name == name } ||
                    (type == "id" && Regex("@\\+id/${Regex.escape(name)}(?:[\"'\\s])").containsMatchIn(other.codeText))
                    ) }.map { it.source.path }
            add("${if (namespace.isBlank()) "" else "$namespace."}R.$type.$name", "resource", paths, resourceNamespace == "android")
        }
        if (doc.source.path.endsWith(".xml")) xmlClass.findAll(doc.codeText).forEach { match ->
            val reference = match.groupValues[1]
            if ('.' in reference) add(reference, "xml class", resolveQualified(reference), reference.startsWith("android.") || reference.startsWith("androidx."))
        }
        if (doc.source.path.endsWith(".gradle") || doc.source.path.endsWith(".gradle.kts")) {
            projectReference.findAll(doc.codeText).forEach { match ->
                val module = match.groupValues[1].removePrefix(":").replace(':', '/')
                add(match.groupValues[1], "gradle module", inventory.filter { it.path == "$module/build.gradle" || it.path == "$module/build.gradle.kts" }.map { it.path })
            }
            artifactReference.findAll(doc.codeText).forEach { match ->
                val coordinate = match.groupValues[1]
                val parts = coordinate.split(':')
                if (parts.size in 3..4 && parts.all { it.isNotBlank() && it.none(Char::isWhitespace) })
                    add(coordinate, "gradle artifact", emptyList(), true)
            }
        }
        // Android imports must not consume the whole budget before local source/resource edges.
        return result.distinct().sortedWith(compareBy<Dependency> {
            if (it.resolvedPaths.isNotEmpty()) 0 else if (it.status == "unresolved") 1 else 2
        }.thenByDescending {
            val concepts = terms(it.reference)
            concepts.intersect(query.primary).size * 4 + concepts.intersect(query.expanded).size
        }).take(40)
    }

    private fun citations(doc: Document, query: Query, commit: String): List<Citation> {
        val ranked = doc.lines.mapIndexed { index, line -> index to (terms(line).intersect(query.primary).size * 4 + terms(line).intersect(query.expanded).size) }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first })
        val windows = mutableListOf<IntRange>()
        for ((index, _) in ranked) {
            val start = (index - 2).coerceAtLeast(0)
            val range = start..minOf(start + MAX_SNIPPET_LINES - 1, doc.lines.lastIndex)
            if (windows.none { existing -> range.first <= existing.last && existing.first <= range.last }) windows += range
            if (windows.size == 2) break
        }
        return windows.sortedBy { it.first }.map { range ->
            val full = range.joinToString("\n") { index -> redact(doc.lines[index]) }
            val snippet = full.take(MAX_SNIPPET_CHARACTERS)
            Citation(doc.source.path, doc.source.sha, commit, range.first + 1, range.first + snippet.count { it == '\n' } + 1, snippet, full.length > snippet.length)
        }
    }

    private fun redact(line: String): String = RepositoryRedaction.line(line)

    private fun proposal(task: String, doc: Document, symbols: List<Symbol>): String {
        val anchors = symbols.take(4).joinToString { "${it.name} (line ${it.line})" }.ifBlank { "the cited source" }
        val taskTerms = terms(task)
        val sourceTerms = doc.terms
        val constraints = mutableListOf<String>()
        if (("scroll" in taskTerms || "appearance" in taskTerms) && sourceTerms.any { it in setOf("scrollview", "scroll", "height", "layout", "font") })
            constraints += "choose one scrolling owner and inspect fixed/weighted container heights so affected controls remain reachable at large font sizes; retain selected-theme and preference bindings"
        if (("menu" in taskTerms || "navigation" in taskTerms) && sourceTerms.any { it in setOf("menu", "navigation", "destination", "startactivity") })
            constraints += "group the matching destinations through the existing navigation/row helpers, preserving destination routes, Back handling and existing handoff callbacks"
        if (taskTerms.any { it in setOf("connect", "diagnos", "provider") } && sourceTerms.any { it in setOf("connect", "provider", "http", "client") })
            constraints += "trace settings validation, provider selection and request/error classification to locate the failure before changing behavior; keep credential storage and diagnostic prompts intact"
        if (taskTerms.any { it in setOf("chat", "context", "conversation") } && sourceTerms.any { it in setOf("history", "conversation", "session", "message") })
            constraints += "verify whether history and asynchronous replies are keyed to a conversation; if shared, propose conversation-bound state and reply routing while retaining saved-chat persistence and voice callers"
        if (taskTerms.any { it in setOf("progress", "roadmap", "phase") } && sourceTerms.any { it in setOf("plan", "roadmap", "phase", "intelligence") })
            constraints += "connect the existing plan reader/status view to explicit implemented/tested/pending evidence at a pinned revision; keep missing evidence pending and avoid inferred completion percentages"
        val kind = when {
            doc.source.path.endsWith(".md") -> "Update documented status only from verified implementation, tests and commit evidence"
            "/test/" in doc.source.path || "/androidTest/" in doc.source.path -> "Extend regression coverage around $anchors"
            doc.source.path.endsWith(".xml") -> "Review the affected views/resources and their owning activity before adjusting $anchors"
            doc.source.path.endsWith(".gradle") || doc.source.path.endsWith(".kts") -> "Check module/build dependencies and compatibility before changing $anchors"
            else -> "Trace $anchors and the listed dependencies; scope the implementation to the behavior needed"
        }
        val candidate = if (constraints.isEmpty()) " Source evidence does not establish the precise behavior change; verify callers and acceptance expectations first." else " Candidate: ${constraints.joinToString("; ")}."
        return "$kind for \"$task\".$candidate Review the resulting diff and run focused checks before applying changes."
    }

    /** Preserve offsets/newlines while preventing comments and quoted examples creating symbols. */
    private fun maskCommentsAndStrings(text: String, maskStrings: Boolean = true): String {
        val masked = text.toCharArray()
        // 0=code, 1=line comment, 2=block comment, 3=string, 4=char, 5=Kotlin triple string.
        // An iterative scanner avoids regex stack growth on long or unterminated untrusted input.
        var state = 0
        var blockDepth = 0
        var index = 0
        fun mask(at: Int) { if (text[at] != '\n' && text[at] != '\r') masked[at] = ' ' }
        fun maskString(at: Int) { if (maskStrings) mask(at) }
        fun starts(value: String): Boolean = text.startsWith(value, index)
        while (index < text.length) {
            when (state) {
                0 -> when {
                    starts("//") -> { mask(index); mask(index + 1); index += 2; state = 1 }
                    starts("/*") -> { mask(index); mask(index + 1); index += 2; state = 2; blockDepth = 1 }
                    starts("\"\"\"") -> { repeat(3) { maskString(index + it) }; index += 3; state = 5 }
                    text[index] == '"' -> { maskString(index); index++; state = 3 }
                    text[index] == '\'' -> { maskString(index); index++; state = 4 }
                    else -> index++
                }
                1 -> if (text[index] == '\n' || text[index] == '\r') { index++; state = 0 } else { mask(index); index++ }
                2 -> when {
                    starts("/*") -> { mask(index); mask(index + 1); index += 2; blockDepth++ }
                    starts("*/") -> { mask(index); mask(index + 1); index += 2; blockDepth--; if (blockDepth == 0) state = 0 }
                    else -> { mask(index); index++ }
                }
                3, 4 -> when {
                    text[index] == '\\' -> { maskString(index); index++; if (index < text.length) { maskString(index); index++ } }
                    (state == 3 && text[index] == '"') || (state == 4 && text[index] == '\'') -> { maskString(index); index++; state = 0 }
                    else -> { maskString(index); index++ }
                }
                5 -> if (starts("\"\"\"")) { repeat(3) { maskString(index + it) }; index += 3; state = 0 } else { maskString(index); index++ }
            }
        }
        return String(masked)
    }
    private fun maskXmlComments(text: String): String {
        val masked = text.toCharArray()
        var inComment = false
        var index = 0
        fun mask(at: Int) { if (text[at] != '\n' && text[at] != '\r') masked[at] = ' ' }
        while (index < text.length) {
            when {
                !inComment && text.startsWith("<!--", index) -> { repeat(4) { mask(index + it) }; index += 4; inComment = true }
                inComment && text.startsWith("-->", index) -> { repeat(3) { mask(index + it) }; index += 3; inComment = false }
                inComment -> { mask(index); index++ }
                else -> index++
            }
        }
        return String(masked)
    }
    private fun impact(doc: Document): String {
        val risks = mutableListOf<String>()
        val text = doc.text.lowercase(Locale.ROOT)
        if (listOf("scrollview", "layout_height", "font", "theme").any { it in text }) risks += "layout reachability, large text and selected-theme behavior"
        if (listOf("startactivity", "menu", "navigation", "onback").any { it in text }) risks += "navigation destinations and Back behavior"
        if (listOf("history", "conversation", "session", "persist").any { it in text }) risks += "state ownership, saved context and delayed/asynchronous results"
        if (listOf("http", "connection", "apikey", "provider", "token").any { it in text }) risks += "provider routing, failure classification and credential handling; preserve credential configuration"
        if (listOf("roadmap", "phase", "milestone", "completion").any { it in text }) risks += "accuracy of progress claims and supporting evidence"
        if (risks.isEmpty()) risks += "callers and linked resources; runtime behavior is not established by lexical inspection"
        return risks.distinct().joinToString("; ").replaceFirstChar { it.uppercaseChar() } + "."
    }
}
