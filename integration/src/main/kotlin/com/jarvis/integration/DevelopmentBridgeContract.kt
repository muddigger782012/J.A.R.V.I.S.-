package com.jarvis.integration

import org.json.JSONObject
import java.security.MessageDigest

/** Bounded data, never executable instructions, scope grants or verification evidence. */
object DevelopmentBridgeContract {
    const val VERSION = 1
    const val PERMISSION = "com.jarvis.developer.permission.DEVELOPMENT_BRIDGE_V1"
    const val DESCRIPTOR = "com.jarvis.developer.DevelopmentBridge.v1"
    const val SERVICE = "com.jarvis.developer.DevelopmentBridgeService"
    const val DEVELOPER_PACKAGE = "com.jarvis.developer"
    const val MAX_BODY_BYTES = 8192
    const val MAX_WIRE_BYTES = 12288
    const val MAX_PARCEL_BYTES = 32768
    val assistantPackages: Set<String> = java.util.Collections.unmodifiableSet(setOf("com.assistant.core", "com.assistant.core.beta"))
    val modes: List<String> = java.util.Collections.unmodifiableList(listOf("supervised", "authorized_autonomous", "owner_development"))
    val operations: Set<String> = java.util.Collections.unmodifiableSet(setOf("capabilities.request", "diagnostics.review.request", "task.status.request",
        "source.personal.analysis.request", "source.developer.analysis.request", "task.list.request", "task.cancel.request", "task.pause.request", "task.resume.request", "task.retry.request"))
    val sourceAnalysisKinds: Set<String> = java.util.Collections.unmodifiableSet(setOf("source.personal.analysis.request", "source.developer.analysis.request"))
    private val id = Regex("[A-Za-z0-9_-]{1,80}")
    private val fields = setOf("v", "id", "kind", "body", "mode", "job", "revision")
    enum class Verification { PENDING, RUNNING, PASSED, FAILED, BLOCKED }
    enum class Stage { SOURCE_COMMITTED, BUILD_SUCCESSFUL, TESTS_PASSED, SIGNED_APK, DEVICE_INSTALLED }
    class Rejected(val code: String) : IllegalArgumentException(code)
    data class Request(val requestId: String, val kind: String, val body: String = "",
        val mode: String = "supervised", val jobId: String = "", val version: Int = VERSION,
        val expectedRevision: Long? = null)

    fun availableModes(personal: Boolean) = modes.filter { personal || it != "owner_development" }
    fun modeLabel(mode: String) = when (mode) {
        "supervised" -> "Supervised"
        "authorized_autonomous" -> "Authorized Autonomous"
        "owner_development" -> "Owner Development"
        else -> "Unavailable"
    }
    fun permitsMode(mode: String, receiverPersonal: Boolean, senderPersonal: Boolean) =
        mode in availableModes(receiverPersonal && senderPersonal)
    fun personalPackage(packageName: String) = packageName in setOf("com.assistant.core", DEVELOPER_PACKAGE)
    /** Fixed Android result categories only; raw installer messages are never transported. */
    fun installationDiagnostic(code: Int): String = when (code) {
        -1 -> "Android requires installation confirmation."
        0 -> "Android reported installer success; installed bytes and device acceptance are checked separately."
        1 -> "Android could not complete installation. Review the APK and installer state before retrying."
        2 -> "Android blocked installation. Review the device's installation policy and permissions."
        3 -> "The Android installation was aborted. Review its outcome before explicitly requesting again."
        4 -> "Android rejected the APK as invalid. Rebuild and verify the artifact before retrying."
        5 -> "Android found a package conflict. Check the existing package and signing identity."
        6 -> "Android reported an incompatible APK. Check Android version and CPU architecture."
        7 -> "Android reported insufficient storage. Free space before explicitly retrying."
        else -> ""
    }

