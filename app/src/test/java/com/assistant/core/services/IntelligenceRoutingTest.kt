package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class IntelligenceRoutingTest {
    @Test fun routingStatusNeverEchoesCredentialsAccidentallyEnteredAsAModelId() {
        for (value in listOf("AIza-private-owner-key", "sk-private-owner-key", "github_pat_private", "PRIVATE_OWNER_TOKEN", "ya29.private-owner-token")) {
            val status = IntelligenceRouting.status(IntelligenceRouting.Provider.GEMINI, true, true, false, value)
            assertFalse(status.contains(value)); assertTrue(status.contains("unverified model ID"))
        }
        assertEquals("gemini-owner-choice", IntelligenceRouting.modelLabel("gemini-owner-choice"))
        assertEquals("my-manual-model", IntelligenceRouting.modelLabel("my-manual-model"))
        assertEquals("Owner_CustomModel", IntelligenceRouting.modelLabel("Owner_CustomModel"))
    }
    @Test fun manualProviderChoiceWinsWithoutChangingTheChosenModel() {
        val calls = mutableListOf<IntelligenceRouting.Provider>()
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GATEWAY, true, IntelligenceRouting.Provider.values().toSet()) {
            calls.add(it); FallbackAiResult("Gateway answer using its configured model", null)
        }
        assertEquals(listOf(IntelligenceRouting.Provider.GATEWAY), calls)
        assertEquals(IntelligenceRouting.Provider.GATEWAY, result.provider)
    }
    @Test fun aFailedProviderFallsBackOnceAndReportsTheActualSource() {
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GEMINI, true, IntelligenceRouting.Provider.values().toSet()) {
            if (it == IntelligenceRouting.Provider.GEMINI) throw java.io.IOException("network") else FallbackAiResult("Answer", null)
        }
        assertEquals(2, result.attempted.size); assertEquals(IntelligenceRouting.Provider.GATEWAY, result.provider)
        assertTrue(result.answer.reply.contains("gateway answered"))
    }
    @Test fun disablingFallbackNeverOverridesManualSelectionOrCallsAnUnconfiguredProvider() {
        var calls = 0
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GATEWAY, false, setOf(IntelligenceRouting.Provider.GEMINI)) {
            calls++; FallbackAiResult("unexpected", null)
        }
        assertEquals(0, calls); assertTrue(result.answer.providerFailed); assertNull(result.answer.lessonJson)
    }
    private fun keepsPrimaryFailure(alternative: () -> FallbackAiResult?) {
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GEMINI, true, IntelligenceRouting.Provider.values().toSet()) {
            if (it == IntelligenceRouting.Provider.GEMINI) FallbackAiResult("Authentication failed; review the saved key.", "untrusted lesson", providerFailed = true)
            else alternative()
        }
        assertTrue(result.answer.providerFailed)
        assertTrue(result.answer.reply, result.answer.reply.contains("Authentication failed"))
        assertTrue(result.answer.reply.contains("Gemini"))
        assertFalse(result.answer.reply.contains("private endpoint and token"))
        assertEquals(IntelligenceRouting.Provider.values().toList(), result.attempted)
        assertNull(result.answer.lessonJson); assertNull(result.provider)
    }
    @Test fun aNullFallbackCannotEraseAnActionablePrimaryFailure() = keepsPrimaryFailure { null }
    @Test fun aThrowingFallbackCannotEraseAnActionablePrimaryFailureOrExposeItsException() = keepsPrimaryFailure {
        throw java.io.IOException("private endpoint and token")
    }
    @Test fun aBlankFallbackCannotEraseAnActionablePrimaryFailure() = keepsPrimaryFailure {
        FallbackAiResult(" ", "untrusted fallback lesson", providerFailed = true)
    }
    @Test fun anUnconfiguredPrimaryIsNotReportedAsATestedConnectionFailure() {
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GEMINI, true, setOf(IntelligenceRouting.Provider.GATEWAY)) {
            FallbackAiResult("Answer", null)
        }
        assertTrue(result.answer.reply, result.answer.reply.contains("not configured"))
        assertEquals(listOf(IntelligenceRouting.Provider.GATEWAY), result.attempted)
        assertEquals(IntelligenceRouting.Provider.GATEWAY, result.provider)
    }
    @Test fun bothProviderFailuresRemainAttributedInEitherManualOrderWithoutAnyLesson() {
        for (primary in IntelligenceRouting.Provider.values()) {
            val result = IntelligenceRouting.ask(primary, true, IntelligenceRouting.Provider.values().toSet()) {
                if (it == IntelligenceRouting.Provider.GEMINI) FallbackAiResult("Primary credential diagnostic", "unsafe lesson", providerFailed = true)
                else FallbackAiResult("Gateway quota diagnostic", "another unsafe lesson", providerFailed = true)
            }
            assertTrue(result.answer.reply.contains("Gemini: Primary credential diagnostic"))
            assertTrue(result.answer.reply.contains("gateway: Gateway quota diagnostic"))
            assertEquals(primary, result.attempted.first())
            assertTrue(result.answer.providerFailed); assertNull(result.answer.lessonJson); assertNull(result.provider)
        }
    }
    @Test fun failedOrEmptyProviderRepliesCannotSupplyALessonOrVerifiedAnswer() {
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GEMINI, true, IntelligenceRouting.Provider.values().toSet()) {
            FallbackAiResult("Failed", "untrusted lesson", providerFailed = true)
        }
        assertTrue(result.answer.providerFailed); assertNull(result.answer.lessonJson); assertNull(result.provider)
    }
    @Test fun cancellationStopsProviderFallbackAndDiscardsALateReply() {
        var active = true; var calls = 0
        val result = IntelligenceRouting.ask(IntelligenceRouting.Provider.GEMINI, true, IntelligenceRouting.Provider.values().toSet(), { active }) {
            calls++; active = false; FallbackAiResult("late reply", "late lesson")
        }
        assertEquals(1, calls); assertNull(result.provider); assertNull(result.answer.lessonJson)
    }
    @Test fun cancellationAndNewTurnsPreventOldWorkersFromMutatingState() {
        val turns = AssistantResponseTurns(); val first = turns.begin(); var changes = 0
        turns.inTurn(first) { turns.mutate { changes++ } }
        turns.cancel { changes = 0 }
        turns.inTurn(first) { turns.mutate { changes++ } }
        assertEquals(0, changes)
        val second = turns.begin()
        turns.inTurn(second) { assertTrue(turns.current()); turns.mutate { changes++ } }
        assertEquals(1, changes); assertFalse(turns.current(first))
    }
    @Test fun responseTurnBindingIsRestoredAfterExceptions() {
        val turns = AssistantResponseTurns(); val first = turns.begin(); turns.cancel()
        runCatching { turns.inTurn(first) { assertFalse(turns.current()); error("test") } }
        assertTrue(turns.current())
    }
    @Test fun oldOrRepeatedRecognizerCallbacksCannotDeliverAnApproval() {
        val gate = RecognitionCaptureGate(); val old = gate.begin(); gate.cancel()
        assertFalse(gate.finish(old))
        val current = gate.begin(); assertFalse(gate.valid(old)); assertTrue(gate.finish(current)); assertFalse(gate.finish(current))
    }
    @Test fun screenConfirmationAndContinuationAreBoundToTheReviewAtCaptureStart() {
        for (text in listOf("confirm screen action", "confirm screen step one", "continue screen task", "continue")) {
            assertFalse(text, VoiceScreenApprovalScope.valid(text, "old", "new"))
            assertTrue(text, VoiceScreenApprovalScope.valid(text, "same", "same"))
        }
        assertTrue(VoiceScreenApprovalScope.valid("cancel screen task", "old", "new"))
        assertTrue(VoiceScreenApprovalScope.valid("screen task tap Menu and verify Downloads", "old", "new"))
    }
    @Test fun supersededWorkCompletesWithOnlyACancellationSignalInsteadOfItsLateResult() {
        val turns = AssistantResponseTurns(); val old = turns.begin()
        val result = HybridAssistantReply("late private answer", needsInterpretation = true, screenContextKey = "old review")
        turns.cancel()
        val delivered = turns.delivery(old, result)
        assertTrue(delivered.cancelled); assertFalse(delivered.text.contains("private"))
        assertNull(delivered.screenContextKey); assertNull(delivered.actionResult); assertFalse(delivered.needsInterpretation)
        val current = turns.begin(); assertSame(result, turns.delivery(current, result))
    }
}
