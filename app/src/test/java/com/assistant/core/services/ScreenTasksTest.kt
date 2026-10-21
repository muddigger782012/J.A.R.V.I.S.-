package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class ScreenTasksTest {
    private fun button(path: String, label: String, context: String = "") = ScreenControls.Control(path, label, true, false,
        ScreenSemantics.Role.BUTTON, context)
    private class Phone : ScreenControls.Platform {
        var available = true
        var reads = 0
        var actions = 0
        var lastPath = ""
        var change = true
        var throwAfterDispatch = false
        var result = ScreenControls.Result.ACCEPTED
        var value = ScreenControls.Snapshot("start", "example", listOf(
            ScreenControls.Control("0/1", "Menu", true, false, ScreenSemantics.Role.BUTTON)), listOf("Start page"), appLabel = "Sample app")
        override fun connected() = available
        override fun snapshot(): ScreenControls.Snapshot { reads++; return value }
        override fun perform(snapshot: ScreenControls.Snapshot, control: ScreenControls.Control, scroll: ScreenControls.Kind?): ScreenControls.Result {
            actions++; lastPath = control.path
            if (throwAfterDispatch) error("transport uncertainty")
            if (change && result == ScreenControls.Result.ACCEPTED) value = if (actions == 1)
                ScreenControls.Snapshot("menu", "example", listOf(ScreenControls.Control("0/2", "Downloads", true, false,
                    ScreenSemantics.Role.BUTTON)), listOf("Menu page"), appLabel = "Sample app")
            else ScreenControls.Snapshot("downloads", "example", emptyList(), listOf("Downloads page"), appLabel = "Sample app")
            return result
        }
        override fun global(kind: ScreenControls.Kind): ScreenControls.Result { actions++; return result }
    }
    private fun start(session: ScreenSession) = session.reply("help me open Menu then Downloads and verify Downloads page")

    @Test fun naturalRequestsBecomeBoundedDataRatherThanHostCommands() {
        val request = ScreenTaskCommands.parse("help me open Menu then run shell reboot and verify Done")!!
        assertEquals(ScreenTaskCommands.Kind.START, request.kind)
        assertEquals(listOf("Menu", "run shell reboot"), request.steps.map { it.label })
        assertTrue(request.steps.all { it.kind == ScreenControls.Kind.TAP_LABEL })
        assertNull(ScreenTaskCommands.parse("help me open Maps"))
        assertNull(ScreenTaskCommands.parse("yes"))
        assertEquals(ScreenTaskCommands.Kind.INVALID, ScreenTaskCommands.parse("screen task tap Menu; reboot")!!.kind)
        assertEquals(ScreenTaskCommands.Kind.INVALID, ScreenTaskCommands.parse("screen task tap A then B then C then D then E and verify Done")!!.kind)
    }
    @Test fun planReviewAndApprovalCannotTapWithoutTheCurrentNumberedStepConfirmation() {
        val phone = Phone(); val session = ScreenSession(phone)
        assertTrue(start(session).contains("approve screen task")); assertEquals(0, phone.reads)
        session.reply("yes"); session.reply("confirm screen step one")
        assertEquals(0, phone.actions)
        assertTrue(session.reply("approve screen task").contains("confirm screen step one"))
        assertEquals(0, phone.actions)
        session.reply("confirm screen action"); assertTrue(session.active); assertEquals(0, phone.actions)
        session.reply("confirm screen step one"); assertEquals(1, phone.actions)
        session.reply("confirm screen step one"); assertEquals(1, phone.actions)
        assertTrue(session.reply("continue screen task").contains("confirm screen step two"))
        session.reply("confirm screen step one"); assertEquals(1, phone.actions)
        session.reply("confirm screen step two"); assertEquals(2, phone.actions)
        assertTrue(session.reply("continue screen task").contains("Verified the requested visible screen text"))
        assertFalse(session.active)
        session.reply("confirm screen step two"); session.reply("continue screen task"); assertEquals(2, phone.actions)
    }
    @Test fun unchangedOrUnexpectedScreensNeverRepeatAnAcceptedTap() {
        val phone = Phone(); phone.change = false; val session = ScreenSession(phone)
        start(session); session.reply("approve screen task"); session.reply("confirm screen step one")
        repeat(3) { assertTrue(session.reply("continue screen task").contains("not changed")) }
        phone.value = phone.value.copy(key = "different", text = listOf("Unexpected page"), controls = emptyList())
        assertTrue(session.reply("continue screen task").contains("next expected control"))
        session.reply("confirm screen step one"); assertEquals(1, phone.actions)
    }
    @Test fun missingFinalPostconditionIsReportedWithoutDeclaringTheTaskComplete() {
        val phone = Phone(); val session = ScreenSession(phone)
        session.reply("screen task tap Menu and verify Final page")
        session.reply("approve screen task"); session.reply("confirm screen step one")
        assertTrue(session.reply("continue screen task").contains("not visible yet"))
        assertTrue(session.active); assertEquals(1, phone.actions)
        phone.value = phone.value.copy(key = "final", text = listOf("Final page"))
        assertTrue(session.reply("continue screen task").contains("Verified")); assertFalse(session.active)
    }
    @Test fun ambiguousTargetsRequireFreshScopedNumberChoices() {
        val phone = Phone(); phone.value = phone.value.copy(controls = listOf(button("0/1", "Menu", "Work"), button("0/2", "Menu", "Personal")))
        val session = ScreenSession(phone)
        start(session)
        assertTrue(session.reply("approve screen task").contains("no duplicate"))
        session.reply("confirm screen step one"); assertEquals(0, phone.actions)
        session.reply("use task control two"); session.reply("confirm screen step one")
        assertEquals("0/2", phone.lastPath); assertEquals(1, phone.actions)
    }
    @Test fun changedChoicesOrTargetsCannotUseAnOldStepReview() {
        val phone = Phone(); phone.value = phone.value.copy(controls = listOf(button("0/1", "Menu"), button("0/2", "Menu")))
        val session = ScreenSession(phone); start(session); session.reply("approve screen task")
        phone.value = phone.value.copy(key = "changed")
        assertTrue(session.reply("use task control two").contains("changed"))
        session.reply("confirm screen step one"); assertEquals(0, phone.actions)
        phone.value = phone.value.copy(controls = listOf(button("0/1", "Menu")))
        session.reply("continue screen task"); phone.value = phone.value.copy(key = "another change")
        session.reply("confirm screen step one"); assertEquals(0, phone.actions)
    }
    @Test fun onlyAnExplicitCorrectionCanReplaceAnUnattemptedStep() {
        val phone = Phone(); phone.value = phone.value.copy(controls = listOf(button("0/1", "More options")))
        val session = ScreenSession(phone); start(session)
        assertTrue(session.reply("change screen step to More options").contains("More options"))
        session.reply("confirm screen step one"); assertEquals(0, phone.actions)
        session.reply("approve screen task"); session.reply("confirm screen step one"); assertEquals(1, phone.actions)
        assertTrue(session.reply("change screen step to Menu").contains("unattempted"))
    }
    @Test fun localMenuAlternativesAreReviewedAndNeverPickTheFirstAmbiguousAlias() {
        val phone = Phone(); phone.value = phone.value.copy(controls = listOf(button("0/1", "More options")))
        val session = ScreenSession(phone); start(session)
        assertTrue(session.reply("approve screen task").contains("More options")); assertEquals(0, phone.actions)
        session.reply("cancel screen task")
        phone.value = phone.value.copy(controls = listOf(button("0/1", "More options"), button("0/2", "Navigation menu")))
        start(session); assertTrue(session.reply("approve screen task").contains("no duplicate")); assertEquals(0, phone.actions)
    }
    @Test fun pauseRetainsIntentWhileDiscardingTheOldApprovalAndCancellationEndsLaterSteps() {
        val phone = Phone(); val session = ScreenSession(phone)
        start(session); session.reply("approve screen task"); session.pause()
        assertTrue(session.active); assertFalse(session.contextual)
        assertNull(ScreenTaskCommands.parse("yes", session.contextual))
        assertTrue(session.reply("screen task status").contains("paused"))
        session.reply("confirm screen step one"); assertEquals(0, phone.actions)
        session.reply("continue screen task"); session.reply("confirm screen step one"); assertEquals(1, phone.actions)
        session.reply("cancel")
        session.reply("continue screen task"); session.reply("confirm screen step two"); assertEquals(1, phone.actions)
        assertFalse(session.active)
    }
    @Test fun unknownOrThrowingDispatchCannotBeResumedOrRetried() {
        for (throwing in listOf(false, true)) {
            val phone = Phone(); phone.result = ScreenControls.Result.UNKNOWN; phone.throwAfterDispatch = throwing
            val session = ScreenSession(phone); start(session); session.reply("approve screen task")
            assertTrue(session.reply("confirm screen step one").contains("uncertain"))
            repeat(2) { session.reply("continue screen task"); session.reply("approve screen task"); session.reply("confirm screen step one") }
            assertEquals(1, phone.actions); assertFalse(session.active)
        }
    }
    @Test fun protectedDifferentAppAndRevokedAccessNeverAuthorizeAnotherStep() {
        for (change in 0..2) {
            val phone = Phone(); val session = ScreenSession(phone); start(session); session.reply("approve screen task")
            when (change) {
                0 -> phone.value = phone.value.copy(blocked = true, text = listOf("private-password"))
                1 -> phone.value = phone.value.copy(packageName = "another.app", key = "new app")
                2 -> phone.available = false
            }
            val answer = session.reply("confirm screen step one")
            assertFalse(answer.contains("private-password")); assertEquals(0, phone.actions)
        }
    }
    @Test fun expiryReconstructionAndAnotherControlCannotReviveATaskApproval() {
        var time = 1000L; val phone = Phone(); val session = ScreenSession(phone) { time }
        start(session); session.reply("approve screen task"); time += 300_001
        assertTrue(session.reply("confirm screen step one").contains("expired")); assertEquals(0, phone.actions)
        start(session); session.reply("approve screen task")
        ScreenSession(phone).reply("confirm screen step one"); assertEquals(0, phone.actions)
        session.reply("tap Menu"); assertFalse(session.active)
        session.reply("confirm screen step one"); assertEquals(0, phone.actions)
    }
    @Test fun aMissingOutcomeIsClarifiedAcrossTurnsBeforeAnyScreenRead() {
        val phone = Phone(); val session = ScreenSession(phone)
        assertTrue(session.reply("screen task open Menu then Downloads").contains("exact visible text"))
        assertEquals(0, phone.reads)
        assertTrue(session.reply("screen task outcome Downloads page").contains("approve screen task"))
        assertEquals(0, phone.actions)
    }
}
