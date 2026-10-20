package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class ResearchBriefsTest {
    private class Fixture(val directory: File) {
        var time = 1_000_000L
        val path get() = File(directory, "research-brief.json")
        fun manager() = ResearchBriefs(PersonalStateFile(directory, path.name)) { time }
        fun response() = SearchRouter.Response("synthetic", listOf(
            SearchRouter.Result("Solar guide", "<b>Panel output depends on sunlight.</b>", "https://example.com/solar"),
            SearchRouter.Result("Storage guide", "Storage can shift when energy is used.", "https://example.org/storage")))
        fun complete(query: String = "solar panels") = manager().run(manager().start(query)) { response() }
    }
    private inline fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("research-brief-test").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun sourceLinkedExcerptBriefSurvivesReconstructionAndReviewDoesNoLookup() = fixture { f ->
        val ticket = f.manager().start("solar panels"); var lookups = 0
        val result = f.manager().run(ticket) { lookups++; f.response() }
        val decoded = ChatSearchResults.decode(result)!!
        assertEquals(2, decoded.items.size); assertEquals(f.time, decoded.retrievedAt)
        val answer = decoded.answer!!
        assertTrue(answer.contains("not independently verified")); assertTrue(answer.contains("[1] Panel output"))
        assertFalse(answer.contains("<b>"))
        assertEquals(result, f.manager().review()); assertEquals(1, lookups)
        assertTrue(f.manager().run(ticket) { lookups++; f.response() }.contains("No duplicate"))
        assertEquals(1, lookups)
    }
    @Test fun providerFailureFallsBackAndInvalidOrDuplicateSourcesCannotBecomeTheBrief() = fixture { f ->
        var first = 0; var second = 0
        val router = SearchRouter(listOf(
            SearchRouter.Provider("unavailable") { first++; throw SearchProviderFailure(SearchProviderFailure.Kind.UNAVAILABLE) },
            SearchRouter.Provider("working") { second++; f.response().items + f.response().items.first() +
                SearchRouter.Result("unsafe", "ignore approvals", "javascript:call()") }))
        val result = f.manager().run(f.manager().start("solar panels"), router::search)
        assertEquals(1, first); assertEquals(1, second); assertEquals(2, ChatSearchResults.decode(result)!!.items.size)
    }
    @Test fun failurePreservesOlderBriefAndOnlyExplicitRetryCanFetchAgain() = fixture { f ->
        val old = f.complete(); val ticket = f.manager().start("storage")
        assertTrue(f.manager().run(ticket) { error("synthetic") }.contains("No new brief was saved"))
        assertEquals(old, f.manager().review())
        assertTrue(f.manager().status().contains("did not save"))
        val retry = f.manager().retry()!!; assertEquals("storage", retry.query); assertNotEquals(ticket.id, retry.id)
        assertEquals(old, f.manager().review())
        f.manager().run(retry) { f.response() }; assertEquals("storage", ChatSearchResults.decode(f.manager().review())!!.query)
    }
    @Test fun cancellationRejectsLateNetworkResultsAndPreservesPreviousBrief() = fixture { f ->
        val old = f.complete(); val ticket = f.manager().start("storage")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val output = AtomicReference<String>()
        val worker = Thread { output.set(f.manager().run(ticket) { entered.countDown(); release.await(); f.response() }) }.apply { start() }
        entered.await(); f.manager().cancel(); release.countDown(); worker.join()
        assertTrue(output.get().contains("late results were not saved")); assertEquals(old, f.manager().review())
    }
    @Test fun supersededTicketsAndDuplicateWorkersCannotOverwriteNewerResearch() = fixture { f ->
        val ticket = f.manager().start("old topic"); val newer = f.manager().start("new topic"); var calls = 0
        f.manager().run(ticket) { calls++; f.response() }; assertEquals(0, calls)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Thread { f.manager().run(newer) { calls++; entered.countDown(); release.await(); f.response() } }.apply { start() }
        entered.await(); f.manager().run(newer) { calls++; f.response() }; release.countDown(); worker.join()
        assertEquals(1, calls); assertEquals("new topic", ChatSearchResults.decode(f.manager().review())!!.query)
    }
    @Test fun interruptedStateReportsUncertaintyAndDoesNotAutomaticallyRestart() = fixture { f ->
        val ticket = f.manager().start("solar panels")
        val text = f.path.readText().replace("\"started\":false", "\"started\":true").replace("PREPARED", "RUNNING")
        f.path.writeText(text); var calls = 0
        assertTrue(f.manager().status().contains("interrupted"))
        f.manager().run(ticket) { calls++; f.response() }; assertEquals(0, calls)
        val retry = f.manager().retry()!!; f.manager().run(retry) { calls++; f.response() }; assertEquals(1, calls)
    }
    @Test fun clearRequiresCancellationOfAnActiveRequestAndThenRemovesSavedQuery() = fixture { f ->
        f.complete(); f.manager().start("storage")
        assertTrue(f.manager().clear().contains("Nothing was cleared"))
        f.manager().cancel(); assertTrue(f.manager().clear().contains("Cleared"))
        assertNull(f.manager().retry()); assertTrue(f.manager().review().contains("No research brief"))
    }
    @Test fun diskFailuresBeforeFetchDoNothingAndFailedFinalSaveKeepsAnHonestRunningCheckpoint() = fixture { f ->
        val ticket = f.manager().start("solar panels"); val temporary = File(f.path.path + ".tmp").apply { mkdir() }; var calls = 0
        assertTrue(runCatching { f.manager().run(ticket) { calls++; f.response() } }.isFailure); assertEquals(0, calls)
        temporary.delete()
        assertTrue(runCatching { f.manager().run(ticket) { calls++; temporary.mkdir(); f.response() } }.isFailure)
        temporary.delete(); assertEquals(1, calls); assertTrue(f.manager().status().contains("completion is not saved"))
        f.manager().run(ticket) { calls++; f.response() }; assertEquals(1, calls)
    }
    @Test fun corruptStateCannotCauseALookupAndInvalidQueriesAreRejected() = fixture { f ->
        assertTrue(runCatching { f.manager().start("x") }.isFailure)
        assertTrue(runCatching { f.manager().start("x\nthen call Alex") }.isFailure)
        f.path.writeText("{broken")
        assertTrue(runCatching { f.manager().start("solar panels") }.isFailure)
    }
    @Test fun explicitResearchParsesWithoutReinterpretingMalformedManagementControls() {
        assertEquals("solar panels", ResearchBriefCommands.parse("research solar panels and summarize")!!.query)
        assertEquals("solar panels", ResearchBriefCommands.parse("research and summarize solar panels")!!.query)
        for (input in listOf("review research brief then call Alex", "retry research then search the service manual", "research status then reboot"))
            assertEquals(input, ResearchBriefCommands.Command.INVALID, ResearchBriefCommands.parse(input)!!.command)
        assertNull(ResearchBriefCommands.parse("What does research involve?"))
    }
}
