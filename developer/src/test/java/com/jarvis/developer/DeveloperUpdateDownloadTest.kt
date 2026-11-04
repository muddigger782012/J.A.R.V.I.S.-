package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Transport/inspection fixtures are deliberately unsigned; real cryptography also runs in native acceptance. */
class DeveloperUpdateDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "Explicit unsigned transport fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val storage = "https://release-assets.githubusercontent.com/github-production-release-asset/1410602023/11111111-2222-3333-4444-555555555555?sig=explicit-test-only"
    private fun metadata() = JSONObject().put("id", 7).put("tag_name", "developer-latest").put("draft", false)
        .put("prerelease", true).put("target_commitish", "a".repeat(40)).put("name", "Jarvis Developer 0.1.185")
        .put("assets", JSONArray().put(JSONObject().put("id", 9).put("name", "Jarvis-Developer.apk")
            .put("size", bytes.size).put("digest", "sha256:$hash").put("browser_download_url", DeveloperUpdateRelease.APK)))
    private fun parse(value: JSONObject = metadata()) = DeveloperUpdateRelease.parse(value.toString().toByteArray())
    private inner class Remote : BuildFeedback.Transport {
        val calls = mutableListOf<Pair<String, String?>>()
        var meta = metadata()
        var payload = bytes
        var status = 200
        var redirect = storage
        var advertised: Long? = null
        var unknownLength = false
        var metadataReads = 0
        var drift = false
        var inspected = 0
        var closed = 0
        var cancelRead: PatchPreviewCancellation? = null
        var automaticRedirect = false
        override fun get(url: String, token: String?, timeoutMillis: Long): BuildFeedback.Response {
            calls += url to token
            assertTrue(timeoutMillis in 1..DeveloperUpdateRelease.MAX_MILLIS)
            val data = when (url) {
                DeveloperUpdateRelease.API -> {
                    metadataReads++
                    if (drift && metadataReads > 1) meta.put("target_commitish", "b".repeat(40))
                    meta.toString().toByteArray()
                }
                DeveloperUpdateRelease.APK -> return BuildFeedback.Response(302, 0, url,
                    ByteArrayInputStream(byteArrayOf()), redirect) { closed++ }
                storage -> payload
                else -> error("Unexpected fixture request")
            }
            val body = object : ByteArrayInputStream(data) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (url == storage) cancelRead?.cancel()
                    return super.read(buffer, offset, length)
                }
            }
            return BuildFeedback.Response(if (url == DeveloperUpdateRelease.API) status else 200,
                if (unknownLength) -1 else advertised ?: data.size.toLong(),
                if (automaticRedirect) "https://other.invalid/EXPLICIT_PRIVATE_FIXTURE" else url, body) { closed++ }
        }
    }
    private fun reader(remote: Remote, cancellation: PatchPreviewCancellation = PatchPreviewCancellation { it() },
        inspector: ((File, DeveloperUpdateRelease.Release) -> Unit)? = null) =
        DeveloperUpdateDownload(temp.root, inspector ?: { file, release ->
            remote.inspected++; assertArrayEquals(bytes, file.readBytes()); assertEquals(hash, release.sha256)
        }, cancellation, remote)
    private fun pending() = File(temp.root, "updates").listFiles().orEmpty().toList()

    @Test fun publicChannelDownloadBindsImmutableSourceBytesAndFreshMetadataWithoutCredentials() {
        val remote = Remote(); val reader = reader(remote); val release = reader.latest()
        val file = reader.download(release)
        assertEquals("a".repeat(40), release.source); assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, remote.inspected); assertTrue(remote.calls.all { it.second == null })
        assertEquals(2, remote.metadataReads)
        reader.verify(file, release)
        assertEquals(3, remote.metadataReads); assertEquals(2, remote.inspected)
        assertEquals(remote.calls.size, remote.closed)
    }
    @Test fun releaseFlagsIdentityPackageChannelAndImmutableSourceAreStrict() {
        for ((key, value) in listOf("draft" to true, "draft" to "false", "prerelease" to false,
            "tag_name" to "jarvis-latest", "target_commitish" to "main", "name" to "EXPLICIT_PRIVATE_FIXTURE", "id" to 1.5)) {
            assertThrows(DeveloperUpdateRelease.Failure::class.java) { parse(metadata().put(key, value)) }
        }
    }
    @Test fun onlyOneFixedApkWithIntegerBoundedSizeAndDigestCanBeSelected() {
        for (kind in listOf("duplicate", "foreign", "negative", "fractional", "tooLarge", "digest", "assetId")) {
            val meta = metadata(); val apk = meta.getJSONArray("assets").getJSONObject(0)
            when (kind) {
                "duplicate" -> meta.getJSONArray("assets").put(JSONObject(apk.toString()))
                "foreign" -> apk.put("browser_download_url", DeveloperUpdateRelease.APK + "?EXPLICIT_PRIVATE_FIXTURE")
                "negative" -> apk.put("size", -1)
                "fractional" -> apk.put("size", 1.5)
                "tooLarge" -> apk.put("size", DeveloperUpdateRelease.MAX_APK + 1)
                "digest" -> apk.put("digest", "unknown")
                "assetId" -> apk.put("id", true)
            }
            assertThrows(kind, DeveloperUpdateRelease.Failure::class.java) { parse(meta) }
        }
    }
    @Test fun duplicateEscapedKeysInvalidUtf8TrailingDataAndDeepMetadataAreRejected() {
        val text = metadata().toString()
        val samples = listOf(text.replaceFirst("{", "{\"id\":8,"),
            text.replaceFirst("{", "{\"\\u0069d\":8,"), text + " EXPLICIT_PRIVATE_FIXTURE",
            text.dropLast(1) + ",\"nested\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}")
        for (value in samples) assertThrows(DeveloperUpdateRelease.Failure::class.java) { DeveloperUpdateRelease.parse(value.toByteArray()) }
        assertThrows(DeveloperUpdateRelease.Failure::class.java) { DeveloperUpdateRelease.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
    }
    @Test fun changedSourceAfterDownloadAndBeforeInstallReviewNeverPasses() {
        val remote = Remote(); val reader = reader(remote); val release = reader.latest()
        remote.drift = true
        assertEquals(DeveloperUpdateRelease.Problem.CHANGED, assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader.download(release) }.problem)
        assertTrue(pending().isEmpty())
        val fresh = Remote(); val next = reader(fresh); val file = next.download(parse())
        fresh.drift = true
        assertEquals(DeveloperUpdateRelease.Problem.CHANGED, assertThrows(DeveloperUpdateRelease.Failure::class.java) { next.verify(file, parse()) }.problem)
    }
    @Test fun storageRedirectsRejectOtherRepositoriesDowngradesCredentialsAndFragments() {
        for (bad in listOf(storage.replace("1410602023", "111"), storage.replace("https:", "http:"),
            storage.replace("https://", "https://user:EXPLICIT_PRIVATE_FIXTURE@"), storage + "#fragment",
            "https://other.invalid/EXPLICIT_PRIVATE_FIXTURE", storage.replace("release-assets.githubusercontent.com", "release-assets.githubusercontent.com.evil.invalid"))) {
            val remote = Remote().apply { redirect = bad }
            assertThrows(Exception::class.java) { reader(remote).download(parse()) }
            assertFalse(remote.calls.any { it.first == bad }); assertEquals(0, remote.inspected); assertTrue(pending().isEmpty())
        }
    }
    @Test fun silentAutomaticRedirectIsRejectedBeforeTrustingAnyBody() {
        val remote = Remote().apply { automaticRedirect = true }
        assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader(remote).latest() }
        assertEquals(1, remote.closed); assertEquals(0, remote.inspected)
    }
    @Test fun unknownLengthOverrunAndTruncationCannotReachTheInspector() {
        for (kind in listOf("overrun", "truncated")) {
            val remote = Remote().apply { unknownLength = true; payload = if (kind == "overrun") bytes + byteArrayOf(0) else bytes.copyOf(bytes.size - 1) }
            assertThrows(Exception::class.java) { reader(remote).download(parse()) }
            assertEquals(0, remote.inspected); assertTrue(pending().isEmpty())
        }
    }
    @Test fun oversizedDeclaredMetadataIsRejectedWithoutAllocatingItsBody() {
        val remote = Remote().apply { advertised = DeveloperUpdateRelease.MAX_METADATA + 1 }
        assertEquals(DeveloperUpdateRelease.Problem.LIMIT, assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader(remote).latest() }.problem)
        assertEquals(1, remote.closed)
    }
    @Test fun changedPayloadAndSignatureFailureRemoveOnlyTheirOwnDownload() {
        val folder = File(temp.root, "updates").apply { mkdir() }; val keep = File(folder, "keep.apk").apply { writeText("existing retained cache") }
        val changed = Remote().apply { payload = ByteArray(bytes.size) }
        assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader(changed).download(parse()) }
        val unsigned = Remote()
        assertEquals(DevelopmentArtifact.Problem.SIGNATURE, assertThrows(DevelopmentArtifact.Failure::class.java) {
            reader(unsigned, inspector = { _, _ -> throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.SIGNATURE) }).download(parse())
        }.problem)
        assertEquals(listOf(keep), pending()); assertEquals("existing retained cache", keep.readText())
    }
    @Test fun cancelBeforeReadAndDuringStreamingClosesResourcesAndDiscardsPartialFiles() {
        val cancel = PatchPreviewCancellation { it() }; cancel.cancel(); val initial = Remote()
        assertEquals(DeveloperUpdateRelease.Problem.CANCELLED, assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader(initial, cancel).latest() }.problem)
        assertTrue(initial.calls.isEmpty())
        val token = PatchPreviewCancellation { it() }; val remote = Remote().apply { cancelRead = token }
        assertThrows(Exception::class.java) { reader(remote, token).download(parse()) }
        assertEquals(0, remote.inspected); assertEquals(2, remote.closed); assertTrue(pending().isEmpty())
    }
    @Test fun concurrentReadsUseDifferentPrivateFilesAndNeverOverwriteTheEarlierVerifiedFile() {
        val reader = reader(Remote()); val first = reader.download(parse()); val second = reader.download(parse())
        assertNotEquals(first.absolutePath, second.absolutePath); assertArrayEquals(bytes, first.readBytes()); assertArrayEquals(bytes, second.readBytes())
    }
    @Test fun modifiedCachedFileAndSymlinkCannotPassFinalReview() {
        val reader = reader(Remote()); val file = reader.download(parse()); file.appendText("changed")
        assertEquals(DeveloperUpdateRelease.Problem.STORAGE, assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader.verify(file, parse()) }.problem)
        val link = File(temp.root, "linked.apk"); Files.createSymbolicLink(link.toPath(), file.toPath())
        assertEquals(DeveloperUpdateRelease.Problem.STORAGE, assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader.verify(link, parse()) }.problem)
    }
    @Test fun httpFailuresAndRemoteExceptionTextStaySanitized() {
        for (code in listOf(403, 404, 429, 500)) {
            val remote = Remote().apply { status = code }
            val failure = assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader(remote).latest() }
            assertFalse(DeveloperUpdateRelease.explain(failure).contains("EXPLICIT_PRIVATE_FIXTURE"))
            assertEquals(0, remote.inspected)
        }
        val failing = BuildFeedback.Transport { _, _, _ -> throw IllegalStateException("EXPLICIT_PRIVATE_FIXTURE") }
        val error = assertThrows(DeveloperUpdateRelease.Failure::class.java) {
            DeveloperUpdateDownload(temp.root, { _, _ -> fail() }, PatchPreviewCancellation(), failing).latest()
        }
        assertFalse(DeveloperUpdateRelease.explain(error).contains("EXPLICIT_PRIVATE_FIXTURE"))
    }
    @Test fun elapsedTimeBudgetIsEnforcedAfterTransportAndInspection() {
        var now = 0L
        val remote = Remote()
        val slow = BuildFeedback.Transport { url, token, timeout -> remote.get(url, token, timeout).also { now = DeveloperUpdateRelease.MAX_MILLIS } }
        val reader = DeveloperUpdateDownload(temp.root, { _, _ -> fail() }, PatchPreviewCancellation(), slow) { now }
        assertEquals(DeveloperUpdateRelease.Problem.LIMIT, assertThrows(DeveloperUpdateRelease.Failure::class.java) { reader.latest() }.problem)
    }
}
