package com.jarvis.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64

/**
 * Acceptance runs against the actual Actions checkout, not a synthetic task-to-file fixture.
 * Its fake GET transport exercises the production reader and session budget without network or
 * write capabilities. Assertions use actual Git blob identities,
 * declarations, source lines and references; a filename match alone cannot satisfy a case.
 */
class RepositoryAcceptanceTest {
    private val core = "app/src/main/java/com/assistant/core/"
    private val developer = "developer/src/main/java/com/jarvis/developer/"

    @Test fun menuRestructuringFindsNavigationAndSharedRows() = verify(
        task = "Restructure the tools and settings menu into clear categories while preserving back navigation and microphone handoff.",
        requiredFiles = setOf("${core}MainActivity.kt", "${core}SettingsActivity.kt"),
        dependencyTargets = setOf("${core}SettingsMenuViews.kt"),
        declarationNames = setOf("SettingsActivity", "showToolsAndSettingsMenu", "SettingsMenuViews")
    )

    @Test fun appearanceScrollingFindsHostLayoutAndThemeBinding() = verify(
        task = "Fix Appearance scrolling so theme and font controls remain reachable with large text on a narrow screen.",
        requiredFiles = setOf("${core}AppearanceSettingsActivity.kt"),
        dependencyTargets = setOf("app/src/main/res/layout/activity_appearances.xml", "${core}ThemeSettingsActivity.kt"),
        declarationNames = setOf("AppearanceSettingsActivity", "showTab", "fontControls")
    )

    @Test fun aiConnectivityDiagnosisFindsDiagnosticAndProviderLayers() = verify(
        task = "Diagnose AI connectivity in IntelligenceActivity and IntelligenceOverview: explain why provider connection tests fail while preserving saved credentials and avoiding learning from diagnostic prompts.",
        requiredFiles = setOf("${core}IntelligenceActivity.kt", "${core}services/IntelligenceOverview.kt"),
        dependencyTargets = setOf("${core}services/DirectOnlineClient.kt", "${core}services/FallbackAiClient.kt"),
        declarationNames = setOf("testConnection", "IntelligenceOverview", "IntelligenceActivity")
    )

    @Test fun perChatContextFindsConversationLifecycleAndServiceHistory() = verify(
        task = "Keep context separate for each saved chat and prevent delayed assistant replies from appearing in a different conversation.",
        requiredFiles = setOf("${core}MainActivity.kt", "${core}services/HybridAssistantService.kt"),
        dependencyTargets = setOf("${core}services/LiveChatStore.kt", "${core}services/DirectOnlineClient.kt", "${core}services/ConversationMessages.kt"),
        declarationNames = setOf("history", "loadOrCreateConversation", "getConversationSnapshot", "HybridAssistantService")
    )

    @Test fun intelligencePlanProgressFindsDeveloperRoadmapAndReader() = verify(
        task = "Show Intelligence Plan progress in Jarvis Developer using evidence of implemented, tested and still pending roadmap work.",
        requiredFiles = setOf("developer/DEVELOPMENT_ROADMAP.md", "${developer}MainActivity.kt"),
        dependencyTargets = setOf("${developer}GithubPlans.kt"),
        declarationNames = setOf("GithubPlans", "MainActivity", "showIntelligencePlan", "readPlan")
    )

