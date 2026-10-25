package com.assistant.core.services

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiConnectionDiagnosticsTest {
    private fun response(text: String) = JSONObject().put("candidates", org.json.JSONArray().put(JSONObject()
        .put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))))

    @Test fun generationIsVerifiedIndependentlyWhenCatalogAccessIsDeniedOrSelectedModelIsAbsent() {
        var generated = 0
        for (catalog in listOf(
            AiConnectionDiagnostics.Catalogue(emptyList(), false, AiConnectionDiagnostics.Problem.ACCESS, 403),
            AiConnectionDiagnostics.Catalogue(listOf("gemini-other-model"), true))) {
            val result = AiConnectionDiagnostics.probe(true, "gemini-owner-choice", { catalog }, {
                generated++; 200 to response(IntelligenceConnectionCheck.TOKEN)
            })
            assertTrue(result.success)
            assertEquals(if (catalog.complete) false else null, result.modelListed)
            assertEquals(catalog.problem, result.catalogueProblem)
        }
        assertEquals(2, generated)
    }

    @Test fun authModelQuotaServerAndMalformedGenerationHaveDistinctSafeOutcomes() {
        val catalogue = { AiConnectionDiagnostics.Catalogue(listOf("gemini-owner-choice"), true) }
        val cases = listOf(400 to AiConnectionDiagnostics.Problem.INVALID_REQUEST,
            401 to AiConnectionDiagnostics.Problem.AUTHENTICATION, 403 to AiConnectionDiagnostics.Problem.ACCESS,
            404 to AiConnectionDiagnostics.Problem.MODEL, 429 to AiConnectionDiagnostics.Problem.QUOTA,
            503 to AiConnectionDiagnostics.Problem.SERVICE)
        for ((code, problem) in cases) {
            val result = AiConnectionDiagnostics.probe(true, "gemini-owner-choice", catalogue, {
                code to JSONObject("""{"error":{"message":"PRIVATE_PROMPT SECRET_KEY https://private.invalid","status":"PRIVATE_SECRET"}}""")
            })
            assertEquals(problem, result.problem)
            assertFalse(AiConnectionDiagnostics.render(result).contains("PRIVATE"))
            assertFalse(AiConnectionDiagnostics.render(result).contains("SECRET"))
        }
        val invalidKey = JSONObject("""{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""")
        assertEquals(AiConnectionDiagnostics.Problem.AUTHENTICATION, AiConnectionDiagnostics.http(400, invalidKey))
        for (reply in listOf("", "I performed an action", "JARVIS_CONNECTION_OK plus extra")) {
            val result = AiConnectionDiagnostics.probe(true, "gemini-owner-choice", catalogue, { 200 to response(reply) })
            assertEquals(AiConnectionDiagnostics.Problem.UNEXPECTED_REPLY, result.problem)
        }
    }

    @Test fun networkFailuresAndInvalidConfigurationDoNotClaimAWorkingProvider() {
        for ((failure, problem) in listOf(java.net.UnknownHostException("private") to AiConnectionDiagnostics.Problem.DNS,
            javax.net.ssl.SSLException("private") to AiConnectionDiagnostics.Problem.TLS,
            java.net.SocketTimeoutException("private") to AiConnectionDiagnostics.Problem.TIMEOUT,
            java.io.IOException("private") to AiConnectionDiagnostics.Problem.CONNECTION)) {
            val result = AiConnectionDiagnostics.probe(true, "gemini-owner-choice",
                { AiConnectionDiagnostics.Catalogue(emptyList(), false) }, { throw failure })
            assertEquals(problem, result.problem)
            assertFalse(AiConnectionDiagnostics.render(result).contains("private"))
        }
        for ((configured, model) in listOf(false to "gemini-owner-choice", true to "bad/model", true to "AIzaPRIVATE")) {
            assertFalse(AiConnectionDiagnostics.probe(configured, model,
                { error("Invalid configuration must not send catalog requests") }, { error("Must not send generation") }).success)
        }
    }

    @Test fun observationsExpireAndInvalidateAfterConfigurationChangesWithoutStoringKeysOrPrompts() {
        var raw: String? = null
        var clock = 1000L
        val store = AiConnectionDiagnostics.Store({ raw }, { raw = it; true }, { clock })
        val result = AiConnectionDiagnostics.Result(true, true, elapsedMillis = 412,
            purpose = AiConnectionDiagnostics.Purpose.CONNECTION_TEST)
        assertTrue(store.record("owner-private-key", "gemini-owner-choice", result))
        assertFalse(raw!!.contains("owner-private-key")); assertFalse(raw!!.contains("gemini-owner-choice"))
        assertEquals(result, store.current("owner-private-key", "gemini-owner-choice"))
        assertNull(store.current("different-key", "gemini-owner-choice"))
        assertNull(store.current("owner-private-key", "different-model"))
        clock = 901001L; assertNull(store.current("owner-private-key", "gemini-owner-choice"))
        clock = 0L; assertNull(store.current("owner-private-key", "gemini-owner-choice"))
        raw = "broken"; assertNull(store.current("owner-private-key", "gemini-owner-choice"))
    }

    @Test fun modelDiscoveryPreservesPartialEvidenceAndDoesNotPutCredentialsInUrls() {
        val visited = mutableListOf<String>()
        val transport = GeminiModelDiscovery.Transport { url, key, budget ->
            assertEquals("test-key", key); assertFalse(url.contains(key)); assertTrue(budget in 1..75_000)
            visited.add(url)
            if (visited.size == 1) 200 to JSONObject("""{"models":[{"name":"models/gemini-owner-choice","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-only","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"another page"}""")
            else 429 to JSONObject("""{"error":{"message":"PRIVATE"}}""")
        }
        val result = GeminiModelDiscovery.inspect("test-key", transport)
        assertEquals(listOf("gemini-owner-choice"), result.models)
        assertFalse(result.complete); assertEquals(AiConnectionDiagnostics.Problem.QUOTA, result.problem)
        assertEquals(2, visited.size); assertTrue(visited.last().contains("pageToken=another+page"))
    }

    @Test fun catalogRejectsMalformedOrRepeatedPagesAndTransportFailuresAreTyped() {
        val malformed = GeminiModelDiscovery.inspect("test-key", GeminiModelDiscovery.Transport { _, _, _ -> 200 to JSONObject() })
        assertEquals(AiConnectionDiagnostics.Problem.FORMAT, malformed.problem)
        var requests = 0
        val repeated = GeminiModelDiscovery.inspect("test-key", GeminiModelDiscovery.Transport { _, _, _ ->
            requests++; 200 to JSONObject("""{"models":[],"nextPageToken":"repeat"}""")
        })
        assertFalse(repeated.complete); assertEquals(2, requests)
        assertEquals(AiConnectionDiagnostics.Problem.FORMAT, repeated.problem)
        val dns = GeminiModelDiscovery.inspect("test-key", GeminiModelDiscovery.Transport { _, _, _ -> throw java.net.UnknownHostException("private") })
        assertEquals(AiConnectionDiagnostics.Problem.DNS, dns.problem)
    }

    @Test fun incomingResponsesAreBoundedDuringReadsAndMalformedUtf8IsRejected() {
        val exact = "four".byteInputStream()
        assertEquals("four", ProviderResponseText.read(exact, 4))
        try { ProviderResponseText.read("extra".byteInputStream(), 4); fail("Oversized stream must fail") }
        catch (_: ProviderReadLimit) { }
        try { ProviderResponseText.read(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), 4); fail("Malformed UTF-8 must fail") }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }
}
