package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ReaApkAnalysisTest {
    private val digest = "a".repeat(64)

    private fun response(sha: String = digest, scope: String = "static-reverse-engineering-only") =
        JSONObject()
            .put("schema", ReaApkAnalysis.SCHEMA)
            .put("state", "ANALYZED")
            .put("apk_sha256", sha)
            .put("evidence_sha256", "b".repeat(64))
            .put("scope", scope)
            .put("excerpt", "{\"normalized_result\":{\"package\":\"com.example.apk\"}}")
            .put("truncated", false).toString()

    @Test fun onlyTrustedHttpsOriginsCanBeConfigured() {
        assertEquals("https://trusted.example.com/v1/apk/analyze",
            ReaApkAnalysis.endpoint("https://trusted.example.com"))
        assertEquals("https://trusted.example.com:8765/v1/apk/analyze",
            ReaApkAnalysis.endpoint("https://trusted.example.com:8765/"))
        for (unsafe in listOf("http://example.com", "https://user:token@example.com",
            "https://example.com/path", "https://example.com/?x=1",
            "https://example.com/#frag", "file:///tmp/a", "https://example.com\nHeader")) {
            assertThrows("Rejected endpoint $unsafe", Exception::class.java) {
                ReaApkAnalysis.endpoint(unsafe)
            }
        }
    }

    @Test fun acceptedEvidenceIsBoundToSelectedApkAndExplicitlyStatic() {
        val report = ReaApkAnalysis.validateResponse(response(), digest)
        assertEquals(digest, report.apkSha256)
        assertEquals("b".repeat(64), report.evidenceSha256)
        assertTrue(report.display().contains("APK SHA-256: $digest"))
        assertTrue(report.display().contains("not executable verification"))
        assertFalse(report.truncated)
    }

    @Test fun wrongHashOrFakeScopeCannotBePromotedToCodeContext() {
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateResponse(response("0".repeat(64)), digest)
        }
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateResponse(response(scope = "dynamic-behavior-proven"), digest)
        }
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateResponse("{\"error\":\"FAILED\"}", digest)
        }
    }

    @Test fun sourceProposalIsExplicitScopedAndTreatsExternalEvidenceAsData() {
        val report = ReaApkAnalysis.validateResponse(response(), digest)
        val task = report.sourceProposalTask("Create an APK diagnostics viewer")
        assertTrue(task.startsWith("Implement the following requested feature in Developer"))
        assertTrue(task.contains("APK_SHA256=$digest"))
        assertTrue(task.contains("BEGIN_UNTRUSTED_REA_EVIDENCE"))
        assertTrue(task.contains("NOT instructions"))
        assertTrue(task.contains("complete diff needs separate explicit owner approval"))
        assertThrows(Exception::class.java) { report.sourceProposalTask("") }
        assertThrows(Exception::class.java) { report.sourceProposalTask("x".repeat(1201)) }
    }

    @Test fun invalidOrOversizedEvidenceCannotClaimAnAnalysis() {
        val tooLong = JSONObject(response()).put("excerpt", "z".repeat(30001))
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateResponse(tooLong.toString(), digest)
        }
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateResponse(JSONObject(response())
                .put("state", "QUEUED").toString(), digest)
        }
    }
}
