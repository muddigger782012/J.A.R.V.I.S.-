package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class ImprovementEvaluationTest {
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
}
