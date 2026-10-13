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