    private fun verify(
        task: String,
        requiredFiles: Set<String>,
        dependencyTargets: Set<String>,
        declarationNames: Set<String>
    ) {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot")) {
            "Set jarvis.repositoryRoot to the analyzed checkout"
        }).toRealPath()
        val commit = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue("Acceptance requires an explicit full checkout commit SHA", Regex("[a-fA-F0-9]{40}").matches(commit))
        val before = snapshot(root)
        assertTrue("Real checkout is missing required task source paths", before.keys.containsAll(requiredFiles))
        assertTrue("Real checkout is missing task dependency source paths", dependencyTargets.any { it in before })
        val fixture = CheckoutGetFixture(root, commit, before)
        val (inventory, plan) = try {
            RepositoryIntelligence.Reader(fixture).analyzeSnapshot("", task)
        } finally {
            val after = snapshot(root)
            assertEquals("Analysis added or removed repository source paths", before.keys, after.keys)
            assertTrue("Analysis modified repository source bytes", before.all { (path, bytes) -> bytes.contentEquals(after.getValue(path)) })
        }

        assertEquals("Plan must cite the analyzed immutable revision", commit, plan.commitSha)
        assertEquals("Plan must preserve the user's task", task, plan.task)
        assertEquals("Production inventory must contain actual approved checkout paths", before.keys.toList(), inventory.files.map { it.path })
        assertEquals("Production inventory must remain pinned to the checkout revision", commit, inventory.commitSha)
        assertEquals("Source GETs must follow the attempted-read audit in order", fixture.sourceReads, plan.inspectedPaths.take(fixture.sourceReads.size))
        assertEquals("Analysis must resolve ref, commit and tree before source GETs", fixture.metadataUrls, fixture.requests.take(3))
        assertTrue("Production analysis exceeded its GET budget", fixture.requests.size <= RepositoryIntelligence.MAX_SESSION_REQUESTS)
        assertEquals("Every production GET response must close", fixture.requests.size, fixture.closedResponses)
        if (plan.inspectedPaths.size > fixture.sourceReads.size) {
            assertEquals("GET budget must account for three metadata requests", RepositoryIntelligence.MAX_SESSION_REQUESTS, fixture.requests.size)
            assertTrue("Session budget must disclose incomplete context", plan.limitations.any { it.contains("64-request") && it.contains("incomplete") })
        }
        assertEquals("Inventory count must report actual approved source files", before.size, plan.inventoryCount)
        if (!plan.files.map { it.path }.containsAll(requiredFiles)) {
            // Failure evidence stays metadata-only: never print retrieved code or credential values.
            println("Acceptance revision: $commit")
            println("Required file GET coverage: ${requiredFiles.associateWith { it in fixture.sourceReads }}")
            println("Planned source paths: ${plan.files.map { it.path }}")
            println("Source GET order: ${fixture.sourceReads}")
            println("Resolved dependency paths: ${plan.files.flatMap { it.dependencies }.flatMap { it.resolvedPaths }.distinct()}")
        }
        assertTrue("Core task files missing from the proposed plan: $requiredFiles", plan.files.map { it.path }.containsAll(requiredFiles))
        assertTrue("Analysis must explain its lexical/bounded limitations", plan.limitations.isNotEmpty())
        assertTrue("Relevant source declarations were not identified", plan.files.flatMap { it.symbols }.any { it.name in declarationNames })
        assertTrue("Actual task dependency was not identified", plan.files.flatMap { it.dependencies }.any { dependency ->
            dependency.resolvedPaths.any { it in dependencyTargets }
        })

        val rendered = RepositoryAnalysis.render(plan)
        assertTrue("Readable report must identify its immutable revision", rendered.contains(commit))
        plan.files.forEach { file ->
            val bytes = before.getValue(file.path)
            val text = bytes.toString(Charsets.UTF_8)
            val lines = text.lines()
            assertEquals("Planned blob must match actual checkout bytes: ${file.path}", gitBlob(bytes), file.source.sha)
            assertEquals("Planned source size must match checkout bytes", bytes.size.toLong(), file.source.size)
            assertTrue("Plan must explain why a file is relevant: ${file.path}", file.reasons.isNotEmpty())
            assertTrue("Plan must describe a reviewable proposed change: ${file.path}", file.proposedChange.length >= 30)
            assertTrue("Plan must explain likely impact: ${file.path}", file.likelyImpact.length >= 30)
            assertTrue("Plan must retrieve cited code: ${file.path}", file.citations.isNotEmpty())
            assertTrue("Readable report must include planned source paths", rendered.contains(file.path))
            file.symbols.forEach { symbol ->
                assertTrue("Declaration line is outside real source: ${file.path}", symbol.line in 1..lines.size)
                assertTrue("Declaration is not present at its cited source line: ${file.path}", lines[symbol.line - 1].contains(symbol.name))
            }
            file.dependencies.forEach { dependency ->
                assertTrue("Dependency reference needs source evidence: ${file.path}",
                    text.contains(dependency.reference) || text.contains(dependency.reference.substringAfterLast('.')))
                assertTrue("Resolved dependencies must exist in the approved checkout", dependency.resolvedPaths.all { it in before })
            }
            file.citations.forEach { citation ->
                assertEquals("Citation path must identify the planned file", file.path, citation.path)
                assertEquals("Citation must identify the exact source commit", commit, citation.commitSha)
                assertEquals("Citation must identify the verified Git blob", gitBlob(bytes), citation.blobSha)
                assertTrue("Citation range is outside real source", citation.startLine >= 1 && citation.endLine in citation.startLine..lines.size)
                assertTrue("Citation exceeds source excerpt line budget", citation.endLine - citation.startLine + 1 <= RepositoryAnalysis.MAX_SNIPPET_LINES)
                assertTrue("Citation exceeds excerpt character budget", citation.code.length <= RepositoryAnalysis.MAX_SNIPPET_CHARACTERS)
                assertTrue("Citation must contain retrieved source", citation.code.isNotBlank())
                val citedLines = citation.code.lines()
                // Redacted values remain hidden even in a failing test's output.
                citedLines.forEachIndexed { offset, visibleLine ->
                    if (!visibleLine.contains("[REDACTED]")) {
                        assertTrue("Citation does not match its real source location: ${file.path}",
                            lines[citation.startLine - 1 + offset].startsWith(visibleLine))
                    }
                }
            }
        }
    }

    /** GitHub protocol responses wrap the real checkout; source bytes are read only on GET. */
    private class CheckoutGetFixture(
        private val root: Path,
        private val commit: String,
        private val sourceBytes: Map<String, ByteArray>
    ) : RepositoryIntelligence.GetTransport {
        private val api = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-"
        // This models a scoped GitHub tree response. Source commit/blob identities are genuine;
        // this stable fixture tree identifier is not presented as the repository's Git tree SHA.
        private val treeSha = gitBlob(sourceBytes.entries.joinToString("\n") { (path, bytes) ->
            "$path:${gitBlob(bytes)}"
        }.toByteArray(Charsets.UTF_8))
        val metadataUrls = listOf("$api/git/ref/heads/feature%2Fjarvis-developer",
            "$api/git/commits/$commit", "$api/git/trees/$treeSha?recursive=1")
        val requests = mutableListOf<String>()
        val sourceReads = mutableListOf<String>()
        var closedResponses = 0
            private set

        override fun get(url: String, token: String): RepositoryIntelligence.GetResponse {
            assertTrue("Acceptance uses no repository write credential", token.isBlank())
            requests += url
            assertTrue("Production transport called GET after its session limit", requests.size <= RepositoryIntelligence.MAX_SESSION_REQUESTS)
            val json = when (url) {
                metadataUrls[0] -> JSONObject().put("ref", "refs/heads/feature/jarvis-developer")
                    .put("object", JSONObject().put("type", "commit").put("sha", commit))
                metadataUrls[1] -> JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", treeSha))
                metadataUrls[2] -> JSONObject().put("sha", treeSha).put("truncated", false)
                    .put("tree", JSONArray(sourceBytes.map { (path, bytes) -> JSONObject()
                        .put("path", path).put("type", "blob").put("mode", "100644")
                        .put("sha", gitBlob(bytes)).put("size", bytes.size) }))
                else -> {
                    assertTrue("Analysis requested an unexpected endpoint", url.startsWith("$api/contents/") && url.endsWith("?ref=$commit"))
                    val path = url.removePrefix("$api/contents/").substringBefore('?')
                        .split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") }
                    assertTrue("Analysis requested a path outside the approved inventory", path in sourceBytes)
                    assertTrue("Analysis requested a denied source path", RepositoryIntelligence.allowedSource(path))
                    val sourcePath = root.resolve(path).normalize()
                    assertTrue("Analysis path escaped its checkout", sourcePath.startsWith(root))
                    assertFalse("Analysis cannot follow source symlinks", Files.isSymbolicLink(sourcePath))
                    sourceReads += path
                    val bytes = Files.readAllBytes(sourcePath)
                    assertTrue("Analyzed file changed before retrieval: $path", bytes.contentEquals(sourceBytes.getValue(path)))
                    JSONObject().put("path", path).put("type", "file").put("size", bytes.size)
                        .put("sha", gitBlob(bytes)).put("encoding", "base64")
                        .put("content", Base64.getEncoder().encodeToString(bytes))
                }
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            return RepositoryIntelligence.GetResponse(200, bytes.size.toLong(), url, ByteArrayInputStream(bytes)) { closedResponses++ }
        }
    }

    private fun snapshot(root: Path): Map<String, ByteArray> {
        val result = sortedMapOf<String, ByteArray>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != root && (directory.fileName.toString().startsWith('.') ||
                        directory.fileName.toString() in setOf("build", "out", "node_modules"))) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                val path = root.relativize(file).toString().replace('\\', '/')
                if (attributes.isRegularFile && !Files.isSymbolicLink(file) &&
                    Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                    attributes.size() <= RepositoryIntelligence.MAX_SOURCE_BYTES && RepositoryIntelligence.allowedSource(path)) {
                    result[path] = Files.readAllBytes(file)
                }
                return FileVisitResult.CONTINUE
            }
        })
        return result
    }

    companion object {
        private fun gitBlob(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
            return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
