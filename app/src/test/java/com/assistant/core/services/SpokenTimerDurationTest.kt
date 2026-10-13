package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class SpokenTimerDurationTest {
    @Test fun spokenNumericAndCompoundDurationsResolveExactly() {
        val examples = mapOf("five minutes" to 300, "ten minutes" to 600, "twenty-five minutes" to 1500,
            "one hour and thirty minutes" to 5400, "1 hour 30 minutes 5 seconds" to 5405,
            "one hundred and twenty five seconds" to 125, "a minute" to 60,
            "twenty four hours" to 86400, "eighty six thousand four hundred seconds" to 86400,
            "0 hours and 5 minutes" to 300, "1 second" to 1)
        examples.forEach { (text, seconds) ->
            assertEquals(text, seconds, SpokenTimerDuration.seconds(text))
            assertEquals(text, seconds, SpokenTimerDuration.command("Start a timer for $text!"))
            assertEquals(seconds.toString(), ExplicitCommandTeaching.resolveDevice("set timer for $text") { error("not an app") }!!.parameters["seconds"])
        }
    }
    @Test fun ambiguousNegatedMixedAndOutOfRangeWordingCannotProduceATimer() {
        for (text in listOf("zero seconds", "25 hours", "twenty four hours and one second", "-1 minute", "1.5 minutes", "half an hour",
            "one one minute", "twenty ten minutes", "one hundred and seconds", "one thousand and seconds", "one minute and",
            "5 minutes or 10 seconds", "one minute and open Chrome", "one second 1 hour", "five minutes five minutes", "999999999 seconds")) {
            assertNull(text, SpokenTimerDuration.seconds(text))
            assertNull(text, SpokenTimerDuration.command("start timer for $text"))
        }
        for (text in listOf("don't start timer for five minutes", "explain start timer for five minutes", "start timer for five minutes and open Chrome", "try capability request start timer for five minutes"))
            assertNull(text, SpokenTimerDuration.command(text))
    }
    @Test fun spokenProposalEditsRetainApprovalAndOutcomeBoundaries() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true }, { 1000 })
        val timer = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
        assertNull(session.consume("make that ten minutes"))
        assertTrue(session.propose(DeviceLearningSession.Pending("work break", timer, true, explicitTeaching = true)))
        val edit = session.consume("Actually, make that one hour and thirty minutes!")!!
        assertEquals(DeviceLearningSession.Correction.UPDATED, edit.correction)
        assertEquals("5400", edit.pending.action.parameters["seconds"])
        assertEquals("work break", edit.pending.phrase)
        assertFalse(edit.accepted); assertFalse(edit.pending.awaitingOutcome)
        val accepted = session.consume("yes")!!
        assertTrue(accepted.accepted)
        assertTrue(session.propose(accepted.pending.copy(awaitingOutcome = true)))
        assertEquals(DeviceLearningSession.Correction.ALREADY_REQUESTED, session.consume("make that five minutes")!!.correction)
        assertEquals("5400", session.consume("it worked")!!.pending.action.parameters["seconds"])
    }
    @Test fun savedRequestReviewAndTrialUseTheSameDurationWithoutExecutionOrLearning() {
        val entry = CapabilityRequestStore.Entry("start a timer for ten minutes", 1000)
        assertEquals("600", CapabilityRequestReview.assess(entry.text) { null }.definition!!.parameters["seconds"])
        var proposal: DeviceLearningSession.Pending? = null
        val reply = CapabilityRequestTrial.prepare(entry, { null }, { proposal = it; true }, { _, _ -> error("not volume") }, "work break")
        assertTrue(reply.contains("Say yes"))
        assertEquals("600", proposal!!.action.parameters["seconds"])
        assertFalse(proposal!!.awaitingOutcome)
    }
}
