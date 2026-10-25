package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class SelfDevelopmentTest {
    private val a = "developer/src/main/java/com/jarvis/developer/ScreenRows.kt"
    private val b = "developer/src/main/java/com/jarvis/developer/ScreenState.kt"
    private val sha = "1".repeat(40)
    private val blob = "2".repeat(40)

    private fun file(path: String = a, dependencies: List<String> = listOf(b)) = RepositoryAnalysis.PlannedFile(
        RepositoryAnalysis.SourceFile(path, blob, 100), listOf("SOURCE_REASON_DO_NOT_COPY"),
        "SOURCE_CHANGE_DO_NOT_COPY", "SOURCE_IMPACT_DO_NOT_COPY",
        listOf(RepositoryAnalysis.Symbol("renderRows", "fun", 8)),
        listOf(RepositoryAnalysis.Dependency("SOURCE_REFERENCE_DO_NOT_COPY", "import", dependencies, "resolved")),
        listOf(RepositoryAnalysis.Citation(path, blob, sha, 7, 9, "SOURCE_INSTRUCTIONS_DO_NOT_COPY")))

    private fun analysis(files: List<RepositoryAnalysis.PlannedFile> = listOf(file(), file(b, emptyList())),
        goal: String = "Improve row scrolling while retaining current state") =
        RepositoryAnalysis.AnalysisPlan(goal, sha, files, listOf("SOURCE_LIMITATION_DO_NOT_COPY"), files.map { it.path }, files.size)

    private fun proposal() = SelfDevelopment.propose("self-screen", analysis(), listOf(b, a))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected source/scope validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun proposesDeterministicAuditedProductionScopeFromActualAnalysisMetadata() {
        val plan = proposal()
        assertEquals(listOf(a, b), plan.scope)
        assertEquals(sha, plan.sourceCommitSha)
        assertEquals("feature/jarvis-developer", plan.sourceBranch)
        assertEquals(analysis().analysisId, plan.analysisId)
        assertEquals(listOf("renderRows:8"), plan.suggestions.first().symbols)
        assertEquals(listOf(b), plan.suggestions.first().resolvedDependencies)
        assertEquals(blob, plan.suggestions.first().blobSha)
        assertEquals(plan.fingerprint, SelfDevelopment.propose("self-screen", analysis().copy(files = analysis().files.reversed()), listOf(a, b)).fingerprint)
        assertTrue(plan.task.contains("fresh reviewable diff"))
        assertTrue(SelfDevelopment.render(plan).contains("not a verified defect"))
        SelfDevelopment.validate(plan)
    }

    @Test fun doesNotCopyUntrustedSourceExcerptReasonsOrProposedInstructions() {
        val text = SelfDevelopment.render(proposal())
        listOf("SOURCE_REASON_DO_NOT_COPY", "SOURCE_CHANGE_DO_NOT_COPY", "SOURCE_IMPACT_DO_NOT_COPY",
            "SOURCE_REFERENCE_DO_NOT_COPY", "SOURCE_INSTRUCTIONS_DO_NOT_COPY", "SOURCE_LIMITATION_DO_NOT_COPY").forEach {
            assertFalse(text.contains(it))
        }
        assertTrue(text.contains("Lexical references are estimates"))
    }

    @Test fun requiresOneToEightUniqueExplicitExistingSourcePaths() {
        rejects { SelfDevelopment.propose("self", analysis(), emptyList()) }
        rejects { SelfDevelopment.propose("self", analysis(), listOf(a, a)) }
        rejects { SelfDevelopment.propose("self", analysis(), (1..9).map { a.replace("ScreenRows", "Screen$it") }) }
        rejects { SelfDevelopment.propose("self", analysis(), listOf(a.replace("ScreenRows", "Absent"))) }
        rejects { SelfDevelopment.propose("self", analysis().copy(inspectedPaths = listOf(b)), listOf(a)) }
    }

    @Test fun excludesNormalBetaExistingTestsBuildAndCriticalControlsWithoutWeakeningPatchPolicy() {
        val denied = listOf("app/src/main/java/com/assistant/Screen.kt", "beta/src/main/java/com/assistant/Screen.kt",
            "developer/src/test/java/com/jarvis/developer/ScreenRowsTest.kt", "developer/build.gradle",
            "developer/src/main/java/com/jarvis/developer/PatchEngine.kt",
            "developer/src/main/java/com/jarvis/developer/MainActivity.kt",
            "developer/src/main/java/com/jarvis/developer/ReleaseState.kt", "../$a", a.replace("ScreenRows", "ApiKey"))
        denied.forEach { path ->
            assertFalse(path, SelfDevelopment.allowedPath(path))
            rejects { SelfDevelopment.propose("self", analysis(listOf(file(path))), listOf(path)) }
        }
        assertTrue(PatchEngine.allowedPath("developer/build.gradle"))
        assertTrue(PatchEngine.allowedPath("developer/src/test/java/com/jarvis/developer/ScreenRowsTest.kt"))
        assertTrue(SelfDevelopment.allowedPath("developer/src/main/res/layout/screen_rows.xml"))
        assertTrue(SelfDevelopment.allowedCandidatePath("developer/src/test/java/com/jarvis/developer/ScreenRowsTest.kt"))
        assertFalse(SelfDevelopment.allowedCandidatePath("developer/build.gradle"))
        assertFalse(SelfDevelopment.allowedCandidatePath("developer/src/main/java/com/jarvis/developer/CandidateBadge.kt"))
    }

    @Test fun requiresExactCommitBlobAndCitationProvenance() {
        val original = file()
        rejects { SelfDevelopment.propose("self", analysis().copy(commitSha = "a".repeat(39)), listOf(a)) }
        rejects { SelfDevelopment.propose("self", analysis().copy(commitSha = "A".repeat(40)), listOf(a)) }
        rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(source = original.source.copy(sha = "0")))), listOf(a)) }
        listOf(original.citations.single().copy(path = b), original.citations.single().copy(commitSha = "3".repeat(40)),
            original.citations.single().copy(blobSha = "4".repeat(40)), original.citations.single().copy(startLine = 0),
            original.citations.single().copy(endLine = 100)).forEach { citation ->
            rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(citations = listOf(citation)))), listOf(a)) }
        }
        rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(citations = emptyList()))), listOf(a)) }
    }

    @Test fun boundsTaskIdentifierGoalAndMetadataBeforePreparingPatchContext() {
        listOf("", "bad/id", "x".repeat(49)).forEach { id -> rejects { SelfDevelopment.propose(id, analysis(), listOf(a)) } }
        listOf("", " ", "x".repeat(4_001), "bad\u0000goal", "bad\u007fgoal").forEach { goal ->
            rejects { SelfDevelopment.propose("self", analysis(goal = goal), listOf(a)) }
        }
        val original = file()
        rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(symbols = (1..13).map {
            RepositoryAnalysis.Symbol("item$it", "fun", it) }))), listOf(a)) }
        rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(source = original.source.copy(size = 200_001)))), listOf(a)) }
        rejects { SelfDevelopment.propose("self", analysis(listOf(original, original)), listOf(a)) }
    }

    @Test fun rejectsRecognizedCredentialsRatherThanCopyingThemIntoTasksOrAudit() {
        val secrets = listOf("ghp_" + "a".repeat(24), "sk-" + "b".repeat(24), "password=plainvalue",
            "api_key = \"plainvalue\"", "Authorization: Basic YWJjOmRlZg==", "Bearer abc123",
            "https://user:password@example.test", "-----BEGIN PRIVATE KEY-----")
        secrets.forEach { secret -> rejects { SelfDevelopment.propose("self", analysis(goal = "Improve rows $secret"), listOf(a)) } }
        val original = file()
        rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(symbols = listOf(
            RepositoryAnalysis.Symbol("ghp_" + "c".repeat(24), "val", 8))))), listOf(a)) }
    }

    @Test fun validatesReadOnlyDependencyReferencesAndDeclarationNames() {
        listOf("../Secrets.kt", "/absolute/State.kt", "a/%2e%2e/State.kt", "developer/secrets/State.kt", "a\\State.kt").forEach { path ->
            rejects { SelfDevelopment.propose("self", analysis(listOf(file(dependencies = listOf(path)))), listOf(a)) }
        }
        val original = file()
        listOf("not a declaration", "execute()", "x".repeat(121)).forEach { name ->
            rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(symbols = listOf(
                RepositoryAnalysis.Symbol(name, "fun", 1))))), listOf(a)) }
        }
        rejects { SelfDevelopment.propose("self", analysis(listOf(original.copy(symbols = listOf(
            RepositoryAnalysis.Symbol("render", "fun", -1))))), listOf(a)) }
    }

    @Test fun copiesMutableAnalysisMetadataAndExplicitScopeBeforeHandoff() {
        val symbols = mutableListOf(RepositoryAnalysis.Symbol("renderRows", "fun", 8))
        val paths = mutableListOf(a, b)
        val result = SelfDevelopment.propose("self", analysis(listOf(file().copy(symbols = symbols), file(b))), paths)
        val prior = result.fingerprint
        symbols.clear(); paths.clear()
        SelfDevelopment.validate(result)
        assertEquals(prior, result.fingerprint)
        assertEquals(2, result.scope.size)
        assertEquals(listOf("renderRows:8"), result.suggestions.first().symbols)
    }

    @Test fun revalidationRejectsMutatedPublishedScopeInsteadOfReusingApprovalContext() {
        val result = proposal()
        @Suppress("UNCHECKED_CAST")
        val mutable = result.scope as MutableList<String>
        mutable[0] = "developer/src/main/java/com/jarvis/developer/OtherRows.kt"
        rejects { SelfDevelopment.validate(result) }
        rejects { SelfDevelopment.render(result) }
    }

    @Test fun proposalFingerprintBindsGoalSourceScopeAndObservedDeclarations() {
        val baseline = proposal()
        val changes = listOf(SelfDevelopment.propose("different", analysis(), listOf(a, b)),
            SelfDevelopment.propose("self-screen", analysis(goal = "Improve row selection state"), listOf(a, b)),
            SelfDevelopment.propose("self-screen", analysis(), listOf(a)),
            SelfDevelopment.propose("self-screen", analysis(listOf(file().copy(symbols = listOf(
                RepositoryAnalysis.Symbol("renderRows", "fun", 9))), file(b))), listOf(a, b)))
        changes.forEach { assertNotEquals(baseline.fingerprint, it.fingerprint) }
    }

    @Test fun actualBlobVerifiedAnalysisProducesProposalWithoutChangingSourceBytes() {
        val directory = Files.createTempDirectory("jarvis-self-readonly-")
        val source = directory.resolve("ScreenRows.kt")
        val content = "package com.jarvis.developer\nobject ScreenRows { fun scrollRows() = 2 }\n"
        try {
            Files.write(source, content.toByteArray(Charsets.UTF_8))
            val before = Files.readAllBytes(source)
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${before.size}\u0000".toByteArray(Charsets.UTF_8))
            val gitBlob = digest.digest(before).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(a, gitBlob, before.size.toLong())), false, sha)
            val analysis = RepositoryAnalysis.analyze("Improve scrollRows behavior", inventory) { path ->
                assertEquals(a, path)
                String(Files.readAllBytes(source), Charsets.UTF_8)
            }
            val result = SelfDevelopment.propose("self-fixture", analysis, listOf(a))
            assertEquals(gitBlob, result.suggestions.single().blobSha)
            assertTrue(result.suggestions.single().symbols.any { it.startsWith("scrollRows:") })
            assertArrayEquals(before, Files.readAllBytes(source))
            assertEquals(listOf("ScreenRows.kt"), Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toArray().toList() })
        } finally {
            Files.deleteIfExists(source); Files.deleteIfExists(directory)
        }
    }
}
