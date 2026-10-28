package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class ImprovementProposalEvidenceTest {
    private class Fixture(val directory: File) {
        var time = 1000L
        val scenario = ImprovementEvaluation.Scenario.RECOVERY
        val path get() = File(directory, "improvement-evaluation.json")
        fun store(build: Int = 479) = ImprovementEvaluation(PersonalStateFile(directory, path.name), build, "3.5.$build") { time }
        fun reply(action: ImprovementEvaluation.Action, number: Int? = null) = store().reply(
            ImprovementEvaluation.Command(action, if (number == null) scenario else null, number = number))
        fun handoff(number: Int) = store().handoff(ImprovementEvaluation.Command(ImprovementEvaluation.Action.SHARE_PROPOSAL, number = number))
        fun start() { store().setEnabled(true); store().record(scenario, ImprovementEvaluation.Outcome.FAILED) }
        fun changeRun(outcome: String) {
            val json = JSONObject(path.readText())
            json.getJSONObject("localRun").getJSONArray("results").getJSONObject(0).put("outcome", outcome)
            path.writeText(json.toString())
        }
    }
    private inline fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("proposal-evidence").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun clearingCurrentChecksCannotEraseTheChecksCapturedWithASavedProposal() {
        val directory = Files.createTempDirectory("proposal-evidence").toFile()
        try {
            fun store() = ImprovementEvaluation(PersonalStateFile(directory, "improvement-evaluation.json"), 479, "3.5.479") { 1000L }
            val evaluation = store()
            val scenario = ImprovementEvaluation.Scenario.RECOVERY
            evaluation.setEnabled(true)
            evaluation.record(scenario, ImprovementEvaluation.Outcome.FAILED)
            evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.RUN_LOCAL))
            evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, scenario))
            evaluation.reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.CLEAR_LOCAL))
            assertNull(store().snapshot().localRun)
            assertTrue("Saved proposal must retain the executed synthetic checks after the current check dataset is cleared",
                store().handoff(scenario)!!.contains("counter dispatchers"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun refreshRetainsTheOriginalRunBaselineAndOwnerReportWhileSavingNewEvidenceSeparately() = fixture { f ->
        f.start()
        f.reply(ImprovementEvaluation.Action.RUN_LOCAL); f.reply(ImprovementEvaluation.Action.BASELINE_LOCAL)
        f.store().saveBaseline()
        f.changeRun("FAILED")
        val original = f.reply(ImprovementEvaluation.Action.PROPOSAL)
        assertTrue(original.contains("passed -> failed; regression or incomplete check"))
        val frozen = f.store().snapshot().proposals.single()
        f.time++
        f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL)
        assertEquals(listOf(1, 2), f.store().snapshot().proposals.map { it.number })
        assertEquals(frozen, f.store().snapshot().proposals.first())
        assertTrue(f.handoff(1)!!.contains("passed -> failed; regression or incomplete check"))
        assertTrue(f.handoff(2)!!.contains("passed -> passed; unchanged"))
        assertTrue(f.handoff(2)!!.contains("Passing local checks do not resolve an owner-reported failure"))
        f.reply(ImprovementEvaluation.Action.CLEAR_LOCAL)
        assertEquals(frozen, f.store().snapshot().proposals.first())
        assertEquals(frozen.evidence!!.localBaseline, f.store().snapshot().proposals.last().evidence!!.localBaseline)
    }

    @Test fun laterChecksCannotBackfillAnEarlierBriefAndRepeatedRefreshDoesNotInventMoreEvidence() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.PROPOSAL)
        f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        f.reply(ImprovementEvaluation.Action.PROPOSAL)
        assertEquals(1, f.store().snapshot().proposals.size)
        assertFalse(f.handoff(1)!!.contains("counter dispatchers"))
        f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL)
        val before = f.store().snapshot()
        f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL)
        assertEquals(before, f.store().snapshot())
        assertEquals(2, before.proposals.size)
        assertTrue(f.handoff(2)!!.contains("counter dispatchers"))
    }

    @Test fun archiveNumbersSelectOlderBuildsAndDismissOnlyTheChosenBrief() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.PROPOSAL)
        f.time++
        f.store(480).record(f.scenario, ImprovementEvaluation.Outcome.UNCLEAR)
        f.store(480).reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, f.scenario))
        assertTrue(f.handoff(1)!!.contains("owner reported failed on 3.5.479"))
        assertTrue(f.handoff(2)!!.contains("owner reported unclear on 3.5.480"))
        f.reply(ImprovementEvaluation.Action.DISMISS_PROPOSAL, number = 1)
        assertNull(f.handoff(1)); assertNotNull(f.handoff(2))
        assertTrue(f.reply(ImprovementEvaluation.Action.REVIEW_PROPOSAL, number = 1).contains("Saved status: dismissed"))
        assertTrue(f.reply(ImprovementEvaluation.Action.PROPOSALS).contains("Proposal 2"))
    }

    @Test fun clearingBriefsDoesNotReuseNumbersOrLoseManualReportsAndChecks() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        f.reply(ImprovementEvaluation.Action.PROPOSAL)
        val before = f.store().snapshot()
        f.reply(ImprovementEvaluation.Action.CLEAR_PROPOSALS)
        assertEquals(before.copy(proposals = emptyList()), f.store().snapshot())
        f.reply(ImprovementEvaluation.Action.PROPOSAL)
        assertEquals(2, f.store().snapshot().proposals.single().number)
        assertNull(f.handoff(1)); assertTrue(f.reply(ImprovementEvaluation.Action.REVIEW_PROPOSAL, 1).contains("No matching"))
    }

    @Test fun versionThreeMigratesLazilyWithoutRetroactiveEvidenceOrChangingConsent() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.RUN_LOCAL); f.reply(ImprovementEvaluation.Action.PROPOSAL)
        f.store().setEnabled(false)
        val old = JSONObject(f.path.readText()).put("version", 3)
        old.remove("nextProposal")
        old.getJSONArray("proposals").getJSONObject(0).also { it.remove("number"); it.remove("evidence") }
        f.path.writeText(old.toString())
        val legacy = f.store().snapshot()
        assertNull(legacy.proposals.single().evidence); assertFalse(legacy.enabled)
        assertTrue(f.handoff(1)!!.contains("Legacy brief")); assertFalse(f.handoff(1)!!.contains("counter dispatchers"))
        assertEquals(old.toString(), f.path.readText())
        f.store().setEnabled(false) // A normal state write upgrades the format without running checks.
        assertEquals(legacy, f.store().snapshot())
        assertEquals(true, JSONObject(f.path.readText()).getJSONArray("proposals").getJSONObject(0).getBoolean("legacyEvidence"))
        f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL)
        assertFalse(f.store().snapshot().enabled)
        assertNull(f.store().snapshot().proposals.first().evidence)
        assertEquals(legacy.localRun, f.store().snapshot().proposals.last().evidence!!.localRun)
    }

    @Test fun corruptFrozenEvidenceAndNumberingFailClosedWithoutChangingTheStoredDataset() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.RUN_LOCAL); f.reply(ImprovementEvaluation.Action.PROPOSAL)
        val original = f.path.readText()
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("proposals").getJSONObject(0).remove("evidence") },
            { it.put("nextProposal", 1) },
            { it.getJSONArray("proposals").getJSONObject(0).put("number", 0) },
            { it.getJSONArray("proposals").getJSONObject(0).getJSONObject("evidence").getJSONObject("localRun").getJSONArray("results").getJSONObject(0).put("case", "UNREVIEWED_CASE") },
            { it.getJSONArray("proposals").getJSONObject(0).getJSONObject("evidence").getJSONObject("localRun").put("time", 1001L) },
            { it.put("version", 5) }
        )
        for (corrupt in corruptions) {
            val json = JSONObject(original); corrupt(json); val raw = json.toString(); f.path.writeText(raw)
            assertTrue(runCatching { f.store().snapshot() }.isFailure)
            assertEquals(raw, f.path.readText())
        }
    }

    @Test fun failedRefreshWriteRetainsThePreviousBriefAndDoesNotConsumeItsNextNumber() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.PROPOSAL); f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        val before = f.store().snapshot(); val handoff = f.handoff(1)
        val temporary = File(f.path.path + ".tmp").apply { mkdir() }
        assertTrue(runCatching { f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL) }.isFailure)
        assertEquals(before, f.store().snapshot()); assertEquals(handoff, f.handoff(1))
        temporary.delete(); f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL)
        assertEquals(2, f.store().snapshot().proposals.last().number)
    }

    @Test fun fullArchiveNeverOverwritesEarlierEvidenceWhenRefreshed() = fixture { f ->
        f.start()
        for (n in 1..ImprovementEvaluation.MAX_PROPOSALS) {
            f.time++; f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
            f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL)
        }
        f.time++; f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        val before = f.store().snapshot()
        assertEquals(ImprovementEvaluation.MAX_PROPOSALS, before.proposals.size)
        assertTrue(f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL).contains("full"))
        assertEquals(before, f.store().snapshot())
    }

    @Test fun mismatchedCheckRevisionsRemainUnpairedAndPreserveBothRuns() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.RUN_LOCAL); f.reply(ImprovementEvaluation.Action.BASELINE_LOCAL)
        val json = JSONObject(f.path.readText()); val baseline = json.getJSONObject("localBaseline")
        baseline.put("revision", 2)
        val rows = baseline.getJSONArray("results")
        baseline.put("results", JSONArray().also { output -> (0 until rows.length()).forEach { i ->
            if (rows.getJSONObject(i).getString("case") != "COMPOSITION") output.put(rows.getJSONObject(i))
        } })
        f.path.writeText(json.toString()); f.reply(ImprovementEvaluation.Action.PROPOSAL)
        val brief = f.handoff(1)!!
        assertTrue(brief.contains("revision changed or is unsupported")); assertFalse(brief.contains("passed -> passed"))
        assertEquals(2, f.store().snapshot().proposals.single().evidence!!.localBaseline!!.revision)
        assertEquals(3, f.store().snapshot().proposals.single().evidence!!.localRun!!.revision)
    }

    @Test fun futureDatedSourceIsRefusedAndDifferentBuildChecksAreExplicitlyLabeled() = fixture { f ->
        f.start(); val before = f.store().snapshot(); f.time--
        assertTrue(f.reply(ImprovementEvaluation.Action.PROPOSAL).contains("current device time"))
        assertEquals(before, f.store().snapshot()); f.time += 2
        f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        f.store(480).record(f.scenario, ImprovementEvaluation.Outcome.FAILED)
        f.store(480).reply(ImprovementEvaluation.Command(ImprovementEvaluation.Action.PROPOSAL, f.scenario))
        assertTrue(f.handoff(1)!!.contains("different build than the source report"))
    }

    @Test fun concurrentRefreshesDeduplicateTheSameSourceAndChecksWithoutLosingNumbers() = fixture { f ->
        f.start(); f.reply(ImprovementEvaluation.Action.PROPOSAL); f.reply(ImprovementEvaluation.Action.RUN_LOCAL)
        val gate = CountDownLatch(1); val error = AtomicReference<Throwable>()
        val workers = (1..2).map { Thread {
            try { gate.await(); f.reply(ImprovementEvaluation.Action.REFRESH_PROPOSAL) } catch (t: Throwable) { error.set(t) }
        }.apply { start() } }
        gate.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }; error.get()?.let { throw AssertionError(it) }
        assertEquals(listOf(1, 2), f.store().snapshot().proposals.map { it.number })
        assertEquals(3, f.store().snapshot().nextProposal)
    }

    @Test fun numberedAndRefreshControlsAreReservedAndMalformedCommandsCannotReachLearning() {
        for (text in listOf("refresh improvement proposal for routine recovery", "review improvement proposal 1", "share improvement proposal 2", "dismiss improvement proposal 3", "review improvement proposal number one", "share improvement proposal two")) {
            assertNotNull(ImprovementEvaluation.command(text)); assertTrue(LearnedCommandReviewCommands.isReserved(text))
        }
        for (text in listOf("refresh improvement proposal for routine recovery then reboot", "share improvement proposal 1 then search the service manual", "review improvement proposal 0", "share improvement proposal -1")) {
            assertNull(ImprovementEvaluation.command(text)); assertTrue(ImprovementEvaluation.isManagementInput(text))
        }
    }
}
