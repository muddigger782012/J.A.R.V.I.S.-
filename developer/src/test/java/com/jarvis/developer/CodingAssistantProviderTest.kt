package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Provider contract checks use fake in-memory credentials and transports; no live request is made. */
class CodingAssistantProviderTest {
    private val sessionLimitMessage =
        "AI request exceeded the 90-second session limit. Try a smaller task or faster model."
    private val key = "runtime-provider-key-42"
    private val task = "Explain the source and suggest a bounded implementation plan."
    private val answer = "Inspect the relevant source and test the proposed change."

    private fun openAi(text: String = answer): JSONObject = JSONObject().put("status", "completed")
        .put("error", JSONObject.NULL).put("incomplete_details", JSONObject.NULL)
        .put("output", JSONArray().put(JSONObject().put("id", "msg-fixture").put("type", "message")
            .put("role", "assistant").put("status", "completed").put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))))

    private fun gemini(text: String = answer): JSONObject = JSONObject().put("candidates", JSONArray()
        .put(JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))))

    private class Fixture : CodingAssistantProvider.PostTransport {
        val requests = ArrayList<CodingAssistantProvider.PostRequest>()
        var bytes = byteArrayOf()
        var status = 200
        var length: Long? = null
        var finalUrl: String? = null
        var closed = 0
        var stream: (() -> InputStream)? = null
        override fun post(request: CodingAssistantProvider.PostRequest): CodingAssistantProvider.PostResponse {
            requests.add(request)
            return CodingAssistantProvider.PostResponse(status, length ?: bytes.size.toLong(), finalUrl ?: request.url,
                stream?.invoke() ?: ByteArrayInputStream(bytes)) { closed++ }
        }
        fun body(value: JSONObject) { bytes = value.toString().toByteArray(Charsets.UTF_8) }
    }

    private fun fails(fake: Fixture, provider: String = "OpenAI", model: String = "", actualKey: String = key, text: String = task) {
        try {
            CodingAssistantProvider.Client(fake).ask(provider, actualKey, model, text)
            fail("Expected safe rejection")
        } catch (failure: IllegalStateException) {
            assertEquals(CodingAssistantProvider.FAILURE, failure.message)
            assertNull(failure.cause)
            if (actualKey.isNotEmpty()) assertFalse(failure.toString().contains(actualKey))
            assertFalse(failure.toString().contains("reflected-error-body"))
        }
    }

    @Test fun openAiUsesResponsesPostWithSelectedHeaderAndDefaultModelWithoutToolsOrPersistence() {
        val fake = Fixture().apply { body(openAi()) }
        assertEquals(answer, CodingAssistantProvider.Client(fake).ask("OpenAI", key, "", task))
        assertEquals(1, fake.requests.size)
        val sent = fake.requests.single()
        assertEquals("https://api.openai.com/v1/responses", sent.url)
        assertEquals(mapOf("Authorization" to "Bearer $key"), sent.headers)
        val serialized = String(sent.body, Charsets.UTF_8)
        assertFalse(serialized.contains(key))
        val body = JSONObject(serialized)
        assertEquals("gpt-4.1-mini", body.getString("model"))
        assertEquals(task, body.getString("input"))
        assertFalse(body.getBoolean("store"))
        assertEquals(8192, body.getInt("max_output_tokens"))
        assertFalse(body.has("tools"))
        assertTrue(body.getString("instructions").contains("no repository"))
        assertEquals(1, fake.closed)
    }

    @Test fun geminiUsesGenerateContentWithSelectedHeaderAndDefaultModel() {
        val fake = Fixture().apply { body(gemini()) }
        assertEquals(answer, CodingAssistantProvider.Client(fake).ask("Gemini", key, "", task))
        val sent = fake.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent", sent.url)
        assertEquals(mapOf("x-goog-api-key" to key), sent.headers)
        assertFalse(sent.url.contains(key))
        val serialized = String(sent.body, Charsets.UTF_8)
        assertFalse(serialized.contains(key))
        val body = JSONObject(serialized)
        val content = body.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", content.getString("role"))
        assertEquals(task, content.getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(1, body.getJSONObject("generationConfig").getInt("candidateCount"))
        assertFalse(body.has("tools"))
        assertEquals(1, fake.closed)
    }

    @Test fun explicitlySelectedOlderGeminiModelIsNotRewrittenToTheDefaultAlias() {
        val fake = Fixture().apply { body(gemini()) }
        CodingAssistantProvider.Client(fake).ask("Gemini", key, "gemini-2.5-flash", task)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            fake.requests.single().url)
    }

    @Test fun explicitModelsAreFrozenIntoTheirSelectedProvidersRequest() {
        val open = Fixture().apply { body(openAi()) }
        CodingAssistantProvider.Client(open).ask("OpenAI", key, "gpt-4.1", task)
        assertEquals("gpt-4.1", JSONObject(String(open.requests.single().body, Charsets.UTF_8)).getString("model"))
        val google = Fixture().apply { body(gemini()) }
        CodingAssistantProvider.Client(google).ask("Gemini", key, "gemini-2.5-pro", task)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent", google.requests.single().url)
    }

    @Test fun unsupportedProvidersAndUnsafeModelsFailBeforeTransport() {
        val fake = Fixture()
        for (provider in listOf("", "openai", "Fixture", "https://evil.example")) fails(fake, provider)
        for (model in listOf("../gemini-2.5-flash", "gemini-2.5-flash?key=leak", "gemini-2.5-flash:generateContent",
            "Gemini-2.5-flash", "gpt-4.1-mini", "https://evil.example", "gemini-2.5-flash\r\nX-Key: injected")) fails(fake, "Gemini", model)
        for (model in listOf("other/model", "Gpt-4.1-mini", "https://evil.example", "gpt-4.1?x=1", "x".repeat(81))) fails(fake, model = model)
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun malformedCredentialsAndBlankOrEchoingTasksFailBeforeTransport() {
        val fake = Fixture()
        for (invalidKey in listOf("", "short", "valid-key\r\nX-Key: injected", "key with spaces", "x".repeat(1025))) fails(fake, actualKey = invalidKey)
        fails(fake, text = "")
        fails(fake, text = " \n\t")
        fails(fake, text = "Explain this credential $key")
        fails(fake, "Gemini", model = "gemini-$key")
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun serializedRequestByteBudgetCountsEscapingAndMultibyteCharacters() {
        val fake = Fixture()
        fails(fake, text = "a" + "\t".repeat(CodingAssistantProvider.MAX_REQUEST_BYTES / 2))
        fails(fake, text = "界".repeat(CodingAssistantProvider.MAX_REQUEST_BYTES / 2))
        fails(fake, text = "A\uD800B")
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun httpErrorsAndRedirectStatusesNeverReadReflectedBodiesOrRetry() {
        for (status in listOf(301, 302, 307, 400, 401, 403, 429, 500)) {
            var read = false
            val fake = Fixture().apply {
                this.status = status
                stream = { object : InputStream() {
                    override fun read(): Int { read = true; error("reflected-error-body $key") }
                } }
            }
            if (status in listOf(301, 302, 307, 400, 401, 403, 429, 500)) {
                try {
                    CodingAssistantProvider.Client(fake).ask("OpenAI", key, "", task)
                    fail("Expected HTTP provider failure")
                } catch (failure: CodingAssistantProvider.ProviderFailure) {
                    assertTrue(failure.message.orEmpty().contains("HTTP $status"))
                    assertFalse(failure.toString().contains(key))
                    assertNull(failure.cause)
                }
            }
            assertFalse(read)
            assertEquals(1, fake.requests.size)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun redirectsAndUnexpectedFinalOriginsAreClosedBeforeReading() {
        for (url in listOf("https://evil.example/collect", "https://api.openai.com/v1/responses?key=$key",
            "https://api.openai.com/v1/responses#fragment", "http://api.openai.com/v1/responses",
            "https://api.openai.com:443/v1/responses", "https://user@api.openai.com/v1/responses",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")) {
            var read = false
            val fake = Fixture().apply {
                finalUrl = url
                stream = { object : InputStream() { override fun read(): Int { read = true; return -1 } } }
            }
            fails(fake)
            assertFalse(read)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun knownAndUnknownLengthLimitsRejectOversizeAndClose() {
        val known = Fixture().apply { body(openAi()); length = CodingAssistantProvider.MAX_RESPONSE_BYTES.toLong() + 1 }
        fails(known)
        assertEquals(1, known.closed)
        val unknown = Fixture().apply { bytes = ByteArray(CodingAssistantProvider.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }; length = -1 }
        fails(unknown)
        assertEquals(1, unknown.closed)
        val invalidLength = Fixture().apply { body(openAi()); length = -2 }
        fails(invalidLength)
        assertEquals(1, invalidLength.closed)
    }

    @Test fun exactByteLimitCanBeReadWithoutUnboundedGrowth() {
        val envelope = openAi("x")
        val overhead = envelope.toString().toByteArray(Charsets.UTF_8).size - 1
        val text = "x".repeat(CodingAssistantProvider.MAX_RESPONSE_BYTES - overhead)
        val fake = Fixture().apply { body(openAi(text)); length = -1 }
        assertEquals(CodingAssistantProvider.MAX_RESPONSE_BYTES, fake.bytes.size)
        assertEquals(text, CodingAssistantProvider.Client(fake).ask("OpenAI", key, "", task))
        assertEquals(1, fake.closed)
    }

    @Test fun invalidUtf8NoProgressAndThrowingStreamsCloseSafely() {
        val invalid = Fixture().apply { bytes = byteArrayOf(0xc3.toByte(), 0x28) }
        fails(invalid)
        assertEquals(1, invalid.closed)
        val stalled = Fixture().apply { length = -1; stream = { object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int = 0
        } } }
        fails(stalled)
        assertEquals(1, stalled.closed)
        val broken = Fixture().apply { stream = { object : InputStream() {
            override fun read(): Int = error("reflected-error-body $key")
        } } }
        fails(broken)
        assertEquals(1, broken.closed)
    }

    @Test fun timeBudgetExpiresAfterSlowTransportAndClosesWithoutReading() {
        var now = 0L
        var reads = 0
        var closes = 0
        val transport = CodingAssistantProvider.PostTransport { request ->
            now = CodingAssistantProvider.MAX_SESSION_MILLIS
            CodingAssistantProvider.PostResponse(200, -1, request.url, object : InputStream() {
                override fun read(): Int { reads++; return -1 }
            }) { closes++ }
        }
        try { CodingAssistantProvider.Client(transport) { now }.ask("OpenAI", key, "", task); fail("Expected timeout") }
        catch (failure: CodingAssistantProvider.ProviderFailure) { assertEquals(sessionLimitMessage, failure.message); assertNull(failure.cause) }
        assertEquals(0, reads)
        assertEquals(1, closes)
    }

    @Test fun timeBudgetExpiresDuringReadAndAlwaysCloses() {
        var now = 0L
        val fake = Fixture().apply { body(openAi()); stream = { object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                now = CodingAssistantProvider.MAX_SESSION_MILLIS
                return super.read(buffer, offset, length)
            }
        } } }
        try { CodingAssistantProvider.Client(fake) { now }.ask("OpenAI", key, "", task); fail("Expected timeout") }
        catch (failure: CodingAssistantProvider.ProviderFailure) { assertEquals(sessionLimitMessage, failure.message); assertNull(failure.cause) }
        assertEquals(1, fake.requests.size)
        assertEquals(1, fake.closed)
    }

    @Test fun clockRollbackFailsBeforeAnyRequest() {
        var calls = 0
        val fake = Fixture()
        try { CodingAssistantProvider.Client(fake) { if (calls++ == 0) 100L else 99L }.ask("OpenAI", key, "", task); fail("Expected timeout") }
        catch (failure: CodingAssistantProvider.ProviderFailure) { assertEquals(sessionLimitMessage, failure.message) }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun openAiRejectsIncompleteErrorsToolsRefusalAndUnexpectedMessageFields() {
        val replies = listOf(
            openAi().put("status", "incomplete"),
            openAi().put("error", JSONObject().put("message", "reflected-error-body $key")),
            openAi().put("incomplete_details", JSONObject().put("reason", "max_output_tokens")),
            openAi().put("output", JSONArray()),
            openAi().apply { getJSONArray("output").put(getJSONArray("output").getJSONObject(0)) },
            openAi().put("tool_calls", JSONArray()),
            openAi().put("output_text", answer),
            openAi().apply { getJSONArray("output").getJSONObject(0).put("type", "function_call") },
            openAi().apply { getJSONArray("output").getJSONObject(0).put("status", "in_progress") },
            openAi().apply { getJSONArray("output").getJSONObject(0).put("role", "user") },
            openAi().apply { getJSONArray("output").getJSONObject(0).put("tool_calls", JSONArray()) },
            openAi().apply { getJSONArray("output").getJSONObject(0).put("content", JSONArray()) },
            openAi().apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("type", "refusal") },
            openAi().apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text", 42) },
            openAi().apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("annotations", JSONArray().put(JSONObject())) }
        )
        for (reply in replies) {
            val fake = Fixture().apply { body(reply) }
            fails(fake)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun geminiRejectsTruncationSafetyBlocksToolsAndNonModelOutput() {
        val replies = listOf(
            gemini().put("error", JSONObject().put("message", "reflected-error-body $key")),
            gemini().apply { getJSONArray("candidates").getJSONObject(0).put("finishReason", "MAX_TOKENS") },
            gemini().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")),
            gemini().put("candidates", JSONArray()),
            gemini().apply { getJSONArray("candidates").put(getJSONArray("candidates").getJSONObject(0)) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).put("functionCall", JSONObject()) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).put("text", JSONObject()) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").put("role", "user") },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).put("thought", true) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").put("parts", JSONArray()) }
        )
        for (reply in replies) {
            val fake = Fixture().apply { body(reply) }
            fails(fake, "Gemini")
            assertEquals(1, fake.closed)
        }
    }

    @Test fun completeMultipartPlainTextIsReturnedWithoutInterpretingAsCodeOrTools() {
        val open = Fixture().apply { body(openAi("First").apply {
            getJSONArray("output").getJSONObject(0).getJSONArray("content")
                .put(JSONObject().put("type", "output_text").put("text", "Second"))
        }) }
        assertEquals("First\nSecond", CodingAssistantProvider.Client(open).ask("OpenAI", key, "", task))
        val google = Fixture().apply { body(gemini("First").apply {
            getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                .put(JSONObject().put("text", "Second"))
        }) }
        assertEquals("FirstSecond", CodingAssistantProvider.Client(google).ask("Gemini", key, "", task))
    }

    @Test fun geminiAllowsHarmlessPartMetadataAndSkipsThinkingParts() {
        val response = gemini("connected")
        val parts = response.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        parts.getJSONObject(0).put("thoughtSignature", "opaque-signature")
        val finalText = parts.getJSONObject(0)
        val withThinking = JSONArray()
            .put(JSONObject().put("thought", true).put("text", "private reasoning"))
            .put(finalText)
        response.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").put("parts", withThinking)
        val fake = Fixture().apply { body(response) }
        assertEquals("connected", CodingAssistantProvider.Client(fake).ask("Gemini", key, "gemini-2.5-flash", task))
        assertEquals(1, fake.closed)
    }

    @Test fun safeNetworkFailuresAreDistinguishedWithoutExposingExceptionDetails() {
        val cases = listOf(
            java.net.SocketTimeoutException("secret $key") to "timed out",
            java.net.UnknownHostException("secret $key") to "hostname",
            java.net.ConnectException("secret $key") to "Could not connect"
        )
        for ((exception, expected) in cases) {
            val client = CodingAssistantProvider.Client(CodingAssistantProvider.PostTransport { throw exception })
            try {
                client.ask("Gemini", key, "gemini-2.5-flash", task)
                fail("Expected safe network failure")
            } catch (failure: CodingAssistantProvider.ProviderFailure) {
                assertTrue(failure.message.orEmpty().contains(expected))
                assertFalse(failure.toString().contains(key))
                assertNull(failure.cause)
            }
        }
    }

    @Test fun emptyTextResponsesFailInsteadOfClaimingSuccess() {
        for (text in listOf("", " \n\t")) {
            fails(Fixture().apply { body(openAi(text)) })
            fails(Fixture().apply { body(gemini(text)) }, "Gemini")
        }
    }

    @Test fun keyEchoesAreRejectedInDecodedTextMetadataAndFieldNames() {
        for (provider in listOf("OpenAI", "Gemini")) {
            val envelope = if (provider == "OpenAI") openAi("Never show $key") else gemini("Never show $key")
            fails(Fixture().apply { body(envelope) }, provider)
            val escaped = envelope.toString().replace(key, key.map { "\\u%04x".format(it.code) }.joinToString(""))
            fails(Fixture().apply { bytes = escaped.toByteArray(Charsets.UTF_8) }, provider)
        }
        fails(Fixture().apply { body(openAi().put("metadata", key)) })
        fails(Fixture().apply { body(openAi().put(key, "value")) })
    }

    @Test fun multipartCredentialEchoCannotLeakAfterTextAssembly() {
        val fake = Fixture().apply { body(gemini(key.take(5)).apply {
            getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                .put(JSONObject().put("text", key.drop(5)))
        }) }
        fails(fake, "Gemini")
        assertEquals(1, fake.closed)
    }

    @Test fun providerBoundaryRemainsProtectedByExistingPatchScope() {
        val path = "developer/src/main/java/com/jarvis/developer/CodingAssistantProvider.kt"
        assertFalse(PatchEngine.allowedPath(path))
        val request = PatchEngine.Request("boundary-protection", "Update the provider boundary", "a".repeat(40),
            listOf(path), listOf(PatchEngine.SourceFile(path, null, null)), "OpenAI", "gpt-4.1-mini")
        try { PatchEngine.validateRequest(request); fail("Provider boundary must remain outside generated patch scope") }
        catch (failure: IllegalArgumentException) { assertTrue(failure.message.orEmpty().contains("allowlist")) }
    }

    @Test fun envelopeMustBeSingleStandardJsonWithUniqueKeysAndBoundedDepth() {
        val valid = openAi().toString()
        val malformed = listOf(valid + valid, valid + " trailing", valid.dropLast(1) + ",}",
            valid.replaceFirst("\"status\":\"completed\"", "\"status\":\"completed\",\"st\\u0061tus\":\"completed\""),
            "{status:'completed'}", "{\"status\":NaN}", "{\"status\":undefined}", "{\"status\":\"bad\nstring\"}",
            "{\"status\":\"\\ud800\"}", "{\"padding\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}")
        for (text in malformed) fails(Fixture().apply { bytes = text.toByteArray(Charsets.UTF_8) })
    }

    @Test fun throwingTransportAndCloseFailuresCannotLeakCredentialOrExceptionCause() {
        val client = CodingAssistantProvider.Client(CodingAssistantProvider.PostTransport {
            throw IllegalArgumentException("https://evil.example?key=$key reflected-error-body")
        })
        try { client.ask("OpenAI", key, "", task); fail("Expected safe failure") }
        catch (failure: IllegalStateException) {
            assertEquals(CodingAssistantProvider.FAILURE, failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(key))
            assertFalse(failure.toString().contains("evil.example"))
        }
        val fake = Fixture().apply { body(openAi()); stream = { object : ByteArrayInputStream(bytes) {
            override fun close() { error("reflected-error-body $key") }
        } } }
        fails(fake)
        assertEquals(1, fake.closed)
    }
    @Test fun anAlreadyCancelledRequestNeverPostsToAProvider() {
        val fake = Fixture().apply { body(openAi()) }
        val cancellation = PatchPreviewCancellation().also { it.cancel() }
        val client = CodingAssistantProvider.Client(fake, cancellation = cancellation)
        val failure = assertThrows(IllegalStateException::class.java) { client.ask("OpenAI", key, "", task) }
        assertEquals(CodingAssistantProvider.FAILURE, failure.message); assertTrue(fake.requests.isEmpty())
    }
    @Test fun cancellationDuringResponseReadingClosesAndRejectsPartialText() {
        val cancellation = PatchPreviewCancellation()
        val fake = Fixture().apply { body(openAi()); stream = { object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length); cancellation.cancel(); return count
            }
        } } }
        val client = CodingAssistantProvider.Client(fake, cancellation = cancellation)
        val failure = assertThrows(IllegalStateException::class.java) { client.ask("OpenAI", key, "", task) }
        assertEquals(CodingAssistantProvider.FAILURE, failure.message); assertEquals(1, fake.closed)
    }
    @Test fun cancellationDisconnectsTheHttpRequestBeforeAResponseExists() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val connection = object : java.net.HttpURLConnection(java.net.URL("https://api.openai.com/v1/responses")) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() { stopped.countDown(); released.countDown() }
            override fun getOutputStream(): java.io.OutputStream = ByteArrayOutputStream()
            override fun getResponseCode(): Int {
                entered.countDown(); released.await(5, java.util.concurrent.TimeUnit.SECONDS)
                throw java.net.SocketException("private transport fixture $key")
            }
        }
        val cancellation = PatchPreviewCancellation()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val client = CodingAssistantProvider.Client(CodingAssistantProvider.http(cancellation) { connection }, cancellation = cancellation)
            val job = pool.submit<Exception?> { try { client.ask("OpenAI", key, "", task); null } catch (failure: Exception) { failure } }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); cancellation.cancel()
            assertTrue(cancellation.isCancelled); assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val failure = job.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(failure); assertEquals(CodingAssistantProvider.FAILURE, failure!!.message)
            assertFalse(failure.toString().contains(key)); assertFalse(failure.toString().contains("private transport fixture"))
        } finally { released.countDown(); pool.shutdownNow() }
    }
}
