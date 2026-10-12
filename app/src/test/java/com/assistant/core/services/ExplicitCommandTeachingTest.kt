package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class ExplicitCommandTeachingTest {
    @Test fun resolvesOnlySingleBoundTargetsAndNonReservedNames() {
        val request = ExplicitCommandTeaching.parse("Teach Jarvis that coffee break means start a timer for 5 minutes")!!
        assertEquals("coffee break", request.phrase)
        assertEquals(mapOf("seconds" to "300"), ExplicitCommandTeaching.resolveDevice(request.target) { error("Timer must not resolve apps") }!!.parameters)
        assertEquals(25, ExactVolumeLearning.parse(ExplicitCommandTeaching.parse("Teach that desk level means set media volume to 25%")!!.target))
        val app = ExplicitCommandTeaching.resolveDevice("open Chrome") { "com.android.chrome" to "Chrome" }!!
        assertEquals("com.android.chrome", app.parameters["package"])
        assertNull(ExplicitCommandTeaching.resolveDevice("open Chrome") { null })
        assertNull(ExplicitCommandTeaching.resolveDevice("open Chrome") { "com.bad.app" to "Different" })
        for (target in listOf("start a timer for 0 minutes", "start a timer for 25 hours", "start a timer for 5 minutes and open Chrome", "run shell id", "call someone"))
            assertNull(ExplicitCommandTeaching.resolveDevice(target) { null })
        for (phrase in listOf("yes", "it worked", "show learned commands", "update app", "set media volume to 25%"))
            assertNull(ExplicitCommandTeaching.parse("Teach that $phrase means open Chrome"))
        assertNull(ExplicitCommandTeaching.parse("Don't teach coffee break means start a timer for 5 minutes"))
    }

    @Test fun explicitTimerTeachingPersistsAcrossApprovalButNeverLearnsBeforeOutcome() {
        var pending: String? = null
        var stored = "[]"
        val memory = ValidatedCommandMemory({ stored }, { stored = it ?: "[]"; true })
        fun session() = DeviceLearningSession({ pending }, { pending = it; true }, { 1000 })
        val action = LearnableDeviceAction("timer", mapOf("seconds" to "300"))
        val original = DeviceLearningSession.Pending("coffee break", action, learnable = true, explicitTeaching = true)
        assertTrue(session().propose(original))
        assertTrue(memory.snapshot().isEmpty())
        val approved = session().consume("yes")!!
        assertTrue(approved.accepted); assertFalse(approved.pending.awaitingOutcome)
        assertTrue(session().propose(approved.pending.copy(awaitingOutcome = true)))
        assertTrue(memory.snapshot().isEmpty())
        val result = session().consume("it worked")!!
        assertTrue(result.accepted); assertTrue(result.pending.awaitingOutcome)
        assertTrue(result.pending.shouldLearn(automaticEnabled = false))
        assertTrue(memory.recordSuccess(result.pending.phrase, result.pending.action.capabilityId, LearnableDeviceAction.capabilities, result.pending.action.parameters))
        assertEquals("300", memory.match("coffee break", LearnableDeviceAction.capabilities)!!.parameters["seconds"])
        assertNull(memory.match("yes", LearnableDeviceAction.capabilities))
        assertNull(memory.match("it worked", LearnableDeviceAction.capabilities))
        assertFalse(original.copy(explicitTeaching = false).shouldLearn(false))
        assertFalse(original.copy(learnable = false).shouldLearn(true))
        assertNull(session().consume("it worked"))
    }

    @Test fun failureCancellationAndInvalidManualFlagsCannotAuthorizeLearning() {
        var raw: String? = null
        val action = LearnableDeviceAction("open_app", mapOf("package" to "com.android.chrome", "label" to "Chrome"))
        val session = DeviceLearningSession({ raw }, { raw = it; true }, { 1000 })
        val original = DeviceLearningSession.Pending("browse desk", action, true, explicitTeaching = true)
        assertFalse(session.propose(original.copy(learnable = false)))
        assertFalse(session.propose(original.copy(replayed = true, awaitingOutcome = true)))
        assertTrue(session.propose(original)); assertFalse(session.consume("no")!!.accepted)
        assertTrue(session.propose(original)); assertNull(session.consume("another request"))
        assertTrue(session.propose(original.copy(awaitingOutcome = true)))
        assertFalse(session.consume("it failed")!!.accepted)
        assertNull(session.consume("it worked"))
    }

    @Test fun explicitVolumeApprovalRetainsAliasAndConsentWithoutAutomaticLearning() {
        var pending: String? = null
        val store = VolumeConfirmationStore({ pending }, { pending = it; true }, { 1000 })
        val lesson = FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume", "percent" to "25"), learnable = true)
        assertTrue(store.propose("desk level", lesson, explicitTeaching = true))
        val approval = store.consume("yes")!!
        assertTrue(approval.approved); assertTrue(approval.pending.explicitTeaching)
        assertEquals("desk level", approval.pending.phrase)
        assertEquals(25, ExactVolumeLearning.fromLesson(approval.pending.lesson))
        assertFalse(store.propose("desk level", lesson.copy(learnable = false), explicitTeaching = true))
        assertNull(store.consume("yes"))
    }
}
