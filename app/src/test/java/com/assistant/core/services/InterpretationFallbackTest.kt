package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test

class InterpretationFallbackTest {
    @Test fun aSuccessfulPrimaryAnswerDoesNotContactTheAlternative() {
        var secondaryCalls = 0
        val primary = FallbackAiResult("Here is a practical next step.", null)
        assertEquals(primary, InterpretationFallback.ask({ primary }, { secondaryCalls++; null }))
        assertEquals(0, secondaryCalls)
    }

    @Test fun failedInterpretationTriesOneAlternativeAndPreservesItsProposalForHostReview() {
        var primaryCalls = 0
        var secondaryCalls = 0
        val reviewedProposal = FallbackAiResult("Would you like a five-minute timer?", "{\"intent\":\"SET_TIMER\"}")
        val result = InterpretationFallback.ask(
            { primaryCalls++; FallbackAiResult("Unavailable", null, providerFailed = true) },
            { secondaryCalls++; reviewedProposal })
        assertEquals(reviewedProposal, result)
        assertEquals(1, primaryCalls); assertEquals(1, secondaryCalls)
    }

    @Test fun exceptionsAndMissingAlternativesNeverBecomeAnActionLesson() {
        val result = InterpretationFallback.ask({ error("network error") }, { error("gateway error") })
        assertTrue(result.providerFailed); assertNull(result.lessonJson)
        val stale = FallbackAiResult("Failed", "untrusted stale lesson", providerFailed = true)
        assertNull(InterpretationFallback.ask({ stale }).lessonJson)
        assertTrue(InterpretationFallback.ask({ stale }, { null }).providerFailed)
    }
}
