package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class ReminderTimeChangesTest {
    private class Fixture(val directory: File) {
        var time = 1_000_000L
        var allowed = true
        var accepts = true
        var registrations = 0
        var posts = 0
        var onSchedule: () -> Unit = {}
        val alarms = linkedMapOf<String, Long>()
        val path get() = File(directory, "personal-reminders.json")
        val platform = object : PersonalReminders.Platform {
            override fun notificationsAvailable() = allowed
            override fun schedule(id: String, dueAt: Long): Boolean {
                registrations++; onSchedule(); if (accepts) alarms[id] = dueAt; return accepts
            }
            override fun cancel(id: String): Boolean { alarms.remove(id); return true }
            override fun post(record: PersonalReminders.Record): PersonalReminders.Delivery { posts++; return PersonalReminders.Delivery.POSTED }
        }
        fun manager() = PersonalReminders(PersonalStateFile(directory, path.name), platform) { time }
        fun commands() = ReminderCommands { manager() }
        fun request(text: String) = commands().reply(ReminderCommands.parse(text)!!)
        fun approved() = manager().approve(manager().prepare("Check the oven", 600)).record!!
        fun approve(review: PersonalReminders.Review) = manager().approve(review.plan, review.replacing)
        fun status(status: PersonalReminders.Status) {
            val root = JSONObject(path.readText()); root.getJSONArray("records").getJSONObject(0).put("status", status.name); path.writeText(root.toString())
        }
    }
    private inline fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("reminder-change").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun aConversationalDurationCorrectionKeepsThePendingReminderTitleAndStillRequiresApproval() {
        val directory = Files.createTempDirectory("reminder-change").toFile()
        try {
            var registrations = 0
            val platform = object : PersonalReminders.Platform {
                override fun notificationsAvailable() = true
                override fun schedule(id: String, dueAt: Long): Boolean { registrations++; return true }
                override fun cancel(id: String) = true
                override fun post(record: PersonalReminders.Record) = PersonalReminders.Delivery.POSTED
            }
            fun manager() = PersonalReminders(PersonalStateFile(directory, "personal-reminders.json"), platform) { 1_000_000L }
            val commands = ReminderCommands { manager() }
            commands.reply(ReminderCommands.parse("prepare a reminder in ten minutes to Check the oven")!!)
            val input = ReminderCommands.parse("change reminder time to five minutes")
            assertNotNull("A pending reminder needs a local time-correction command", input)
            commands.reply(input!!)
            val changed = manager().pending()!!
            assertEquals("Check the oven", changed.title)
            assertEquals(300_000L, changed.dueAt - changed.created)
            assertEquals(0, registrations)
        } finally { directory.deleteRecursively() }
    }

    @Test fun pendingCorrectionsIssueNewReviewTokensPreserveResearchAndRejectStaleApproval() = fixture { f ->
        val report = ChatSearchResults.encode("synthetic research", listOf(SearchRouter.Result("Source", "Synthetic excerpt", "https://example.com/source")), retrievedAt = f.time)
        val original = f.manager().prepare("Review synthetic research", 600, ResearchBriefs.Snapshot(report))
        val revised = f.manager().revise(f.manager().review()!!, 300)!!
        assertNotEquals(original.id, revised.plan.id)
        assertEquals(original.title, revised.plan.title); assertEquals(original.research, revised.plan.research)
        assertNull(f.manager().approve(original).record); assertEquals(0, f.registrations)
        assertEquals(revised.plan, f.approve(revised).record!!.plan)
        assertEquals(1, f.registrations)
    }

    @Test fun preparingCancellingAndCorrectingAChangeLeaveTheApprovedAlarmUntouchedUntilExactApproval() = fixture { f ->
        val old = f.approved()
        val change = f.manager().prepareChange(old.number, 300)!!
        assertEquals(old, f.manager().records().single()); assertEquals(old.plan.dueAt, f.alarms[old.plan.id])
        assertEquals(1, f.registrations)
        val revised = f.manager().revise(change, 900)!!
        assertNull(f.approve(change).record)
        assertTrue(f.manager().discardPending())
        assertNull(f.approve(revised).record); assertEquals(old, f.manager().records().single())
        val current = f.manager().prepareChange(old.number, 300)!!
        assertNull(f.manager().approve(current.plan).record) // A new-reminder approval cannot bypass the bound replacement.
        val changed = f.approve(current).record!!
        assertEquals(old.number, changed.number); assertEquals(old.plan.id, changed.plan.id)
        assertEquals(current.plan.dueAt, f.alarms[old.plan.id]); assertEquals(1, f.alarms.size)
        assertEquals(2, f.registrations)
    }

    @Test fun reconstructedReviewerAndBareYesMustShowTheWholeChangeBeforeApproving() = fixture { f ->
        val old = f.approved()
        val first = f.commands()
        assertTrue(first.reply(ReminderCommands.parse("change reminder one to five minutes")!!).contains("existing approved alarm is unchanged"))
        assertTrue(first.confirmation("yes")!!.contains("bare confirmation")); assertEquals(1, f.registrations)
        val restored = f.commands()
        assertTrue(restored.reply(ReminderCommands.parse("approve reminder")!!).contains("before approving again"))
        assertEquals(1, f.registrations)
        assertTrue(restored.reply(ReminderCommands.parse("approve reminder")!!).contains("Time change for reminder"))
        assertEquals(old.plan.id, f.manager().records().single().plan.id)
        assertEquals(2, f.registrations)
    }

    @Test fun cancellationPostingAndUncertainDeliveryInvalidateAStagedChangeWithoutRepeatingAnything() = fixture { f ->
        val old = f.approved()
        for (status in listOf(PersonalReminders.Status.POSTED, PersonalReminders.Status.DELIVERY_UNKNOWN, PersonalReminders.Status.CANCELLED,
                PersonalReminders.Status.BLOCKED, PersonalReminders.Status.USER_CONFIRMED, PersonalReminders.Status.MISSED)) {
            f.status(PersonalReminders.Status.SCHEDULED)
            val change = f.manager().prepareChange(old.number, 300)!!
            f.status(status)
            assertNull(f.manager().review()); assertNull(f.approve(change).record)
            assertNull(f.manager().prepareChange(old.number, 600))
            assertEquals(status, f.manager().records().single().status)
        }
        assertEquals(1, f.registrations); assertEquals(0, f.posts)
    }

    @Test fun anOldBroadcastCannotPostBeforeTheNewTimeOrRepeatAnAlreadyPostedReminder() = fixture { f ->
        val old = f.approved()
        val change = f.manager().prepareChange(old.number, 1200)!!
        val changed = f.approve(change).record!!
        f.time = old.plan.dueAt + 1
        f.manager().deliver(old.plan.id)
        assertEquals(0, f.posts); assertEquals(changed.plan.dueAt, f.alarms[old.plan.id])
        f.time = changed.plan.dueAt + 1
        assertEquals(PersonalReminders.Status.POSTED, f.manager().deliver(old.plan.id).record!!.status)
        f.manager().deliver(old.plan.id); f.manager().restore()
        assertEquals(1, f.posts); assertEquals(1, f.manager().records().size)
    }

    @Test fun notificationDenialKeepsTheOriginalTimeAndAllowsOnlyFreshReviewedApprovalLater() = fixture { f ->
        val old = f.approved(); val change = f.manager().prepareChange(old.number, 300)!!
        f.allowed = false
        assertNull(f.approve(change).record); assertEquals(old, f.manager().records().single())
        assertEquals(old.plan.dueAt, f.alarms[old.plan.id]); assertEquals(change, f.manager().review())
        f.allowed = true; f.time += 120_001
        assertNull(f.approve(change).record); assertEquals(1, f.registrations)
    }

    @Test fun failedAndInterruptedRegistrationRetainsOneIdentityAndCanRecoverWithoutASecondReminder() = fixture { f ->
        val old = f.approved(); val change = f.manager().prepareChange(old.number, 1200)!!
        val temporary = File(f.path.path + ".tmp")
        f.onSchedule = { temporary.mkdir() }
        assertTrue(runCatching { f.approve(change) }.isFailure)
        temporary.delete(); f.onSchedule = {}
        assertNull(f.manager().review())
        assertEquals(PersonalReminders.Status.SCHEDULING, f.manager().records().single().status)
        f.manager().restore()
        assertEquals(1, f.alarms.size); assertEquals(old.plan.id, f.manager().records().single().plan.id)
        assertEquals(change.plan.dueAt, f.alarms[old.plan.id])
        assertNull(f.approve(change).record)
        f.time = change.plan.dueAt + 1; f.manager().deliver(old.plan.id); f.manager().deliver(old.plan.id)
        assertEquals(1, f.posts)
    }

    @Test fun storageFailureBeforeApprovalOrCorrectionDoesNotTouchTheApprovedAlarmOrConsumeTheReview() = fixture { f ->
        val old = f.approved(); val change = f.manager().prepareChange(old.number, 300)!!
        val before = f.path.readText(); val temporary = File(f.path.path + ".tmp").apply { mkdir() }
        assertTrue(runCatching { f.approve(change) }.isFailure)
        assertTrue(runCatching { f.manager().revise(change, 900) }.isFailure)
        assertEquals(before, f.path.readText()); assertEquals(old.plan.dueAt, f.alarms[old.plan.id]); assertEquals(1, f.registrations)
        temporary.delete(); assertNotNull(f.approve(change).record)
    }

    @Test fun invalidCorrectionDiscardsOnlyItsMatchingReviewAndCannotEraseANewerReview() = fixture { f ->
        val old = f.approved(); val change = f.manager().prepareChange(old.number, 300)!!
        val newer = f.manager().prepareChange(old.number, 900)!!
        assertNull(f.manager().revise(change, null)); assertEquals(newer, f.manager().review())
        assertNull(f.manager().revise(newer, null)); assertNull(f.manager().review())
        assertEquals(old, f.manager().records().single()); assertEquals(1, f.registrations)
    }

    @Test fun competingApprovalsForOneChangeRegisterOnceAndRetainTheOriginalReminderNumber() = fixture { f ->
        val old = f.approved(); val change = f.manager().prepareChange(old.number, 300)!!
        val gate = CountDownLatch(1); val error = AtomicReference<Throwable>()
        val workers = (1..2).map { Thread { try { gate.await(); f.approve(change) } catch (t: Throwable) { error.set(t) } }.apply { start() } }
        gate.countDown(); workers.forEach { it.join(5000); assertFalse(it.isAlive) }; error.get()?.let { throw AssertionError(it) }
        assertEquals(2, f.registrations); assertEquals(listOf(old.number), f.manager().records().map { it.number })
        assertEquals(1, f.alarms.size)
    }

    @Test fun legacyStorageMigratesWithoutChangingAlarmIdentifiersHistoryOrCounters() = fixture { f ->
        val old = f.approved(); val legacy = JSONObject(f.path.readText()).put("version", 1)
        f.path.writeText(legacy.toString())
        assertEquals(listOf(old), f.manager().records()); assertEquals(legacy.toString(), f.path.readText())
        val change = f.manager().prepareChange(old.number, 300)!!
        assertEquals(2, JSONObject(f.path.readText()).getInt("version"))
        f.approve(change)
        val next = f.manager().approve(f.manager().prepare("New reminder", 600)).record!!
        assertEquals(2, next.number); assertEquals(old.plan.id, f.manager().records().first().plan.id)
    }

    @Test fun aFullHistoryCanChangeAnExistingReminderWithoutAllocatingAnotherNumber() = fixture { f ->
        repeat(PersonalReminders.MAX_RECORDS) { f.approved() }
        val change = f.manager().prepareChange(1, 900)!!
        assertNotNull(f.approve(change).record)
        assertEquals(PersonalReminders.MAX_RECORDS, f.manager().records().size)
        assertEquals(101, JSONObject(f.path.readText()).getInt("next"))
    }

    @Test fun corruptReplacementBindingsFailClosedAndCannotRegisterAnAlarm() = fixture { f ->
        f.approved(); f.manager().prepareChange(1, 300)
        val original = f.path.readText()
        for (corrupt in listOf<(JSONObject) -> Unit>(
                { it.getJSONObject("pending").put("title", "Another title") },
                { it.getJSONObject("replacing").put("status", "DELIVERY_UNKNOWN") },
                { it.getJSONObject("replacing").put("number", 0) },
                { it.put("version", 1) })) {
            val root = JSONObject(original); corrupt(root); f.path.writeText(root.toString())
            assertTrue(runCatching { f.manager().restore() }.isFailure)
            assertEquals(1, f.registrations)
        }
    }

    @Test fun completedHistoryCleanupRemovesAStaleChangeCopyButPreservesAnUnrelatedPendingReminder() = fixture { f ->
        val old = f.approved()
        f.manager().prepareChange(old.number, 300)
        f.manager().cancel(old.number)
        assertEquals(1, f.manager().clearCompleted())
        val cleaned = JSONObject(f.path.readText())
        assertFalse("A deleted reminder must not remain in a stale review", cleaned.has("replacing"))
        assertFalse(cleaned.has("pending"))
        val another = f.approved()
        val unrelated = f.manager().prepare("Keep this review", 600)
        f.manager().cancel(another.number); f.manager().clearCompleted()
        assertEquals(unrelated, f.manager().pending())
    }

    @Test fun timeChangeControlsStayLocalAndShortCorrectionsNeverClaimAnotherGoalOrImplicitlyChooseARecord() {
        for (text in listOf("change reminder one to five minutes", "reschedule reminder 2 in ten minutes", "change the reminder time to five minutes")) {
            assertNotNull(ReminderCommands.parse(text)); assertTrue(LearnedCommandReviewCommands.isReserved(text))
        }
        assertNotNull(ReminderCommands.contextualEdit("actually, make it five minutes"))
        assertNull(ReminderCommands.parse("actually make that five minutes")) // The owning live review resolves it.
        assertNull(ReminderCommands.contextualEdit("change goal duration to five minutes"))
        assertNull(ReminderCommands.parse("change goal duration to five minutes"))
        assertNull(ReminderCommands.parse("change reminder 1 to five minutes then reboot")!!.seconds)
        assertEquals(ReminderCommands.Command.INVALID, ReminderCommands.parse("reschedule reminder then search the service manual")!!.command)
    }
}
