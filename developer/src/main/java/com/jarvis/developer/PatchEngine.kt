package com.jarvis.developer

import java.io.StringReader
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Pure proposal validation. This object has no repository, filesystem, process, or network access. */
object PatchEngine {
    const val MAX_FILES = 8
    const val MAX_FILE_BYTES = 200_000
    const val MAX_TOTAL_BYTES = 400_000
    private const val MAX_RESPONSE_CHARS = 850_000
    private const val MAX_DIFF_BYTES = 240_000
    private const val MAX_DIFF_CELLS = 2_000_000

    data class SourceFile(val path: String, val sha: String?, val content: String?)
    data class Request(
        val taskId: String,
        val task: String,
        val baseCommitSha: String,
        val allowedPaths: List<String>,
        val sources: List<SourceFile>,
        val provider: String,
        val model: String
    )
    data class Edit(val path: String, val content: String)

    class Proposal internal constructor(
        val request: Request,
        val edits: List<Edit>,
        val summary: String,
        val id: String,
        val diff: String
    )

    /** Cannot be constructed without checking both the complete proposal and exact confirmation. */
    class ApprovedProposal private constructor(val proposal: Proposal, private val confirmedId: String) {
        internal fun validate() {
            revalidate(proposal)
            require(confirmedId == proposal.id) { "Approval no longer matches proposal" }
        }
        companion object {
            internal fun confirm(proposal: Proposal, confirmation: String): ApprovedProposal {
                revalidate(proposal)
                require(confirmation == proposal.id) { "Exact proposal ID confirmation is required" }
                return ApprovedProposal(proposal, confirmation)
            }
        }
    }

    private val gitSha = Regex("[0-9a-f]{40}")
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")
    private val safePath = Regex("[A-Za-z0-9_./-]+")
    private val protectedName = Regex(
        "(?i)(?:agent|patch|github|repository|model|provider|transport|credential|secret|token|password|signing|keystore|updat|release|recovery|approval|publish|mainactivity|buildconfig|apikey|api_key|networksecurity)"
    )
    private val knownSecret = Regex("\\b(?:gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,})\\b|-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----")
    private val namedSecret = Regex("(?i)(?<![A-Za-z0-9_])(?:[A-Za-z0-9_]{0,120}(?:api[_-]?key|token|password|secret|authorization))[\"']?\\s*+(?::\\s*+[A-Za-z?]++)?\\s*+[=:]\\s*+[\"'][^\"'\\r\\n]++[\"']")
    private val credentialName = Regex("(?i)(?:api[_-]?key|token|password|secret|authorization)")
    private val protectedResourceName = Regex("(?i)(?:signing|keystore|update|release|recovery|credential|application[_-]?id|package[_-]?name|file[_-]?paths|network[_-]?security)")
    private val execution = Regex(
        "(?i)\\b(?:Runtime|ProcessBuilder|DexClassLoader|URLClassLoader|ScriptEngineManager|MethodHandles|ClassLoader|javaClass|getClass|exec|execve|spawn|load|loadLibrary|getDeclaredMethod|getDeclaredField|getDeclaredConstructor|getMethod|getField|getConstructor|forName|invoke|newInstance)\\b|\\b(?-i:Class)\\b|\\b(?:java\\s*\\.\\s*lang\\s*\\.\\s*reflect|kotlin\\s*\\.\\s*reflect|javax\\s*\\.\\s*script)\\b|::[ \\t]*class\\b|\\\\u[0-9a-f]{4}"
    )
    private val packageLine = Regex("(?m)^[ \\t]*package[ \\t]+([A-Za-z_][A-Za-z0-9_.]*)[ \\t]*;?[ \\t]*\\r?$")
    private val dependencyLine = Regex("[ \\t]*(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|debugImplementation|releaseImplementation)(?:[ \\t]+([\"'])[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+,-]+\\1|[ \\t]*\\([ \\t]*([\"'])[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+,-]+\\2[ \\t]*\\))[ \\t]*\\r?")

