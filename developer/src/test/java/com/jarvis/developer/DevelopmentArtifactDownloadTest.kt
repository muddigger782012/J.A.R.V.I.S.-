package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DevelopmentArtifactDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val commit = "a".repeat(40)
    private val branch = "feature/jarvis-developer-task/artifact-test-aaaaaaaaaaaa"
    private val source = DevelopmentArtifact.Source("job_" + "1".repeat(64), UUID.randomUUID().toString(), commit, branch,
        PatchEngine.Target.DEVELOPER, 42, 1, "b".repeat(64))
    private val api = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-"
    private val storage = "https://productionresultssa1.blob.core.windows.net/fixture/archive?sig=explicit-test-only"
    private val bytes = "Explicit unsigned payload; the inspector in this transport fixture is simulated.".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("sourceCommit", commit).put("runId", 42)
        .put("runAttempt", 1).put("target", "DEVELOPER").put("signing", "debug")
        .put("apks", JSONArray().put(JSONObject().put("file", "developer-debug.apk").put("bytes", bytes.size)
            .put("sha256", hash(bytes)).put("packageName", "com.jarvis.developer").put("versionCode", 17)
            .put("versionName", "0.1.17").put("signerSha256", "c".repeat(64)).put("abis", JSONArray())))
    private fun archive(manifest: JSONObject, extra: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, data) in listOf("development-artifact.json" to manifest.toString().toByteArray(), manifest.getJSONArray("apks").getJSONObject(0).getString("file") to bytes) +
                if (extra) listOf("../escape.apk" to bytes) else emptyList()) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private inner class Remote(val zip: ByteArray, val personal: Boolean = false) : BuildFeedback.Transport {
        val calls = mutableListOf<Pair<String, String?>>()
        var wrongSource = false
        var wrongBranch = false
        var expired = false
        var wrongDigest = false
        var redirect = storage
        var actualAttempt = 1
        var truncated = false
        var denied = false
        override fun get(url: String, token: String?, timeoutMillis: Long): BuildFeedback.Response {
            calls += url to token
            if (denied) return BuildFeedback.Response(403, 0, url, ByteArrayInputStream(byteArrayOf()))
            val body = when (url) {
                "$api/actions/runs/42" -> JSONObject().put("id", 42).put("head_sha", if (wrongSource) "d".repeat(40) else commit)
                    .put("head_branch", if (wrongBranch) "main" else if (personal) "feature/jarvis-personal-repair/artifact-test-aaaaaaaaaaaa" else branch).put("status", "completed").put("conclusion", "success")
                    .put("event", "push").put("run_attempt", actualAttempt).put("path", if (personal) ".github/workflows/build-jarvis.yml" else ".github/workflows/build-jarvis-developer.yml")
                    .put("repository", JSONObject().put("full_name", "muddigger782012/J.A.R.V.I.S.-"))
                    .put("head_repository", JSONObject().put("full_name", "muddigger782012/J.A.R.V.I.S.-")).toString().toByteArray()
                "$api/actions/runs/42/artifacts?per_page=30" -> JSONObject().put("total_count", 1)
                    .put("artifacts", JSONArray().put(JSONObject().put("id", 99).put("name", if (personal) "Jarvis-personal-job-APK" else "Jarvis-Developer-debug-APK")
                        .put("expired", expired).put("size_in_bytes", zip.size).put("digest", "sha256:" + if (wrongDigest) "0".repeat(64) else hash(zip))
                        .put("workflow_run", JSONObject().put("id", 42).put("head_sha", commit)))).toString().toByteArray()
                "$api/actions/artifacts/99/zip" -> return BuildFeedback.Response(302, 0, url, ByteArrayInputStream(byteArrayOf()), redirect)
                storage -> if (truncated) zip.copyOf(zip.size - 1) else zip
                else -> error("Unexpected request")
            }
            return BuildFeedback.Response(200, body.size.toLong(), url, ByteArrayInputStream(body))
        }
    }
    @Test fun authenticatedApiReadDropsCredentialsAtStorageAndBindsTheActualPayloadToSource() {
        val remote = Remote(archive(manifest())); val files = DevelopmentArtifactFiles(temp.root); var inspected = false
        val reader = DevelopmentArtifactDownload(remote, files, { file, record ->
            assertArrayEquals(bytes, file.readBytes()); assertEquals(hash(bytes), record.apkSha256); inspected = true
        })
        val result = reader.read(source, 42, "explicit-test-token", listOf("x86_64"))
        assertTrue(inspected); assertEquals(commit, result.commit); assertEquals(99, result.artifact)
        assertArrayEquals(bytes, files.checked(result).readBytes())
        assertTrue(remote.calls.filter { it.first.startsWith(api) }.all { it.second == "explicit-test-token" })
        assertNull(remote.calls.single { it.first == storage }.second)
        assertFalse(files.root.listFiles()!!.any { it.extension == "part" })
    }
    @Test fun personalJobUsesTheProvenanceBundleWhileTheUpdaterKeepsItsOwnApkOnlyArtifact() {
        val m = manifest().put("target", "PERSONAL").put("signing", "production")
        m.getJSONArray("apks").getJSONObject(0).put("file", "app-x86_64-release.apk").put("packageName", "com.assistant.core")
            .put("versionCode", 600).put("versionName", "3.6.600")
        val personalSource = source.copy(target = PatchEngine.Target.PERSONAL,
            branch = "feature/jarvis-personal-repair/artifact-test-aaaaaaaaaaaa")
        val result = DevelopmentArtifactDownload(Remote(archive(m), personal = true), DevelopmentArtifactFiles(temp.root), { _, record ->
            assertEquals("com.assistant.core", record.packageName)
        }).read(personalSource, 42, "explicit-test-token", listOf("x86_64"))
        assertEquals("production", result.signing)
        assertEquals(600L, result.versionCode)
    }
    @Test fun changedSourceBranchExpiredDigestAndTruncatedPayloadNeverReachTheInspector() {
        for (problem in listOf("source", "branch", "expired", "digest", "truncated")) {
            val remote = Remote(archive(manifest())).apply {
                wrongSource = problem == "source"; wrongBranch = problem == "branch"; expired = problem == "expired"
                wrongDigest = problem == "digest"; truncated = problem == "truncated"
            }
            var called = false
            assertThrows(DevelopmentArtifact.Failure::class.java) {
                DevelopmentArtifactDownload(remote, DevelopmentArtifactFiles(temp.newFolder()), { _, _ -> called = true })
                    .read(source, 42, "", listOf("x86_64"))
            }
            assertFalse(problem, called)
        }
    }
    @Test fun manifestFromEarlierAttemptWrongProductOrUnexpectedZipPathCannotPass() {
        for (problem in listOf("attempt", "product", "zipPath", "package", "hash", "fractionalRun")) {
            val m = manifest()
            if (problem == "attempt") m.put("runAttempt", 2)
            if (problem == "product") m.put("target", "PERSONAL")
            if (problem == "package") m.getJSONArray("apks").getJSONObject(0).put("packageName", "com.assistant.core.beta")
            if (problem == "hash") m.getJSONArray("apks").getJSONObject(0).put("sha256", "0".repeat(64))
            if (problem == "fractionalRun") m.put("runId", 42.5)
            var called = false
            assertThrows(DevelopmentArtifact.Failure::class.java) {
                DevelopmentArtifactDownload(Remote(archive(m, problem == "zipPath")), DevelopmentArtifactFiles(temp.newFolder()), { _, _ -> called = true })
                    .read(source, 42, "", listOf("x86_64"))
            }
            assertFalse(problem, called)
        }
    }
    @Test fun unsupportedRedirectNeverReceivesTheCredentialOrARequest() {
        val remote = Remote(archive(manifest())).apply { redirect = "https://attacker.invalid/file?sig=fake" }
        assertThrows(DevelopmentArtifact.Failure::class.java) {
            DevelopmentArtifactDownload(remote, DevelopmentArtifactFiles(temp.root), { _, _ -> fail() }).read(source, 42, "explicit-test-token", listOf("x86_64"))
        }
        assertEquals(3, remote.calls.size)
    }
    @Test fun cancellationSizeAndCacheSymlinksFailWithoutAnInstallSideEffect() {
        val signal = PatchPreviewCancellation(); signal.cancel()
        val remote = Remote(archive(manifest()))
        assertThrows(DevelopmentArtifact.Failure::class.java) {
            DevelopmentArtifactDownload(remote, DevelopmentArtifactFiles(temp.root), { _, _ -> fail() }, signal)
                .read(source, 42, "", listOf("x86_64"))
        }
        assertTrue(remote.calls.isEmpty())
        val root = temp.newFolder(); val destination = temp.newFolder()
        java.nio.file.Files.createSymbolicLink(java.io.File(root, "development-artifacts").toPath(), destination.toPath())
        assertThrows(IllegalArgumentException::class.java) { DevelopmentArtifactFiles(root).temporary() }
        assertTrue(destination.listFiles()!!.isEmpty())
    }
    @Test fun signatureFailureIsPreservedAndLeavesNoVerifiedCacheEntry() {
        val folder = temp.newFolder()
        val failure = assertThrows(DevelopmentArtifact.Failure::class.java) {
            DevelopmentArtifactDownload(Remote(archive(manifest())), DevelopmentArtifactFiles(folder), { _, _ ->
                throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.SIGNATURE)
            }).read(source, 42, "", listOf("x86_64"))
        }
        assertEquals(DevelopmentArtifact.Problem.SIGNATURE, failure.problem)
        assertFalse(DevelopmentArtifactFiles(folder).root.listFiles()!!.any { it.extension == "apk" })
    }
    @Test fun artifactAccessDenialIsNotMisreportedAsAnUnsupportedSourceOrSuccessfulDownload() {
        val remote = Remote(archive(manifest())).apply { denied = true }
        val failure = assertThrows(DevelopmentArtifact.Failure::class.java) {
            DevelopmentArtifactDownload(remote, DevelopmentArtifactFiles(temp.root), { _, _ -> fail() })
                .read(source, 42, "explicit-test-token", listOf("x86_64"))
        }
        assertEquals(DevelopmentArtifact.Problem.ACCESS, failure.problem)
        assertTrue(DevelopmentArtifact.explain(failure).contains("Actions read access"))
        assertEquals(1, remote.calls.size)
    }
}