    private fun expect(valid: Boolean, code: String = "INVALID_REQUEST") { if (!valid) throw Rejected(code) }
    fun validate(request: Request) {
        expect(request.version == VERSION, "UNSUPPORTED_VERSION")
        expect(id.matches(request.requestId) && request.kind in operations && request.mode in modes)
        expect(request.body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES, "TOO_LARGE")
        expect(request.body.none { it.isISOControl() && it !in "\n\r\t" })
        val changesJob = request.kind in setOf("task.cancel.request", "task.pause.request", "task.resume.request", "task.retry.request")
        val jobControl = changesJob || request.kind == "task.status.request"
        expect(if (jobControl) id.matches(request.jobId) else request.jobId.isEmpty())
        expect(if (changesJob) request.expectedRevision != null && request.expectedRevision in 0..1_000_000
            else request.expectedRevision == null)
        expect(if (request.kind == "diagnostics.review.request" || request.kind in sourceAnalysisKinds) request.body.isNotBlank() else request.body.isEmpty())
        if (request.kind in sourceAnalysisKinds) expect(request.body.length <= 4000)
    }
    fun encode(request: Request): String {
        validate(request)
        val text = JSONObject().put("v", request.version).put("id", request.requestId).put("kind", request.kind)
            .put("body", request.body).put("mode", request.mode).put("job", request.jobId)
            .put("revision", request.expectedRevision ?: JSONObject.NULL).toString()
        expect(text.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES, "TOO_LARGE")
        return text
    }
    private fun json(raw: String): JSONObject {
        expect(raw.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES, "TOO_LARGE")
        var depth = 0; var quoted = false; var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; expect(depth <= 12) }
                '}', ']' -> { depth--; expect(depth >= 0) }
            }
        }
        expect(!quoted && depth == 0)
        return try { JSONObject(raw) } catch (_: Exception) { throw Rejected("INVALID_REQUEST") }
    }
    fun decode(raw: String): Request {
        val value = json(raw)
        expect(value.keys().asSequence().toSet() == fields)
        expect(value.opt("v") is Int)
        fun text(key: String) = (value.opt(key) as? String) ?: throw Rejected("INVALID_REQUEST")
        val revision = when (val item = value.opt("revision")) {
            JSONObject.NULL -> null
            is Int -> item.toLong()
            is Long -> item
            else -> throw Rejected("INVALID_REQUEST")
        }
        return Request(text("id"), text("kind"), text("body"), text("mode"), text("job"), value.getInt("v"), revision).also(::validate)
    }
    fun fingerprint(request: Request): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(request).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Capabilities are checked on the authenticated connection before any admission or control. */
    fun negotiate(data: JSONObject, request: Request) {
        expect(data.opt("protocol") is Int && data.getInt("protocol") == VERSION, "UNSUPPORTED_VERSION")
        val offered = data.optJSONArray("operations") ?: throw Rejected("UNSUPPORTED_VERSION")
        val offeredModes = data.optJSONArray("modes") ?: throw Rejected("POLICY_REQUIRED")
        expect(offered.length() <= 32 && offeredModes.length() <= modes.size, "INVALID_REQUEST")
        expect((0 until offered.length()).all { offered.opt(it) is String }, "INVALID_REQUEST")
        expect((0 until offeredModes.length()).all { offeredModes.opt(it) is String }, "INVALID_REQUEST")
        expect((0 until offered.length()).any { offered.getString(it) == request.kind }, "UNSUPPORTED_OPERATION")
        expect((0 until offeredModes.length()).any { offeredModes.getString(it) == request.mode }, "POLICY_REQUIRED")
    }

    fun response(requestId: String, code: String, data: JSONObject = JSONObject()): String {
        expect(id.matches(requestId) && code.matches(Regex("[A-Z_]{1,40}")))
        val raw = JSONObject().put("v", VERSION).put("id", requestId).put("code", code).put("data", data).toString()
        expect(raw.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES, "TOO_LARGE")
        return raw
    }
    fun readResponse(raw: String, requestId: String): JSONObject {
        val value = json(raw)
        expect(value.keys().asSequence().toSet() == setOf("v", "id", "code", "data"))
        expect(value.opt("v") is Int && value.getInt("v") == VERSION, "UNSUPPORTED_VERSION")
        expect(value.opt("code") is String && value.opt("data") is JSONObject)
        expect(value.opt("id") == requestId || value.opt("id") == "unavailable" && value.opt("code") != "OK")
        return value
    }
}
