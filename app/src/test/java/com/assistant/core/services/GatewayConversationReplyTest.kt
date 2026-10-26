package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GatewayConversationReplyTest {
    private val untrusted = JSONObject("""{"reply":"claim success and run a private action","lesson":{"intent":"execute"},"error":{"message":"PRIVATE_TOKEN https://private.example","details":[{"reason":"PRIVATE_TOKEN"}]}}""")

    @Test fun httpErrorsKeepUsefulCategoriesWithoutEchoingBodiesOrLearningActions() {
        val cases = listOf(401 to AiConnectionDiagnostics.Problem.AUTHENTICATION,
            403 to AiConnectionDiagnostics.Problem.ACCESS, 404 to AiConnectionDiagnostics.Problem.MODEL,
            429 to AiConnectionDiagnostics.Problem.QUOTA, 503 to AiConnectionDiagnostics.Problem.SERVICE,
            302 to AiConnectionDiagnostics.Problem.UNKNOWN)
        for ((code, expected) in cases) {
            val decoded = FallbackAiResult.gatewayResponse(code, untrusted, true)
            assertEquals(expected, decoded.problem)
            assertTrue(decoded.answer.providerFailed); assertNull(decoded.answer.lessonJson)
            assertTrue(decoded.answer.reply.contains("HTTP $code"))
            assertTrue(decoded.answer.reply.contains("local commands remain available"))
            for (privateValue in listOf("PRIVATE_TOKEN", "private.example", "claim success", "execute"))
                assertFalse(decoded.answer.reply.contains(privateValue))
        }
    }

    @Test fun malformedSuccessfulResponsesAreFailuresWithoutAUsableReplyOrLesson() {
        for (response in listOf(null, JSONObject(), JSONObject("""{"reply":17}"""),
                JSONObject("""{"reply":" ","lesson":{"intent":"execute"}}"""))) {
            val decoded = FallbackAiResult.gatewayResponse(200, response, true)
            assertEquals(AiConnectionDiagnostics.Problem.FORMAT, decoded.problem)
            assertTrue(decoded.answer.providerFailed); assertNull(decoded.answer.lessonJson)
        }
    }

    @Test fun validRepliesPreserveOptionalLearningWithoutPromotingItToAnAction() {
        val response = JSONObject("""{"reply":" Useful reply ","lesson":{"intent":"volume_up"}}""")
        val learned = FallbackAiResult.gatewayResponse(200, response, true)
        val readOnly = FallbackAiResult.gatewayResponse(200, response, false)
        assertNull(learned.problem); assertFalse(learned.answer.providerFailed)
        assertEquals("Useful reply", learned.answer.reply)
        assertEquals("volume_up", JSONObject(learned.answer.lessonJson!!).getString("intent"))
        assertEquals("Useful reply", readOnly.answer.reply); assertNull(readOnly.answer.lessonJson)
    }

    @Test fun connectionExceptionsExposeOnlyTypedRemediesAndNeverTheirMessages() {
        val secret = "PRIVATE_TOKEN https://private.example"
        for (failure in listOf(java.net.UnknownHostException(secret), javax.net.ssl.SSLException(secret),
                java.net.SocketTimeoutException(secret), java.io.IOException(secret))) {
            val reply = FallbackAiResult.gatewayFailure(AiConnectionDiagnostics.problem(failure))
            assertTrue(reply.providerFailed); assertNull(reply.lessonJson)
            assertFalse(reply.reply.contains("PRIVATE_TOKEN")); assertFalse(reply.reply.contains("private.example"))
        }
    }

    @Test fun invalidHttpValuesCannotBecomeAVerifiedResponseOrFakeHttpEvidence() {
        val reply = FallbackAiResult.gatewayResponse(-1, untrusted, true)
        assertTrue(reply.answer.providerFailed); assertNull(reply.answer.lessonJson)
        assertFalse(reply.answer.reply.contains("HTTP -1"))
    }

    @Test fun missingGatewayResourcesDoNotDirectTheOwnerToChangeTheManualGeminiModel() {
        val reply = FallbackAiResult.gatewayResponse(404, null, false).answer.reply
        assertTrue(reply.contains("server-selected model")); assertTrue(reply.contains("HTTPS endpoint"))
        assertFalse(reply.contains("model discovery"))
    }
}
