package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class ContactCallWorkflowTest {
    private class Platform : ContactCallWorkflow.Platform {
        var rows = listOf(ContactCallWorkflow.Recipient("1", "Alex", "+1 202 555 0100"), ContactCallWorkflow.Recipient("2", "Alex", "+1 202 555 0101"))
        var permission = true; var unchanged = true; var phone = true; var accepts = true; var calls = 0
        var dial: Boolean? = null; var after: () -> Unit = {}
        override fun lookup(query: String) = ContactCallWorkflow.Lookup(rows, permission || ContactCallWorkflow.phone(query) != null)
        override fun current(recipient: ContactCallWorkflow.Recipient) = unchanged
        override fun canCall() = phone
        override fun request(recipient: ContactCallWorkflow.Recipient, dialOnly: Boolean): Boolean {
            calls++; dial = dialOnly; after(); return accepts
        }
    }
    private class Fixture(val directory: File) {
        val platform = Platform(); var time = 1_000_000L
        val path get() = File(directory, "contact-call.json")
        fun manager() = ContactCallWorkflow(PersonalStateFile(directory, path.name), platform) { time }
        fun commands() = ContactCallCommands { manager() }
        fun prepared(): ContactCallWorkflow.Plan {
            manager().prepare("Alex"); val plan = manager().pending()!!
            manager().select(plan.id, 2); return manager().pending()!!
        }
    }
    private inline fun fixture(block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("contact-call-test").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }
    @Test fun lookupChoicesBareYesAndApprovalWithoutSelectionCannotPlaceACall() = fixture { f ->
        val c = f.commands()
        assertTrue(c.reply(ContactCallCommands.parse("find contact Alex and confirm call")!!).contains("Contact choices"))
        assertTrue(c.confirmation("yes")!!.contains("bare confirmation"))
        c.reply(ContactCallCommands.parse("approve contact call")!!)
        assertEquals(0, f.platform.calls)
        assertTrue(c.reply(ContactCallCommands.parse("choose contact 2")!!).contains("0101"))
        assertTrue(c.reply(ContactCallCommands.parse("approve contact call")!!).contains("Requested a call"))
        c.reply(ContactCallCommands.parse("approve contact call")!!)
        assertEquals(1, f.platform.calls)
        assertTrue(f.manager().status().contains("connection or completion is not verified"))
    }
    @Test fun restoredOrReplacedReviewsMustBePresentedAgainBeforeApproval() = fixture { f ->
        val first = f.prepared()
        val restored = f.commands()
        assertTrue(restored.reply(ContactCallCommands.parse("approve contact call")!!).contains("before approving again"))
        assertEquals(0, f.platform.calls)
        f.manager().prepare("2025550199")
        assertTrue(f.manager().approve(first).contains("no longer current"))
        assertTrue(restored.reply(ContactCallCommands.parse("approve contact call")!!).contains("before approving again"))
        assertEquals(0, f.platform.calls)
        restored.reply(ContactCallCommands.parse("approve contact call")!!)
        assertEquals(1, f.platform.calls)
    }
    @Test fun aConcurrentReplacementBetweenReadingAndRenderingCannotBindAnUnseenRecipient() = fixture { f ->
        f.prepared(); val old = f.path.readText()
        f.manager().prepare("2025550199"); val replacement = f.path.readText()
        f.path.writeText(old)
        var replace = true
        val manager = ContactCallWorkflow(PersonalStateFile(f.directory, f.path.name), f.platform) {
            if (replace) { replace = false; f.path.writeText(replacement) }
            f.time
        }
        val commands = ContactCallCommands { manager }
        assertTrue(commands.reply(ContactCallCommands.parse("review contact call")!!).contains("0101"))
        assertTrue(commands.reply(ContactCallCommands.parse("approve contact call")!!).contains("0199"))
        assertEquals(0, f.platform.calls)
    }
    @Test fun malformedControlsCannotBindAnUnseenReview() = fixture { f ->
        f.prepared(); val commands = f.commands()
        commands.reply(ContactCallCommands.parse("approve contact call then search the service manual")!!)
        commands.reply(ContactCallCommands.parse("choose contact 2 then reboot")!!)
        assertEquals(0, f.platform.calls)
        assertTrue(commands.reply(ContactCallCommands.parse("approve contact call")!!).contains("before approving again"))
        assertEquals(0, f.platform.calls)
    }
    @Test fun expiredFutureClockAndChangedContactsDoNotDispatch() = fixture { f ->
        val plan = f.prepared(); f.time += 120_000
        assertTrue(f.manager().approve(plan).contains("no longer current"))
        f.time = plan.createdAt - 1; assertNull(f.manager().pending())
        f.time += 1_000; val fresh = f.prepared(); f.platform.unchanged = false
        assertTrue(f.manager().approve(fresh).contains("No call was requested"))
        assertEquals(0, f.platform.calls)
    }
    @Test fun missingContactsHasANumberAlternativeAndMissingPhoneUsesTheDialer() = fixture { f ->
        f.platform.permission = false
        assertTrue(f.manager().prepare("Alex").contains("complete phone number"))
        assertNull(f.manager().pending())
        f.manager().prepare("+1 202 555 0100"); f.platform.phone = false
        val reply = f.manager().approve(f.manager().pending()!!)
        assertTrue(reply.contains("Requested the dialer")); assertEquals(true, f.platform.dial)
        assertEquals(1, f.platform.calls)
    }
    @Test fun cancellationAndRejectedRequestsDoNotCreateAutomaticRetries() = fixture { f ->
        val old = f.prepared(); f.manager().cancel(); f.manager().approve(old)
        assertEquals(0, f.platform.calls)
        val plan = f.prepared(); f.platform.accepts = false
        assertTrue(f.manager().approve(plan).contains("Android rejected"))
        f.manager().approve(plan); f.manager().status()
        assertEquals(1, f.platform.calls)
    }
    @Test fun aThrownRequestAndAnInterruptedCheckpointStayUncertainWithoutRepeating() = fixture { f ->
        val plan = f.prepared(); f.platform.after = { error("Synthetic ambiguous dispatch") }
        assertTrue(f.manager().approve(plan).contains("uncertain"))
        val json = JSONObject(f.path.readText()); json.getJSONObject("last").put("status", "DISPATCHING"); f.path.writeText(json.toString())
        assertTrue(f.manager().status().contains("uncertain"))
        f.manager().approve(plan); assertEquals(1, f.platform.calls)
        val changed = f.prepared(); f.platform.unchanged = false
        f.manager().approve(changed)
        assertTrue(f.manager().status().contains("uncertain"))
        assertEquals(1, f.platform.calls)
    }
    @Test fun diskFailureBeforeDispatchDoesNothingAndAfterDispatchNeverRestoresApproval() = fixture { f ->
        val plan = f.prepared(); val temporary = File(f.path.path + ".tmp").apply { mkdir() }
        assertTrue(runCatching { f.manager().approve(plan) }.isFailure); assertEquals(0, f.platform.calls)
        temporary.delete(); f.platform.after = { temporary.mkdir() }
        assertTrue(runCatching { f.manager().approve(plan) }.isFailure); assertEquals(1, f.platform.calls)
        temporary.delete(); assertTrue(f.manager().status().contains("uncertain"))
        f.manager().approve(plan); assertEquals(1, f.platform.calls)
    }
    @Test fun simultaneousVoiceAndChatApprovalsDispatchOnlyOnce() = fixture { f ->
        val plan = f.prepared(); val gate = CountDownLatch(1)
        val threads = (1..2).map { Thread { gate.await(); f.manager().approve(plan) }.apply { start() } }
        gate.countDown(); threads.forEach { it.join() }
        assertEquals(1, f.platform.calls)
    }
    @Test fun phoneParsingNeverExtractsDigitsFromAContactNameOrAcceptsServiceCodes() {
        for (value in listOf("Alex 2", "R2D2", "*123#", "123;456", "123,456", "123\n456", "2")) assertNull(value, ContactCallWorkflow.phone(value))
        assertNotNull(ContactCallWorkflow.phone("+1 (202) 555-0100"))
        val rows = listOf(ContactCallWorkflow.Recipient("1", "Alex", "2025550100"), ContactCallWorkflow.Recipient("2", "Alex Smith", "2025550101"))
        assertEquals(1, ContactCallWorkflow.matches("Alex", rows).size)
        assertTrue(ContactCallWorkflow.matches("Alexa", rows).isEmpty())
    }
    @Test fun corruptOversizedAndUnknownVersionStateFailClosed() = fixture { f ->
        f.prepared(); val original = f.path.readText()
        for (bad in listOf("{broken", original.replace("\"version\":1", "\"version\":9"), " ".repeat(256001))) {
            f.path.writeText(bad)
            assertTrue(runCatching { f.manager().prepare("Alex") }.isFailure)
            assertEquals(0, f.platform.calls)
        }
    }
}
