package com.assistant.core.services

import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.learning.ValidatedCommandMemory
import org.junit.Assert.*
import org.junit.Test

class RoutineCreationTest {
    private fun lesson(phrase: String = "quiet time", steps: String = "volume_down", confidence: Double = .95) =
        FallbackIntentLesson("CREATE_ROUTINE", confidence, mapOf("phrase" to phrase, "steps" to steps), learnable = true)

    @Test fun creationRequestsCannotFallThroughToImmediateDeviceCommands() {
        for (request in listOf("Create a routine called quiet time that lowers media volume", "Please make a shortcut called updates that opens the updater",
            "Build a capability called hello that opens the updater", "Create a routine called reboot that runs shell id"))
            assertTrue(RoutineProposal.isCreationRequest(request))
        for (request in listOf("volume down", "quiet time", "don't create a routine", "what does create a routine mean", "\"create a routine\""))
            assertFalse(RoutineProposal.isCreationRequest(request))
    }

    @Test fun aiProposalNeedsReviewAndExplicitApprovalBeforeOfflineStorage() {
        var saved = "[]"
        var pending: String? = null
        fun registry() = ApprovedRoutineRegistry({ saved }, { saved = it; true })
        fun confirmations() = RoutineConfirmationStore({ pending }, { pending = it; true }, { 1_000L })
        val proposal = RoutineProposal.fromLesson(lesson())!!
        assertTrue(FallbackLessonProtocol.accepts(lesson()))
        assertFalse(LearningAdmissionPolicy.canRecordWithoutActionResult(lesson()))
        assertTrue(confirmations().propose(proposal))
        assertNull(registry().find("quiet time"))
        val approved = confirmations().consume("Confirm routine!")!!
        assertTrue(approved.approved)
        assertTrue(registry().saveApproved(approved.routine))
        assertEquals(proposal, registry().find(" QUIET  TIME "))
        assertEquals(listOf("volume_down"), registry().find("quiet time")!!.actions)
        assertNull(confirmations().consume("confirm routine"))
        assertTrue(confirmations().propose(proposal))
        assertFalse(confirmations().consume("cancel routine")!!.approved)
    }

    @Test fun unrelatedTurnsAndBareYesCannotApproveAndExpirySurvivesRecreation() {
        var pending: String? = null
        var now = 1_000L
        fun confirmations() = RoutineConfirmationStore({ pending }, { pending = it; true }, { now })
        val proposal = RoutineProposal.fromLesson(lesson())!!
        for (reply in listOf("yes", "it works", "what time is it", "show assistant memory")) {
            assertTrue(confirmations().propose(proposal))
            assertNull(confirmations().consume(reply))
            assertNull(confirmations().consume("confirm routine"))
        }
        assertTrue(confirmations().propose(proposal))
        now += 120_001
        assertNull(confirmations().consume("confirm routine"))
        assertNull(pending)
    }

    @Test fun rejectsUnsupportedLowConfidenceExtraEntitiesAndReservedNames() {
        assertNull(RoutineProposal.fromLesson(lesson(confidence = .89)))
        assertNull(RoutineProposal.fromLesson(lesson().copy(learnable = false)))
        assertNull(RoutineProposal.fromLesson(lesson().copy(entities = lesson().entities + ("code" to "arbitrary"))))
        for (steps in listOf("", "shell", "volume_down,volume_down", "volume_down,send_message", "volume_down;app_update", "mute", "set_timer"))
            assertNull(RoutineProposal.fromLesson(lesson(steps = steps)))
        for (phrase in listOf("yes", "it works", "show assistant memory", "do that again", "open Chrome", "volume down", "quiet; reboot", "x"))
            assertNull(RoutineProposal.fromLesson(lesson(phrase = phrase)))
        assertEquals(listOf("volume_down", "app_update"), RoutineProposal.fromLesson(lesson(steps = "volume_down,app_update"))!!.actions)
    }

    @Test fun approvedMeaningCannotBeSilentlyReplacedAndConflictingLegacyRowsNeverRun() {
        var saved = "[]"
        fun registry() = ApprovedRoutineRegistry({ saved }, { saved = it; true })
        val down = RoutineProposal.fromLesson(lesson())!!
        val up = RoutineProposal.fromLesson(lesson(steps = "volume_up"))!!
        assertTrue(registry().saveApproved(down))
        assertTrue(registry().saveApproved(down))
        assertEquals(1, registry().count())
        assertFalse(registry().saveApproved(up))
        assertEquals(down, registry().find("quiet time"))
        assertTrue(registry().forget("quiet time"))
        assertTrue(registry().saveApproved(up))
        saved = """[{"phrase":"quiet time","actions":["volume_down"]},{"phrase":"quiet time","actions":["volume_up"]}]"""
        assertNull(registry().find("quiet time"))
        assertFalse(registry().saveApproved(down))
        assertTrue(registry().forget("QUIET TIME"))
        assertNull(registry().find("quiet time"))
    }

    @Test fun failedWritesAndMalformedPendingCannotSaveAnApproval() {
        val proposal = RoutineProposal.fromLesson(lesson())!!
        assertFalse(ApprovedRoutineRegistry({ "[]" }, { false }).saveApproved(proposal))
        assertFalse(RoutineConfirmationStore({ null }, { false }).propose(proposal))
        var pending: String? = """{"phrase":"quiet time","steps":["shell"],"created":1000}"""
        val malformed = RoutineConfirmationStore({ pending }, { pending = it; true }, { 1_000L })
        assertNull(malformed.consume("confirm routine"))
        assertNull(pending)
        var raw: String? = null
        RoutineConfirmationStore({ raw }, { raw = it; true }).propose(proposal)
        assertNull(RoutineConfirmationStore({ raw }, { false }).consume("confirm routine"))
    }

    @Test fun pausedOrConflictingLearnedPhrasesStillReserveTheirNames() {
        var saved = "[]"
        val commands = ValidatedCommandMemory({ saved }, { saved = it ?: "[]"; true })
        assertTrue(commands.recordSuccess("quiet-time", "volume_down", setOf("volume_down")))
        assertTrue(commands.hasSavedPhrase("quiet time"))
        assertTrue(commands.recordFailure("quiet time", "volume_down", emptyMap()))
        assertTrue(commands.hasSavedPhrase("quiet time"))
        assertNull(commands.match("quiet time", setOf("volume_down")))
        commands.forget("quiet time")
        assertFalse(commands.hasSavedPhrase("quiet time"))
    }

    @Test fun storageLimitDoesNotEvictExistingApprovedRoutines() {
        var saved = "[]"
        val registry = ApprovedRoutineRegistry({ saved }, { saved = it; true })
        for (i in 1..50) assertTrue(registry.saveApproved(RoutineProposal.fromLesson(lesson(phrase = "routine $i"))!!))
        assertFalse(registry.saveApproved(RoutineProposal.fromLesson(lesson(phrase = "routine 51"))!!))
        assertNotNull(registry.find("routine 1"))
        assertEquals(50, registry.count())
    }
}
