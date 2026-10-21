package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WorkflowDiagnosticsTest {
    private class Fixture {
        val directory = Files.createTempDirectory("workflow-diagnostics").toFile()
        val path = File(directory, "workflow-diagnostics.json")
        var time = 1_000L
        fun store(build: Int = 1) = WorkflowDiagnostics(PersonalStateFile(directory, path.name), build, "3.5.$build") { time++ }
        fun reply(text: String) = store().reply(WorkflowDiagnostics.command(text)!!)
        fun observe(code: WorkflowDiagnostics.Code, attempt: String = UUID.randomUUID().toString(), build: Int = 1) = store(build).observe(attempt, code)
    }
    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture(); try { block(f) } finally { f.directory.deleteRecursively() }
    }

    @Test fun collectionIsSeparatelyOffAndDisableAndClearPersistWithoutChangingWorkflowFiles() = fixture { f ->
        val manual = ImprovementEvaluation(PersonalStateFile(f.directory, "improvement-evaluation.json"), 1, "3.5.1")
        manual.setEnabled(true)
        f.observe(WorkflowDiagnostics.Code.NO_SOURCES)
        assertFalse(f.path.exists())
        assertFalse(f.store().snapshot().enabled)
        f.reply("enable workflow diagnostics")
        f.observe(WorkflowDiagnostics.Code.NO_SOURCES)
        f.reply("disable workflow diagnostics")
        f.observe(WorkflowDiagnostics.Code.NO_SOURCES)
        assertEquals(1, f.store().snapshot().events.size)
        File(f.directory, "research-brief.json").writeText("private saved brief")
        f.reply("clear workflow diagnostics")
        assertEquals(WorkflowDiagnostics.Snapshot(), f.store().snapshot())
        assertEquals("private saved brief", File(f.directory, "research-brief.json").readText())
        assertTrue(manual.snapshot().enabled)
    }

    @Test fun repeatedCallbacksAndRetriesDoNotInventIndependentFailuresOrCombineBuilds() = fixture { f ->
        f.reply("enable workflow diagnostics")
        val id = UUID.randomUUID().toString()
        repeat(5) { f.observe(WorkflowDiagnostics.Code.NO_SOURCES, id) }
        f.observe(WorkflowDiagnostics.Code.NO_SOURCES, build = 2)
        assertTrue(f.reply("review workflow improvements").contains("0 recurrent"))
        f.observe(WorkflowDiagnostics.Code.NO_SOURCES)
        assertTrue(f.reply("review workflow improvements").contains("1 recurrent"))
        f.observe(WorkflowDiagnostics.Code.BRIEF_SAVED, id)
        val report = f.reply("review workflow improvements")
        assertTrue(report.contains("1 recurrent")) // A saved excerpt is not proof a recurring cause was fixed.
        assertTrue(report.contains("not a proven code defect"))
        assertEquals(4, f.store().snapshot().events.size)
    }

    @Test fun preparedEvidenceIsFrozenBoundedAndReviewableAfterWindowRolloverAndDismissal() = fixture { f ->
        f.reply("enable workflow diagnostics")
        repeat(25) { f.observe(WorkflowDiagnostics.Code.NO_SOURCES) }
        f.reply("prepare workflow improvement for research")
        val original = f.store().snapshot().proposals.single()
        assertEquals(25, original.occurrences)
        assertEquals(WorkflowDiagnostics.MAX_LINKS, original.evidence.size)
        f.reply("prepare workflow improvement for research")
        assertEquals(1, f.store().snapshot().proposals.size)
        repeat(WorkflowDiagnostics.MAX_EVENTS) { f.observe(WorkflowDiagnostics.Code.ALARM_REGISTERED, build = 2) }
        assertEquals(WorkflowDiagnostics.MAX_EVENTS, f.store().snapshot().events.size)
        assertEquals(original, f.store().snapshot().proposals.single())
        val brief = f.store().handoff(WorkflowDiagnostics.Family.RESEARCH)!!
        assertTrue(brief.contains("25 distinct workflows"))
        assertTrue(brief.contains("Synthetic regression"))
        assertTrue(brief.contains("No patch, test run, pull request"))
        f.reply("dismiss workflow improvement for research")
        assertNull(f.store().handoff(WorkflowDiagnostics.Family.RESEARCH))
        assertTrue(f.reply("review workflow improvement for research").contains("dismissed"))
        f.reply("clear workflow improvements")
        assertTrue(f.store().snapshot().proposals.isEmpty())
        assertEquals(WorkflowDiagnostics.MAX_EVENTS, f.store().snapshot().events.size)
        assertTrue(f.store().snapshot().enabled)
    }

    @Test fun malformedManagementControlsRemainReservedWithoutAuthorizingCollectionOrSharing() {
        for (text in listOf("enable workflow diagnostics and upload", "share workflow improvement for research then reboot",
            "prepare workflow improvement for a private contact", "clear workflow improvements then search the manual")) {
            assertEquals(text, WorkflowDiagnostics.Action.INVALID, WorkflowDiagnostics.command(text)!!.action)
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
        }
        assertNull(WorkflowDiagnostics.command("How can workflow diagnostics help?"))
        assertNull(WorkflowDiagnostics.command("yes"))
        assertEquals(WorkflowDiagnostics.Action.ENABLE, WorkflowDiagnostics.command("Enable workflow diagnostics!")!!.action)
    }

    @Test fun everyRetainedBriefRemainsReviewableAfterANewerBriefAndCapacityNeverSilentlyOverwritesEvidence() = fixture { f ->
        f.reply("enable workflow diagnostics")
        for (build in 1..WorkflowDiagnostics.MAX_PROPOSALS + 1) {
            repeat(2) { f.observe(WorkflowDiagnostics.Code.NO_SOURCES, build = build) }
            val reply = f.store(build).reply(WorkflowDiagnostics.Command(WorkflowDiagnostics.Action.PREPARE, WorkflowDiagnostics.Family.RESEARCH))
            assertTrue(if (build <= WorkflowDiagnostics.MAX_PROPOSALS) reply.contains("2 distinct workflows") else reply.contains("briefs are full"))
        }
        val saved = f.store().snapshot().proposals
        assertEquals(WorkflowDiagnostics.MAX_PROPOSALS, saved.size)
        val listing = f.reply("review workflow improvements")
        assertTrue(listing.contains("1. No usable sources returned on 3.5.1"))
        for (index in saved.indices) {
            val archived = f.reply("review workflow improvement ${index + 1}")
            assertTrue(archived.contains(saved[index].id))
            assertTrue(archived.contains("on 3.5.${index + 1};"))
        }
        assertEquals(WorkflowDiagnostics.Action.INVALID, WorkflowDiagnostics.command("review workflow improvement 99")!!.action)
        assertEquals(WorkflowDiagnostics.Action.INVALID, WorkflowDiagnostics.command("review workflow improvement 1 then share")!!.action)
        assertEquals(saved, f.store().snapshot().proposals)
    }

    @Test fun corruptedDataCannotTurnConsentOnAndDiagnosticsFailureCannotBreakResearch() = fixture { f ->
        f.path.writeText("corrupt private diagnostics")
        f.observe(WorkflowDiagnostics.Code.NO_SOURCES)
        assertEquals("corrupt private diagnostics", f.path.readText())
        try { f.reply("enable workflow diagnostics"); fail("Must not overwrite corruption") } catch (_: Exception) { }
        val briefs = ResearchBriefs(PersonalStateFile(f.directory, "research-brief.json"), observe = f.store()::observe) { f.time++ }
        val response = briefs.run(briefs.start("private topic")) { sources() }
        assertNotNull(ChatSearchResults.decode(response))
        assertEquals("corrupt private diagnostics", f.path.readText())
        f.reply("clear workflow diagnostics")
        assertFalse(f.store().snapshot().enabled)
        assertNotNull(ChatSearchResults.decode(briefs.review()))
    }

    @Test fun actualWorkflowJournalsFeedOnlyTypedCategoriesAndNeverPrivateTargets() = fixture { f ->
        f.reply("enable workflow diagnostics")
        val diagnostics = f.store()
        val contacts = ContactCallWorkflow(PersonalStateFile(f.directory, "contact-call.json"), object : ContactCallWorkflow.Platform {
            override fun lookup(query: String) = ContactCallWorkflow.Lookup(emptyList(), false)
            override fun current(recipient: ContactCallWorkflow.Recipient) = true
            override fun canCall() = false
            override fun request(recipient: ContactCallWorkflow.Recipient, dialOnly: Boolean) = true
        }, observe = diagnostics::observe) { f.time++ }
        contacts.prepare("Private Person")
        val briefs = ResearchBriefs(PersonalStateFile(f.directory, "research-brief.json"), observe = diagnostics::observe) { f.time++ }
        val ticket = briefs.start("Private Query")
        briefs.run(ticket) { SearchRouter.Response(null, emptyList()) }
        briefs.run(ticket) { fail("Duplicate lookup"); sources() }
        var available = false
        val platform = object : PersonalReminders.Platform {
            override fun notificationsAvailable() = available
            override fun schedule(id: String, dueAt: Long) = true
            override fun cancel(id: String) = true
            override fun post(record: PersonalReminders.Record) = PersonalReminders.Delivery.POSTED
        }
        val reminders = PersonalReminders(PersonalStateFile(f.directory, "personal-reminders.json"), platform, observe = diagnostics::observe) { f.time }
        val plan = reminders.prepare("Private Reminder", 1)
        reminders.approve(plan); reminders.approve(plan)
        available = true
        reminders.approve(plan)
        reminders.restore() // Same registration observation is deduplicated.
        f.time += 2_000
        reminders.deliver(plan.id)
        reminders.deliver(plan.id)
        val codes = diagnostics.snapshot().events.map { it.code }
        assertEquals(listOf(WorkflowDiagnostics.Code.CONTACTS_UNAVAILABLE, WorkflowDiagnostics.Code.NO_SOURCES,
            WorkflowDiagnostics.Code.NOTIFICATIONS_BLOCKED, WorkflowDiagnostics.Code.ALARM_REGISTERED, WorkflowDiagnostics.Code.NOTIFICATION_POSTED), codes)
        for (privateValue in listOf("Private Person", "Private Query", "Private Reminder")) {
            assertFalse(f.path.readText().contains(privateValue))
            assertFalse(f.reply("review workflow diagnostics").contains(privateValue))
        }
    }

    @Test fun cancellationAndConsentRevocationRejectLateResearchEvidence() = fixture { f ->
        f.reply("enable workflow diagnostics")
        val briefs = ResearchBriefs(PersonalStateFile(f.directory, "research-brief.json"), observe = f.store()::observe) { f.time++ }
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val answer = AtomicReference<String>()
        val ticket = briefs.start("private delayed query")
        val worker = Thread { answer.set(briefs.run(ticket) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); sources() }) }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            briefs.cancel()
            release.countDown(); worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(answer.get().contains("late results were not saved"))
            assertTrue(f.store().snapshot().events.isEmpty())
            f.reply("disable workflow diagnostics")
            briefs.run(briefs.start("private successful query")) { sources() }
            assertTrue(f.store().snapshot().events.isEmpty())
        } finally { release.countDown(); worker.join(5_000) }
    }

    @Test fun throwingObserverCannotChangeCallDispatchOrReminderRegistration() = fixture { f ->
        var requests = 0
        val calls = ContactCallWorkflow(PersonalStateFile(f.directory, "contact-call.json"), object : ContactCallWorkflow.Platform {
            override fun lookup(query: String) = ContactCallWorkflow.Lookup(emptyList())
            override fun current(recipient: ContactCallWorkflow.Recipient) = true
            override fun canCall() = false
            override fun request(recipient: ContactCallWorkflow.Recipient, dialOnly: Boolean): Boolean { requests++; return true }
        }, observe = { _, _ -> error("Diagnostics unavailable") }) { f.time++ }
        calls.prepare("+1 202 555 0100")
        assertTrue(calls.approve(calls.pending()!!).contains("Requested the dialer"))
        assertEquals(1, requests)
        val reminders = PersonalReminders(PersonalStateFile(f.directory, "personal-reminders.json"), object : PersonalReminders.Platform {
            override fun notificationsAvailable() = true
            override fun schedule(id: String, dueAt: Long) = true
            override fun cancel(id: String) = true
            override fun post(record: PersonalReminders.Record) = PersonalReminders.Delivery.UNKNOWN
        }, observe = { _, _ -> error("Diagnostics unavailable") }) { f.time }
        val plan = reminders.prepare("Synthetic", 1)
        assertEquals(PersonalReminders.Status.SCHEDULED, reminders.approve(plan).record!!.status)
        f.time += 2_000
        assertEquals(PersonalReminders.Status.DELIVERY_UNKNOWN, reminders.deliver(plan.id).record!!.status)
        assertEquals(PersonalReminders.Status.DELIVERY_UNKNOWN, reminders.deliver(plan.id).record!!.status)
    }

    private fun sources() = SearchRouter.Response("synthetic", listOf(SearchRouter.Result("Source", "Synthetic excerpt", "https://example.org/source")))
}
