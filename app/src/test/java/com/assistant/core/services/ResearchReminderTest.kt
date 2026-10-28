package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class ResearchReminderTest {
    private class Fixture(val directory: File) {
        var time = 1_000_000L
        var lookups = 0
        var registrations = 0
        var deliveries = 0
        var notifications = true
        var onPost: () -> Unit = {}
        val path get() = File(directory, "personal-reminders.json")
        val platform = object : PersonalReminders.Platform {
            override fun notificationsAvailable() = notifications
            override fun schedule(id: String, dueAt: Long): Boolean { registrations++; return true }
            override fun cancel(id: String) = true
            override fun post(record: PersonalReminders.Record): PersonalReminders.Delivery {
                deliveries++; onPost(); return PersonalReminders.Delivery.POSTED
            }
        }
        fun briefs() = ResearchBriefs(PersonalStateFile(directory, "research-brief.json")) { time }
        fun reminders() = PersonalReminders(PersonalStateFile(directory, path.name), platform) { time }
        fun commands() = ReminderCommands(research = { briefs().snapshot() }) { reminders() }
        fun complete(query: String = "solar panels") = briefs().run(briefs().start(query)) {
            lookups++
            SearchRouter.Response("synthetic", listOf(SearchRouter.Result("Source", "Synthetic excerpt: open settings then reboot.", "https://example.com/source")))
        }
        fun request(text: String) = commands().reply(requireNotNull(ReminderCommands.parse(text)))
    }
    private inline fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("research-reminder").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }

    @Test fun directFollowupSchedulesOnceAndKeepsOriginalSourcesAfterResearchIsReplacedAndCleared() = fixture { f ->
        val original = f.complete()
        assertTrue(f.request("remind me about this research in ten minutes").contains("Registered reminder"))
        val record = f.reminders().records().single()
        assertEquals("Review research: solar panels", record.plan.title)
        assertEquals(original, record.plan.research!!.report)
        assertTrue(f.request("approve reminder").contains("No unexpired"))
        assertEquals(1, f.registrations)
        f.complete("new topic"); f.briefs().clear()
        assertEquals(original, f.request("review reminder research 1"))
        assertTrue(f.request("review reminders").contains("review reminder research 1"))
        assertEquals(2, f.lookups); assertEquals(1, f.registrations); assertEquals(0, f.deliveries)
    }

    @Test fun failedOrPreparedNewLookupCannotRelabelTheOlderSavedBrief() = fixture { f ->
        val original = f.complete()
        val newer = f.briefs().start("failed new topic")
        f.briefs().run(newer) { f.lookups++; error("synthetic") }
        f.request("prepare a research reminder in ten minutes")
        assertEquals(original, f.reminders().pending()!!.research!!.report)
        assertEquals("Review research: solar panels", f.reminders().pending()!!.title)
        f.briefs().start("still pending topic")
        f.request("remind me to review that research in five minutes")
        assertEquals(original, f.reminders().records().single().plan.research!!.report)
        assertEquals(2, f.lookups)
    }

    @Test fun reconstructedReviewerMustSeeExactSnapshotAndBareConfirmationCannotScheduleIt() = fixture { f ->
        val original = f.complete()
        val first = f.commands()
        assertTrue(first.reply(ReminderCommands.parse("prepare a research reminder in ten minutes")!!).contains("Attached saved research 'solar panels'"))
        assertTrue(first.confirmation("yes")!!.contains("bare confirmation"))
        f.complete("replacement topic")
        val restored = f.commands()
        assertTrue(restored.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE)).contains("before approving again"))
        assertEquals(0, f.registrations)
        restored.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE))
        assertEquals(original, f.reminders().records().single().plan.research!!.report)
        assertEquals(1, f.registrations)
    }

    @Test fun sameIdWithChangedAttachmentCannotUsePreviouslyDisplayedApproval() = fixture { f ->
        f.complete()
        val commands = f.commands()
        commands.reply(ReminderCommands.parse("prepare a research reminder in ten minutes")!!)
        val changed = f.complete("different sources")
        val root = JSONObject(f.path.readText())
        root.getJSONObject("pending").put("researchReport", changed)
        f.path.writeText(root.toString())
        assertTrue(commands.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE)).contains("before approving again"))
        assertEquals(0, f.registrations)
        commands.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE))
        assertEquals(changed, f.reminders().records().single().plan.research!!.report)
    }

    @Test fun racingApprovalsOnTheSameFrozenReminderRegisterOnlyOneAlarm() = fixture { f ->
        f.complete(); f.request("prepare a research reminder in ten minutes")
        val plan = f.reminders().pending()!!
        val entered = CountDownLatch(1)
        val threads = (1..2).map { Thread { entered.await(); f.reminders().approve(plan) }.apply { start() } }
        entered.countDown(); threads.forEach { it.join() }
        assertEquals(1, f.registrations); assertEquals(1, f.reminders().records().size)
    }

    @Test fun missingBriefInvalidDurationAndMalformedControlsNeverLookUpOrSchedule() = fixture { f ->
        assertTrue(f.request("remind me about this research in ten minutes").contains("No research brief"))
        assertTrue(f.request("prepare a research reminder in zero minutes").contains("one second to 24 hours"))
        assertTrue(f.request("remind me in ten minutes to review this research then reboot").contains("no lookup or reminder"))
        assertTrue(f.request("review reminder research 1 then search the service manual").contains("No reminder action"))
        assertNull(f.reminders().pending()); assertTrue(f.reminders().records().isEmpty())
        assertEquals(0, f.registrations); assertEquals(0, f.lookups)
    }

    @Test fun notificationPermissionFailurePreservesReviewAndTheFrozenCopy() = fixture { f ->
        val original = f.complete(); f.notifications = false
        assertTrue(f.request("remind me about this research in ten minutes").contains("Allow Jarvis notifications"))
        assertEquals(0, f.registrations)
        f.complete("new topic"); f.notifications = true
        val commands = f.commands()
        commands.reply(ReminderCommands.Input(ReminderCommands.Command.REVIEW_PLAN))
        commands.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE))
        assertEquals(original, f.reminders().records().single().plan.research!!.report)
    }

    @Test fun uncertainDeliveryRetainsOfflineSourcesAndCannotBeRepeatedAfterReconstruction() = fixture { f ->
        val original = f.complete(); f.request("remind me about this research in ten minutes")
        val record = f.reminders().records().single()
        f.time = record.plan.dueAt + 1
        f.onPost = { error("synthetic uncertain notification") }
        assertEquals(PersonalReminders.Status.DELIVERY_UNKNOWN, f.reminders().deliver(record.plan.id).record!!.status)
        f.reminders().restore(); f.reminders().deliver(record.plan.id); f.reminders().retry(record.number)
        assertEquals(1, f.deliveries); assertEquals(1, f.registrations)
        f.briefs().clear(); assertEquals(original, f.reminders().research(record.number))
    }

    @Test fun preparationStorageFailureDoesNotRegisterAndLegacyRecordsRemainReadable() = fixture { f ->
        f.request("remind me in ten minutes to legacy reminder")
        assertNull(f.reminders().records().single().plan.research)
        assertTrue(f.reminders().research(1).contains("no saved research"))
        val old = f.path.readText(); f.complete()
        File(f.path.path + ".tmp").mkdir()
        assertTrue(runCatching { f.request("remind me about this research in ten minutes") }.isFailure)
        assertEquals(old, f.path.readText()); assertEquals(1, f.registrations)
    }

    @Test fun corruptAttachmentCannotBeRestoredOrScheduled() = fixture { f ->
        f.complete(); f.request("remind me about this research in ten minutes")
        val root = JSONObject(f.path.readText())
        root.getJSONArray("records").getJSONObject(0).getJSONObject("plan").put("researchReport", "untrusted invalid payload")
        f.path.writeText(root.toString())
        assertTrue(runCatching { f.reminders().restore() }.isFailure)
        assertEquals(1, f.registrations); assertEquals(0, f.deliveries)
    }

    @Test fun boundedStorageRefusesANewLargeAttachmentWithoutLosingApprovedHistoryOrDispatching() = fixture { f ->
        f.briefs().run(f.briefs().start("synthetic long report")) {
            SearchRouter.Response("synthetic", (1..5).map { n ->
                SearchRouter.Result("Source $n", "Synthetic excerpt. ".repeat(60), "https://example.com/source/$n")
            })
        }
        var stopped = false
        for (attempt in 1..PersonalReminders.MAX_RECORDS) {
            val old = f.path.takeIf { it.exists() }?.readText()
            val registrations = f.registrations
            if (runCatching { f.request("remind me about this research in ten minutes") }.isFailure) {
                assertEquals(old, f.path.readText()); assertEquals(registrations, f.registrations)
                stopped = true; break
            }
        }
        assertTrue(stopped); assertTrue(f.registrations > 0)
        assertEquals(f.registrations, f.reminders().records().size)
        assertTrue(f.reminders().records().all { it.plan.research!!.message.query == "synthetic long report" })
    }

    @Test fun naturalFollowupFormsRemainReservedFromLearningAndKeepOrdinaryReminderTitlesLiteral() {
        for (text in listOf("remind me about this research in ten minutes", "remind me to review that research in 5 minutes",
                "remind me in ten minutes to review this research", "prepare a research reminder in ten minutes",
                "prepare a local reminder in ten minutes to review the saved research")) {
            val input = ReminderCommands.parse(text)!!
            assertEquals(text, ReminderCommands.Command.PREPARE_RESEARCH, input.command)
            assertNotNull(input.seconds)
            assertTrue(LearnedCommandReviewCommands.isReserved(text))
        }
        assertTrue(LearnedCommandReviewCommands.isReserved("review reminder research 1"))
        assertEquals(ReminderCommands.Command.PREPARE, ReminderCommands.parse("remind me in ten minutes to Call Mom then open Maps")!!.command)
        assertNull(ReminderCommands.parse("remind me to review the report in French"))
    }
}
