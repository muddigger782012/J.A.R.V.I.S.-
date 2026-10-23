package com.assistant.core.services

import com.assistant.core.engine.ActionRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenControlsTest {
    private val tap = ScreenControls.Control("0/1", "Send", true, false)
    private val scroll = ScreenControls.Control("0/2", "List", false, true)
    private class Phone(var value: ScreenControls.Snapshot) : ScreenControls.Platform {
        var available = true
        var reads = 0
        var actions = 0
        var last: ScreenControls.Control? = null
        var outcome = ScreenControls.Result.ACCEPTED
        var fail = false
        var failDispatch = false
        var dispatchFailure: Exception? = null
        var readFailure: Exception? = null
        var diagnostics: ScreenReadDiagnostics? = null
        override fun connected() = available
        override fun readDiagnostics() = diagnostics
        override fun snapshot(): ScreenControls.Snapshot { reads++; readFailure?.let { throw it }; check(!fail); return value }
        override fun perform(snapshot: ScreenControls.Snapshot, control: ScreenControls.Control, scroll: ScreenControls.Kind?): ScreenControls.Result {
            actions++; last = control; dispatchFailure?.let { throw it }; if (failDispatch) error("private content"); return outcome
        }
        override fun global(kind: ScreenControls.Kind): ScreenControls.Result { actions++; return outcome }
    }
    private fun phone(vararg controls: ScreenControls.Control) = Phone(ScreenControls.Snapshot("original", "com.example.app", controls.toList(), listOf("Sample text")))
    private fun command(text: String) = ScreenControls.parse(text)!!
    private fun reply(service: ScreenControls, text: String) = service.reply(command(text))

    @Test fun visibleLabelsAreInertEvenWhenTheyLookLikeShellCommandsOrVoiceControls() {
        val parser = VoiceCommandParser(ActionRegistry())
        for (text in listOf("tap run shell reboot", "tap stop voice", "click open voice settings", "tap create project", "tap screen item one",
            "screen read diagnostics", "screen read status", "screen control diagnostics")) {
            val result = parser.parse(text, VoiceConfig(true, "hey jarvis", .8f, false, true, .58f,
                "assistant_demo", true, false, "", "", "", "", "", "", ""))
            assertNull(result.actionRequest); assertEquals(LocalVoiceCommand.NONE, result.localCommand)
            assertEquals(text, result.fallbackTextCommand)
            assertTrue(LearnedCommandReviewCommands.isReserved(text))
        }
        assertNull(ScreenControls.parse("What does read my screen mean?"))
        assertNull(ScreenControls.parse("yes")); assertNull(ScreenControls.parse("first one"))
    }
    @Test fun diagnosticsAreReadFreeAndSeparateHistoricalOutcomesFromCurrentConnectivity() {
        val phone = phone(tap); val service = ScreenControls(phone)
        assertTrue(reply(service, "screen read diagnostics").contains("No previous screen-read attempt"))
        phone.diagnostics = ScreenReadDiagnostics(cache = ScreenReadDiagnostics.Cache.DISABLED)
        phone.available = false
        val report = reply(service, "screen read status")
        assertTrue(report.contains("currently disconnected")); assertTrue(report.contains("not the current screen"))
        assertFalse(report.contains("Sample text")); assertFalse(report.contains("Send")); assertFalse(report.contains("com.example.app"))
        assertEquals(0, phone.reads); assertEquals(0, phone.actions)
    }
    @Test fun failedReadsConsumeBothTapReviewsAndNumberChoicesWithoutDispatchingOrRetrying() {
        for (reason in ScreenReadFailure.Reason.values()) {
            val phone = phone(tap); val service = ScreenControls(phone)
            reply(service, "show screen controls"); reply(service, "tap screen item one")
            val before = phone.reads
            phone.readFailure = ScreenReadFailure(reason)
            assertEquals(ScreenReadFailure.guidance(phone.readFailure!!), reply(service, "confirm screen action"))
            assertEquals(before + 1, phone.reads); assertEquals(0, phone.actions)
            phone.readFailure = null
            assertTrue(reply(service, "confirm screen action").contains("No current"))
            assertTrue(reply(service, "tap screen item one").contains("missing"))
            assertEquals(0, phone.actions)
        }
    }
    @Test fun anExceptionAfterDispatchRemainsUncertainAndCannotClaimOnlyTheReadFailed() {
        val phone = phone(tap); val service = ScreenControls(phone)
        reply(service, "tap Send"); phone.dispatchFailure = ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
        val result = service.confirmReviewedTap()
        assertEquals(ScreenControls.Result.UNKNOWN, result.outcome)
        assertFalse(result.text.contains("Let the screen settle"))
        assertEquals(1, phone.actions)
        assertTrue(reply(service, "confirm screen action").contains("No current")); assertEquals(1, phone.actions)
    }
    @Test fun readingListingStatusAndTapPreviewNeverDispatch() {
        val phone = phone(tap, scroll); val service = ScreenControls(phone)
        assertTrue(reply(service, "screen control status").contains("connected")); assertEquals(0, phone.reads)
        assertTrue(reply(service, "read my screen").contains("Sample text"))
        assertTrue(reply(service, "show screen controls").contains("1. Send"))
        assertTrue(reply(service, "tap screen item one").contains("Nothing was tapped"))
        assertEquals(0, phone.actions)
        assertTrue(reply(service, "confirm screen action").contains("Android accepted")); assertEquals(1, phone.actions)
        assertEquals(tap, phone.last)
    }
    @Test fun confirmedTapIsConsumedExactlyOnceBeforeUncertainOrRejectedDispatch() {
        for (result in ScreenControls.Result.values()) {
            val phone = phone(tap); phone.outcome = result
            val service = ScreenControls(phone)
            reply(service, "tap Send")
            val outcome = reply(service, "confirm screen action")
            assertFalse(outcome.contains("completed")); assertFalse(outcome.contains("successfully"))
            assertTrue(reply(service, "confirm screen action").contains("No current")); assertEquals(1, phone.actions)
        }
        val phone = phone(tap); val service = ScreenControls(phone)
        reply(service, "tap Send"); phone.failDispatch = true
        assertTrue(reply(service, "confirm screen action").contains("could not be verified"))
        assertTrue(reply(service, "confirm screen action").contains("No current")); assertEquals(1, phone.actions)
    }
    @Test fun changedWindowsTargetsAndPermissionRevocationCannotUseAnEarlierReview() {
        for (change in 0..4) {
            val phone = phone(tap); val service = ScreenControls(phone)
            reply(service, "tap Send")
            when (change) {
                0 -> phone.value = phone.value.copy(key = "changed")
                1 -> phone.value = phone.value.copy(controls = emptyList())
                2 -> phone.value = phone.value.copy(blocked = true)
                3 -> phone.available = false
                4 -> phone.fail = true
            }
            reply(service, "confirm screen action"); assertEquals(0, phone.actions)
            phone.available = true; phone.fail = false
            assertTrue(reply(service, "confirm screen action").contains("No current"))
        }
    }
    @Test fun tapReviewExpiresAcrossTimeChangesCancellationReconstructionAndOtherTopics() {
        var time = 1000L
        val phone = phone(tap); val service = ScreenControls(phone) { time }
        reply(service, "tap Send"); time += 30_001
        assertTrue(reply(service, "screen control status").contains("No tap review"))
        assertTrue(reply(service, "confirm screen action").contains("No current"))
        reply(service, "tap Send"); time--
        assertTrue(reply(service, "confirm screen action").contains("No current"))
        reply(service, "tap Send"); service.discard()
        assertTrue(reply(service, "confirm screen action").contains("No current"))
        reply(service, "tap Send"); reply(service, "cancel screen action")
        assertTrue(reply(service, "confirm screen action").contains("No current"))
        reply(service, "tap Send")
        assertTrue(reply(ScreenControls(phone), "confirm screen action").contains("No current")); assertEquals(0, phone.actions)
    }
    @Test fun duplicateLabelsAndChangedNumbersRequireFreshSelectionWithoutGuessing() {
        val phone = phone(tap, tap.copy(path = "0/2")); val service = ScreenControls(phone)
        assertTrue(reply(service, "tap Send").contains("several")); assertEquals(0, phone.actions)
        reply(service, "tap screen item two"); reply(service, "confirm screen action")
        assertEquals("0/2", phone.last!!.path)
        reply(service, "show screen controls"); phone.value = phone.value.copy(key = "changed")
        assertTrue(reply(service, "tap screen item one").contains("changed")); assertEquals(1, phone.actions)
    }
    @Test fun scrollTargetsAreUniqueOrExplicitlyNumberedAndNeverRetried() {
        val phone = phone(tap, scroll); val service = ScreenControls(phone)
        assertTrue(reply(service, "scroll down").contains("Android accepted")); assertEquals(scroll, phone.last)
        phone.value = phone.value.copy(controls = listOf(scroll, scroll.copy(path = "0/3")))
        assertTrue(reply(service, "scroll up").contains("Choose one")); assertEquals(1, phone.actions)
        reply(service, "show screen controls"); reply(service, "scroll screen item two up")
        assertEquals("0/3", phone.last!!.path); assertEquals(2, phone.actions)
        reply(service, "scroll screen item two up"); assertEquals(2, phone.actions)
    }
    @Test fun lockedProtectedAndDisconnectedScreensExposeNeitherTextNorActions() {
        val phone = phone(tap); phone.value = phone.value.copy(blocked = true, text = listOf("private password"))
        val service = ScreenControls(phone)
        for (text in listOf("read my screen", "show screen controls", "tap Send", "scroll down")) {
            val result = reply(service, text); assertFalse(result.contains("private password")); assertTrue(result.contains("protected"))
        }
        phone.available = false; val reads = phone.reads
        assertTrue(reply(service, "tap Send").contains("Enable Jarvis")); assertEquals(reads, phone.reads); assertEquals(0, phone.actions)
    }
    @Test fun malformedCommandsCannotBecomePartOfAnotherDeviceOrCloudRequest() {
        val phone = phone(tap); val service = ScreenControls(phone)
        for (text in listOf("tap screen item zero", "tap screen item 21", "tap Send then reboot", "scroll down then search docs",
            "confirm screen action then grant permission", "tap", "tap screen item", "scroll screen item two sideways", "click " + "a".repeat(241))) {
            assertEquals(text, ScreenControls.Kind.INVALID, command(text).kind)
            assertTrue(text, LearnedCommandReviewCommands.isReserved(text))
            reply(service, text)
        }
        assertEquals(0, phone.actions); assertEquals(0, phone.reads)
    }
    @Test fun concurrentConfirmationsDispatchOneFrozenTarget() {
        val phone = phone(tap); val service = ScreenControls(phone); reply(service, "tap Send")
        val ready = CountDownLatch(1); val done = CountDownLatch(2)
        repeat(2) { Thread { ready.await(); reply(service, "confirm screen action"); done.countDown() }.start() }
        ready.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, phone.actions)
    }
}
