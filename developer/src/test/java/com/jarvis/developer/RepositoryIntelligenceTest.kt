package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** Network-free contract tests: the fixture has no write operation and keeps every requested URL. */
class RepositoryIntelligenceTest {

    @Test fun personalMainRequiresAnExplicitProductSelectionAndStillPinsVerifiedBlobBytes() {
        val personalPath = "app/src/main/java/com/assistant/core/services/AssistantPersonality.kt"
        val bytes = "package com.assistant.core.services\nobject AssistantPersonality\n".toByteArray()
        val fake = fixture(listOf(node(personalPath, bytes)))
        fake.json("$api/git/ref/heads/main", JSONObject().put("ref", "refs/heads/main")
            .put("object", JSONObject().put("type", "commit").put("sha", commit)))
        fake.json(sourceUrl(personalPath), blob(personalPath, bytes))
        fails { RepositoryIntelligence.Reader(fake).inventory("", "main") }
        assertTrue(fake.requests.isEmpty())
        val reader = RepositoryIntelligence.Reader(fake)
        RepositoryIntelligence.requireApiUrl("$api/git/ref/heads/main", PatchEngine.Target.PERSONAL)
        fails { RepositoryIntelligence.requireApiUrl(refUrl(), PatchEngine.Target.PERSONAL) }
        val inventory = reader.inventory("", "main", PatchEngine.Target.PERSONAL)
        assertEquals(commit, inventory.commitSha)
        assertEquals(String(bytes), reader.readSource("", inventory, personalPath))
        assertEquals(4, fake.requests.size)
        assertTrue(fake.requests.all { it.startsWith(api + "/") })
    }
    private val api = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-"
    private val commit = "a".repeat(40)
    private val treeSha = "b".repeat(40)
    private val path = "developer/src/main/java/com/jarvis/developer/MainActivity.kt"
    private val source = "package com.jarvis.developer\nclass MainActivity\n".toByteArray()

    private class Fixture : RepositoryIntelligence.GetTransport {
        val requests = ArrayList<String>()
        val replies = LinkedHashMap<String, () -> RepositoryIntelligence.GetResponse>()
        var closed = 0
        override fun get(url: String, token: String): RepositoryIntelligence.GetResponse {
            requests.add(url)
            return (replies[url] ?: error("Unexpected GET: $url"))()
        }
        fun json(url: String, body: JSONObject) {
            val bytes = body.toString().toByteArray()
            reply(url, bytes)
        }
        fun reply(url: String, bytes: ByteArray, length: Long = bytes.size.toLong(), status: Int = 200,
                  finalUrl: String = url) {
            replies[url] = { RepositoryIntelligence.GetResponse(status, length, finalUrl, ByteArrayInputStream(bytes)) { closed++ } }
        }
    }

    private fun refUrl() = "$api/git/ref/heads/feature%2Fjarvis-developer"
    private fun commitUrl() = "$api/git/commits/$commit"
    private fun treeUrl() = "$api/git/trees/$treeSha?recursive=1"
    private fun sourceUrl(selectedPath: String = path) = "$api/contents/$selectedPath?ref=$commit"
    private fun blobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun node(selectedPath: String = path, bytes: ByteArray = source, mode: String = "100644") = JSONObject()
        .put("path", selectedPath).put("type", "blob").put("mode", mode)
        .put("size", bytes.size).put("sha", blobSha(bytes))
    private fun tree(nodes: List<JSONObject> = listOf(node()), truncated: Boolean = false) = JSONObject()
        .put("sha", treeSha).put("truncated", truncated).put("tree", JSONArray(nodes))
    private fun blob(selectedPath: String = path, bytes: ByteArray = source) = JSONObject()
        .put("path", selectedPath).put("type", "file").put("size", bytes.size)
        .put("sha", blobSha(bytes)).put("encoding", "base64")
        .put("content", Base64.getEncoder().encodeToString(bytes))
    private fun fixture(nodes: List<JSONObject> = listOf(node())) = Fixture().apply {
        json(refUrl(), JSONObject().put("ref", "refs/heads/feature/jarvis-developer")
            .put("object", JSONObject().put("type", "commit").put("sha", commit)))
        json(commitUrl(), JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", treeSha)))
        json(treeUrl(), tree(nodes))
        json(sourceUrl(), blob())
    }
    private fun fails(message: String? = null, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (failure: RuntimeException) {
            if (message != null) assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(message))
        }
    }

