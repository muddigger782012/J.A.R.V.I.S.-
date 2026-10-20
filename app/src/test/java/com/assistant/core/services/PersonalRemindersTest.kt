package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class PersonalRemindersTest {
    private class Platform : PersonalReminders.Platform {
        var allowed = true
        var accepts = true
        var removes = true
        val alarms = linkedMapOf<String, Long>()
        var registrations = 0
        var deliveries = 0
        var outcome = PersonalReminders.Delivery.POSTED
        var interrupted = false
        var onPost: () -> Unit = {}
        override fun notificationsAvailable() = allowed
        override fun schedule(id: String, dueAt: Long): Boolean {
            registrations++
            if (accepts) alarms[id] = dueAt
            return accepts
        }
        override fun cancel(id: String): Boolean { if (removes) alarms.remove(id); return removes }
        override fun post(record: PersonalReminders.Record): PersonalReminders.Delivery {
            deliveries++
            if (interrupted) error("Synthetic interrupted delivery")
            onPost()
            return if (allowed) outcome else PersonalReminders.Delivery.BLOCKED
        }
    }
    private class Fixture(val directory: File) {
        var time = 1_000_000L
        val platform = Platform()
        val path get() = File(directory, "personal-reminders.json")
        fun manager() = PersonalReminders(PersonalStateFile(directory, path.name), platform) { time }
        fun plan() = manager().prepare("Check the oven", 600)
        fun approved(): PersonalReminders.Record = manager().approve(plan()).record!!
        fun status(status: PersonalReminders.Status) {
            val json = JSONObject(path.readText())
            json.getJSONArray("records").getJSONObject(0).put("status", status.name)
            path.writeText(json.toString())
        }
    }
    private inline fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("personal-reminders").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }

    @Test fun planningReviewAndBareConfirmationNeverRegisterAndApprovalMustMatchFreshDetails() = fixture { f ->
        val first = f.plan()
        val commands = ReminderCommands { f.manager() }
        assertTrue(commands.confirmation("yes")!!.contains("bare confirmation"))
        assertEquals(0, f.platform.registrations)
        val changed = f.manager().prepare("Check the door", 300)
        assertNull(f.manager().approve(first).record)
        assertEquals(changed, f.manager().pending())
        assertTrue(commands.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE)).contains("before approving again"))
        assertEquals(0, f.platform.registrations)
        commands.reply(ReminderCommands.Input(ReminderCommands.Command.APPROVE))
        assertEquals(1, f.platform.registrations)
        assertEquals("Check the door", f.manager().records().single().plan.title)
        assertNull(f.manager().approve(changed).record)
        assertEquals(1, f.platform.registrations)
    }

    @Test fun notificationPermissionFailureKeepsTheReviewAndNeverClaimsRegistration() = fixture { f ->
        val plan = f.plan()
        f.platform.allowed = false
        assertTrue(f.manager().approve(plan).message.contains("Allow Jarvis notifications"))
        assertEquals(plan, f.manager().pending())
        assertTrue(f.manager().records().isEmpty())
        assertEquals(0, f.platform.registrations)
        f.platform.allowed = true
        assertEquals(PersonalReminders.Status.SCHEDULED, f.manager().approve(plan).record!!.status)
    }

    @Test fun fullySpecifiedReminderRequestsAuthorizeSchedulingWhilePrepareRequestsOnlyReview() = fixture { f ->
        val commands = ReminderCommands { f.manager() }
        assertTrue(commands.reply(ReminderCommands.parse("remind me in ten minutes to Check the oven")!!).contains("Registered reminder"))
        assertEquals(1, f.platform.registrations)
        assertNull(f.manager().pending())
        assertTrue(commands.reply(ReminderCommands.parse("prepare a reminder in ten minutes to Check the door")!!).contains("Review local reminder"))
        assertEquals(1, f.platform.registrations)
        assertNotNull(f.manager().pending())
        f.platform.allowed = false
        val denied = commands.reply(ReminderCommands.parse("remind me in five minutes to Check notifications")!!)
        assertTrue(denied.contains("Allow Jarvis notifications"))
        assertEquals(1, f.platform.registrations)
        assertNotNull(f.manager().pending())
    }

    @Test fun expiredOrAlreadyDueReviewsCannotRegisterAnAlarm() = fixture { f ->
        var plan = f.manager().prepare("Short reminder", 1)
        f.time += 1_001
        assertNull(f.manager().pending())
        assertNull(f.manager().approve(plan).record)
        plan = f.plan()
        f.time += 120_001
        assertNull(f.manager().approve(plan).record)
        assertEquals(0, f.platform.registrations)
    }

    @Test fun restartRecoveryReplacesTheSameAlarmAndNeverPostsDuringInspection() = fixture { f ->
        val record = f.approved()
        f.status(PersonalReminders.Status.SCHEDULING)
        assertEquals(PersonalReminders.Status.SCHEDULING, f.manager().records().single().status)
        assertEquals(1, f.platform.registrations)
        repeat(3) { f.manager().restore() }
        assertEquals(setOf(record.plan.id), f.platform.alarms.keys)
        assertEquals(PersonalReminders.Status.SCHEDULED, f.manager().records().single().status)
        assertEquals(0, f.platform.deliveries)
    }

    @Test fun dueNotificationPostsOnceAndOldBroadcastsCannotReviveClearedHistory() = fixture { f ->
        val record = f.approved()
        f.manager().deliver(record.plan.id)
        assertEquals(0, f.platform.deliveries)
        f.time = record.plan.dueAt + 1
        assertEquals(PersonalReminders.Status.POSTED, f.manager().deliver(record.plan.id).record!!.status)
        f.manager().deliver(record.plan.id)
        f.manager().restore()
        assertEquals(1, f.platform.deliveries)
        assertEquals(1, f.manager().clearCompleted())
        assertNull(f.manager().deliver(record.plan.id).record)
        assertEquals(1, f.platform.deliveries)
    }

    @Test fun cancellationIsDurableEvenWhenRemovingThePlatformAlarmFails() = fixture { f ->
        val record = f.approved()
        f.platform.removes = false
        assertTrue(f.manager().cancel(record.number).message.contains("suppress later delivery"))
        f.time = record.plan.dueAt + 1
        f.manager().deliver(record.plan.id)
        f.manager().restore()
        assertEquals(0, f.platform.deliveries)
        assertEquals(PersonalReminders.Status.CANCELLED, f.manager().records().single().status)
    }

    @Test fun interruptedOrAmbiguousDeliveryNeverRetriesAndOwnerConfirmationRemainsSeparate() = fixture { f ->
        val record = f.approved()
        f.time = record.plan.dueAt + 1
        f.platform.interrupted = true
        assertEquals(PersonalReminders.Status.DELIVERY_UNKNOWN, f.manager().deliver(record.plan.id).record!!.status)
        f.manager().retry(record.number)
        f.manager().restore()
        f.manager().deliver(record.plan.id)
        assertEquals(1, f.platform.deliveries)
        assertEquals(0, f.manager().clearCompleted())
        val confirmed = f.manager().confirmShown(record.number)
        assertTrue(confirmed.message.contains("your report, not device verification"))
        assertEquals(PersonalReminders.Status.USER_CONFIRMED, confirmed.record!!.status)
        assertEquals(1, f.manager().clearCompleted())
        f.manager().deliver(record.plan.id)
        assertEquals(1, f.platform.deliveries)
    }

    @Test fun writeAheadDeliveryFromADeadProcessBecomesUncertainWithoutPosting() = fixture { f ->
        val record = f.approved()
        f.status(PersonalReminders.Status.DELIVERING)
        assertEquals(PersonalReminders.Status.DELIVERY_UNKNOWN, f.manager().records().single().status)
        f.manager().restore()
        f.time = record.plan.dueAt + 1
        f.manager().deliver(record.plan.id)
        assertEquals(0, f.platform.deliveries)
        assertEquals(1, f.platform.registrations)
    }

    @Test fun blockedDeliveryAndFailedRegistrationHaveExplicitSafeRetryPaths() = fixture { f ->
        f.platform.accepts = false
        val record = f.approved()
        assertEquals(PersonalReminders.Status.SCHEDULE_FAILED, record.status)
        f.platform.accepts = true
        assertEquals(PersonalReminders.Status.SCHEDULED, f.manager().retry(record.number).record!!.status)
        f.time = record.plan.dueAt + 1
        f.platform.allowed = false
        assertEquals(PersonalReminders.Status.BLOCKED, f.manager().deliver(record.plan.id).record!!.status)
        f.manager().retry(record.number)
        assertEquals(PersonalReminders.Status.BLOCKED, f.manager().records().single().status)
        f.platform.allowed = true
        f.manager().retry(record.number)
        assertEquals(setOf(record.plan.id), f.platform.alarms.keys)
        assertEquals(PersonalReminders.Status.POSTED, f.manager().deliver(record.plan.id).record!!.status)
    }

    @Test fun VeryLateRemindersExpireAndCapacityDoesNotSilentlyEvictEvidenceOrReuseNumbers() = fixture { f ->
        val first = f.approved()
        f.time = first.plan.dueAt + 86_400_001L
        f.manager().restore()
        assertEquals(PersonalReminders.Status.MISSED, f.manager().records().single().status)
        assertEquals(0, f.platform.deliveries)
        repeat(PersonalReminders.MAX_RECORDS - 1) { f.approved() }
        val pending = f.plan()
        assertTrue(f.manager().approve(pending).message.contains("history is full"))
        assertEquals(pending, f.manager().pending())
        assertEquals(1, f.manager().clearCompleted())
        assertEquals(101, f.manager().approve(pending).record!!.number)
        assertNull(f.manager().deliver(first.plan.id).record)
    }

    @Test fun concurrentAssistantsCannotApproveTheSameReminderTwice() = fixture { f ->
        val plan = f.plan()
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val workers = List(2) { Thread {
            try { start.await(); f.manager().approve(plan) } catch (error: Throwable) { failure.set(error) }
        }.also { it.start() } }
        start.countDown()
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(1, f.manager().records().size)
        assertEquals(1, f.platform.registrations)
    }

    @Test fun corruptFutureOrOversizedStorageCannotBecomeAnEmptyRepeatableJournal() = fixture { f ->
        for (invalid in listOf("broken", "{\"version\":999}", " ".repeat(256_001))) {
            f.path.writeText(invalid)
            try { f.plan(); fail("Must not overwrite bad state") } catch (_: Exception) { }
            assertEquals(invalid, f.path.readText())
        }
        assertEquals(0, f.platform.registrations)
    }

    @Test fun writeAheadFailureCannotDispatchAndAFailedPostCheckpointRemainsUncertain() = fixture { f ->
        val plan = f.plan()
        val temporary = File(f.path.path + ".tmp")
        assertTrue(temporary.mkdir())
        try { f.manager().approve(plan); fail("Storage failure must stop registration") } catch (_: Exception) { }
        assertEquals(0, f.platform.registrations)
        assertEquals(plan, f.manager().pending())
        assertTrue(temporary.delete())
        val approved = f.manager().approve(plan).record!!
        f.time = plan.dueAt + 1
        f.platform.onPost = { check(temporary.mkdir()) }
        try { f.manager().deliver(plan.id); fail("Must not claim a saved result") } catch (_: Exception) { }
        assertEquals(1, f.platform.deliveries)
        assertEquals("DELIVERING", JSONObject(f.path.readText()).getJSONArray("records").getJSONObject(0).getString("status"))
        assertTrue(temporary.delete())
        f.platform.onPost = {}
        assertEquals(PersonalReminders.Status.DELIVERY_UNKNOWN, f.manager().records().single().status)
        f.manager().deliver(approved.plan.id)
        f.manager().restore()
        assertEquals(1, f.platform.deliveries)
    }

    @Test fun parserKeepsReminderTitlesAsDataAndRejectsPartialOrMalformedManagementCommands() {
        val parsed = ReminderCommands.parse("remind me in ten minutes to Call Mom then open Maps")!!
        assertEquals(600, parsed.seconds)
        assertEquals("Call Mom then open Maps", parsed.title)
        assertEquals(600, ReminderCommands.parse("remind me to Check the oven in ten minutes")!!.seconds)
        assertEquals("Check the oven", ReminderCommands.parse("remind me to Check the oven in ten minutes")!!.title)
        assertNull(ReminderCommands.parse("remind me in zero minutes to test")!!.seconds)
        assertNull(ReminderCommands.parse("remind me in ten minutes then reboot to test")!!.seconds)
        assertEquals(ReminderCommands.Command.INVALID, ReminderCommands.parse("approve reminder then reboot")!!.command)
        assertEquals(ReminderCommands.Command.INVALID, ReminderCommands.parse("show my reminders then reboot")!!.command)
        assertEquals(ReminderCommands.Command.CONFIRM_SHOWN, ReminderCommands.parse("reminder 2 was shown")!!.command)
        assertNull(ReminderCommands.parse("remind me to check the calendar")) // Existing calendar flow remains available.
        assertNull(ReminderCommands.parse("remind me to review the report in French"))
    }
}
