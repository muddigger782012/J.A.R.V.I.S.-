package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaMethodReferencesTest {
    private val sha = "a".repeat(64)
    private val owner = "com.example.MainActivity"

    private fun sample(): JSONObject = JSONObject()
        .put("schema", ReaMethodReferences.SCHEMA)
        .put("state", "ANALYZED")
        .put("scope", "static-method-references-only")
        .put("apk_sha256", sha)
        .put("evidence_sha256", "b".repeat(64))
        .put("class_name", owner)
        .put("method_name", "onCreate").put("overload_index", 0)
        .put("reference_count", 1)
        .put("references", JSONArray().put(JSONObject()
            .put("kind", "method").put("name", "launch")
            .put("reported_full_name", "a.Other.launch()")
            .put("containing_class", "a.Other")
            .put("direct_invokes_exhaustive", JSONObject.NULL)))
        .put("reference_unit", "using_node")
        .put("coverage", "complete")
        .put("position_coverage", "partial_or_unknown")
        .put("truncated", false)

    @Test fun validatesSourceBoundReferencesAndExplicitIncompletePositions() {
        val result = ReaMethodReferences.validateResponse(
            sample().toString(), sha, owner, "onCreate", 0)
        assertEquals(1, result.referenceCount)
        assertEquals("partial_or_unknown", result.positionCoverage)
        assertNull(result.references[0].invokesExhaustive)
        assertTrue(result.display().contains("reflection and runtime calls may be missed"))
        assertTrue(result.proposalEvidence().contains("BEGIN_UNTRUSTED_REA_METHOD_REFERENCES"))
    }

    @Test fun refusesMismatchedMethodOrInventedCoverage() {
        val cases = listOf(
            sample().put("class_name", "a.Other"),
            sample().put("method_name", "onClick"),
            sample().put("overload_index", 3),
            sample().put("coverage", "runtime-verified"),
            sample().put("position_coverage", "verified-all-calls"),
            sample().put("evidence_sha256", "broken"),
            sample().put("reference_count", -2)
        )
        for (item in cases) assertThrows(Exception::class.java) {
            ReaMethodReferences.validateResponse(item.toString(), sha, owner, "onCreate", 0)
        }
    }

    @Test fun rejectsControlCharactersAndFalseTruncation() {
        for (item in listOf(
            sample().put("truncated", true),
            sample().put("references", JSONArray().put(JSONObject()
                .put("kind", "method").put("name", "bad\nname")
                .put("reported_full_name", "a.Other.launch()")
                .put("containing_class", "a.Other")
                .put("direct_invokes_exhaustive", false)))
        )) assertThrows(Exception::class.java) {
            ReaMethodReferences.validateResponse(item.toString(), sha, owner, "onCreate", 0)
        }
    }

    @Test fun reportsOmittedReferenceEntriesWithoutCompletenessClaim() {
        val parsed = sample().put("reference_count", 42).put("truncated", true)
        val result = ReaMethodReferences.validateResponse(
            parsed.toString(), sha, owner, "onCreate", 0)
        assertTrue(result.truncated)
        assertTrue(result.display().contains("omitted"))
    }
}