    @Test fun inventoryPinsCommitTreeAndVerifiedSourceWithoutFollowingMovedBranch() {
        val fake = fixture()
        val reader = RepositoryIntelligence.Reader(fake)
        val inventory = reader.inventory("test-only-token")
        // Simulate branch movement after discovery. Source must still use the original immutable SHA.
        fake.json(refUrl(), JSONObject().put("ref", "refs/heads/feature/jarvis-developer")
            .put("object", JSONObject().put("type", "commit").put("sha", "c".repeat(40))))
        assertEquals(commit, inventory.commitSha)
        assertFalse(inventory.truncated)
        assertEquals(String(source), reader.readSource("test-only-token", inventory, path))
        assertEquals(listOf(refUrl(), commitUrl(), treeUrl(), sourceUrl()), fake.requests)
        assertEquals(4, fake.closed)
        assertTrue(fake.requests.none { it.contains("test-only-token") })
    }

    @Test fun inventoryOnlyOffersApprovedSmallRegularSourceFiles() {
        val safePaths = listOf(path, "app/src/main/java/com/assistant/core/MainActivity.kt",
            "developer/src/main/kotlin/com/jarvis/developer/Feature.kt",
            "app/src/main/kotlin/com/assistant/core/Feature.kt",
            "app/src/main/res/layout/activity_appearances.xml", "app/src/main/AndroidManifest.xml",
            "developer/DEVELOPMENT_ROADMAP.md", "docs/UI_THEMING.md", "developer/build.gradle")
        val denied = listOf("developer/local.properties", "developer/src/main/res/values/secrets.xml",
            "app/google-services.json", ".github/workflows/release.yml", "gradle.properties",
            "developer/src/main/res/drawable/icon.png", "other/src/main/Unsafe.kt")
        val fake = fixture(safePaths.map { node(it) } + denied.map { node(it) } +
            listOf(node("developer/src/main/java/Link.kt", mode = "120000"),
                node("developer/src/main/java/Large.kt").put("size", 200_001),
                JSONObject().put("path", ".github").put("type", "tree").put("mode", "040000").put("sha", treeSha)))
        val inventory = RepositoryIntelligence.Reader(fake).inventory("")
        assertEquals(safePaths.sorted(), inventory.files.map { it.path })
    }