    fun allowedPath(path: String): Boolean {
        if (path.length !in 1..240 || !safePath.matches(path)) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith('.') }) return false
        if (path == "developer/build.gradle" || path == "developer/build.gradle.kts") return true
        if (parts.any { protectedName.containsMatchIn(it) }) return false
        val code = Regex("developer/src/(?:main|test|androidTest)/(?:java|kotlin)/com/jarvis/developer/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:kt|java)")
        val resource = Regex("developer/src/main/res/(?:layout|drawable|values|menu)(?:-[A-Za-z0-9_-]+)?/[A-Za-z0-9_]+\\.xml")
        return code.matches(path) || resource.matches(path)
    }

    fun validateRequest(request: Request) {
        require(identifier.matches(request.taskId)) { "Invalid task ID" }
        require(gitSha.matches(request.baseCommitSha)) { "Full immutable base commit SHA is required" }
        require(request.task.isNotBlank() && request.task.length <= 12_000) { "Task is empty or too large" }
        require(request.provider.matches(Regex("[A-Za-z][A-Za-z0-9 ._-]{0,63}"))) { "Invalid provider" }
        require(request.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,119}"))) { "Invalid model" }
        safeText(request.task)
        safeText(request.provider)
        safeText(request.model)
        require(request.allowedPaths.size in 1..MAX_FILES) { "Scope must contain 1..$MAX_FILES files" }
        require(request.allowedPaths.distinct().size == request.allowedPaths.size) { "Duplicate allowed path" }
        require(request.allowedPaths.all(::allowedPath)) { "Path is outside Developer patch allowlist" }
        require(request.sources.size == request.allowedPaths.size && request.sources.map { it.path }.toSet() == request.allowedPaths.toSet()) { "Complete exact source scope is required" }
        require(request.sources.map { it.path }.distinct().size == request.sources.size) { "Duplicate source path" }
        var total = 0
        for (source in request.sources) {
            require((source.sha == null) == (source.content == null)) { "Source needs both Git blob SHA and content, or neither for a new file" }
            if (source.content != null) {
                validateText(source.content)
                if (source.path.endsWith(".xml")) validateXml(source.content)
                total += source.content.toByteArray(Charsets.UTF_8).size
                require(gitSha.matches(source.sha.orEmpty()) && blobSha(source.content) == source.sha) { "Source Git blob SHA mismatch" }
            } else {
                require(!source.path.endsWith(".gradle") && !source.path.endsWith(".gradle.kts")) { "New Gradle configuration is forbidden" }
            }
        }
        require(total <= MAX_TOTAL_BYTES) { "Source scope exceeds total byte budget" }
    }

    fun parse(request: Request, response: String): Proposal {
        validateRequest(request)
        require(response.length <= MAX_RESPONSE_CHARS) { "Model response exceeds byte budget" }
        val parsed = StrictJson(response).proposal()
        val frozen = request.copy(allowedPaths = request.allowedPaths.toList(), sources = request.sources.map { it.copy() })
        val edits = parsed.second.map { it.copy() }.sortedBy { it.path }
        validateEdits(frozen, edits, parsed.first)
        val diff = createDiff(frozen, edits)
        return Proposal(frozen, edits, parsed.first, fingerprint(frozen, edits, parsed.first), diff)
    }

    fun revalidate(proposal: Proposal) {
        validateRequest(proposal.request)
        validateEdits(proposal.request, proposal.edits, proposal.summary)
        require(proposal.id == fingerprint(proposal.request, proposal.edits, proposal.summary)) { "Proposal fingerprint mismatch" }
        require(proposal.diff == createDiff(proposal.request, proposal.edits)) { "Proposal diff mismatch" }
    }

    fun approve(proposal: Proposal, confirmedId: String): ApprovedProposal = ApprovedProposal.confirm(proposal, confirmedId)
    fun validateApproval(approved: ApprovedProposal) = approved.validate()

    fun branchName(proposal: Proposal): String {
        revalidate(proposal)
        // A sibling namespace avoids Git's file/directory ref conflict with feature/jarvis-developer.
        return "feature/jarvis-developer-task/${proposal.request.taskId}-${proposal.id.take(12)}"
    }

    private fun safeText(text: String) {
        require(!knownSecret.containsMatchIn(text) && !namedSecret.containsMatchIn(text)) { "Recognized credential literal is forbidden" }
        require(text.none { it == '\u0000' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') || it == '\u007f' }) { "Binary or control text is forbidden" }
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (Character.isHighSurrogate(char)) {
                require(index < text.length && Character.isLowSurrogate(text[index++])) { "Invalid Unicode text" }
            } else require(!Character.isLowSurrogate(char)) { "Invalid Unicode text" }
        }
    }

    private fun validateText(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "File exceeds byte budget" }
        safeText(text)
    }

    private fun validateEdits(request: Request, edits: List<Edit>, summary: String) {
        require(summary.isNotBlank() && summary.length <= 8_000) { "Summary is empty or too large" }
        safeText(summary)
        require(edits.size in 1..MAX_FILES) { "Proposal needs bounded edits" }
        require(edits.map { it.path }.distinct().size == edits.size) { "Duplicate edit path" }
        var total = 0
        for (edit in edits) {
            require(allowedPath(edit.path) && edit.path in request.allowedPaths) { "Edit outside explicit task scope" }
            val original = request.sources.single { it.path == edit.path }.content
            require(edit.content.isNotBlank()) { "Empty files and deletion are forbidden" }
            require(edit.content != original) { "No-op edit is forbidden" }
            validateText(edit.content)
            total += edit.content.toByteArray(Charsets.UTF_8).size
            when {
                edit.path.endsWith(".xml") -> {
                    val document = validateXml(edit.content)
                    require(!hasProtectedResource(document) && (original == null || !hasProtectedResource(validateXml(original)))) { "Protected signing, package, or update resource configuration is outside patch scope" }
                }
                edit.path.endsWith(".gradle") || edit.path.endsWith(".gradle.kts") -> validateGradle(requireNotNull(original), edit.content)
                else -> {
                    require(!execution.containsMatchIn(edit.content)) { "Shell, reflection, or dynamic execution hooks are forbidden" }
                    val expected = edit.path.substringAfter("/com/jarvis/developer/").substringBeforeLast('/', "")
                        .replace('/', '.').let { if (it.isEmpty()) "com.jarvis.developer" else "com.jarvis.developer.$it" }
                    val packages = packageLine.findAll(edit.content).map { it.groupValues[1] }.toList()
                    require(packages == listOf(expected)) { "Developer source package must match its path" }
                    if (original != null) require(packageLine.findAll(original).map { it.groupValues[1] }.toList() == packages) { "Package identity must be preserved" }
                }
            }
        }
        require(total <= MAX_TOTAL_BYTES) { "Edits exceed total byte budget" }
    }

    private fun validateXml(content: String): org.w3c.dom.Document {
        require(!Regex("(?i)<!\\s*(?:DOCTYPE|ENTITY)").containsMatchIn(content)) { "XML DTD/entities are forbidden" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        factory.isExpandEntityReferences = false
        // Android's parser does not implement every optional JAXP feature. Lexical DTD rejection
        // above plus an entity resolver that always fails remain mandatory on every platform.
        for ((feature, value) in listOf(XMLConstants.FEATURE_SECURE_PROCESSING to true,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false)) {
            try { factory.setFeature(feature, value) } catch (_: javax.xml.parsers.ParserConfigurationException) { /* optional parser hardening */ }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External XML entity is forbidden") }
        builder.setErrorHandler(object : DefaultHandler() {
            override fun error(error: SAXParseException) { throw error }
            override fun fatalError(error: SAXParseException) { throw error }
        })
        return try {
            val document = builder.parse(InputSource(StringReader(content)))
            val pending = java.util.ArrayDeque<Pair<org.w3c.dom.Node, Int>>()
            pending.add(document.documentElement to 1)
            var visited = 0
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                require(++visited <= 20_000 && depth <= 64) { "XML complexity exceeds review budget" }
                if (node is org.w3c.dom.Element) {
                    val attributes = node.attributes
                    for (index in 0 until attributes.length) safeText(attributes.item(index).nodeValue.orEmpty())
                    require(!credentialName.containsMatchIn(node.getAttribute("name"))) { "Recognized credential resource is forbidden" }
                }
                val children = node.childNodes
                for (index in 0 until children.length) pending.add(children.item(index) to depth + 1)
            }
            // Decode predefined/numeric entities before checking recognizable values as well.
            safeText(document.documentElement.textContent.orEmpty())
            document
        }
        catch (error: IllegalArgumentException) { throw error }
        catch (_: Exception) { throw IllegalArgumentException("Malformed or unsafe XML") }
    }

    private fun hasProtectedResource(document: org.w3c.dom.Document): Boolean {
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length).any {
            protectedResourceName.containsMatchIn((elements.item(it) as org.w3c.dom.Element).getAttribute("name"))
        }
    }

    /** Only literal dependency lines may differ; every other original byte is retained. */
    private fun validateGradle(before: String, after: String) {
        fun skeleton(text: String): String {
            val result = StringBuilder()
            var inDependencies = false
            var blocks = 0
            for (line in lines(text)) {
                val trimmed = line.text.trim()
                if (trimmed == "dependencies {") {
                    require(!inDependencies) { "Nested dependency blocks are forbidden" }
                    blocks++
                    inDependencies = true
                } else if (inDependencies && trimmed == "}") inDependencies = false
                else if (inDependencies && dependencyLine.matches(line.text)) continue
                result.append(line.text)
                if (line.newline) result.append('\n')
            }
            require(blocks == 1 && !inDependencies) { "One existing dependencies block is required" }
            return result.toString()
        }
        require(skeleton(before) == skeleton(after)) { "Gradle permits only static dependency edits; all configuration must remain byte-identical" }
    }

    private fun blobSha(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        return hex(digest.digest(bytes))
    }

    private fun fingerprint(request: Request, edits: List<Edit>, summary: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            if (value == null) { digest.update("-1:".toByteArray()); return }
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray()); digest.update(bytes)
        }
        field("jarvis-patch-v1"); field(request.taskId); field(request.task); field(request.baseCommitSha)
        field(request.provider); field(request.model)
        field(request.allowedPaths.size.toString()); request.allowedPaths.forEach { field(it) }
        field(request.sources.size.toString()); request.sources.forEach { field(it.path); field(it.sha); field(it.content) }
        field(summary); field(edits.size.toString()); edits.forEach { field(it.path); field(it.content) }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Line(val text: String, val newline: Boolean)
    private data class DiffLine(val kind: Char, val line: Line)

    private fun lines(text: String): List<Line> {
        val result = ArrayList<Line>()
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start)
            if (end == -1) { result.add(Line(text.substring(start), false)); break }
            result.add(Line(text.substring(start, end), true)); start = end + 1
        }
        return result
    }

    private fun createDiff(request: Request, edits: List<Edit>): String {
        val output = StringBuilder()
        for (edit in edits.sortedBy { it.path }) {
            val source = request.sources.single { it.path == edit.path }
            val old = lines(source.content.orEmpty())
            val new = lines(edit.content)
            require((old.size.toLong() + 1) * (new.size.toLong() + 1) <= MAX_DIFF_CELLS) { "Diff exceeds line comparison budget; reduce task scope" }
            val width = new.size + 1
            val lcs = IntArray((old.size + 1) * width)
            for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
                lcs[i * width + j] = if (old[i] == new[j]) 1 + lcs[(i + 1) * width + j + 1]
                    else maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
            }
            val changes = ArrayList<DiffLine>()
            var i = 0; var j = 0
            while (i < old.size || j < new.size) {
                when {
                    i < old.size && j < new.size && old[i] == new[j] -> { changes.add(DiffLine(' ', old[i])); i++; j++ }
                    i < old.size && (j == new.size || lcs[(i + 1) * width + j] >= lcs[i * width + j + 1]) -> changes.add(DiffLine('-', old[i++]))
                    else -> changes.add(DiffLine('+', new[j++]))
                }
            }
            output.append("diff --git a/${edit.path} b/${edit.path}\n")
            if (source.content == null) output.append("new file mode 100644\n")
            output.append("--- ${if (source.content == null) "/dev/null" else "a/${edit.path}"}\n+++ b/${edit.path}\n")
            val hunks = ArrayList<IntRange>()
            changes.indices.filter { changes[it].kind != ' ' }.forEach { index ->
                val start = maxOf(0, index - 3); val end = minOf(changes.lastIndex, index + 3)
                val previous = hunks.lastOrNull()
                if (previous != null && start <= previous.last + 1) hunks[hunks.lastIndex] = previous.first..maxOf(previous.last, end)
                else hunks.add(start..end)
            }
            for (hunk in hunks) {
                val oldStart = changes.take(hunk.first).count { it.kind != '+' }
                val newStart = changes.take(hunk.first).count { it.kind != '-' }
                val oldCount = hunk.count { changes[it].kind != '+' }
                val newCount = hunk.count { changes[it].kind != '-' }
                output.append("@@ -${if (oldCount == 0) oldStart else oldStart + 1},$oldCount +${if (newCount == 0) newStart else newStart + 1},$newCount @@\n")
                for (index in hunk) {
                    val line = changes[index]
                    output.append(line.kind).append(line.line.text).append('\n')
                    if (!line.line.newline) output.append("\\ No newline at end of file\n")
                }
            }
            require(output.toString().toByteArray(Charsets.UTF_8).size <= MAX_DIFF_BYTES) { "Complete diff exceeds review budget; reduce task scope" }
        }
        return output.toString()
    }

    /** Strict, bounded schema parser: no comments, duplicate/unknown fields, coerced values or tails. */
    private class StrictJson(private val text: String) {
        private var at = 0
        private fun whitespace() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun take(char: Char) { whitespace(); require(at < text.length && text[at++] == char) { "Invalid patch JSON" } }
        private fun peek(char: Char): Boolean { whitespace(); return at < text.length && text[at] == char }
        private fun string(): String {
            take('"')
            val result = StringBuilder()
            while (at < text.length) {
                val char = text[at++]
                if (char == '"') return result.toString()
                require(char >= ' ') { "Unescaped JSON control character" }
                if (char != '\\') { result.append(char); continue }
                require(at < text.length) { "Truncated JSON escape" }
                when (val escape = text[at++]) {
                    '"', '\\', '/' -> result.append(escape)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        require(at + 4 <= text.length) { "Truncated JSON Unicode escape" }
                        val digits = text.substring(at, at + 4)
                        require(digits.all { it in "0123456789abcdefABCDEF" }) { "Invalid JSON Unicode escape" }
                        result.append(digits.toInt(16).toChar()); at += 4
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape")
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }
        private fun fields(onField: (String) -> Unit) {
            take('{')
            val seen = HashSet<String>()
            if (!peek('}')) while (true) {
                val key = string()
                require(seen.add(key)) { "Duplicate JSON field" }
                take(':'); onField(key)
                if (peek('}')) break
                take(',')
                require(!peek('}')) { "Trailing JSON comma" }
            }
            take('}')
        }
        fun proposal(): Pair<String, List<Edit>> {
            var summary: String? = null
            var edits: List<Edit>? = null
            fields { key ->
                when (key) {
                    "summary" -> summary = string()
                    "edits" -> {
                        take('[')
                        val parsed = ArrayList<Edit>()
                        if (!peek(']')) while (true) {
                            require(parsed.size < MAX_FILES) { "Too many model edits" }
                            var path: String? = null; var content: String? = null
                            fields { field -> when (field) {
                                "path" -> path = string()
                                "content" -> content = string()
                                else -> throw IllegalArgumentException("Unknown edit JSON field")
                            } }
                            parsed.add(Edit(requireNotNull(path) { "Missing edit path" }, requireNotNull(content) { "Missing edit content" }))
                            if (peek(']')) break
                            take(','); require(!peek(']')) { "Trailing JSON comma" }
                        }
                        take(']'); edits = parsed
                    }
                    else -> throw IllegalArgumentException("Unknown patch JSON field")
                }
            }
            whitespace(); require(at == text.length) { "Trailing model JSON data" }
            return requireNotNull(summary) { "Missing summary" } to requireNotNull(edits) { "Missing edits" }
        }
    }
}
