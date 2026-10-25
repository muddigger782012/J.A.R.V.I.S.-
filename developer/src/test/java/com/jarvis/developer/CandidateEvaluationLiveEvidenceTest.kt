package com.jarvis.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Historical observations copied from completed CI jobs, not modeled package facts.
 * Replaying these records does not recheck today's moving release channels or APK bytes.
 */
class CandidateEvaluationLiveEvidenceTest {
    @Test fun recordedSignedRecoveryStillBlocksPromotionWithoutPreviousBackupOrTaskIsolation() {
        val evidence = CandidateEvaluation.parseEvidence(resource("live-candidate-blocked.json"),
            CandidateEvaluation.Expected(SOURCE_SHA, CandidateEvaluation.CONTROLLER_BRANCH,
                SOURCE_SHA, EVALUATION_RUN, 1))
        assertEquals("BLOCKED", evidence.status)
        assertEquals(SOURCE_SHA, evidence.source.baseCommitSha)
        assertFalse(evidence.source.branchIsolated)
        assertTrue(evidence.source.changedPaths.isEmpty())
        assertEquals(CandidateEvaluation.PACKAGE_NAME, evidence.candidate.packageName)
        assertTrue(evidence.tests.passed)
        assertEquals(272, evidence.tests.tests)
        assertEquals(0, evidence.tests.failures)
        assertEquals(0, evidence.tests.errors)
        assertEquals(0, evidence.tests.skipped)
        assertEquals(0, evidence.tests.lintErrors)

        val knownGood = requireNotNull(evidence.baseline)
        assertEquals("213d202a198da00f71acc750fcb20ac7a38e0924", knownGood.sourceCommitSha)
        assertEquals(CandidateEvaluation.PACKAGE_NAME, knownGood.packageName)
        assertEquals(53L, knownGood.versionCode)
        assertEquals("e1826417d32a2ad4c591a1580a4b184f0e06b79ef4200233990144ddf1af6969", knownGood.apkSha256)
        assertEquals(1_645_395L, knownGood.apkBytes)
        assertEquals("93a2f2031692f947440f4cfd874a166609cd2735eeda28e60904da64e8245790", knownGood.signerSha256)
        assertNull(evidence.backup)
        assertTrue(evidence.missingReasons.contains("BACKUP_MISSING"))

        val recovery = requireNotNull(evidence.rebuild)
        assertTrue(recovery.verified)
        assertEquals(knownGood.sourceCommitSha, recovery.facts.sourceCommitSha)
        assertEquals(knownGood.packageName, recovery.facts.packageName)
        assertEquals(knownGood.signerSha256, recovery.facts.signerSha256)
        assertEquals(knownGood.signerSha256, evidence.candidate.signerSha256)
        assertTrue(evidence.candidate.versionCode > knownGood.versionCode)
        assertTrue(recovery.facts.versionCode > evidence.candidate.versionCode)
        assertNotEquals(knownGood.apkSha256, recovery.facts.apkSha256)
        assertNotEquals(evidence.candidate.apkSha256, recovery.facts.apkSha256)

        val report = BuildDiagnostics.parse(SOURCE_SHA, CandidateEvaluation.CONTROLLER_BRANCH,
            DEBUG_RUN, 1, "completed", "success", "JARVIS_JUNIT_SUMMARY=" + resource("live-debug-summary.json"))
        assertTrue(report.evidenceComplete)
        assertEquals(272, report.tests.size)
        assertTrue(report.tests.values.all { it == BuildDiagnostics.TestState.PASSED })
        // This run bootstrapped the controller itself, so the exact debug record is
        // both comparison inputs. Passing CI cannot invent task-branch isolation.
        val review = CandidateEvaluation.assess(report, report, evidence, 1_000L)
        assertFalse(review.eligible)
        assertTrue(review.blockers.any { it.contains("previous-release backup is missing") })
        assertTrue(review.blockers.any { it.contains("isolated task branch") })
        try {
            CandidateEvaluation.confirm(review, review.fingerprint, true, 1_001L)
            fail("Historical passing build and signed recovery cannot bypass missing backup or isolation")
        } catch (_: IllegalArgumentException) {
            // The production gate refused metadata promotion even with exact confirmation.
        } catch (_: IllegalStateException) {
            // A refused review may never be reused as a capability.
        }
    }

    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/phase5/$name")) {
        "The exact completed-job evidence snapshot must be retained with this regression"
    }.use { it.bufferedReader(Charsets.UTF_8).readText() }

    companion object {
        private const val SOURCE_SHA = "6b9997a39d86e88f56375e4fcd4d937509889b79"
        private const val EVALUATION_RUN = 39_034_767_928L
        private const val DEBUG_RUN = 39_034_767_966L
    }
}
