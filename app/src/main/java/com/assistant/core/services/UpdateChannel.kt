package com.assistant.core.services

import java.net.URI
import java.util.Locale
import org.json.JSONObject
import org.json.JSONTokener

/** App identity, not a user preference, selects the update channel. */
enum class UpdateChannel(
    val id: String,
    val applicationId: String,
    val displayName: String,
    val releaseTag: String,
    val assetPrefix: String
) {
    JARVIS("jarvis", "com.assistant.core", "Jarvis", "jarvis-latest", "Jarvis"),
    BETA("beta", "com.assistant.core.beta", "Jarvis Beta", "beta-latest", "Beta");

    val metadataUrl: String
        get() = "https://raw.githubusercontent.com/$REPOSITORY/main/updates/$id.json"

    fun artifactUrl(abi: String): String =
        "https://github.com/$REPOSITORY/releases/download/$releaseTag/$assetPrefix-$abi.apk"

    companion object {
        const val REPOSITORY = "muddigger782012/J.A.R.V.I.S.-"
        fun forApplicationId(applicationId: String): UpdateChannel =
            entries.firstOrNull { it.applicationId == applicationId }
                ?: throw UpdateException(UpdateFailureCode.UNSUPPORTED_APP, "This application has no supported update channel.")
    }
}

data class UpdateArtifact(
    val abi: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val minSdk: Int
)

data class UpdateMetadata(
    val channel: UpdateChannel,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val sourceCommit: String,
    val artifacts: List<UpdateArtifact>
)

data class InstalledUpdateIdentity(
    val applicationId: String,
    val versionCode: Long,
    val signingCertificateSha256: Set<String>,
    val supportedAbis: List<String>,
    val sdkInt: Int
)

data class UpdateCandidate(val metadata: UpdateMetadata, val artifact: UpdateArtifact)

data class ApkUpdateIdentity(
    val applicationId: String,
    val versionCode: Long,
    val signingCertificateSha256: Set<String>,
    val minSdk: Int,
    val nativeAbis: Set<String>
)

enum class UpdateFailureCode {
    UNSUPPORTED_APP, METADATA_NOT_FOUND, INVALID_METADATA, NO_COMPATIBLE_APK,
    DIFFERENT_SIGNER, NETWORK, DOWNLOAD_INTEGRITY, INVALID_APK, WRONG_PACKAGE,
    VERSION_MISMATCH, STORAGE, CANCELLED
}

data class UpdateFailure(val code: UpdateFailureCode, val message: String)

/** Messages deliberately exclude response bodies, credentials and signed redirect URLs. */
class UpdateException(val code: UpdateFailureCode, message: String) : Exception(message) {
    fun failure(): UpdateFailure = UpdateFailure(code, message ?: "Update failed.")
}

object UpdatePolicy {
    const val MAX_APK_BYTES = 1024L * 1024 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024
    val SUPPORTED_ARTIFACT_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val digestPattern = Regex("[a-fA-F0-9]{64}")
    private val commitPattern = Regex("[a-fA-F0-9]{40}")

    fun parseMetadata(json: String, expectedChannel: UpdateChannel): UpdateMetadata {
        try {
            validateJsonEnvelope(json)
            val parser = JSONTokener(json)
            val root = parser.nextValue() as? JSONObject ?: throw IllegalArgumentException("Expected object")
            require(parser.nextClean() == '\u0000')
            require(integer(root, "schemaVersion") == 1L)
            require(string(root, "channel") == expectedChannel.id)
            require(string(root, "applicationId") == expectedChannel.applicationId)
            val versionCode = integer(root, "versionCode")
            require(versionCode > 0)
            val versionName = string(root, "versionName")
            require(versionName.isNotBlank() && versionName.length <= 128 && versionName.none { it.isISOControl() })
            val sourceCommit = string(root, "sourceCommit")
            require(commitPattern.matches(sourceCommit))
            val values = root.getJSONArray("artifacts")
            require(values.length() in 1..SUPPORTED_ARTIFACT_ABIS.size)
            val artifacts = (0 until values.length()).map { index ->
                val item = values.getJSONObject(index)
                val abi = string(item, "abi")
                require(abi in SUPPORTED_ARTIFACT_ABIS)
                val url = string(item, "url")
                require(isArtifactUrl(url, expectedChannel, abi))
                val sizeBytes = integer(item, "sizeBytes")
                require(sizeBytes in 1..MAX_APK_BYTES)
                val digest = string(item, "sha256")
                val signer = string(item, "signingCertificateSha256")
                require(digestPattern.matches(digest) && digestPattern.matches(signer))
                val minSdk = integer(item, "minSdk")
                require(minSdk in 26..Int.MAX_VALUE.toLong())
                UpdateArtifact(abi, url, sizeBytes, digest.lowercase(Locale.ROOT), signer.lowercase(Locale.ROOT), minSdk.toInt())
            }
            require(artifacts.map { it.abi }.distinct().size == artifacts.size)
            // A channel release is one signed application, irrespective of native architecture.
            require(artifacts.map { it.signingCertificateSha256 }.distinct().size == 1)
            return UpdateMetadata(expectedChannel, expectedChannel.applicationId, versionCode, versionName,
                sourceCommit.lowercase(Locale.ROOT), artifacts)
        } catch (_: Exception) {
            throw UpdateException(UpdateFailureCode.INVALID_METADATA, "The update manifest is invalid or belongs to a different application.")
        }
    }

