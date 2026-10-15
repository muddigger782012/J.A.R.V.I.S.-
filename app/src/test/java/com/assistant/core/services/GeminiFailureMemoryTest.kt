package com.assistant.core.services

import com.assistant.core.learning.ValidatedCommandMemory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiFailureMemoryTest {
    @Test fun typedFailuresRemainSanitizedAndOldConnectionRecordsStillExplainUnknownCause() {
        var raw: String? = null
        val memory = GeminiFailureMemory({ raw }, { raw = it; true }, { 1000 })
        for (kind in GeminiFailureKind.values()) {
            assertTrue(memory.record(0, null, kind))
            assertEquals(kind.name, JSONObject(raw!!).getString("category"))
            assertTrue(memory.explain().contains(kind.diagnostic))
            assertTrue(memory.explain().contains(kind.explanation))
            assertTrue(memory.explain().contains("not a live outage check"))
        }
        raw = """{"code":0,"status":"","reasons":[],"created":1000}"""
        assertTrue(memory.explain().contains("can't tell"))
        raw = """{"code":0,"status":"","reasons":[],"category":"SECRET_KEY https://private.example","created":1000}"""
        assertTrue(memory.explain().contains("can't tell"))
        assertFalse(memory.explain().contains("SECRET_KEY"))
        assertFalse(memory.explain().contains("https://"))

        assertTrue(memory.record(503, JSONObject("""{"error":{"status":"UNAVAILABLE"}}"""), GeminiFailureKind.DNS))
        assertFalse(JSONObject(raw!!).has("category"))
        assertTrue(memory.explain().contains("HTTP 503, UNAVAILABLE"))
        assertFalse(memory.explain().contains(GeminiFailureKind.DNS.diagnostic))
    }

    @Test fun naturalRepairFollowupsUseRecentFailureWithoutRevivingStaleOrMissingContext() {
        var raw: String? = null
        var clock = 1000L
        val memory = GeminiFailureMemory({ raw }, { raw = it; true }, { clock })
        val questions = listOf("What can I do to fix it?", "Is there anything I can do on my end to fix it", "Hey Jarvis how do I fix it")
        for (q in questions) assertFalse(memory.shouldAnswer(q))
        memory.record(503, JSONObject("""{"error":{"status":"UNAVAILABLE"}}"""))
        val before = raw
        for (q in questions) { assertTrue(memory.shouldAnswer(q)); assertTrue(GeminiFailureMemory.isQuestion(q)) }
        assertEquals(before, raw)
        clock += 600001
        for (q in questions) assertFalse(memory.shouldAnswer(q))
        assertTrue(memory.shouldAnswer("why did Gemini reject the request"))
        assertFalse(memory.shouldAnswer("what can I do to fix it and open Chrome"))
        clock = 0
        for (q in questions) assertFalse(memory.shouldAnswer(q))
        assertFalse(GeminiFailureMemory({ "broken" }, { false }).shouldAnswer(questions.first()))
        memory.record(0, null)
        assertTrue(memory.explain().contains("Check internet access and retry"))
    }
    @Test fun unavailableFailureExplainsCauseAndRemedyLocallyWithoutRetainingSensitiveBody() {
        var raw: String? = null
        val memory = GeminiFailureMemory({ raw }, { raw = it; true }, { 1000 })
        val response = JSONObject("""{"error":{"status":"UNAVAILABLE","message":"SECRET_KEY private prompt https://private.example","details":[{"reason":"OVERLOADED"}]}}""")
        assertTrue(memory.record(503, response))
        val stored = raw!!
        assertFalse(stored.contains("SECRET_KEY")); assertFalse(stored.contains("private prompt")); assertFalse(stored.contains("https://"))
        val reader = GeminiFailureMemory({ raw }, { fail("explanation cannot write"); false })
        val reply = reader.explain()
        assertTrue(reply.contains("HTTP 503, UNAVAILABLE")); assertTrue(reply.contains("not evidence"))
        assertTrue(reply.contains("retry")); assertTrue(reply.contains("not a live outage check"))
        assertEquals(stored, raw)
        assertFalse(OnlineAnswers.geminiError(503, response).contains("rejected"))
    }

    @Test fun errorTypesGetSpecificStepsWhileUnknownCausesStayUnknown() {
        var raw: String? = null
        val memory = GeminiFailureMemory({ raw }, { raw = it; true })
        for ((code, hint) in listOf(429 to "quota", 401 to "API key", 403 to "doesn't establish which", 404 to "model", 400 to "invalid", 500 to "server-side", 418 to "enough recorded detail", 0 to "can't tell")) {
            assertTrue(memory.record(code, null)); assertTrue("$code", memory.explain().contains(hint))
        }
        memory.record(400, JSONObject("""{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}"""))
        assertTrue(memory.explain().contains("Authentication failed"))
        val original = raw
        assertFalse(memory.record(200, null)); assertEquals(original, raw)
        assertTrue(GeminiFailureMemory({ null }, { false }).explain().contains("No Gemini failure"))
        assertTrue(GeminiFailureMemory({ "broken" }, { false }).explain().contains("No Gemini failure"))
        assertFalse(GeminiFailureMemory({ null }, { false }).record(503, null))
    }

    @Test fun whyAndFixQuestionsAreReservedAndKeepPendingOutcomeWithoutApprovingIt() {
        for (question in listOf("Why did Gemini reject the request?", "Hey Jarvis why did Gemini reject the request", "how do I fix the Gemini error", "how can I fix this error")) {
            assertTrue(question, GeminiFailureMemory.isQuestion(question))
            val commands = ValidatedCommandMemory({ "[]" }, { fail("diagnostic control cannot learn"); false })
            assertFalse(commands.recordSuccess(question, "volume_up", VerifiedVolumeLearning.capabilities))
            var raw: String? = null
            val session = DeviceLearningSession({ raw }, { raw = it; true })
            session.propose(DeviceLearningSession.Pending("timer", LearnableDeviceAction("timer", mapOf("seconds" to "600")), false, awaitingOutcome = true))
            val before = raw
            assertNull(session.consume(question)); assertEquals(before, raw)
            assertTrue(session.consume("yes")!!.reminder)
            assertTrue(session.consume("it worked")!!.accepted)
        }
        assertFalse(GeminiFailureMemory.isQuestion("why did Gemini fail and open Chrome"))
    }
}

