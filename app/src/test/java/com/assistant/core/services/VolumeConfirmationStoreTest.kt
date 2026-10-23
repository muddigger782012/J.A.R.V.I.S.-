package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class VolumeConfirmationStoreTest {
    @Test fun exactLevelRequestsCannotBeProposedAsOneStepActions() {
        var writes = 0
        var pending: String? = null
        val store = VolumeConfirmationStore({ pending }, { pending = it; writes++; true })
        for (phrase in listOf("set music volume to half", "set the sound to twenty five percent",
            "boost the sound all the way up")) assertFalse(store.propose(phrase, lesson()))
        assertEquals(0, writes)
        assertNull(pending)
        val exact = FallbackIntentLesson("DEVICE_SETTING", .95,
            mapOf("setting" to "media_volume", "percent" to "50"), learnable = true)
        assertTrue(store.propose("set music volume to half", exact))
        assertEquals(50, ExactVolumeLearning.fromLesson(store.consume("yes")!!.pending.lesson))
    }
    private fun lesson() = FallbackIntentLesson("DEVICE_SETTING", 0.95,
        mapOf("setting" to "media_volume", "direction" to "down"), learnable = true)

    @Test fun approvalUsesOriginalPhraseAcrossInstancesAndPersistsItForOfflineReuse() {
        var pending: String? = null
        var saved = "[]"
        fun confirmations() = VolumeConfirmationStore({ pending }, { pending = it; true }, { 1_000L })
        fun memory() = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertTrue(confirmations().propose("Less racket please!", lesson()))
        // No action or lesson is saved merely because a confirmation was offered.
        assertEquals(0, memory().learnedPhraseCount())
        val approval = confirmations().consume("yes, fine")!!
        assertTrue(approval.approved)
        val result = VerifiedVolumeLearning.execute(approval.pending.phrase, approval.pending.lesson, true,
            { true }, { phrase, capability -> memory().recordSuccess(phrase, capability, VerifiedVolumeLearning.capabilities) })
        assertEquals(VerifiedVolumeLearning.Result(true, true), result)
        assertEquals("volume_down", memory().match("less racket please", VerifiedVolumeLearning.capabilities)?.capabilityId)
        assertNull(memory().match("yes", VerifiedVolumeLearning.capabilities))
        assertNull(confirmations().consume("yes"))
    }

    @Test fun cancellationUnrelatedTurnAndExpiryInvalidateProposal() {
        var pending: String? = null
        var time = 1_000L
        val store = VolumeConfirmationStore({ pending }, { pending = it; true }, { time })
        assertTrue(store.propose("less racket please", lesson()))
        assertFalse(store.consume("no thanks")!!.approved)
        assertNull(store.consume("yes"))
        assertTrue(store.propose("less racket please", lesson()))
        assertNull(store.consume("what time is it"))
        assertNull(store.consume("yes"))
        assertTrue(store.propose("less racket please", lesson()))
        time += 120_001
        assertNull(store.consume("yes"))
    }

    @Test fun failedActionDoesNotLearnOriginalRequestOrApproval() {
        var pending: String? = null
        val store = VolumeConfirmationStore({ pending }, { pending = it; true })
        assertTrue(store.propose("less racket please", lesson()))
        val approval = store.consume("yes")!!
        assertEquals(VerifiedVolumeLearning.Result(false, false), VerifiedVolumeLearning.execute(
            approval.pending.phrase, approval.pending.lesson, true, { false }, { _, _ -> fail("Must not learn"); true }))
    }

    @Test fun approvalsAndUnsupportedLessonsCannotBecomeProposalsOrSavedAliases() {
        val store = VolumeConfirmationStore({ null }, { true })
        assertFalse(store.propose("yes", lesson()))
        assertFalse(store.propose("please", lesson().copy(confidence = 0.1)))
        val memory = ValidatedCommandMemory({ """[{"phrase":"yes","capabilityId":"volume_down","successes":1}]""" }, { true })
        assertNull(memory.match("yes", VerifiedVolumeLearning.capabilities))
        assertFalse(memory.recordSuccess("yes, fine", "volume_down", VerifiedVolumeLearning.capabilities))
    }

    @Test fun storageFailuresNeverAuthorizeExecution() {
        var pending: String? = null
        val normal = VolumeConfirmationStore({ pending }, { pending = it; true })
        assertTrue(normal.propose("less racket please", lesson()))
        assertNull(VolumeConfirmationStore({ pending }, { false }).consume("yes"))
        assertFalse(VolumeConfirmationStore({ null }, { false }).propose("less racket please", lesson()))
    }
}
