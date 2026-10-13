package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class CapabilityRequestTrialTest {
    private val resolver: (String) -> Pair<String, String>? = { if (it.equals("Chrome", true)) "com.android.chrome" to "Chrome" else null }

    @Test fun trialStagesCanonicalTimerApprovalWithoutMutatingBacklogOrLearning() {
        var backlog: String? = null
        val writer = CapabilityRequestStore({ backlog }, { backlog = it; true }, { 1000 })
        writer.add("start a timer for 10 minutes")
        val before = backlog
        val reader = CapabilityRequestStore({ backlog }, { fail("trial must not write backlog"); false })
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        val reply = CapabilityRequestTrial.prepare(reader.find("start a timer for 10 minutes")!!, resolver,
            session::propose, { _, _ -> fail("not a volume trial"); false })
        assertTrue(reply.contains("Say yes")); assertTrue(reply.contains("Nothing has run yet"))
        assertEquals(before, backlog)
        val approval = session.consume("yes")!!
        assertTrue(approval.accepted); assertFalse(approval.pending.awaitingOutcome)
        assertEquals("start a timer for 600 seconds", approval.pending.phrase)
        assertEquals("600", approval.pending.action.parameters["seconds"])
        assertFalse(approval.pending.shouldLearn(true)); assertFalse(approval.pending.explicitTeaching)
        assertNull(session.consume("yes"))
        session.propose(approval.pending.copy(awaitingOutcome = true))
        assertTrue(session.consume("yes")!!.reminder)
        assertTrue(session.consume("it worked")!!.accepted)
        assertEquals(before, backlog)
    }

    @Test fun cancellationExpiryAndFailedPersistenceNeverGrantApproval() {
        var raw: String? = null
        var clock = 1000L
        val session = DeviceLearningSession({ raw }, { raw = it; true }, { clock })
        val entry = CapabilityRequestStore.Entry("start timer for 5 minutes", 1000)
        fun prepare() = CapabilityRequestTrial.prepare(entry, resolver, session::propose, { _, _ -> false })
        prepare(); assertFalse(session.consume("no")!!.accepted); assertNull(session.consume("yes"))
        prepare(); clock += 300001; assertNull(session.consume("yes"))
        val failed = DeviceLearningSession({ null }, { false })
        assertTrue(CapabilityRequestTrial.prepare(entry, resolver, failed::propose, { _, _ -> false }).contains("Could not retain"))
        assertNull(failed.consume("yes"))
    }

    @Test fun appTargetIsResolvedAgainForEachTrialAndUnavailableTargetsNeverStage() {
        var available = true
        var calls = 0
        var pending: DeviceLearningSession.Pending? = null
        val resolve: (String) -> Pair<String, String>? = { calls++; if (available) resolver(it) else null }
        val entry = CapabilityRequestStore.Entry("open Chrome", 1000)
        CapabilityRequestReview.assess(entry.text, resolve)
        available = false
        val rejected = CapabilityRequestTrial.prepare(entry, resolve, { pending = it; true }, { _, _ -> false })
        assertNull(pending); assertTrue(rejected.contains("couldn't resolve")); assertEquals(2, calls)
        available = true
        CapabilityRequestTrial.prepare(entry, resolve, { pending = it; true }, { _, _ -> false })
        val bound = pending!!
        assertEquals("com.android.chrome", bound.action.parameters["package"])
        assertEquals("open Chrome", bound.phrase); assertFalse(bound.learnable)
    }

    @Test fun exactVolumeUsesNonLearningExpiringOneShotApproval() {
        var raw: String? = null
        val volume = VolumeConfirmationStore({ raw }, { raw = it; true })
        val response = CapabilityRequestTrial.prepare(CapabilityRequestStore.Entry("turn volume all the way up", 1000), resolver,
            { fail("not a device trial"); false }, { phrase, lesson -> volume.propose(phrase, lesson) })
        assertTrue(response.contains("100%")); assertTrue(response.contains("Nothing has changed yet"))
        val approval = volume.consume("yes")!!
        assertEquals(100, ExactVolumeLearning.fromLesson(approval.pending.lesson))
        assertEquals("set media volume to 100%", approval.pending.phrase)
        assertFalse(approval.pending.lesson.canTeachLocally()); assertFalse(approval.pending.explicitTeaching)
        assertNull(volume.consume("yes"))
    }

    @Test fun unsupportedRequestsDoNotReachEitherApprovalStore() {
        for (text in listOf("update app", "lower volume", "check calendar before leaving", "start timer for 25 hours", "open Missing", "don't open Chrome", "open Chrome and call Mom")) {
            val reply = CapabilityRequestTrial.prepare(CapabilityRequestStore.Entry(text, 1000), resolver,
                { fail("unsupported device proposal: $text"); false }, { _, _ -> fail("unsupported volume proposal: $text"); false })
            assertTrue(reply.contains("Nothing was run"))
        }
    }

    @Test fun trialControlIsExactReservedAndInvalidatesOldOutcomeInsteadOfConfirmingIt() {
        assertEquals("open Chrome", CapabilityDiscovery.trial(" Try capability request \"open Chrome\"! "))
        assertEquals("", CapabilityDiscovery.trial("try capability request"))
        assertNull(CapabilityDiscovery.trial("don't try capability request open Chrome"))
        val memory = ValidatedCommandMemory({ "[]" }, { fail("control cannot be learned"); false })
        assertFalse(memory.recordSuccess("try capability request open Chrome", "volume_up", VerifiedVolumeLearning.capabilities))
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true })
        session.propose(DeviceLearningSession.Pending("old timer", LearnableDeviceAction("timer", mapOf("seconds" to "300")), false, awaitingOutcome = true))
        assertNull(session.consume("try capability request open Chrome"))
        assertNull(session.consume("it worked"))
    }
}
