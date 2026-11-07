package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaMethodInspectionTest {
    private val sha = "a".repeat(64)
    private val className = "com.example.MainActivity"
    private val method = "onCreate"

    private fun sample(): JSONObject = JSONObject()
        .put("schema", ReaMethodInspection.SCHEMA)
        .put("state", "ANALYZED")
        .put("scope", "static-method-inspection-only")
        .put("apk_sha256", sha)
        .put("evidence_sha256", "b".repeat(64))
        .put("source_sha256", "c".repeat(64))
        .put("class_name", className).put("method_name", method)
        .put("overload_index", 0)
        .put("signature", "onCreate(Bundle): void")
        .put("representation", "java").put("body_status", "available")
        .put("source_status", "partial").put("fell_back", false)
        .put("markers", JSONArray())
        .put("source_excerpt", "void onCreate() { return; }")
        .put("source_characters", 27)
        .put("truncated", false)

    @Test fun acceptsExactApkMethodAndPreservesPartialCoverage() {
        val content = "void onCreate() { return; }"
        val report = sample().put("source_characters", content.length)
        val result = ReaMethodInspection.validateResponse(
            report.toString(), sha, className, method, 0)
        assertEquals("partial", result.sourceStatus)
        assertFalse(result.truncated)
        assertTrue(result.display().contains("Source coverage: partial"))
        assertTrue(result.proposalEvidence().contains("BEGIN_UNTRUSTED_REA_METHOD_EVIDENCE"))
        assertTrue(result.proposalEvidence().contains("Source coverage: partial"))
    }

    @Test fun deniesArbitraryMethodSelectorsAndIdentityMismatches() {
        for (invalid in listOf("", "--help", "a;id", "x y", "name\nX", "a.b")) {
            assertFalse(ReaMethodInspection.validMethod(invalid, 0))
        }
        assertTrue(ReaMethodInspection.validMethod("<init>", 0))
        assertFalse(ReaMethodInspection.validMethod("onCreate", -1))
        for (bad in listOf(
            sample().put("class_name", "different.Class"),
            sample().put("overload_index", 1),
            sample().put("method_name", "different"),
            sample().put("source_sha256", "x".repeat(64)),
            sample().put("scope", "executed-method"))) {
            assertThrows(Exception::class.java) {
                ReaMethodInspection.validateResponse(
                    bad.toString(), sha, className, method, 0)
            }
        }
    }

    @Test fun rejectsUnboundedSourceAndFalseCompletenessClaims() {
        for (bad in listOf(
            sample().put("source_excerpt", "X".repeat(6001)),
            sample().put("source_characters", -1),
            sample().put("source_status", "verified-runtime"),
            sample().put("representation", "native-executed"),
            sample().put("truncated", true),
            sample().put("signature", "unsafe\nsignature"))) {
            assertThrows(Exception::class.java) {
                ReaMethodInspection.validateResponse(
                    bad.toString(), sha, className, method, 0)
            }
        }
    }

    @Test fun exposesBoundedSmaliFallbackAndTruncation() {
        val body = sample()
            .put("representation", "smali")
            .put("fell_back", true)
            .put("markers", JSONArray().put("java-fallback"))
            .put("source_excerpt", "line".repeat(1500))
            .put("source_characters", 9000)
            .put("truncated", true)
        val result = ReaMethodInspection.validateResponse(
            body.toString(), sha, className, method, 0)
        assertEquals("smali", result.representation)
        assertTrue(result.fellBack)
        assertTrue(result.truncated)
        assertTrue(result.display().contains("excerpt truncated"))
        assertTrue(result.proposalEvidence().length < 4000)
    }
}
