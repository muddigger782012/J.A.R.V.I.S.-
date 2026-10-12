package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class ExactVolumeLearningTest {
    private fun lesson(percent: String = "25") = FallbackIntentLesson("DEVICE_SETTING", .95,
        mapOf("setting" to "media_volume", "percent" to percent), learnable = true)

    @Test fun onlyBoundMediaPercentageAndExplicitLocalCommandsAreAccepted() {
        assertEquals(25, ExactVolumeLearning.fromLesson(lesson()))
        assertEquals(0, ExactVolumeLearning.parse("set media volume to 0%"))
        assertEquals(100, ExactVolumeLearning.parse("Please set my media volume to 100 percent."))
        assertEquals(25, ExactVolumeLearning.parse("put the volume at 25%"))
        for (text in listOf("Don't set media volume to 25%", "Explain set volume to 25%", "set alarm volume to 25%", "set volume to -25%", "set volume to 101%", "set volume to 25.5%"))
            assertNull(ExactVolumeLearning.parse(text))
        for (percent in listOf("-1", "101", "025", "25.5", "25%", "quiet")) assertNull(ExactVolumeLearning.fromLesson(lesson(percent)))
        assertNull(ExactVolumeLearning.fromLesson(lesson().copy(confidence = .89)))
        assertNull(ExactVolumeLearning.fromLesson(lesson().copy(entities = lesson().entities + ("direction" to "up"))))
        assertNull(ExactVolumeLearning.fromLesson(lesson().copy(entities = lesson().entities + ("stream" to "alarm"))))
        assertNull(ExactVolumeLearning.fromParameters(mapOf("percent" to "25", "package" to "com.bad.app")))
    }

    @Test fun originalPhraseIsLearnedOnlyAfterApprovalAndVerificationThenRestoresOffline() {
        var pending: String? = null
        var stored = "[]"
        val memory = ValidatedCommandMemory({ stored }, { stored = it ?: "[]"; true })
        val phrase = "I'd like my media volume at 25 percent"
        val approval = VolumeConfirmationStore({ pending }, { pending = it; true }, { 1000 })
        assertTrue(approval.propose(phrase, lesson()))
        assertNull(memory.match(phrase, ExactVolumeLearning.capabilities))
        val approved = approval.consume("yes")!!
        assertTrue(approved.approved)
        var requested: Int? = null
        val outcome = ExactVolumeLearning.execute(approved.pending.phrase, ExactVolumeLearning.fromLesson(approved.pending.lesson)!!, true,
            set = { requested = it; true }, record = { original, params -> memory.recordSuccess(original, ExactVolumeLearning.CAPABILITY, ExactVolumeLearning.capabilities, params) })
        assertTrue(outcome.executed); assertTrue(outcome.learned); assertEquals(25, requested)
        val restored = ValidatedCommandMemory({ stored }, { error("Read only") }).match(phrase, ExactVolumeLearning.capabilities)!!
        assertEquals(25, ExactVolumeLearning.fromParameters(restored.parameters))
        assertNull(memory.match("yes", ExactVolumeLearning.capabilities))
        assertNull(approval.consume("yes"))
        val summary = LearnedCommandReview.render(1, memory, AssistantMemory({ "{}" }, { true }), emptyList())
        assertTrue(summary.contains("25%")); assertFalse(summary.contains("unsupported"))
    }

    @Test fun disabledOrFailedLearningCannotClaimSavedSuccessAndMeaningCannotChange() {
        var stored = "[]"
        val memory = ValidatedCommandMemory({ stored }, { stored = it ?: "[]"; true })
        fun run(percent: Int, enabled: Boolean, verified: Boolean) = ExactVolumeLearning.execute("quiet level", percent, enabled, { verified }) { phrase, params ->
            memory.recordSuccess(phrase, ExactVolumeLearning.CAPABILITY, ExactVolumeLearning.capabilities, params)
        }
        assertFalse(run(25, true, false).learned)
        assertFalse(run(25, false, true).learned)
        assertTrue(memory.snapshot().isEmpty())
        assertTrue(run(25, true, true).learned)
        assertTrue(memory.recordFailure("quiet level", ExactVolumeLearning.CAPABILITY, mapOf("percent" to "25")))
        assertNull(memory.match("quiet level", ExactVolumeLearning.capabilities))
        assertTrue(run(25, true, true).learned)
        val conflict = run(50, true, true)
        assertTrue(conflict.executed); assertFalse(conflict.learned)
        assertEquals("25", memory.match("quiet level", ExactVolumeLearning.capabilities)!!.parameters["percent"])
        assertFalse(ExactVolumeLearning.execute("quiet", 101, true, { error("Invalid must not execute") }, { _, _ -> error("Must not record") }).executed)
    }
    @Test fun maximumAndExplicitLevelsCannotReplayAsLegacyRelativeCommands() {
        val maxPhrases = listOf("raise media volume 100%", "raise media volume to 100%", "turn volume to max", "turn volume all the way up")
        for (phrase in maxPhrases) {
            assertEquals(100, ExactVolumeLearning.parse(phrase))
            val legacy = """[{"phrase":"$phrase","capabilityId":"volume_up","parameters":{},"successes":1}]"""
            val memory = ValidatedCommandMemory({ legacy }, { fail("legacy review must not rewrite memory"); false })
            assertNull(memory.match(phrase, VerifiedVolumeLearning.capabilities))
            var requested: Int? = null
            val result = ExactVolumeLearning.execute(phrase, ExactVolumeLearning.parse(phrase)!!, false,
                { requested = it; true }, { _, _ -> fail("local command must not rewrite a legacy meaning"); false })
            assertTrue(result.executed); assertFalse(result.learned); assertEquals(100, requested)
            val review = LearnedCommandReview.render(1, memory, AssistantMemory({ "{}" }, { false }), emptyList())
            assertTrue(review.contains("local command sets media volume to 100%"))
            assertTrue(review.contains("older one-step meaning is ignored"))
        }
        val empty = ValidatedCommandMemory({ "[]" }, { fail("conflicting relative lesson must not save"); false })
        assertFalse(empty.recordSuccess("turn volume to max", "volume_up", VerifiedVolumeLearning.capabilities))
        assertFalse(empty.recordSuccess("lower media volume to 25%", "volume_down", VerifiedVolumeLearning.capabilities))
        assertEquals(25, ExactVolumeLearning.parse("lower media volume to 25%"))
        assertEquals(0, ExactVolumeLearning.parse("Please turn the media volume all the way down!"))
        assertEquals(0, ExactVolumeLearning.parse("set volume to minimum"))
        for (phrase in listOf("raise volume", "turn volume up", "don't turn volume to max", "explain turn volume to max",
            "turn alarm volume to max", "turn volume to max and open Chrome", "set volume to 101%", "raise volume 100"))
            assertNull(ExactVolumeLearning.parse(phrase))
    }

}
