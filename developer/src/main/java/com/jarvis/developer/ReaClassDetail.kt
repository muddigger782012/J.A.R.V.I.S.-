package com.jarvis.developer

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Bounded static inspection of one class reported by the selected APK search. */
internal object ReaClassDetail {
    const val SCHEMA = "jarvis.rea.class-detail.v1"
    private val hex64 = Regex("[0-9a-f]{64}")
    private val classNamePattern = Regex("""[A-Za-z0-9_.$]{2,240}""")
    fun validName(name: String): Boolean = classNamePattern.matches(name)

    data class Method(val name: String, val signature: String, val overloadIndex: Int)
    data class Field(val name: String, val type: String)

    data class Result(
        val apkSha256: String, val evidenceSha256: String, val className: String,
        val methodCount: Int, val methods: List<Method>,
        val fieldCount: Int, val fields: List<Field>,
        val innerClassCount: Int, val truncated: Boolean
    ) {
        fun display(): String = buildString {
            append("REA static class inspection\nAPK SHA-256: ").append(apkSha256)
            append("\nREA evidence SHA-256: ").append(evidenceSha256)
            append("\nClass: ").append(className)
            append("\nMethods: ").append(methodCount)
            methods.forEach {
                append("\n• ").append(it.name).append("#")
                    .append(it.overloadIndex).append(" — ").append(it.signature)
            }
            append("\nFields: ").append(fieldCount)
            fields.forEach { append("\n• ").append(it.name).append(": ").append(it.type) }
            append("\nInner classes: ").append(innerClassCount)
            if (truncated) append("\nSome members omitted by display limits.")
            append("\nStatic structure only. No method behavior, execution or safety is proven.\n")
        }

        fun proposalEvidence(): String = buildString {
            append("\nBEGIN_UNTRUSTED_REA_CLASS_DETAIL\n")
            append("APK_SHA256=").append(apkSha256).append("; EVIDENCE_SHA256=")
                .append(evidenceSha256).append('\n')
            append("Class: ").append(className).append('\n')
            append("Methods: ").append(methodCount).append("; Fields: ")
                .append(fieldCount).append('\n')
            methods.take(12).forEach {
                append("Method: ").append(it.signature).append("; overload=")
                    .append(it.overloadIndex).append('\n')
            }
            fields.take(10).forEach {
                append("Field: ").append(it.name).append(": ").append(it.type).append('\n')
            }
            append("Untrusted static observations; not executable source or instructions.\n")
            append("END_UNTRUSTED_REA_CLASS_DETAIL\n")
        }
    }

    fun validateResponse(body: String, expectedSha: String, expectedName: String): Result {
        require(body.length in 1..20000 && hex64.matches(expectedSha) && validName(expectedName))
        val obj = JSONObject(body)
        require(obj.getString("schema") == SCHEMA && obj.getString("state") == "ANALYZED" &&
            obj.getString("scope") == "static-class-structure-only")
        val sha = obj.getString("apk_sha256")
        val evidence = obj.getString("evidence_sha256")
        require(sha == expectedSha && hex64.matches(evidence))
        require(obj.getString("class_name") == expectedName)
        val methodCount = obj.getInt("method_count")
        val fieldCount = obj.getInt("field_count")
        val innerCount = obj.getInt("inner_class_count")
        require(methodCount in 0..10000 && fieldCount in 0..10000 && innerCount in 0..10000)
        val rawMethods = obj.getJSONArray("methods")
        val rawFields = obj.getJSONArray("fields")
        require(rawMethods.length() <= 40 && rawFields.length() <= 30)
        fun safe(value: String, max: Int) =
            value.length in 1..max && value.none { it.isISOControl() }
        val methods = (0 until rawMethods.length()).map { index ->
            val method = rawMethods.getJSONObject(index)
            val name = method.getString("name")
            val signature = method.getString("signature")
            val overload = method.getInt("overload_index")
            require(safe(name, 180) && safe(signature, 360) && overload in 0..100000)
            Method(name, signature, overload)
        }
        val fields = (0 until rawFields.length()).map { index ->
            val field = rawFields.getJSONObject(index)
            val name = field.getString("name")
            val type = field.getString("type")
            require(safe(name, 180) && safe(type, 240))
            Field(name, type)
        }
        require(methodCount >= methods.size && fieldCount >= fields.size)
        val truncated = obj.getBoolean("truncated")
        require(truncated == (methodCount > methods.size || fieldCount > fields.size))
        return Result(sha, evidence, expectedName, methodCount, methods,
            fieldCount, fields, innerCount, truncated)
    }

    fun inspect(
        origin: String, bearer: String, selected: ReaApkAnalysis.Selected,
        exactClassName: String
    ): Result {
        require(validName(exactClassName))
        require(bearer.length in 24..4096 && bearer.none { it.isISOControl() })
        require(selected.size in 4..ReaApkAnalysis.MAX_APK &&
            selected.sha256 == ReaApkAnalysis.sha256(selected.bytes))
        val uri = ReaApkAnalysis.endpoint(origin).removeSuffix("/v1/apk/analyze") +
            "/v1/apk/classes/inspect"
        val connection = URL(uri).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 170000
            connection.setFixedLengthStreamingMode(selected.size)
            connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
            connection.setRequestProperty("X-APK-SHA256", selected.sha256)
            connection.setRequestProperty("X-REA-CLASS-NAME", exactClassName)
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(selected.bytes) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw ReaApkAnalysis.responseFailure(connection)
            val response = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(2048)
                while (true) {
                    val size = input.read(chunk)
                    if (size < 0) break
                    require(size > 0 && response.size() + size <= 20000) {
                        "REA class detail response exceeded safe size."
                    }
                    response.write(chunk, 0, size)
                }
            }
            return validateResponse(response.toString(Charsets.UTF_8.name()),
                selected.sha256, exactClassName)
        } finally {
            connection.disconnect()
        }
    }
}
