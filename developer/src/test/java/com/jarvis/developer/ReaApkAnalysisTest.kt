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
            .put("truncated", false)
            .put("archive_entries", 2)
            .put("archive_uncompressed_bytes", 16384).toString()

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

    @Test fun healthProbeContractDistinguishesAuthenticatedBridgeFromInstalledEngine() {
        assertEquals("https://trusted.example.com:8765/v1/health",
            ReaApkAnalysis.healthEndpoint("https://trusted.example.com:8765"))
        val healthy = JSONObject().put("schema", "jarvis.rea.health.v1")
            .put("state", "BRIDGE_REACHABLE").put("engine", "DOCTOR_PASSED").toString()
        assertTrue(ReaApkAnalysis.validateHealth(healthy).contains("doctor passed"))
        assertTrue(ReaApkAnalysis.validateHealth(healthy).contains("not been tested"))
        val failed = JSONObject(healthy).put("engine", "DOCTOR_FAILED").toString()
        assertTrue(ReaApkAnalysis.validateHealth(failed).contains("doctor failed"))
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateHealth(JSONObject(healthy)
                .put("engine", "APK_ANALYSIS_PROVEN").toString())
        }
        assertThrows(Exception::class.java) {
            ReaApkAnalysis.validateHealth(JSONObject(healthy)
                .put("schema", "unexpected").toString())
        }
        assertThrows(Exception::class.java) { ReaApkAnalysis.validateHealth("x".repeat(2049)) }
    }

    @Test fun acceptedEvidenceIsBoundToSelectedApkAndExplicitlyStatic() {
        val report = ReaApkAnalysis.validateResponse(response(), digest)
        assertEquals(digest, report.apkSha256)
        assertEquals("b".repeat(64), report.evidenceSha256)
        assertTrue(report.display().contains("APK SHA-256: $digest"))
        assertTrue(report.display().contains("not executable verification"))
        assertFalse(report.truncated)
        assertEquals(2, report.archiveEntries)
        assertEquals(16384L, report.archiveDeclaredBytes)
        assertTrue(report.display().contains("declared uncompressed bytes"))
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

    @Test fun archivePreflightClaimsMustBeBoundedAndPaired() {
        val missing = JSONObject(response()).apply { remove("archive_uncompressed_bytes") }
        val excessive = JSONObject(response()).put("archive_entries", 20001)
        val negative = JSONObject(response()).put("archive_uncompressed_bytes", -1)
        for (bad in listOf(missing, excessive, negative)) {
            assertThrows(Exception::class.java) {
                ReaApkAnalysis.validateResponse(bad.toString(), digest)
            }
        }
        val backwardsCompatible = JSONObject(response()).apply {
            remove("archive_entries")
            remove("archive_uncompressed_bytes")
        }
        assertNull(ReaApkAnalysis.validateResponse(backwardsCompatible.toString(), digest).archiveEntries)
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
