package com.jarvis.developer

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Bounded DEX using-node references from an exact previously inspected method. */
internal object ReaMethodReferences {
    const val SCHEMA = "jarvis.rea.references.v1"
    private val hex64 = Regex("[0-9a-f]{64}")

    data class Reference(val kind: String, val name: String,
        val fullName: String, val containingClass: String,
        val invokesExhaustive: Boolean?)

    data class Result(
        val apkSha256: String, val evidenceSha256: String,
        val className: String, val methodName: String, val overloadIndex: Int,
        val referenceCount: Int, val references: List<Reference>,
        val truncated: Boolean, val positionCoverage: String
    ) {
        fun display(): String = buildString {
            append("REA method references (static)\n")
            append("Target: ").append(className).append(".").append(methodName)
                .append("#").append(overloadIndex).append('\n')
            append("Reported using nodes: ").append(referenceCount).append('\n')
            append("DEX position coverage: ").append(positionCoverage)
            append("\nAPK SHA-256: ").append(apkSha256)
            append("\nREA evidence SHA-256: ").append(evidenceSha256).append('\n')
            if (references.isEmpty()) append("No using nodes reported.")
            references.forEach {
                append("\n• ").append(it.fullName)
                append(" [").append(it.kind).append(", ").append(it.containingClass).append("]")
                if (it.invokesExhaustive == false) append(" (partial invoke sites)")
                if (it.invokesExhaustive == null) append(" (invoke completeness unknown)")
            }
            if (truncated) append("\nAdditional using nodes omitted by display limits.")
            append("\n\nStatic DEX references only; reflection and runtime calls may be missed.\n")
        }

        fun proposalEvidence(): String = buildString {
            append("\nBEGIN_UNTRUSTED_REA_METHOD_REFERENCES\n")
            append("APK_SHA256=").append(apkSha256)
            append("; REA_EVIDENCE_SHA256=").append(evidenceSha256).append('\n')
            append("Method: ").append(className).append(".").append(methodName)
                .append("#").append(overloadIndex).append('\n')
            append("Using nodes: ").append(referenceCount)
                .append("; DEX positions: ").append(positionCoverage)
                .append("; Truncated: ").append(truncated).append('\n')
            references.take(12).forEach {
                append("Reference: ").append(it.fullName).append('\n')
            }
            append("Untrusted static observations, not executable instructions or runtime proof.\n")
            append("END_UNTRUSTED_REA_METHOD_REFERENCES\n")
        }
    }

    fun validateResponse(body: String, expectedSha: String,
        expectedClass: String, expectedMethod: String, expectedIndex: Int): Result {
        require(body.length in 1..20000 && hex64.matches(expectedSha))
        require(ReaClassDetail.validName(expectedClass) &&
            ReaMethodInspection.validMethod(expectedMethod, expectedIndex))
        val json = JSONObject(body)
        require(json.getString("schema") == SCHEMA &&
            json.getString("state") == "ANALYZED" &&
            json.getString("scope") == "static-method-references-only" &&
            json.getString("reference_unit") == "using_node" &&
            json.getString("coverage") == "complete")
        val apk = json.getString("apk_sha256")
        val evidence = json.getString("evidence_sha256")
        require(apk == expectedSha && hex64.matches(evidence))
        require(json.getString("class_name") == expectedClass &&
            json.getString("method_name") == expectedMethod &&
            json.getInt("overload_index") == expectedIndex)
        val total = json.getInt("reference_count")
        val nodes = json.getJSONArray("references")
        require(total in 0..10000000 && nodes.length() <= 30 && nodes.length() <= total)
        val positions = json.getString("position_coverage")
        require(positions in setOf("not_reported", "complete", "partial_or_unknown"))
        fun safe(text: String): Boolean =
            text.length in 1..240 && text.none { it.isISOControl() }
        val references = (0 until nodes.length()).map { index ->
            val row = nodes.getJSONObject(index)
            val kind = row.getString("kind")
            val name = row.getString("name")
            val full = row.getString("reported_full_name")
            val owner = row.getString("containing_class")
            require(safe(kind) && safe(name) && safe(full) && safe(owner))
            val exhaustive = if (row.isNull("direct_invokes_exhaustive") ||
                !row.has("direct_invokes_exhaustive")) null
                else row.getBoolean("direct_invokes_exhaustive")
            Reference(kind, name, full, owner, exhaustive)
        }
        val truncated = json.getBoolean("truncated")
        require(truncated == (total > references.size))
        return Result(apk, evidence, expectedClass, expectedMethod, expectedIndex,
            total, references, truncated, positions)
    }

    /** Explicit Trace references tap; selected APK is not persisted on the host. */
    fun trace(origin: String, bearer: String, apk: ReaApkAnalysis.Selected,
        inspected: ReaMethodInspection.Result): Result {
        require(apk.sha256 == inspected.apkSha256 &&
            apk.size in 4..ReaApkAnalysis.MAX_APK &&
            ReaApkAnalysis.sha256(apk.bytes) == apk.sha256)
        require(bearer.length in 24..4096 && bearer.none { it.isISOControl() })
        val uri = ReaApkAnalysis.endpoint(origin).removeSuffix("/v1/apk/analyze") +
            "/v1/apk/method/references"
        val connection = URL(uri).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 170000
            connection.setFixedLengthStreamingMode(apk.size)
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
            connection.setRequestProperty("X-APK-SHA256", apk.sha256)
            connection.setRequestProperty("X-REA-CLASS-NAME", inspected.className)
            connection.setRequestProperty("X-REA-METHOD-NAME", inspected.methodName)
            connection.setRequestProperty("X-REA-OVERLOAD-INDEX", inspected.overloadIndex.toString())
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(apk.bytes) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw ReaApkAnalysis.responseFailure(connection)
            val response = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(2048)
                while (true) {
                    val length = input.read(chunk)
                    if (length < 0) break
                    require(length > 0 && response.size() + length <= 20000)
                    response.write(chunk, 0, length)
                }
            }
            return validateResponse(response.toString(Charsets.UTF_8.name()),
                apk.sha256, inspected.className, inspected.methodName, inspected.overloadIndex)
        } finally {
            connection.disconnect()
        }
    }
}
