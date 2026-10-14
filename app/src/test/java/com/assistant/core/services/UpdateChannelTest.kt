package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateChannelTest {
    private val digest = "ab".repeat(32)
    private val signer = "cd".repeat(32)
    private val installed = InstalledUpdateIdentity("com.assistant.core", 108, setOf(signer), listOf("arm64-v8a", "armeabi-v7a"), 34)

    @Test fun eachApplicationGetsItsOwnChannelAndDownloadSource() {
        for (channel in UpdateChannel.entries) {
            assertEquals(channel, UpdateChannel.forApplicationId(channel.applicationId))
            val metadata = UpdatePolicy.parseMetadata(manifest(channel).toString(), channel)
            val identity = installed.copy(applicationId = channel.applicationId)
            assertEquals(channel.artifactUrl("arm64-v8a"), UpdatePolicy.selectCandidate(metadata, identity)!!.artifact.url)
            val opposite = if (channel == UpdateChannel.JARVIS) UpdateChannel.BETA else UpdateChannel.JARVIS
            failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(manifest(opposite).toString(), channel) }
        }
        failure(UpdateFailureCode.UNSUPPORTED_APP) { UpdateChannel.forApplicationId("com.assistant.core.copy") }
    }

    @Test fun parserRejectsMissingFieldsWrongTypesAndMalformedManifest() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("schemaVersion") }, { it.put("schemaVersion", 2) }, { it.put("schemaVersion", "1") },
            { it.put("versionCode", -1) }, { it.put("versionCode", 109.5) }, { it.put("versionCode", "109") },
            { it.put("versionCode", "999999999999999999999999") }, { it.put("versionName", "") },
            { it.put("sourceCommit", "main") }, { it.put("applicationId", "com.assistant.core.beta") },
            { it.put("channel", "beta") }, { it.put("artifacts", JSONArray()) },
            { it.getJSONArray("artifacts").getJSONObject(0).put("sizeBytes", 0) },
            { it.getJSONArray("artifacts").getJSONObject(0).put("sizeBytes", UpdatePolicy.MAX_APK_BYTES + 1) },
            { it.getJSONArray("artifacts").getJSONObject(0).put("sha256", "not-a-hash") },
            { it.getJSONArray("artifacts").getJSONObject(0).put("signingCertificateSha256", "") },
            { it.getJSONArray("artifacts").getJSONObject(0).put("minSdk", 25) },
            { it.getJSONArray("artifacts").getJSONObject(0).put("minSdk", 26.1) },
            { it.getJSONArray("artifacts").getJSONObject(0).put("abi", "mips") },
            { it.getJSONArray("artifacts").put(it.getJSONArray("artifacts").getJSONObject(0)) }
        )
        for (mutate in mutations) {
            val root = manifest()
            mutate(root)
            failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(root.toString(), UpdateChannel.JARVIS) }
        }
        failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata("{", UpdateChannel.JARVIS) }
        failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(manifest().toString() + " trailing-content", UpdateChannel.JARVIS) }
        failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(manifest().toString() + '\u0000' + "trailing-content", UpdateChannel.JARVIS) }
        failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(manifest().toString() + manifest().toString(), UpdateChannel.JARVIS) }
        val deeplyNested = manifest().toString().dropLast(1) + ",\"extra\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}"
        failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(deeplyNested, UpdateChannel.JARVIS) }
        failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(" ".repeat(UpdatePolicy.MAX_METADATA_BYTES + 1), UpdateChannel.JARVIS) }
    }

    @Test fun manifestsCannotChooseArbitraryOrOppositeChannelUrls() {
        val permitted = UpdateChannel.JARVIS.artifactUrl("arm64-v8a")
        val urls = listOf(
            permitted.replace("https:", "http:"), "$permitted?redirect=evil", "$permitted#fragment",
            permitted.replace("github.com", "github.com.evil.example"), permitted.replace("github.com", "evil@github.com"),
            permitted.replace("github.com", "github.com:443"), permitted.replace("Jarvis-arm64", "Beta-arm64"),
            permitted.replace("jarvis-latest", "beta-latest"), permitted.replace("J.A.R.V.I.S.-", "other-repository"),
            UpdateChannel.JARVIS.artifactUrl("x86_64"), "https://raw.githubusercontent.com/attacker/payload/main/a.apk"
        )
        for (url in urls) {
            val root = manifest()
            root.getJSONArray("artifacts").getJSONObject(0).put("url", url)
            failure(UpdateFailureCode.INVALID_METADATA) { UpdatePolicy.parseMetadata(root.toString(), UpdateChannel.JARVIS) }
        }
    }

    @Test fun metadataSelectionRejectsDowngradesAndPrefersDevicesFirstSupportedArchitecture() {
        assertNull(UpdatePolicy.selectCandidate(metadata().copy(versionCode = 108), installed))
        assertNull(UpdatePolicy.selectCandidate(metadata().copy(versionCode = 107), installed))
        val arm32 = metadata().artifacts.single().copy(abi = "armeabi-v7a", url = UpdateChannel.JARVIS.artifactUrl("armeabi-v7a"))
        val both = metadata().copy(artifacts = listOf(arm32) + metadata().artifacts)
        assertEquals("arm64-v8a", UpdatePolicy.selectCandidate(both, installed)!!.artifact.abi)
        assertEquals("armeabi-v7a", UpdatePolicy.selectCandidate(both, installed.copy(supportedAbis = listOf("armeabi-v7a")))!!.artifact.abi)
        failure(UpdateFailureCode.NO_COMPATIBLE_APK) { UpdatePolicy.selectCandidate(metadata(), installed.copy(supportedAbis = listOf("x86_64"))) }
        failure(UpdateFailureCode.NO_COMPATIBLE_APK) { UpdatePolicy.selectCandidate(metadata().copy(artifacts = metadata().artifacts.map { it.copy(minSdk = 35) }), installed) }
        failure(UpdateFailureCode.DIFFERENT_SIGNER) { UpdatePolicy.selectCandidate(metadata(), installed.copy(signingCertificateSha256 = setOf(digest))) }
        failure(UpdateFailureCode.DIFFERENT_SIGNER) { UpdatePolicy.selectCandidate(metadata(), installed.copy(signingCertificateSha256 = setOf(signer, digest))) }
    }

    @Test fun archiveMustMatchPackageIncreasingAdvertisedVersionSignerMinimumAndroidAndSplit() {
        val candidate = UpdatePolicy.selectCandidate(metadata(), installed)!!
        val archive = ApkUpdateIdentity(installed.applicationId, 109, setOf(signer), 26, setOf("arm64-v8a"))
        UpdatePolicy.validateArchive(candidate, installed, archive)
        failure(UpdateFailureCode.WRONG_PACKAGE) { UpdatePolicy.validateArchive(candidate, installed, archive.copy(applicationId = UpdateChannel.BETA.applicationId)) }
        for (version in listOf(108L, 107L, 110L)) {
            failure(UpdateFailureCode.VERSION_MISMATCH) { UpdatePolicy.validateArchive(candidate, installed, archive.copy(versionCode = version)) }
        }
        failure(UpdateFailureCode.VERSION_MISMATCH) { UpdatePolicy.validateArchive(candidate, installed.copy(versionCode = 109), archive) }
        failure(UpdateFailureCode.DIFFERENT_SIGNER) { UpdatePolicy.validateArchive(candidate, installed, archive.copy(signingCertificateSha256 = setOf(digest))) }
        failure(UpdateFailureCode.DIFFERENT_SIGNER) { UpdatePolicy.validateArchive(candidate, installed, archive.copy(signingCertificateSha256 = emptySet())) }
        for (abis in listOf(emptySet(), setOf("x86_64"), setOf("arm64-v8a", "armeabi-v7a"))) {
            failure(UpdateFailureCode.NO_COMPATIBLE_APK) { UpdatePolicy.validateArchive(candidate, installed, archive.copy(nativeAbis = abis)) }
        }
        failure(UpdateFailureCode.NO_COMPATIBLE_APK) { UpdatePolicy.validateArchive(candidate, installed, archive.copy(minSdk = 35)) }
        failure(UpdateFailureCode.INVALID_APK) { UpdatePolicy.validateArchive(candidate.copy(artifact = candidate.artifact.copy(url = UpdateChannel.BETA.artifactUrl("arm64-v8a"))), installed, archive) }
    }

    @Test fun redirectsRequireHttpsGithubReleaseStorageAndTokensStayAtOriginalUrl() {
        val source = UpdateChannel.JARVIS.artifactUrl("arm64-v8a")
        assertTrue(UpdatePolicy.isAllowedRedirect("https://release-assets.githubusercontent.com/123/apk?sig=test", source, UpdateChannel.JARVIS, "arm64-v8a"))
        assertTrue(UpdatePolicy.isAllowedRedirect("https://objects.githubusercontent.com/github-production-release-asset/123", source, UpdateChannel.JARVIS, "arm64-v8a"))
        for (url in listOf("http://release-assets.githubusercontent.com/a", "https://evil.example/a", "https://raw.githubusercontent.com/attacker/a", "https://release-assets.githubusercontent.com.evil.example/a", "https://user@release-assets.githubusercontent.com/a")) {
            assertFalse(url, UpdatePolicy.isAllowedRedirect(url, source, UpdateChannel.JARVIS, "arm64-v8a"))
        }
        assertTrue(UpdatePolicy.authorizationAllowed(source, source))
        assertFalse(UpdatePolicy.authorizationAllowed("https://release-assets.githubusercontent.com/a", source))
        assertFalse(UpdatePolicy.authorizationAllowed(UpdateChannel.BETA.artifactUrl("arm64-v8a"), source))
        assertFalse(UpdatePolicy.isAllowedRedirect("https://objects.githubusercontent.com/a", UpdateChannel.JARVIS.metadataUrl, UpdateChannel.JARVIS, null))
    }

    private fun metadata() = UpdatePolicy.parseMetadata(manifest().toString(), UpdateChannel.JARVIS)
    private fun manifest(channel: UpdateChannel = UpdateChannel.JARVIS): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("channel", channel.id).put("applicationId", channel.applicationId)
        .put("versionCode", 109).put("versionName", "3.4.109").put("sourceCommit", "a".repeat(40))
        .put("artifacts", JSONArray().put(JSONObject().put("abi", "arm64-v8a").put("url", channel.artifactUrl("arm64-v8a"))
            .put("sizeBytes", 512).put("sha256", digest).put("signingCertificateSha256", signer).put("minSdk", 26)))
    private fun failure(code: UpdateFailureCode, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: UpdateException) { assertEquals(code, error.code) }
    }
}
