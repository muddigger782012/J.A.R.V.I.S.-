package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReaClassSearchTest {
    private val sha = "a".repeat(64)

    private fun response(query: String = "MainActivity"): String =
        JSONObject()
            .put("schema", ReaClassSearch.SCHEMA)
            .put("state", "ANALYZED")
            .put("scope", "static-class-name-search-only")
            .put("apk_sha256", sha)
            .put("evidence_sha256", "b".repeat(64))
            .put("query", query)
            .put("total_classes", 115)
            .put("match_count", 2)
            .put("matches", JSONArray().put("com.example.MainActivity")
                .put("com.example.MainActivity\$Inner"))
            .put("truncated", false)
            .toString()

    @Test fun validatesHashBoundSearchAndReadableEvidence() {
        val result = ReaClassSearch.validateResponse(response(), sha, "MainActivity")
        assertEquals(115, result.totalClasses)
        assertEquals(2, result.matchCount)
        assertEquals("com.example.MainActivity", result.matches.first())
        assertTrue(result.display().contains("REA class-name search"))
        assertTrue(result.display().contains(sha))
        assertTrue(result.proposalEvidence().contains("BEGIN_UNTRUSTED_REA_CLASS_SEARCH"))
        assertTrue(result.proposalEvidence().contains("static observations"))
    }

    @Test fun forbidsCommandLikeQueriesAndChangesToApkIdentity() {
        for (query in listOf("", "a", "../Main", "--help", "a b",
            "a;id", "x".repeat(81), "Main\nInjected")) {
            assertFalse(ReaClassSearch.validQuery(query))
        }
        assertTrue(ReaClassSearch.validQuery("MainActivity"))
        assertTrue(ReaClassSearch.validQuery("com.example.Foo\$Inner"))
        assertThrows(Exception::class.java) {
            ReaClassSearch.validateResponse(response(), "0".repeat(64), "MainActivity")
        }
        assertThrows(Exception::class.java) {
            ReaClassSearch.validateResponse(response(), sha, "Other")
        }
    }

    @Test fun cannotPromoteInvalidOrOverclaimedClassInventory() {
        for (invalid in listOf(
            JSONObject(response()).put("match_count", 1),
            JSONObject(response()).put("truncated", true),
            JSONObject(response()).put("total_classes", -1),
            JSONObject(response()).put("scope", "dynamic-analysis"),
            JSONObject(response()).put("matches", JSONArray().put("bad\nname"))
        )) {
            assertThrows(Exception::class.java) {
                ReaClassSearch.validateResponse(invalid.toString(), sha, "MainActivity")
            }
        }
    }

    @Test fun boundedTruncatedResultsAreReportedAsPartial() {
        val body = JSONObject(response()).put("match_count", 4).put("truncated", true)
        val result = ReaClassSearch.validateResponse(body.toString(), sha, "MainActivity")
        assertEquals(4, result.matchCount)
        assertTrue(result.truncated)
        assertTrue(result.display().contains("omitted"))
    }
}
