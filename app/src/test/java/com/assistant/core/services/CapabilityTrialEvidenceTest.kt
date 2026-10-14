package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

class CapabilityTrialEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val timer = LearnableDeviceAction("timer", mapOf("seconds" to "600"))
    @Test fun requestIdentitySurvivesLegacyReadsButForgetAndReaddStartsFresh() {
        var raw: String? = "[{\"text\":\"start timer for ten minutes\",\"created\":1000}]"
        val requests = CapabilityRequestStore({ raw }, { raw = it; true }, { 1000 })
        val original = requests.snapshot().single()
        assertEquals(original.id, requests.snapshot().single().id)
        val store = CapabilityTrialEvidence(temporary.root) { 2000 }
        assertTrue(store.record(original.id, timer, CapabilityTrialEvidence.Outcome.USER_CONFIRMED))
        assertNotNull(store.latest(original))
        assertTrue(requests.forget(original.text))
        requests.add(original.text)
        val replacement = requests.snapshot().single()
        assertNotEquals(original.id, replacement.id)
        assertNull(store.latest(replacement))
    }
    @Test fun trialLinkPersistsAcrossApprovalEditAndOutcomeWithoutVerifyingEditedOriginalTarget() {
        val entry = CapabilityRequestStore.Entry("start timer for ten minutes", 1000)
        var raw: String? = null
        fun session() = DeviceLearningSession({ raw }, { raw = it; true }, { 2000 })
        val store = CapabilityTrialEvidence(temporary.root) { 2000 }
        CapabilityRequestTrial.prepare(entry, { null }, session()::propose, { _, _ -> error("not volume") }, "work break")
        assertNull(store.latest(entry))
        val edit = session().consume("make that five minutes")!!
        assertEquals(entry.id, edit.pending.requestId)
        assertFalse(edit.accepted)
        assertEquals(1000L, entry.created)
        val approval = session().consume("yes")!!
        assertNull(store.latest(entry))
        assertTrue(session().propose(approval.pending.copy(awaitingOutcome = true)))
        val outcome = session().consume("it worked")!!
        assertEquals(entry.id, outcome.pending.requestId)
        assertTrue(store.record(outcome.pending.requestId, outcome.pending.action, CapabilityTrialEvidence.Outcome.USER_CONFIRMED))
        val text = store.render(entry, CapabilityRequestReview.assess(entry.text) { null }.definition)
        assertTrue(text.contains("differs")); assertTrue(text.contains("300-second"))
        assertEquals("300", CapabilityTrialEvidence(temporary.root) { 2000 }.latest(entry)!!.action.parameters["seconds"])
    }
    @Test fun sourceAndExactTargetRemainVisibleAndLatestFailureSupersedesSuccess() {
        val entry = CapabilityRequestStore.Entry("start timer for ten minutes", 1000)
        val store = CapabilityTrialEvidence(temporary.root) { 2000 }
        store.record(entry.id, timer, CapabilityTrialEvidence.Outcome.USER_CONFIRMED)
        assertTrue(store.render(entry, CapabilityRequestReview.assess(entry.text) { null }.definition).contains("User confirmed success"))
        store.record(entry.id, timer, CapabilityTrialEvidence.Outcome.USER_FAILED)
        assertEquals(CapabilityTrialEvidence.Outcome.USER_FAILED, store.latest(entry)!!.outcome)
        assertTrue(store.render(entry, CapabilityRequestReview.assess(entry.text) { null }.definition).contains("new installed feature"))
        assertFalse(store.record(null, timer, CapabilityTrialEvidence.Outcome.USER_CONFIRMED))
        assertFalse(store.record("invalid", timer, CapabilityTrialEvidence.Outcome.USER_CONFIRMED))
        repeat(110) { store.record(entry.id, timer, CapabilityTrialEvidence.Outcome.REQUEST_FAILED) }
        assertEquals(100, store.snapshot().size)
        File(temporary.root, "capability-trial-evidence.json").writeText("broken")
        assertNull(store.latest(entry))
    }
    @Test fun exactVolumeTrialCarriesLinkThroughRestartAndRequiresDeviceReadback() {
        val entry = CapabilityRequestStore.Entry("set media volume to 25%", 1000)
        var pending: String? = null
        fun confirmations() = VolumeConfirmationStore({ pending }, { pending = it; true }, { 2000 })
        val evidence = CapabilityTrialEvidence(temporary.root) { 2000 }
        CapabilityRequestTrial.prepare(entry, { null }, { error("not timer") }, { _, _ -> error("must link") },
            proposeLinkedVolume = { phrase, lesson, id -> confirmations().propose(phrase, lesson, requestId = id) })
        assertNull(evidence.latest(entry))
        val approved = confirmations().consume("yes")!!
        assertTrue(approved.approved)
        assertEquals(entry.id, approved.pending.requestId)
        assertFalse(approved.pending.lesson.learnable)
        assertNull(confirmations().consume("yes"))
        val action = LearnableDeviceAction(ExactVolumeLearning.CAPABILITY, mapOf("percent" to "25"))
        assertFalse(action.isValid()) // Volume evidence cannot enter the timer/app session or alias matcher.
        assertFalse(evidence.record(entry.id, action, CapabilityTrialEvidence.Outcome.USER_CONFIRMED))
        assertFalse(evidence.record(entry.id, timer, CapabilityTrialEvidence.Outcome.DEVICE_VERIFIED))
        val result = ExactVolumeLearning.execute(approved.pending.phrase, 25, false,
            { percent -> VerifiedMediaVolumeLevel.execute(percent, 15, { 4 }, { }) }, { _, _ -> error("no alias") })
        assertTrue(result.executed)
        assertTrue(evidence.record(entry.id, action, CapabilityTrialEvidence.Outcome.DEVICE_VERIFIED))
        val text = CapabilityTrialEvidence(temporary.root) { 2000 }.render(entry, CapabilityRequestReview.assess(entry.text) { null }.definition)
        assertTrue(text.contains("Android volume readback verified"))
        assertTrue(text.contains("nearest supported level")); assertFalse(text.contains("differs"))
        assertTrue(evidence.record(entry.id, action, CapabilityTrialEvidence.Outcome.NOT_VERIFIED))
        assertEquals(CapabilityTrialEvidence.Outcome.NOT_VERIFIED, evidence.latest(entry)!!.outcome)
        assertFalse(evidence.record(entry.id, action.copy(parameters = mapOf("percent" to "101")), CapabilityTrialEvidence.Outcome.DEVICE_VERIFIED))
    }
    @Test fun invalidOrRelativeVolumeLinksCannotApproveAndCancelledTrialsHaveNoEvidence() {
        var raw: String? = null
        var time = 2000L
        fun confirmations() = VolumeConfirmationStore({ raw }, { raw = it; true }, { time })
        val entry = CapabilityRequestStore.Entry("set media volume to 25%", 1000)
        val exact = FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume", "percent" to "25"))
        val relative = exact.copy(entities = mapOf("setting" to "media_volume", "direction" to "up"))
        assertFalse(confirmations().propose("volume up", relative, requestId = entry.id))
        assertFalse(confirmations().propose("set volume", exact, requestId = "bad"))
        assertTrue(confirmations().propose("set volume", exact, requestId = entry.id))
        raw = JSONObject(raw!!).put("requestId", "bad").toString()
        assertNull(confirmations().consume("yes")); assertNull(raw)
        assertTrue(confirmations().propose("set volume", exact, requestId = entry.id))
        assertFalse(confirmations().consume("no")!!.approved)
        assertNull(CapabilityTrialEvidence(temporary.root) { time }.latest(entry))
        assertTrue(confirmations().propose("set volume", exact, requestId = entry.id))
        time += 120001
        assertNull(confirmations().consume("yes"))
    }
    @Test fun invalidTrialLinkCannotBecomeAnApprovedPendingAction() {
        var raw: String? = null
        val session = DeviceLearningSession({ raw }, { raw = it; true }, { 2000 })
        assertFalse(session.propose(DeviceLearningSession.Pending("test", timer, false, requestId = "bad")))
        assertTrue(session.propose(DeviceLearningSession.Pending("test", timer, false)))
        raw = JSONObject(raw!!).put("requestId", "bad").toString()
        assertNull(session.consume("yes")); assertNull(raw)
        assertEquals("Choose a page starting at 1: show capability requests.", CapabilityDiscovery.render(0, emptyList()) { error("must not read evidence") })
        assertTrue(CapabilityDiscovery.render(Int.MAX_VALUE, listOf(CapabilityRequestStore.Entry("timer", 1000))) { error("must not read evidence") }.contains("There are"))
    }
}
