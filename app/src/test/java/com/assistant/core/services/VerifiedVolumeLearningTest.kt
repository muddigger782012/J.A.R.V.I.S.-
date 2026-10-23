package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class VerifiedVolumeLearningTest {
    @Test fun relativeInterpretationsOfExplicitLevelsNeverAdjustOrLearn() {
        for (phrase in listOf("set music volume to half", "could you set the sound to twenty five percent?",
            "boost the sound all the way up")) {
            assertNull(VerifiedVolumeLearning.execute(phrase, lesson(), true,
                { fail("Must not execute a conflicting one-step interpretation"); true },
                { _, _ -> fail("Must not learn a conflicting interpretation"); true }))
        }
    }
    @Test fun providerEnvelopeRequiresUsableReplyAndStructuredLesson() {
        assertNull(FallbackAiResult.fromAssistantJson("not json"))
        assertNull(FallbackAiResult.fromAssistantJson("{\"reply\":\"\"}"))
        assertNull(FallbackAiResult.fromAssistantJson("{\"reply\":5}"))
        assertNull(FallbackAiResult.fromAssistantJson("{\"lesson\":{\"intent\":\"DEVICE_SETTING\"}}"))
        val conversational = FallbackAiResult.fromAssistantJson("{\"reply\":\"Hello\",\"lesson\":null}")
        assertEquals("Hello", conversational?.reply)
        assertNull(conversational?.lessonJson)
        val interpreted = FallbackAiResult.fromAssistantJson("""{"reply":"I'll try lowering media volume","lesson":{"intent":"DEVICE_SETTING","confidence":0.95,"entities":{"setting":"media_volume","direction":"down"},"learnable":true}}""")
        assertEquals("volume_down", VerifiedVolumeLearning.capability(FallbackIntentLesson.fromJson(interpreted!!.lessonJson!!)!!))
    }

    private fun lesson(direction: String = "down", confidence: Double = 0.95, learnable: Boolean = true) =
        FallbackIntentLesson("DEVICE_SETTING", confidence, mapOf("setting" to "media_volume", "direction" to direction), learnable = learnable)

    @Test fun verifiedInterpretationPersistsAndReplaysAfterMemoryRecreation() {
        var saved = "[]"
        fun memory() = ValidatedCommandMemory({ saved }, { value -> saved = value ?: "[]"; true })
        var volume = 5
        val result = VerifiedVolumeLearning.execute("less racket please", lesson(), true,
            adjust = { volume--; true },
            record = { phrase, capability -> memory().recordSuccess(phrase, capability, VerifiedVolumeLearning.capabilities) })
        assertEquals(VerifiedVolumeLearning.Result(true, true), result)
        val offline = memory().match("LESS RACKET PLEASE", VerifiedVolumeLearning.capabilities)
        assertEquals("volume_down", offline?.capabilityId)
        // Offline replay uses the stored capability, with no provider involved.
        if (offline?.capabilityId == "volume_down") volume--
        assertEquals(3, volume)
    }

    @Test fun unchangedOrFailedActionDoesNotCreateLesson() {
        var recordings = 0
        val result = VerifiedVolumeLearning.execute("less racket please", lesson(), true,
            adjust = { VolumeChangeVerification.changed(0, 0, false) },
            record = { _, _ -> recordings++; true })
        assertEquals(VerifiedVolumeLearning.Result(false, false), result)
        assertEquals(0, recordings)
    }

    @Test fun exceptionsDoNotBecomeLearnedSuccess() {
        val result = VerifiedVolumeLearning.execute("less racket", lesson(), true,
            adjust = { throw IllegalStateException("blocked") }, record = { _, _ -> fail("Must not learn"); true })
        assertEquals(VerifiedVolumeLearning.Result(false, false), result)
    }

    @Test fun lowConfidenceUnknownDirectionAndOtherCapabilitiesNeverExecute() {
        val invalid = listOf(lesson(confidence = 0.89), lesson("mute"),
            FallbackIntentLesson("CALL_CONTACT", 0.99, learnable = true),
            lesson().copy(entities = mapOf("setting" to "ring_volume", "direction" to "down")),
            lesson().copy(entities = lesson().entities + ("code" to "anything")))
        invalid.forEach { candidate ->
            assertNull(VerifiedVolumeLearning.execute("request", candidate, true,
                adjust = { fail("Must not execute"); true }, record = { _, _ -> fail("Must not learn"); true }))
        }
    }

    @Test fun learningDisabledOrProviderDisallowsLearningStillDoesNotPersist() {
        listOf(false to lesson(), true to lesson(learnable = false)).forEach { (enabled, candidate) ->
            assertEquals(VerifiedVolumeLearning.Result(true, false), VerifiedVolumeLearning.execute(
                "less racket", candidate, enabled, { true }, { _, _ -> fail("Must not learn"); true }))
        }
    }

    @Test fun conflictingPhraseAndFailedWriteAreNotReportedAsLearned() {
        var saved = "[]"
        val memory = ValidatedCommandMemory({ saved }, { value -> saved = value ?: "[]"; true })
        assertTrue(memory.recordSuccess("custom phrase", "volume_up", VerifiedVolumeLearning.capabilities))
        val result = VerifiedVolumeLearning.execute("custom phrase", lesson(), true, { true },
            { phrase, capability -> memory.recordSuccess(phrase, capability, VerifiedVolumeLearning.capabilities) })
        assertEquals(VerifiedVolumeLearning.Result(true, false), result)
        assertEquals("volume_up", memory.match("custom phrase", VerifiedVolumeLearning.capabilities)?.capabilityId)
        val failedDisk = ValidatedCommandMemory({ "[]" }, { false })
        assertFalse(failedDisk.recordSuccess("phrase", "volume_down", VerifiedVolumeLearning.capabilities))
    }

    @Test fun volumeVerificationRequiresChangeInRequestedDirection() {
        assertTrue(VolumeChangeVerification.changed(3, 4, true))
        assertTrue(VolumeChangeVerification.changed(4, 3, false))
        assertFalse(VolumeChangeVerification.changed(3, 3, true))
        assertFalse(VolumeChangeVerification.changed(3, 2, true))
        assertFalse(VolumeChangeVerification.changed(3, 4, false))
    }
}