    /** Null means the installed version is already as new as this channel. */
    fun selectCandidate(metadata: UpdateMetadata, installed: InstalledUpdateIdentity): UpdateCandidate? {
        if (metadata.applicationId != installed.applicationId ||
            metadata.channel != UpdateChannel.forApplicationId(installed.applicationId)) {
            throw UpdateException(UpdateFailureCode.WRONG_PACKAGE, "This update belongs to a different application.")
        }
        if (metadata.versionCode <= installed.versionCode) return null
        val artifact = installed.supportedAbis.asSequence()
            .mapNotNull { abi -> metadata.artifacts.firstOrNull { it.abi == abi && it.minSdk <= installed.sdkInt } }
            .firstOrNull()
            ?: throw UpdateException(UpdateFailureCode.NO_COMPATIBLE_APK, "No update APK supports this device's Android version and processor.")
        if (installed.signingCertificateSha256 != setOf(artifact.signingCertificateSha256)) {
            throw UpdateException(UpdateFailureCode.DIFFERENT_SIGNER, "The update signing certificate does not match this installation. Your existing app and data have been kept.")
        }
        return UpdateCandidate(metadata, artifact)
    }

    /** Run again immediately before handing the verified archive to Android. */
    fun validateArchive(candidate: UpdateCandidate, installed: InstalledUpdateIdentity, archive: ApkUpdateIdentity) {
        val selected = selectCandidate(candidate.metadata, installed)
            ?: throw UpdateException(UpdateFailureCode.VERSION_MISMATCH, "The APK is not newer than this installation.")
        if (selected.artifact != candidate.artifact || !isArtifactUrl(candidate.artifact.url, candidate.metadata.channel, candidate.artifact.abi)) {
            throw UpdateException(UpdateFailureCode.INVALID_APK, "The APK does not match the selected update artifact.")
        }
        if (archive.applicationId != installed.applicationId || archive.applicationId != candidate.metadata.applicationId) {
            throw UpdateException(UpdateFailureCode.WRONG_PACKAGE, "The downloaded APK belongs to a different application.")
        }
        if (archive.versionCode != candidate.metadata.versionCode || archive.versionCode <= installed.versionCode) {
            throw UpdateException(UpdateFailureCode.VERSION_MISMATCH, "The downloaded APK version does not match the newer advertised update.")
        }
        if (archive.signingCertificateSha256 != installed.signingCertificateSha256 ||
            archive.signingCertificateSha256 != setOf(candidate.artifact.signingCertificateSha256)) {
            throw UpdateException(UpdateFailureCode.DIFFERENT_SIGNER, "The APK signing certificate does not match this installation. Your existing app and data have been kept.")
        }
        if (archive.minSdk != candidate.artifact.minSdk || archive.minSdk > installed.sdkInt ||
            archive.nativeAbis != setOf(candidate.artifact.abi) || candidate.artifact.abi !in installed.supportedAbis) {
            throw UpdateException(UpdateFailureCode.NO_COMPATIBLE_APK, "The downloaded APK is not compatible with this device.")
        }
    }

    fun isMetadataUrl(url: String, channel: UpdateChannel): Boolean = canonicalHttpsUrl(url) == channel.metadataUrl
    fun isArtifactUrl(url: String, channel: UpdateChannel, abi: String): Boolean =
        abi in SUPPORTED_ARTIFACT_ABIS && canonicalHttpsUrl(url) == channel.artifactUrl(abi)

    /** Only GitHub's release storage hosts receive redirected APK requests, without Authorization. */
    fun isAllowedRedirect(url: String, originalUrl: String, channel: UpdateChannel, abi: String?): Boolean {
        val uri = safeHttpsUri(url) ?: return false
        if (abi == null) return isMetadataUrl(url, channel)
        if (url == originalUrl) return isArtifactUrl(url, channel, abi)
        return uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }

    fun authorizationAllowed(url: String, originalUrl: String): Boolean =
        url == originalUrl && safeHttpsUri(url)?.host in setOf("github.com", "raw.githubusercontent.com")

    private fun canonicalHttpsUrl(url: String): String? = safeHttpsUri(url)?.toASCIIString()
    // JSONObject recursively parses objects/arrays on Android. Bound recursion before invoking it.
    internal fun validateJsonEnvelope(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES)
        var depth = 0
        var inString = false
        var escaped = false
        for (character in json) {
            if (inString) {
                require(character >= ' ')
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else {
                require(!character.isISOControl() || character == '\t' || character == '\n' || character == '\r')
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> { depth++; require(depth <= 16) }
                    '}', ']' -> { depth--; require(depth >= 0) }
                }
            }
        }
        require(depth == 0 && !inString)
    }
    private fun safeHttpsUri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
        it.scheme == "https" && it.host != null && it.userInfo == null && it.port == -1 && it.fragment == null
    }
    private fun string(root: JSONObject, key: String): String = root.get(key) as? String
        ?: throw IllegalArgumentException("Expected string")
    private fun integer(root: JSONObject, key: String): Long {
        val value = root.get(key)
        require(value is Number && Regex("[0-9]+").matches(value.toString()))
        return value.toString().toLong()
    }
}
