package com.assistant.core.services

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "PK-fake-APK-with-verified-bytes".toByteArray()
    private val channel = UpdateChannel.JARVIS

    @Test fun streamsDownloadWithProgressAndDisconnectsAfterExactHashAndSize() {
        val response = FakeConnection(URL(channel.artifactUrl("arm64-v8a")), 200, bytes)
        val transport = transport(response)
        val target = File(temporary.root, "update.part")
        var progress = 0L
        transport.download(candidate(), target) { count, total -> progress = count; assertEquals(bytes.size.toLong(), total) }
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong(), progress)
        assertTrue(response.disconnected)
        assertFalse(response.instanceFollowRedirects)
        assertEquals(15_000, response.connectTimeout)
        assertEquals(30_000, response.readTimeout)
    }

    @Test fun corruptTruncatedOversizedOrMismatchedContentLengthDownloadsLeaveNoPartialFile() {
        val cases = listOf(
            FakeConnection(URL(candidate().artifact.url), 200, "corrupt".toByteArray(), reportedLength = -1),
            FakeConnection(URL(candidate().artifact.url), 200, bytes.dropLast(1).toByteArray(), reportedLength = -1),
            FakeConnection(URL(candidate().artifact.url), 200, bytes + byteArrayOf(1), reportedLength = -1),
            FakeConnection(URL(candidate().artifact.url), 200, bytes, reportedLength = bytes.size + 1L),
            FakeConnection(URL(candidate().artifact.url), 200, bytes.map { (it + 1).toByte() }.toByteArray())
        )
        for (response in cases) {
            val target = File(temporary.root, "update.part")
            failure(UpdateFailureCode.DOWNLOAD_INTEGRITY) { transport(response).download(candidate(), target) }
            assertFalse(target.exists())
            assertTrue(response.disconnected)
        }
    }

    @Test fun redirectsAreManualAndCredentialsNeverReachGithubAssetStorage() {
        val original = FakeConnection(URL(candidate().artifact.url), 302, location = "https://release-assets.githubusercontent.com/123/apk?sig=test")
        val asset = FakeConnection(URL("https://release-assets.githubusercontent.com/123/apk?sig=test"), 200, bytes)
        val transport = transport(original, asset, token = "saved-token")
        transport.download(candidate(), File(temporary.root, "update.part"))
        assertEquals("Bearer saved-token", original.getRequestProperty("Authorization"))
        assertNull(asset.getRequestProperty("Authorization"))
        assertTrue(original.disconnected)
        assertTrue(asset.disconnected)
    }

    @Test fun maliciousRedirectsNeverOpenAnotherConnectionOrLeakCredentials() {
        for (url in listOf("https://evil.example/steal", "http://objects.githubusercontent.com/a", UpdateChannel.BETA.artifactUrl("arm64-v8a"), "https://raw.githubusercontent.com/attacker/repo/main/a.apk")) {
            val original = FakeConnection(URL(candidate().artifact.url), 302, location = url)
            var requests = 0
            val transport = UpdateHttpTransport("saved-token", { requests++; original })
            val target = File(temporary.root, "update.part")
            failure(UpdateFailureCode.NETWORK) { transport.download(candidate(), target) }
            assertEquals(1, requests)
            assertFalse(target.exists())
            assertTrue(original.disconnected)
        }
    }

    @Test fun excessiveRedirectsHttpFailuresAndHeaderInjectionFailCleanly() {
        val loop = FakeConnection(URL(candidate().artifact.url), 302, location = candidate().artifact.url)
        var requests = 0
        val target = File(temporary.root, "update.part")
        failure(UpdateFailureCode.NETWORK) { UpdateHttpTransport(null, { requests++; loop }).download(candidate(), target) }
        assertEquals(6, requests)
        assertFalse(target.exists())
        for (status in listOf(401, 403, 404, 500, 206)) {
            val response = FakeConnection(URL(candidate().artifact.url), status)
            failure(UpdateFailureCode.NETWORK) { transport(response).download(candidate(), target) }
            assertFalse(target.exists())
        }
        requests = 0
        failure(UpdateFailureCode.NETWORK) { UpdateHttpTransport("bad\r\nAuthorization: attacker", { requests++; loop }).download(candidate(), target) }
        assertEquals(0, requests)
    }

    @Test fun brokenStreamsAndInterruptedOrExpiredDownloadsDeleteTheirPartialFile() {
        val broken = FakeConnection(URL(candidate().artifact.url), 200, bytes, streamFactory = {
            object : InputStream() { override fun read(): Int = throw IOException("must not expose this failure detail") }
        })
        val target = File(temporary.root, "update.part")
        try { transport(broken).download(candidate(), target); fail("Expected broken stream") } catch (_: IOException) {}
        assertFalse(target.exists())
        assertTrue(broken.disconnected)
        Thread.currentThread().interrupt()
        try {
            failure(UpdateFailureCode.CANCELLED) { transport(FakeConnection(URL(candidate().artifact.url), 200, bytes)).download(candidate(), target) }
            assertFalse(target.exists())
        } finally { Thread.interrupted() }
        var clockCalls = 0
        val expired = UpdateHttpTransport(null, { FakeConnection(it, 200, bytes) }, { if (clockCalls++ == 0) 0L else Long.MAX_VALUE })
        failure(UpdateFailureCode.NETWORK) { expired.download(candidate(), target) }
        assertFalse(target.exists())
    }

    @Test fun streamCloseFailureAlsoDeletesPartialFileAndDisconnects() {
        val response = FakeConnection(URL(candidate().artifact.url), 200, bytes, streamFactory = {
            object : ByteArrayInputStream(bytes) {
                override fun close() { throw IOException("private stream close detail") }
            }
        })
        val target = File(temporary.root, "update.part")
        try { transport(response).download(candidate(), target); fail("Expected stream close failure") }
        catch (_: IOException) {}
        assertFalse(target.exists())
        assertTrue(response.disconnected)
    }

    @Test fun missingUnpublishedOversizedOrRedirectedMetadataDoesNotFallBackToAnUnverifiedApk() {
        val response = FakeConnection(URL(channel.metadataUrl), 200, "{}".toByteArray())
        assertEquals("{}", transport(response).fetchMetadata(channel))
        failure(UpdateFailureCode.METADATA_NOT_FOUND) { transport(FakeConnection(URL(channel.metadataUrl), 404)).fetchMetadata(channel) }
        failure(UpdateFailureCode.INVALID_METADATA) { transport(FakeConnection(URL(channel.metadataUrl), 200, ByteArray(UpdatePolicy.MAX_METADATA_BYTES + 1), reportedLength = -1)).fetchMetadata(channel) }
        failure(UpdateFailureCode.INVALID_METADATA) { transport(FakeConnection(URL(channel.metadataUrl), 200, reportedLength = UpdatePolicy.MAX_METADATA_BYTES + 1L)).fetchMetadata(channel) }
        failure(UpdateFailureCode.NETWORK) { transport(FakeConnection(URL(channel.metadataUrl), 302, location = "https://evil.example/manifest.json")).fetchMetadata(channel) }
    }

    private fun candidate(): UpdateCandidate {
        val artifact = UpdateArtifact("arm64-v8a", channel.artifactUrl("arm64-v8a"), bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).hex(), "cd".repeat(32), 26)
        return UpdateCandidate(UpdateMetadata(channel, channel.applicationId, 109, "3.4.109", "a".repeat(40), listOf(artifact)), artifact)
    }
    private fun transport(vararg responses: FakeConnection, token: String? = null): UpdateHttpTransport {
        var index = 0
        return UpdateHttpTransport(token, { assertTrue("Unexpected extra network request", index < responses.size); responses[index++] })
    }
    private fun failure(code: UpdateFailureCode, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: UpdateException) { assertEquals(code, error.code) }
    }
    private class FakeConnection(
        url: URL, private val status: Int, private val body: ByteArray = byteArrayOf(),
        private val location: String? = null, private val reportedLength: Long = body.size.toLong(),
        private val streamFactory: () -> InputStream = { ByteArrayInputStream(body) }
    ) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = streamFactory()
        override fun getContentLengthLong() = reportedLength
        override fun getHeaderField(name: String): String? = if (name == "Location") location else null
    }
}