    @Test fun deniedTraversalAndCredentialPathsFailBeforeNetworkAccessEvenInForgedInventory() {
        val fake = Fixture()
        val reader = RepositoryIntelligence.Reader(fake)
        for (candidate in listOf("../developer/Unsafe.kt", "developer/../app/build.gradle",
            "developer//build.gradle", "developer/.env", "developer/local.properties",
            "developer/src/main/res/values/credentials.xml", "gradle.properties",
            "docs/credentials/setup.md", "docs/SIGNING/setup.md", "docs/keystore_material/howto.md",
            "developer/src/main/java/com/jarvis/developer/credentials/Config.kt",
            "developer/src/main/java/com/jarvis/developer/Secrets/Config.kt",
            "https://evil.example/source.kt", "developer/src/main/java/A%2fB.kt")) {
            val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(candidate, blobSha(source), source.size.toLong())), false, commit)
            fails { reader.readSource("", inventory, candidate) }
        }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun malformedCommitOrTreeIdentityIsRejected() {
        for (invalid in listOf("main", "a".repeat(39), "a".repeat(41), "g".repeat(40), "a".repeat(40) + "?")) {
            val fake = fixture()
            fake.json(refUrl(), JSONObject().put("ref", "refs/heads/feature/jarvis-developer")
                .put("object", JSONObject().put("type", "commit").put("sha", invalid)))
            fails("Git identity") { RepositoryIntelligence.Reader(fake).inventory("") }
            assertEquals(1, fake.requests.size)
        }
        val fake = fixture()
        fake.json(commitUrl(), JSONObject().put("sha", "c".repeat(40)).put("tree", JSONObject().put("sha", treeSha)))
        fails("Commit identity") { RepositoryIntelligence.Reader(fake).inventory("") }
        fake.json(commitUrl(), JSONObject().put("sha", commit).put("tree", JSONObject().put("sha", treeSha)))
        fake.json(treeUrl(), tree().put("sha", "c".repeat(40)))
        fails("Tree identity") { RepositoryIntelligence.Reader(fake).inventory("") }
    }

    @Test fun truncatedDuplicateAndMalformedTreesAreRejected() {
        val fake = fixture()
        val reader = RepositoryIntelligence.Reader(fake)
        fake.json(treeUrl(), tree(truncated = true))
        fails("truncated") { reader.inventory("") }
        fake.json(treeUrl(), tree(listOf(node(), node())))
        fails("duplicate") { reader.inventory("") }
        fake.json(treeUrl(), tree(listOf(node().put("sha", "invalid"))))
        fails("Git identity") { reader.inventory("") }
        fake.json(treeUrl(), tree(listOf(node().put("size", -1))))
        fails("size") { reader.inventory("") }
        fake.json(treeUrl(), tree(listOf(node().put("mode", "040000"))))
        fails("blob mode") { reader.inventory("") }
        fake.json(treeUrl(), JSONObject().put("sha", treeSha).put("tree", JSONArray()))
        fails { reader.inventory("") }
    }

    @Test fun sourceMustMatchBothAdvertisedBlobIdentityAndActualGitDigest() {
        val fake = fixture()
        val reader = RepositoryIntelligence.Reader(fake)
        val inventory = reader.inventory("")
        fake.json(sourceUrl(), blob().put("sha", "c".repeat(40)))
        fails("blob identity") { reader.readSource("", inventory, path) }
        // A malicious server retaining the claimed identity but changing bytes must also fail.
        val changed = source.copyOf().apply { this[0] = 'P'.code.toByte() }
        fake.json(sourceUrl(), blob(bytes = changed).put("sha", blobSha(source)))
        fails("pinned Git blob") { reader.readSource("", inventory, path) }
        fake.json(sourceUrl(), blob().put("size", source.size + 1))
        fails("size") { reader.readSource("", inventory, path) }
        fake.json(sourceUrl(), blob().put("type", "symlink"))
        fails("source response") { reader.readSource("", inventory, path) }
    }

    @Test fun oversizedKnownLengthClosesResponseWithoutReadingBody() {
        val fake = fixture()
        var read = false
        fake.replies[refUrl()] = {
            RepositoryIntelligence.GetResponse(200, 64_001, refUrl(), object : InputStream() {
                override fun read(): Int { read = true; error("Body must not be read") }
            }) { fake.closed++ }
        }
        fails("read limit") { RepositoryIntelligence.Reader(fake).inventory("") }
        assertFalse(read)
        assertEquals(1, fake.closed)
    }

    @Test fun unknownLengthStreamStopsAtBudgetPlusOneAndCloses() {
        val fake = fixture()
        var supplied = 0
        fake.replies[refUrl()] = {
            RepositoryIntelligence.GetResponse(200, -1, refUrl(), object : InputStream() {
                override fun read(): Int { supplied++; return 'x'.code }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    bytes.fill('x'.code.toByte(), offset, offset + length)
                    supplied += length
                    return length
                }
            }) { fake.closed++ }
        }
        fails("read limit") { RepositoryIntelligence.Reader(fake).inventory("") }
        assertEquals(64_001, supplied)
        assertEquals(1, fake.closed)
    }

    @Test fun sourceBodyBudgetIsAppliedBeforeJsonOrBase64Allocation() {
        val fake = fixture()
        val reader = RepositoryIntelligence.Reader(fake)
        val inventory = reader.inventory("")
        fake.reply(sourceUrl(), byteArrayOf(), length = 350_001)
        fails("read limit") { reader.readSource("", inventory, path) }
        val before = fake.requests.size
        val hugeInventory = inventory.copy(files = listOf(inventory.files.single().copy(size = 200_001)))
        fails("read limit") { reader.readSource("", hugeInventory, path) }
        assertEquals(before, fake.requests.size)
    }

    @Test fun redirectErrorsAndForeignOriginsNeverReturnSource() {
        val fake = fixture()
        fake.reply(refUrl(), byteArrayOf(), status = 302)
        fails("HTTP 302") { RepositoryIntelligence.Reader(fake).inventory("") }
        fake.reply(refUrl(), byteArrayOf(), finalUrl = "https://evil.example/repository")
        fails("origin") { RepositoryIntelligence.Reader(fake).inventory("") }
        for (url in listOf("http://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-/git/ref/heads/main",
            "https://api.github.com.evil.example/repos/muddigger782012/J.A.R.V.I.S.-/git/ref/heads/main",
            "https://token@api.github.com/repos/muddigger782012/J.A.R.V.I.S.-/git/ref/heads/main",
            "https://api.github.com:443/repos/muddigger782012/J.A.R.V.I.S.-/git/ref/heads/main",
            "https://api.github.com/repos/someone/else/contents/file")) {
            fails("origin") { RepositoryIntelligence.requireApiUrl(url) }
        }
        assertEquals(2, fake.closed)
    }

    @Test fun invalidSourceEncodingAndUtf8AreRejected() {
        val fake = fixture()
        val reader = RepositoryIntelligence.Reader(fake)
        val inventory = reader.inventory("")
        fake.json(sourceUrl(), blob().put("content", "!".repeat(((source.size + 2) / 3) * 4)))
        fails("encoding") { reader.readSource("", inventory, path) }
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val badPath = "developer/src/main/java/Invalid.kt"
        val badInventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(badPath, blobSha(invalidUtf8), 2)), false, commit)
        fake.json(sourceUrl(badPath), blob(badPath, invalidUtf8))
        try { reader.readSource("", badInventory, badPath); fail("Malformed UTF-8 must fail") }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test fun binaryContentIsRejectedEvenWhenFilenameAndGitDigestAreValid() {
        val fake = fixture()
        val binary = byteArrayOf(0, 1, 2)
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, blobSha(binary), 3)), false, commit)
        fake.json(sourceUrl(), blob(bytes = binary))
        fails("Binary") { RepositoryIntelligence.Reader(fake).readSource("", inventory, path) }
    }

    @Test fun authenticationAndRateLimitFailuresStopTheWholeAnalysisSession() {
        for (status in listOf(401, 403, 429)) {
            val secondPath = "developer/src/main/java/com/jarvis/developer/OtherActivity.kt"
            val fake = fixture(listOf(node(), node(secondPath)))
            fake.reply(sourceUrl(), byteArrayOf(), status = status)
            fake.json(sourceUrl(secondPath), blob(secondPath))
            val reader = RepositoryIntelligence.Reader(fake)
            val (inventory, plan) = reader.analyzeSnapshot("", "MainActivity source")
            assertEquals(2, inventory.files.size)
            assertTrue(plan.files.isEmpty())
            assertTrue(plan.limitations.any { it.contains("stopped after HTTP $status") })
            assertEquals(4, fake.requests.size)
            fails("HTTP $status") { reader.readSource("", inventory, secondPath) }
            assertEquals("No transport call may follow a session-stopping response", 4, fake.requests.size)
            assertEquals(4, fake.closed)
        }
    }

    @Test fun requestCapStopsTransportCallsAndIsReportedInThePlan() {
        val paths = (0 until 70).map { "developer/src/main/java/com/jarvis/developer/MainActivity$it.kt" }
        val fake = fixture(paths.map { node(it) })
        paths.forEach { fake.json(sourceUrl(it), blob(it)) }
        val reader = RepositoryIntelligence.Reader(fake)
        val (inventory, plan) = reader.analyzeSnapshot("", "MainActivity source")
        assertEquals(RepositoryIntelligence.MAX_SESSION_REQUESTS, fake.requests.size)
        assertTrue(plan.files.isNotEmpty())
        assertTrue(plan.limitations.any { it.contains("64-request session limit") })
        fails("64-request session limit") { reader.readSource("", inventory, paths.last()) }
        assertEquals(RepositoryIntelligence.MAX_SESSION_REQUESTS, fake.requests.size)
        assertEquals(RepositoryIntelligence.MAX_SESSION_REQUESTS, fake.closed)
    }

    @Test fun elapsedTimeStopsFurtherReadsBeforeTheTransportIsCalled() {
        val fake = fixture()
        var clock = 0L
        val reader = RepositoryIntelligence.Reader(fake) { clock }
        val inventory = reader.inventory("")
        clock = RepositoryIntelligence.MAX_SESSION_MILLIS
        fails("3-minute session budget") { reader.readSource("", inventory, path) }
        fails("3-minute session budget") { reader.readSource("", inventory, path) }
        assertEquals(3, fake.requests.size)
    }

    @Test fun invalidTaskIsRejectedBeforeAnyNetworkRequest() {
        val fake = Fixture()
        fails { RepositoryIntelligence.Reader(fake).analyzeSnapshot("", " ") }
        fails { RepositoryIntelligence.Reader(fake).analyzeSnapshot("", "x".repeat(4_001)) }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun taskBranchInventoryPinsRepairSourceAndSubsequentReadsStayAtItsCommit() {
        val taskBranch = "feature/jarvis-developer-task/repair-012345abcdef"
        val taskRef = "$api/git/ref/heads/feature%2Fjarvis-developer-task%2Frepair-012345abcdef"
        val fake = fixture()
        fake.json(taskRef, JSONObject().put("ref", "refs/heads/$taskBranch")
            .put("object", JSONObject().put("type", "commit").put("sha", commit)))
        val reader = RepositoryIntelligence.Reader(fake)
        val inventory = reader.inventory("", taskBranch)
        fake.json(taskRef, JSONObject().put("ref", "refs/heads/$taskBranch")
            .put("object", JSONObject().put("type", "commit").put("sha", "c".repeat(40))))
        assertEquals(String(source), reader.readSource("", inventory, path))
        assertEquals(listOf(taskRef, commitUrl(), treeUrl(), sourceUrl()), fake.requests)
        assertEquals(4, fake.closed)
        fake.json(taskRef, JSONObject().put("ref", "refs/heads/$taskBranch")
            .put("object", JSONObject().put("type", "commit").put("sha", commit)))
        val (_, plan) = RepositoryIntelligence.Reader(fake).analyzeSnapshot("", "MainActivity source", taskBranch)
        assertEquals(commit, plan.commitSha)
        assertEquals(taskRef, fake.requests[4])
    }

    @Test fun branchScopeIsCheckedBeforeNetworkAndReturnedRefMustMatchRequestedTaskBranch() {
        val fake = Fixture()
        for (branch in listOf("main", "beta", "feature/jarvis-developer/task/a",
            "feature/jarvis-developer-task/a-ABCDEF123456", "feature/jarvis-developer-task/../a-012345abcdef",
            "feature/jarvis-developer?other=1", "feature%2Fjarvis-developer")) {
            fails("branch") { RepositoryIntelligence.Reader(fake).inventory("", branch) }
        }
        assertTrue(fake.requests.isEmpty())
        val taskBranch = "feature/jarvis-developer-task/repair-012345abcdef"
        val taskRef = "$api/git/ref/heads/feature%2Fjarvis-developer-task%2Frepair-012345abcdef"
        fake.json(taskRef, JSONObject().put("ref", "refs/heads/feature/jarvis-developer")
            .put("object", JSONObject().put("type", "commit").put("sha", commit)))
        fails("Unexpected repository reference") { RepositoryIntelligence.Reader(fake).inventory("", taskBranch) }
        assertEquals(1, fake.requests.size)
        assertEquals(1, fake.closed)
    }

    @Test fun analysisEndpointAllowlistRejectsWritesMovingContentsAndOtherBranches() {
        for (url in listOf("$api/git/refs", "$api/git/ref/heads/main", "$api/git/ref/heads/feature/jarvis-developer",
            "$api/releases", "$api/actions/runs/17", "$api/contents/$path?ref=feature%2Fjarvis-developer",
            "$api/contents/developer/local.properties?ref=$commit", "$api/git/trees/$treeSha?recursive=1&extra=1")) {
            fails("scoped read-only") { RepositoryIntelligence.requireApiUrl(url) }
        }
        for (url in listOf(refUrl(), commitUrl(), treeUrl(), sourceUrl())) RepositoryIntelligence.requireApiUrl(url)
    }
}
