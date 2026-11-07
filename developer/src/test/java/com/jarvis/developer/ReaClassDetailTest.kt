package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaClassDetailTest {
    private val sha = "a".repeat(64)
    private val className = "com.example.MainActivity"

    private fun sample(): JSONObject = JSONObject()
        .put("schema", ReaClassDetail.SCHEMA)
        .put("state", "ANALYZED")
        .put("scope", "static-class-structure-only")
        .put("apk_sha256", sha)
        .put("evidence_sha256", "b".repeat(64))
        .put("class_name", className)
        .put("method_count", 1)
        .put("methods", JSONArray().put(JSONObject()
            .put("name", "onCreate")
            .put("signature", "onCreate(Bundle): void")
            .put("overload_index", 0)))
        .put("field_count", 1)
        .put("fields", JSONArray().put(JSONObject()
            .put("name", "count").put("type", "int")))
        .put("inner_class_count", 0)
        .put("truncated", false)

    @Test fun acceptsHashBoundClassMembersAndShowsReadableEvidence() {
        val result = ReaClassDetail.validateResponse(sample().toString(), sha, className)
        assertEquals(className, result.className)
        assertEquals("onCreate", result.methods[0].name)
        assertEquals("int", result.fields[0].type)
        assertTrue(result.display().contains("onCreate(Bundle): void"))
        assertTrue(result.display().contains("onCreate#0"))
        assertTrue(result.proposalEvidence().contains("BEGIN_UNTRUSTED_REA_CLASS_DETAIL"))
    }

    @Test fun rejectsMalformedSelectorsAndUnboundClassIdentity() {
        for (name in listOf("", "x", "../class", "--help", "a;id", "A\nB")) {
            assertFalse(ReaClassDetail.validName(name))
        }
        assertTrue(ReaClassDetail.validName("a.MainActivity\$Inner"))
        assertThrows(Exception::class.java) {
            ReaClassDetail.validateResponse(sample().toString(), "0".repeat(64), className)
        }
        assertThrows(Exception::class.java) {
            ReaClassDetail.validateResponse(sample().toString(), sha, "com.example.Other")
        }
    }

    @Test fun cannotClaimCompleteClassStructureFromMalformedEvidence() {
        val invalid = listOf(
            sample().put("method_count", -1),
            sample().put("method_count", 0),
            sample().put("fields", JSONArray().put(JSONObject()
                .put("name", "bad\nfield").put("type", "int"))),
            sample().put("truncated", true),
            sample().put("scope", "dynamic-class-execution")
        )
        for (candidate in invalid) {
            assertThrows(Exception::class.java) {
                ReaClassDetail.validateResponse(candidate.toString(), sha, className)
            }
        }
    }

    @Test fun reportsTruncationWithoutClaimingAllMethods() {
        val sample = sample().put("method_count", 5).put("truncated", true)
        val report = ReaClassDetail.validateResponse(sample.toString(), sha, className)
        assertTrue(report.truncated)
        assertTrue(report.display().contains("omitted"))
    }
}
