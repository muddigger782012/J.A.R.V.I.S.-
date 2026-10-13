package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class TeachSavedCapabilityTest {
    @Test fun numberedTeachingParsesExplicitAliasAndReservesTheManagementControl() {
        val parsed = CapabilityDiscovery.teaching("Teach capability request number one as \"work break\"!")!!
        assertEquals("number one", parsed.reference); assertEquals("work break", parsed.alias)
        assertFalse(CapabilityDiscovery.needsClarification("teach capability request number one as work break"))
        for (text in listOf("teach capability request number one", "don't teach capability request number one as work break", "teach capability request number one and two as work break"))
            assertTrue(text, CapabilityDiscovery.needsClarification(text))
        val memory = ValidatedCommandMemory({ "[]" }, { fail("management control must not be learned"); false })
        assertFalse(memory.recordSuccess("teach capability request number one as work break", "volume_up", VerifiedVolumeLearning.capabilities))
    }

    @Test fun timerAliasRequiresFreshApprovalAndOutcomeAndSurvivesSupportedProposalEdit() {
        var backlog: String? = null
        val store = CapabilityRequestStore({ backlog }, { backlog = it; true })
        store.add("start timer for 10 minutes")
        val before = backlog
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val reply = CapabilityRequestTrial.prepare(store.findReference("number one")!!, { null }, session::propose,
            { _, _ -> fail("not volume"); false }, alias = "work break")
        assertTrue(reply.contains("'work break' will start a 600-second timer"))
        assertTrue(reply.contains("Nothing has run or been learned yet"))
        val edit = session.consume("make that 5 minutes")!!
        assertEquals("300", edit.pending.action.parameters["seconds"])
        assertEquals("work break", edit.pending.phrase); assertTrue(edit.pending.explicitTeaching)
        val approval = session.consume("yes")!!
        assertTrue(approval.accepted); assertFalse(approval.pending.awaitingOutcome)
        assertTrue(approval.pending.shouldLearn(false))
        assertNull(session.consume("it worked"))
        session.propose(approval.pending.copy(awaitingOutcome = true))
        assertTrue(session.consume("yes")!!.reminder)
        val outcome = session.consume("it worked")!!
        assertTrue(outcome.accepted); assertTrue(outcome.pending.awaitingOutcome)
        var lessons: String? = null
        val memory = ValidatedCommandMemory({ lessons ?: "[]" }, { lessons = it; true })
        assertTrue(memory.recordSuccess(outcome.pending.phrase, outcome.pending.action.capabilityId,
            LearnableDeviceAction.capabilities, outcome.pending.action.parameters))
        assertEquals("300", memory.match("work break", LearnableDeviceAction.capabilities)!!.parameters["seconds"])
        assertEquals(before, backlog)
    }

    @Test fun cancelledFailedExpiredOrUnsupportedTeachingNeverCreatesLesson() {
        var raw: String? = null
        var clock = 1000L
        val session = DeviceLearningSession({ raw }, { raw = it; true }, { clock })
        val entry = CapabilityRequestStore.Entry("start timer for 10 minutes", 1000)
        fun prepare() = CapabilityRequestTrial.prepare(entry, { null }, session::propose, { _, _ -> false }, "work break")
        prepare(); assertFalse(session.consume("no")!!.accepted); assertNull(session.consume("yes"))
        prepare(); val approval = session.consume("yes")!!.pending
        session.propose(approval.copy(awaitingOutcome = true)); assertFalse(session.consume("it failed")!!.accepted)
        assertNull(session.consume("it worked"))
        prepare(); clock += 300001; assertNull(session.consume("yes"))
        for (name in listOf("yes", "it worked", "try capability request number one", "show status", "open Chrome")) {
            val reply = CapabilityRequestTrial.prepare(entry, { null }, { fail("reserved alias"); false }, { _, _ -> false }, name)
            assertTrue(reply.contains("non-reserved"))
        }
        for (target in listOf("open Missing", "check my calendar", "update app", "lower volume"))
            CapabilityRequestTrial.prepare(CapabilityRequestStore.Entry(target, 1000), { null },
                { fail("unsupported target"); false }, { _, _ -> fail("unsupported target"); false }, "work break")
        assertTrue(CapabilityRequestTrial.prepare(entry, { null }, { false }, { _, _ -> false }, "work break").contains("Could not retain"))
    }

    @Test fun volumeAliasIsExplicitAndRecordsOnlyAfterVerifiedReadback() {
        var raw: String? = null
        val volume = VolumeConfirmationStore({ raw }, { raw = it; true })
        CapabilityRequestTrial.prepare(CapabilityRequestStore.Entry("set media volume to 25%", 1000), { null },
            { fail("not app/timer"); false }, { phrase, lesson -> volume.propose(phrase, lesson, explicitTeaching = true) }, "quiet work")
        val approval = volume.consume("yes")!!
        assertEquals("quiet work", approval.pending.phrase); assertTrue(approval.pending.explicitTeaching)
        assertTrue(approval.pending.lesson.canTeachLocally())
        var writes = 0
        val failed = ExactVolumeLearning.execute(approval.pending.phrase, 25, true, { false }, { _, _ -> writes++; true })
        assertFalse(failed.learned); assertEquals(0, writes)
        val success = ExactVolumeLearning.execute(approval.pending.phrase, 25, true, { it == 25 }, { _, params -> writes++; params["percent"] == "25" })
        assertTrue(success.learned); assertEquals(1, writes)
        assertNull(volume.consume("yes"))
    }
}
