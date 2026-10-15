package com.assistant.core.services

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Recreates pending installer state only; callers must verifyDownloaded on a worker before installing. */
object UpdateReceiptState {
    private val uuidFilename = Regex("(jarvis|beta)-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.apk")

    fun encode(downloaded: UpdateDownloadResult.Downloaded): String {
        val metadata = downloaded.candidate.metadata
        require(validFilename(downloaded.file.name, metadata.channel))
        val serializedMetadata = JSONObject()
            .put("schemaVersion", 1)
            .put("channel", metadata.channel.id)
            .put("applicationId", metadata.applicationId)
            .put("versionCode", metadata.versionCode)
            .put("versionName", metadata.versionName)
            .put("sourceCommit", metadata.sourceCommit)
            .put("artifacts", JSONArray().also { values ->
                metadata.artifacts.forEach { artifact ->
                    values.put(JSONObject()
                        .put("abi", artifact.abi)
                        .put("url", artifact.url)
                        .put("sizeBytes", artifact.sizeBytes)
                        .put("sha256", artifact.sha256)
                        .put("signingCertificateSha256", artifact.signingCertificateSha256)
                        .put("minSdk", artifact.minSdk))
                }
            })
        val parsed = UpdatePolicy.parseMetadata(serializedMetadata.toString(), metadata.channel)
        require(parsed.artifacts.singleOrNull { it.abi == downloaded.candidate.artifact.abi } == downloaded.candidate.artifact)
        return JSONObject()
            .put("receiptSchemaVersion", 1)
            .put("metadata", serializedMetadata)
            .put("selectedAbi", downloaded.candidate.artifact.abi)
            .put("fileName", downloaded.file.name)
            .toString()
    }

    /** Returns an unverified receipt; no network request, installer launch, or app preference mutation. */
    fun decode(raw: String, updateDirectory: File, expectedChannel: UpdateChannel): UpdateDownloadResult.Downloaded? = try {
        UpdatePolicy.validateJsonEnvelope(raw)
        val parser = JSONTokener(raw)
        val root = parser.nextValue() as? JSONObject ?: throw IllegalArgumentException("Expected object")
        require(parser.nextClean() == '\u0000')
        val schema = root.get("receiptSchemaVersion")
        require(schema is Number && schema.toString() == "1")
        val fileName = root.get("fileName") as? String ?: throw IllegalArgumentException("Expected filename")
        require(validFilename(fileName, expectedChannel))
        val metadataObject = root.get("metadata") as? JSONObject ?: throw IllegalArgumentException("Expected metadata")
        val metadata = UpdatePolicy.parseMetadata(metadataObject.toString(), expectedChannel)
        val abi = root.get("selectedAbi") as? String ?: throw IllegalArgumentException("Expected ABI")
        val artifact = metadata.artifacts.singleOrNull { it.abi == abi }
            ?: throw IllegalArgumentException("Unknown selected ABI")
        val file = File(updateDirectory, fileName)
        require(file.canonicalFile.parentFile == updateDirectory.canonicalFile)
        UpdateDownloadResult.Downloaded(file, UpdateCandidate(metadata, artifact))
    } catch (_: Exception) {
        null
    }

    private fun validFilename(name: String, channel: UpdateChannel): Boolean =
        uuidFilename.matches(name) && name.startsWith("${channel.id}-")
}
