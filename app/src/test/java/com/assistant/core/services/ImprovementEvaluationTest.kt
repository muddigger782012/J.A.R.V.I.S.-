package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class ImprovementEvaluationTest {
    @Test fun revisionTwoChecksAndTheirBaselineSurviveTheCompositionUpgradeWithoutLosingManualReports() = fixture { directory ->
        val evaluation = store(directory)
        evaluation.setEnabled(true)
        evaluation.record(ImprovementEvaluation.Scenario.RESEARCH, ImprovementEvaluation.Outcome.FAILED)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.BASELINE_LOCAL))
        val file = File(directory, "improvement-evaluation.json")
        val json = org.json.JSONObject(file.readText())
        for (key in listOf("localRun", "localBaseline")) {
            val run = json.getJSONObject(key); run.put("revision", 2)
            val rows = run.getJSONArray("results")
            run.put("results", org.json.JSONArray().also { out -> (0 until rows.length()).forEach { i ->
                if (rows.getJSONObject(i).getString("case") != "COMPOSITION") out.put(rows.getJSONObject(i))
            } })
        }
        file.writeText(json.toString())
        assertEquals(7, evaluation.snapshot().localRun!!.results.size)
        assertEquals(2, evaluation.snapshot().localBaseline!!.revision)
        assertEquals(ImprovementEvaluation.Outcome.FAILED, evaluation.snapshot().samples.single().outcome)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.COMPARE_LOCAL)).contains("revision changed"))
        assertEquals(7, evaluation.snapshot().localBaseline!!.results.size)
    }

    @Test fun previousLocalCheckRevisionSurvivesUpgradeWithoutLosingOwnerFeedbackOrBaseline() = fixture { directory ->
        val evaluation = store(directory)
        evaluation.setEnabled(true)
        evaluation.record(ImprovementEvaluation.Scenario.RECOVERY, ImprovementEvaluation.Outcome.FAILED)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.BASELINE_LOCAL))
        val file = File(directory, "improvement-evaluation.json")
        val json = org.json.JSONObject(file.readText())
        for (key in listOf("localRun", "localBaseline")) {
            val run = json.getJSONObject(key); run.put("revision", 1)
            val rows = run.getJSONArray("results")
            run.put("results", org.json.JSONArray().also { output -> (0 until 5).forEach { output.put(rows.getJSONObject(it)) } })
        }
        file.writeText(json.toString())
        assertEquals(1, evaluation.snapshot().localBaseline!!.revision)
        assertEquals(ImprovementEvaluation.Outcome.FAILED, evaluation.snapshot().samples.single().outcome)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.COMPARE_LOCAL)).contains("revision changed"))
        assertEquals(5, evaluation.snapshot().localBaseline!!.results.size)
    }
    private inline fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("improvement-evaluation").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun store(directory: File, build: Int = 435) = ImprovementEvaluation(
        PersonalStateFile(directory, "improvement-evaluation.json"), build, "3.5.$build") { 1_000L }

    @Test fun recordingIsOffUntilExplicitOptInAndPositiveExamplesAreNotFabricatedResults() = fixture { directory ->
        val evaluation = store(directory)
        assertFalse(evaluation.snapshot().enabled)
        assertFalse(evaluation.record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.PASSED))
        val review = evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.REVIEW))
        assertTrue(review.contains("owner-reported positive example"))
        assertTrue(evaluation.snapshot().samples.isEmpty())
        assertFalse(File(directory, "improvement-evaluation.json").exists())
    }

    @Test fun optInExplicitReportsDisableAndResetSurviveStoreReconstruction() = fixture { directory ->
        val first = store(directory)
        assertTrue(first.setEnabled(true))
        assertTrue(store(directory).record(ImprovementEvaluation.Scenario.GOAL_REVIEW, ImprovementEvaluation.Outcome.FAILED))
        assertEquals(ImprovementEvaluation.Outcome.FAILED, first.snapshot().samples.single().outcome)
        assertTrue(first.saveBaseline())
        assertTrue(store(directory).setEnabled(false))
        assertFalse(first.record(ImprovementEvaluation.Scenario.GOAL_REVIEW, ImprovementEvaluation.Outcome.PASSED))
        assertEquals(1, first.snapshot().samples.size)
        assertTrue(first.clear())
        assertEquals(ImprovementEvaluation.Snapshot(false, emptyList(), emptyList()), store(directory).snapshot())
    }

    @Test fun baselineComparisonPairsOnlyRetestedCasesAndKeepsRegressionsAndUncertaintyVisible() = fixture { directory ->
        val before = store(directory, 435)
        assertFalse(before.saveBaseline())
        assertTrue(before.setEnabled(true))
        before.record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.FAILED)
        before.record(ImprovementEvaluation.Scenario.CONVERSATION, ImprovementEvaluation.Outcome.PASSED)
        before.record(ImprovementEvaluation.Scenario.UPDATES, ImprovementEvaluation.Outcome.PASSED)
        before.record(ImprovementEvaluation.Scenario.MAPS, ImprovementEvaluation.Outcome.PASSED)
        assertTrue(before.saveBaseline())
        val after = store(directory, 436)
        after.record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.PASSED)
        after.record(ImprovementEvaluation.Scenario.CONVERSATION, ImprovementEvaluation.Outcome.FAILED)
        after.record(ImprovementEvaluation.Scenario.UPDATES, ImprovementEvaluation.Outcome.UNCLEAR)
        after.record(ImprovementEvaluation.Scenario.GREETING, ImprovementEvaluation.Outcome.PASSED)
        val comparison = after.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.COMPARE))
        assertTrue(comparison.contains("reported improvement"))
        assertTrue(comparison.contains("reported regression"))
        assertTrue(comparison.contains("uncertain; needs another checked result"))
        assertFalse(comparison.contains("maps discovery:"))
        assertFalse(comparison.contains("initial sir:"))
        assertTrue(comparison.contains("not measured reliability"))
        assertEquals(4, after.snapshot().baseline.size)
    }

    @Test fun repeatedReportsReplaceOneScenarioResultRatherThanInventingMoreIndependentEvidence() = fixture { directory ->
        val evaluation = store(directory)
        evaluation.setEnabled(true)
        repeat(3) { evaluation.record(ImprovementEvaluation.Scenario.IDENTITY, ImprovementEvaluation.Outcome.FAILED) }
        evaluation.record(ImprovementEvaluation.Scenario.IDENTITY, ImprovementEvaluation.Outcome.PASSED)
        assertEquals(1, evaluation.snapshot().samples.size)
        assertEquals(ImprovementEvaluation.Outcome.PASSED, evaluation.snapshot().samples.single().outcome)
    }

    @Test fun developerBriefLinksOneCheckedFailureToSyntheticTestsAndReviewedDeliveryWithoutClaimingExecution() = fixture { directory ->
        val evaluation = store(directory)
        val proposal = ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, ImprovementEvaluation.Scenario.CORRECTIONS)
        assertTrue(evaluation.reply(proposal).contains("before preparing"))
        evaluation.setEnabled(true)
        evaluation.record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.PASSED)
        assertTrue(evaluation.reply(proposal).contains("positive regression case"))
        evaluation.record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.FAILED)
        evaluation.setEnabled(false)
        val brief = evaluation.reply(proposal)
        assertTrue(brief.contains("Local reference: evaluation-435-corrections-1000"))
        assertTrue(brief.contains("ConversationMemory"))
        assertTrue(brief.contains("Verify the test fails before"))
        assertTrue(brief.contains("owner reported failed"))
        assertTrue(brief.contains("No patch, test run, pull request"))
        assertTrue(brief.contains("updater read token is not used"))
    }

    @Test fun malformedOrPrivateFreeTextIsNotAcceptedAsEvaluationDataOrAnExecutableInstruction() {
        for (text in listOf("record improvement feedback for my private conversation as failed",
            "record improvement feedback for correction handling as passed then reboot",
            "prepare improvement proposal for run arbitrary shell code", "yes", "enable improvement evaluation and upload it")) {
            assertNull(text, ImprovementEvaluation.command(text))
        }
        assertTrue(ImprovementEvaluation.isManagementInput("record improvement feedback for my private conversation as failed"))
        assertEquals(ImprovementEvaluation.Outcome.UNCLEAR,
            ImprovementEvaluation.command("Record improvement feedback for maps discovery as unclear!")!!.outcome)
    }

    @Test fun corruptOrFutureDatasetFailsClosedAndOnlyExplicitClearResetsConsent() = fixture { directory ->
        val file = File(directory, "improvement-evaluation.json")
        val evaluation = store(directory)
        for (invalid in listOf("broken", "{\"version\":1,\"dataset\":999}", " ".repeat(256_001))) {
            file.writeText(invalid)
            try { evaluation.setEnabled(true); fail("Must not overwrite corruption") } catch (_: Exception) { }
            assertEquals(invalid, file.readText())
            assertTrue(evaluation.clear())
            assertFalse(evaluation.snapshot().enabled)
        }
    }

    @Test fun boundedReportsKeepAnExplicitBaselineEvenWhenOldSamplesLeaveTheWindow() = fixture { directory ->
        val first = store(directory, 1)
        first.setEnabled(true)
        first.record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.FAILED)
        first.saveBaseline()
        for (build in 2..ImprovementEvaluation.MAX_SAMPLES + 4) {
            assertTrue(store(directory, build).record(ImprovementEvaluation.Scenario.CORRECTIONS, ImprovementEvaluation.Outcome.PASSED))
        }
        val snapshot = store(directory).snapshot()
        assertEquals(ImprovementEvaluation.MAX_SAMPLES, snapshot.samples.size)
        assertEquals(1, snapshot.baseline.single().build)
        assertFalse(snapshot.samples.any { it.build == 1 })
    }

    @Test fun concurrentInstancesPreserveDistinctCheckedCases() = fixture { directory ->
        store(directory).setEnabled(true)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val threads = ImprovementEvaluation.Scenario.values().map { scenario -> Thread {
            try { start.await(); check(store(directory).record(scenario, ImprovementEvaluation.Outcome.UNCLEAR)) }
            catch (error: Throwable) { failure.set(error) }
        }.also { it.start() } }
        start.countDown()
        threads.forEach { it.join(5_000); assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(ImprovementEvaluation.Scenario.values().toSet(), store(directory).snapshot().samples.map { it.scenario }.toSet())
    }

    @Test fun savedProposalsKeepOriginalEvidenceAcrossCorrectionRestartAndDismissal() = fixture { directory ->
        val evaluation = store(directory)
        val scenario = ImprovementEvaluation.Scenario.CORRECTIONS
        evaluation.setEnabled(true)
        evaluation.record(scenario, ImprovementEvaluation.Outcome.FAILED)
        val prepare = ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, scenario)
        val original = evaluation.reply(prepare)
        assertTrue(original.contains("saved locally"))
        assertEquals(1, store(directory).snapshot().proposals.size)
        assertEquals(original, store(directory).reply(prepare))
        evaluation.record(scenario, ImprovementEvaluation.Outcome.PASSED)
        val review = store(directory).reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.REVIEW_PROPOSAL, scenario))
        assertTrue(review.contains("owner reported failed"))
        assertTrue(review.contains("newer report supersedes this source (passed)"))
        assertTrue(evaluation.reply(prepare).contains("positive regression case"))
        val nextBuild = store(directory, 436)
        assertTrue(nextBuild.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.REVIEW_PROPOSAL, scenario)).contains("previous build"))
        assertTrue(nextBuild.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.DISMISS_PROPOSAL, scenario)).contains("Dismissed"))
        val retained = store(directory).snapshot().proposals.single()
        assertEquals(ImprovementEvaluation.ProposalStatus.DISMISSED, retained.status)
        assertEquals(ImprovementEvaluation.Outcome.FAILED, retained.source.outcome)
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSALS)).contains("dismissed"))
    }

    @Test fun proposalCapacityNeverSilentlyOverwritesEvidenceAndExplicitClearPreservesEvaluation() = fixture { directory ->
        store(directory).setEnabled(true)
        for (build in 1..ImprovementEvaluation.MAX_PROPOSALS) {
            val evaluation = store(directory, build)
            evaluation.record(ImprovementEvaluation.Scenario.UPDATES, ImprovementEvaluation.Outcome.UNCLEAR)
            evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, ImprovementEvaluation.Scenario.UPDATES))
        }
        val next = store(directory, 100)
        next.record(ImprovementEvaluation.Scenario.UPDATES, ImprovementEvaluation.Outcome.FAILED)
        next.saveBaseline()
        val before = next.snapshot()
        assertEquals(ImprovementEvaluation.MAX_PROPOSALS, before.proposals.size)
        assertTrue(next.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, ImprovementEvaluation.Scenario.UPDATES)).contains("full"))
        assertEquals(before, next.snapshot())
        next.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.CLEAR_PROPOSALS))
        assertEquals(before.copy(proposals = emptyList()), next.snapshot())
        next.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, ImprovementEvaluation.Scenario.UPDATES))
        assertEquals(1, next.snapshot().proposals.size)
        assertTrue(next.clear())
        assertTrue(next.snapshot().proposals.isEmpty())
        assertFalse(next.snapshot().enabled)
    }

    @Test fun legacyEvaluationMigratesWithoutChangingConsentReportsOrBaseline() = fixture { directory ->
        val evaluation = store(directory)
        evaluation.setEnabled(true)
        evaluation.record(ImprovementEvaluation.Scenario.RECOVERY, ImprovementEvaluation.Outcome.FAILED)
        evaluation.saveBaseline()
        val before = evaluation.snapshot()
        val file = File(directory, "improvement-evaluation.json")
        val legacy = org.json.JSONObject(file.readText()).put("version", 1).also { it.remove("proposals") }
        file.writeText(legacy.toString())
        assertEquals(before, store(directory).snapshot())
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, ImprovementEvaluation.Scenario.RECOVERY))
        assertEquals(before.samples, evaluation.snapshot().samples)
        assertEquals(before.baseline, evaluation.snapshot().baseline)
        assertTrue(evaluation.snapshot().enabled)
        assertEquals(3, org.json.JSONObject(file.readText()).getInt("version"))
    }

    @Test fun proposalControlsAcceptOnlyKnownScenariosAndCannotApproveCodeExecution() {
        assertEquals(ImprovementEvaluation.Action.PROPOSALS, ImprovementEvaluation.command("review improvement proposals")!!.action)
        assertEquals(ImprovementEvaluation.Action.REVIEW_PROPOSAL, ImprovementEvaluation.command("review improvement proposal for maps discovery")!!.action)
        assertEquals(ImprovementEvaluation.Action.DISMISS_PROPOSAL, ImprovementEvaluation.command("dismiss improvement proposal for update status")!!.action)
        for (text in listOf("review improvement proposal for my private transcript", "dismiss improvement proposal for maps discovery then reboot", "apply improvement proposal for maps discovery")) {
            assertNull(ImprovementEvaluation.command(text))
        }
        assertTrue(ImprovementEvaluation.isManagementInput("review improvement proposal for my private transcript"))
    }

    @Test fun localChecksRequireOptInPersistActualResultsAndRemainSeparateFromOwnerReports() = fixture { directory ->
        val evaluation = store(directory)
        val run = ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL)
        assertTrue(evaluation.reply(run).contains("No checks were run"))
        assertNull(evaluation.snapshot().localRun)
        evaluation.setEnabled(true)
        evaluation.record(ImprovementEvaluation.Scenario.RECOVERY, ImprovementEvaluation.Outcome.UNCLEAR)
        val reports = evaluation.snapshot().samples
        val response = evaluation.reply(run)
        assertTrue(response.contains("counter dispatchers"))
        assertTrue(response.contains("No phone action"))
        assertTrue(store(directory).snapshot().localRun!!.results.all { it.outcome == LocalFoundationChecks.Outcome.PASSED })
        assertEquals(reports, evaluation.snapshot().samples)
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.BASELINE_LOCAL)).contains("Saved the actual"))
        store(directory, 436).reply(run)
        val compared = store(directory, 436).reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.COMPARE_LOCAL))
        assertTrue(compared.contains("3.5.435 to 3.5.436"))
        assertTrue(compared.contains("passed -> passed; unchanged"))
        val beforeClear = evaluation.snapshot()
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.CLEAR_LOCAL))
        assertEquals(beforeClear.copy(localRun = null, localBaseline = null), evaluation.snapshot())
        evaluation.setEnabled(false)
        assertTrue(evaluation.reply(run).contains("No checks were run"))
    }

    @Test fun localComparisonsPreserveFailedResultsAndRefuseMismatchedCheckRevisions() = fixture { directory ->
        val evaluation = store(directory)
        evaluation.setEnabled(true)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.BASELINE_LOCAL))
        val file = File(directory, "improvement-evaluation.json")
        val json = org.json.JSONObject(file.readText())
        json.getJSONObject("localRun").getJSONArray("results").getJSONObject(0).put("outcome", "FAILED")
        file.writeText(json.toString())
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.COMPARE_LOCAL)).contains("regression or incomplete check"))
        json.getJSONObject("localRun").put("revision", LocalFoundationChecks.REVISION + 1)
        file.writeText(json.toString())
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.COMPARE_LOCAL)).contains("revision changed"))
        json.getJSONObject("localRun").getJSONArray("results").getJSONObject(0).put("case", "UNREVIEWED_INPUT")
        file.writeText(json.toString())
        try { evaluation.snapshot(); fail("Unknown persisted check must fail closed") } catch (_: IllegalArgumentException) { }
        assertTrue(evaluation.clear())
        assertFalse(evaluation.snapshot().enabled)
    }

    @Test fun installedVersionTwoBriefsAndConsentSurviveLocalCheckMigrationWithoutAutomaticRuns() = fixture { directory ->
        val evaluation = store(directory)
        evaluation.setEnabled(true)
        evaluation.record(ImprovementEvaluation.Scenario.UPDATES, ImprovementEvaluation.Outcome.FAILED)
        evaluation.saveBaseline()
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, ImprovementEvaluation.Scenario.UPDATES))
        evaluation.setEnabled(false)
        val before = evaluation.snapshot()
        val file = File(directory, "improvement-evaluation.json")
        val versionTwo = org.json.JSONObject(file.readText()).put("version", 2)
        file.writeText(versionTwo.toString())
        assertEquals(before, store(directory).snapshot())
        assertTrue(evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL)).contains("No checks were run"))
        assertEquals(versionTwo.toString(), file.readText())
        evaluation.setEnabled(true)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        assertEquals(before.proposals, evaluation.snapshot().proposals)
        assertEquals(before.samples, evaluation.snapshot().samples)
        assertEquals(before.baseline, evaluation.snapshot().baseline)
        assertTrue(evaluation.clear())
        assertEquals(ImprovementEvaluation.Snapshot(false, emptyList(), emptyList()), evaluation.snapshot())
    }

    @Test fun explicitHandoffContainsSavedEvidenceAndSeparateLocalChecksWithoutSubmittingOrMutatingAnything() = fixture { directory ->
        val evaluation = store(directory)
        val scenario = ImprovementEvaluation.Scenario.RECOVERY
        assertNull(evaluation.handoff(scenario))
        evaluation.setEnabled(true)
        evaluation.record(scenario, ImprovementEvaluation.Outcome.FAILED)
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, scenario))
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
        evaluation.record(scenario, ImprovementEvaluation.Outcome.PASSED)
        val before = evaluation.snapshot()
        val artifact = evaluation.handoff(scenario)!!
        assertTrue(artifact.contains("owner reported failed"))
        assertTrue(artifact.contains("newer report supersedes this source (passed)"))
        assertTrue(artifact.contains("Separate local-check snapshot"))
        assertTrue(artifact.contains("counter dispatchers"))
        assertTrue(artifact.contains("no raw conversation"))
        assertEquals(before, evaluation.snapshot())
        evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.DISMISS_PROPOSAL, scenario))
        assertNull(evaluation.handoff(scenario))
        assertEquals(ImprovementEvaluation.Action.SHARE_PROPOSAL, ImprovementEvaluation.command("share improvement proposal for routine recovery")!!.action)
        assertNull(ImprovementEvaluation.command("share improvement proposal for routine recovery then send it"))
        assertTrue(ImprovementEvaluation.isManagementInput("share improvement proposal for unknown private details"))
    }
}
