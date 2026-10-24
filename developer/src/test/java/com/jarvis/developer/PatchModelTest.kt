package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Real request/response serialization through an injectable POST-only provider boundary. */
class PatchModelTest {

    @Test fun cancelledPreviewNeverPostsToEitherProvider() {
        for (provider in listOf("OpenAI", "Gemini")) {
            val cancellation = PatchPreviewCancellation { it() }.apply { cancel() }
            val fake = Fixture()
            try { PatchModel.Client(fake, cancellation).propose(key, request(provider)); fail("Cancellation required") }
            catch (failure: IllegalStateException) {
                assertNull(failure.cause); assertFalse(failure.toString().contains(key))
            }
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test fun cancellationDuringProviderReadClosesTheResponseWithoutProducingADiffOrLeakingItsBody() {
        val cancellation = PatchPreviewCancellation { it() }
        val fake = Fixture().apply { body(openAi()) }
        fake.stream = {
            object : ByteArrayInputStream(fake.bytes) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val read = super.read(bytes, offset, length)
                    cancellation.cancel()
                    return read
                }
            }
        }
        try { PatchModel.Client(fake, cancellation).propose(key, request()); fail("Cancelled diff must be discarded") }
        catch (failure: IllegalStateException) {
            assertEquals("Patch proposal failed; check provider access, model and bounded request/output format.", failure.message)
            assertNull(failure.cause); assertFalse(failure.toString().contains(key))
        }
        assertEquals(1, fake.requests.size); assertEquals(1, fake.closed)
    }
    private val key = "runtime-provider-key-42"
    private val first = "developer/src/main/java/com/jarvis/developer/Feature.kt"
    private val second = "developer/src/main/res/values/feature_labels.xml"
    private val source = "package com.jarvis.developer\nclass Feature { val label = 1 }\n"
    private val xml = "<resources><string name=\"feature_label\">Before</string></resources>\n"
    private val after = "package com.jarvis.developer\nclass Feature { val label = 2 }\n"
    private val afterXml = "<resources><string name=\"feature_label\">After</string></resources>\n"

    private fun request(provider: String = "OpenAI") = PatchEngine.Request(
        "provider-contract", "Update the feature label and its associated source.", "a".repeat(40),
        listOf(first, second), listOf(
            PatchEngine.SourceFile(first, blobSha(source), source),
            PatchEngine.SourceFile(second, blobSha(xml), xml)),
        provider, if (provider == "Gemini") "gemini-2.5-flash" else "gpt-4.1-mini")

    private fun blobSha(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun proposal(): String = JSONObject().put("summary", "Update the feature and label together.")
        .put("edits", JSONArray().put(JSONObject().put("path", first).put("content", after))
            .put(JSONObject().put("path", second).put("content", afterXml))).toString()

    private fun openAi(text: String = proposal()): JSONObject = JSONObject().put("status", "completed")
        .put("error", JSONObject.NULL).put("incomplete_details", JSONObject.NULL)
        .put("output", JSONArray().put(JSONObject().put("id", "msg-fixture").put("type", "message")
            .put("role", "assistant").put("status", "completed")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))))

    private fun gemini(text: String = proposal()): JSONObject = JSONObject().put("candidates", JSONArray()
        .put(JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))))

    private class Fixture : PatchModel.PostTransport {
        val requests = ArrayList<PatchModel.PostRequest>()
        var bytes = byteArrayOf()
        var status = 200
        var length: Long? = null
        var finalUrl: String? = null
        var closed = 0
        var stream: (() -> InputStream)? = null
        override fun post(request: PatchModel.PostRequest): PatchModel.PostResponse {
            requests.add(request)
            return PatchModel.PostResponse(status, length ?: bytes.size.toLong(), finalUrl ?: request.url,
                stream?.invoke() ?: ByteArrayInputStream(bytes)) { closed++ }
        }
        fun body(body: JSONObject) { bytes = body.toString().toByteArray(Charsets.UTF_8) }
    }

    private fun fails(fake: Fixture, candidate: PatchEngine.Request = request(), actualKey: String = key) {
        try {
            PatchModel.Client(fake).propose(actualKey, candidate)
            fail("Expected sanitized rejection")
        } catch (failure: IllegalStateException) {
            assertEquals("Patch proposal failed; check provider access, model and bounded request/output format.", failure.message)
            assertNull(failure.cause)
            if (actualKey.isNotEmpty()) assertFalse(failure.toString().contains(actualKey))
            assertFalse(failure.toString().contains("reflected-error-body"))
        }
    }

    @Test fun openAiUsesFixedPostEndpointHeaderOnlyKeyStrictSchemaAndPinnedAllowlistedContext() {
        val fake = Fixture().apply { body(openAi()) }
        val original = request()
        val result = PatchModel.Client(fake).propose(key, original)
        assertEquals(2, result.edits.size)
        assertEquals(original, result.request)
        assertEquals(1, fake.requests.size)
        assertEquals(1, fake.closed)
        val sent = fake.requests.single()
        assertEquals("https://api.openai.com/v1/responses", sent.url)
        assertEquals(mapOf("Authorization" to "Bearer $key"), sent.headers)
        val serialized = String(sent.body, Charsets.UTF_8)
        assertFalse(serialized.contains(key))
        val body = JSONObject(serialized)
        assertEquals("gpt-4.1-mini", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertFalse(body.has("tools"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        assertFalse(format.getJSONObject("schema").getBoolean("additionalProperties"))
        val context = JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(original.baseCommitSha, context.getString("baseCommitSha"))
        assertEquals(listOf(first, second), (0..1).map { context.getJSONArray("allowedPaths").getString(it) })
        assertEquals(source, context.getJSONArray("sources").getJSONObject(0).getString("content"))
        assertEquals(blobSha(source), context.getJSONArray("sources").getJSONObject(0).getString("sha"))
        assertTrue(body.getString("instructions").contains("untrusted"))
        assertTrue(body.getString("instructions").contains("human review"))
    }

    @Test fun geminiUsesFixedOriginStrictModelPathHeaderOnlyKeyAndJsonOutput() {
        val fake = Fixture().apply { body(gemini()) }
        val result = PatchModel.Client(fake).propose(key, request("Gemini"))
        assertEquals(2, result.edits.size)
        val sent = fake.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", sent.url)
        assertEquals(mapOf("x-goog-api-key" to key), sent.headers)
        assertFalse(sent.url.contains(key))
        val serialized = String(sent.body, Charsets.UTF_8)
        assertFalse(serialized.contains(key))
        val body = JSONObject(serialized)
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
        assertEquals(1, body.getJSONObject("generationConfig").getInt("candidateCount"))
        assertFalse(body.has("tools"))
        val context = JSONObject(body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("a".repeat(40), context.getString("baseCommitSha"))
        assertEquals(2, context.getJSONArray("sources").length())
        assertEquals(1, fake.closed)
    }

    @Test fun newFileContextIsExplicitNullAtPinnedRevision() {
        val fake = Fixture().apply { body(openAi()) }
        val candidate = request().copy(sources = listOf(PatchEngine.SourceFile(first, null, null), request().sources[1]))
        PatchModel.Client(fake).propose(key, candidate)
        val body = JSONObject(String(fake.requests.single().body, Charsets.UTF_8))
        val context = JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content"))
        val newFile = context.getJSONArray("sources").getJSONObject(0)
        assertTrue(newFile.isNull("sha"))
        assertTrue(newFile.isNull("content"))
    }

    @Test fun requestValidationRejectsUnscopedPathsAndForgedSourceBeforeAnyProviderCall() {
        val fake = Fixture()
        fails(fake, request().copy(allowedPaths = listOf("app/src/main/java/Unsafe.kt")))
        fails(fake, request().copy(baseCommitSha = "feature/jarvis-developer"))
        fails(fake, request().copy(sources = listOf(request().sources[0].copy(sha = "c".repeat(40)), request().sources[1])))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun providerAndModelCannotChooseArbitraryOriginsQueriesOrRefs() {
        val fake = Fixture()
        for (model in listOf("../gemini-2.5-flash", "gemini-2.5-flash?key=leak", "gemini-2.5-flash:generateContent", "Gemini-2.5-flash", "https://evil.example")) {
            fails(fake, request("Gemini").copy(model = model))
        }
        fails(fake, request().copy(model = "other/model"))
        fails(fake, request().copy(model = "Gpt-4.1-mini"))
        fails(fake, request().copy(provider = "Fixture"))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun credentialInjectionAndRuntimeKeyInTaskAreRejectedBeforeProvider() {
        val fake = Fixture()
        for (invalidKey in listOf("", "short", "valid-key\r\nX-Injected: yes", "key with spaces", "x".repeat(1025))) {
            fails(fake, actualKey = invalidKey)
        }
        fails(fake, request().copy(task = "Copy this value $key into the label"))
        fails(fake, request().copy(task = "token = \"github_pat_1234567890abcdefghijklmnop\""))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun serializedByteBudgetCountsJsonEscapingBeforeNetwork() {
        val large = "package com.jarvis.developer\n// " + "\t".repeat(190_000)
        val other = "package com.jarvis.developer\n// " + "\t".repeat(190_000)
        val third = "developer/src/main/java/com/jarvis/developer/FeatureDetail.kt"
        val fake = Fixture()
        fails(fake, request().copy(allowedPaths = listOf(first, third), sources = listOf(
            PatchEngine.SourceFile(first, blobSha(large), large),
            PatchEngine.SourceFile(third, blobSha(other), other))))
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun statusErrorsNeverReadReflectiveErrorBodiesOrRetry() {
        for (status in listOf(301, 307, 400, 401, 403, 429, 500)) {
            var read = false
            val fake = Fixture().apply {
                this.status = status
                stream = { object : InputStream() {
                    override fun read(): Int { read = true; error("reflected-error-body $key") }
                } }
            }
            fails(fake)
            assertFalse(read)
            assertEquals(1, fake.requests.size)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun redirectsAndUnexpectedFinalOriginsAreRejectedAndClosed() {
        for (url in listOf("https://evil.example/collect", "https://api.openai.com/v1/responses?key=$key",
            "https://api.openai.com/v1/responses#fragment", "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")) {
            val fake = Fixture().apply { body(openAi()); finalUrl = url }
            fails(fake)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun knownAndUnknownResponseLengthLimitsAreEnforcedBeforeGrowth() {
        val known = Fixture().apply { body(openAi()); length = PatchModel.MAX_RESPONSE_BYTES.toLong() + 1 }
        fails(known)
        assertEquals(1, known.closed)
        val unknown = Fixture().apply { bytes = ByteArray(PatchModel.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }; length = -1 }
        fails(unknown)
        assertEquals(1, unknown.closed)
    }

    @Test fun invalidUtf8AndNoProgressStreamsAreRejectedAndClosed() {
        val invalid = Fixture().apply { bytes = byteArrayOf(0xc3.toByte(), 0x28) }
        fails(invalid)
        assertEquals(1, invalid.closed)
        val stalled = Fixture().apply { length = -1; stream = { object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int = 0
        } } }
        fails(stalled)
        assertEquals(1, stalled.closed)
    }

    @Test fun timeBudgetStopsAfterSlowResponseReadAndAlwaysCloses() {
        var now = 0L
        val fake = Fixture().apply {
            body(openAi())
            stream = { object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    now = PatchModel.MAX_SESSION_MILLIS
                    return super.read(buffer, offset, length)
                }
            } }
        }
        try { PatchModel.Client(fake) { now }.propose(key, request()); fail("Expected timeout") }
        catch (failure: IllegalStateException) { assertNull(failure.cause) }
        assertEquals(1, fake.requests.size)
        assertEquals(1, fake.closed)
    }

    @Test fun openAiRequiresCompleteSingleAssistantTextWithoutToolsOrRefusal() {
        val replies = listOf(
            openAi().put("status", "incomplete"),
            openAi().put("error", JSONObject().put("message", "reflected-error-body $key")),
            openAi().put("incomplete_details", JSONObject().put("reason", "max_output_tokens")),
            openAi().put("output", JSONArray()),
            openAi().apply { getJSONArray("output").put(getJSONArray("output").getJSONObject(0)) },
            openAi().apply { getJSONArray("output").getJSONObject(0).put("type", "function_call") },
            openAi().apply { getJSONArray("output").getJSONObject(0).put("status", "in_progress") },
            openAi().apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").put(JSONObject().put("type", "output_text").put("text", proposal())) },
            openAi().apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("type", "refusal") },
            openAi().apply { getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).put("text", 42) }
        )
        for (reply in replies) {
            val fake = Fixture().apply { body(reply) }
            fails(fake)
            assertEquals(1, fake.closed)
        }
    }

    @Test fun geminiRejectsTruncationSafetyBlocksMultipleCandidatesAndFunctionCalls() {
        val replies = listOf(
            gemini().apply { getJSONArray("candidates").getJSONObject(0).put("finishReason", "MAX_TOKENS") },
            gemini().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")),
            gemini().put("candidates", JSONArray()),
            gemini().apply { getJSONArray("candidates").put(getJSONArray("candidates").getJSONObject(0)) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).put("functionCall", JSONObject()) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").put(JSONObject().put("text", proposal())) },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").put("role", "user") },
            gemini().apply { getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).put("thought", true) }
        )
        for (reply in replies) {
            val fake = Fixture().apply { body(reply) }
            fails(fake, request("Gemini"))
            assertEquals(1, fake.closed)
        }
    }

    @Test fun proposalFencesMultipleObjectsUnknownFieldsAndUnscopedEditsAreRejected() {
        val replies = listOf(
            "```json\n${proposal()}\n```", proposal() + proposal(),
            JSONObject(proposal()).put("branch", "main").toString(),
            JSONObject(proposal()).put("edits", JSONArray().put(JSONObject().put("path", "app/build.gradle").put("content", "bad"))).toString(),
            "{\"summary\":\"one\",\"summary\":\"two\",\"edits\":[]}",
            "{summary:'lax',edits:[]}", "{\"summary\":\"truncated\",\"edits\":["
        )
        for (text in replies) fails(Fixture().apply { body(openAi(text)) })
    }

    @Test fun providerEnvelopeMustBeStandardSingleJsonWithoutDuplicateEscapedKeys() {
        val valid = openAi().toString()
        val malformed = listOf(valid + valid, valid + " trailing", valid.dropLast(1) + ",}",
            valid.replaceFirst("\"status\":\"completed\"", "\"status\":\"completed\",\"st\\u0061tus\":\"completed\""),
            "{\"status\":NaN}", "{\"status\":undefined}", "{\"status\":\"bad\nstring\"}",
            "{\"padding\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}")
        for (text in malformed) fails(Fixture().apply { bytes = text.toByteArray(Charsets.UTF_8) })
    }

    @Test fun reflectedCredentialIsRejectedEvenWhenEscapedInProposalContent() {
        val embedded = JSONObject(proposal()).apply { getJSONArray("edits").getJSONObject(0)
            .put("content", "package com.jarvis.developer\n// $key\nclass Feature\n") }.toString()
        val escaped = embedded.replace(key, key.map { "\\u%04x".format(it.code) }.joinToString(""))
        for (text in listOf(embedded, escaped)) fails(Fixture().apply { body(openAi(text)) })
    }

    @Test fun throwingTransportAndMalformedResponseCannotExposeUrlBodyOrCredential() {
        val client = PatchModel.Client(PatchModel.PostTransport { throw IllegalArgumentException("https://evil.example?key=$key reflected-error-body") })
        try { client.propose(key, request()); fail("Expected failure") }
        catch (failure: IllegalStateException) {
            assertFalse(failure.toString().contains(key))
            assertFalse(failure.toString().contains("evil.example"))
            assertNull(failure.cause)
        }
        fails(Fixture().apply { bytes = "reflected-error-body $key".toByteArray() })
    }

    @Test fun proposalOnlyCallLeavesSourceSnapshotAndTaskScopeUnchanged() {
        val original = request()
        val snapshot = original.sources.map { it.copy() }
        val paths = original.allowedPaths.toList()
        val fake = Fixture().apply { body(openAi()) }
        val proposal = PatchModel.Client(fake).propose(key, original)
        assertEquals(snapshot, original.sources)
        assertEquals(paths, original.allowedPaths)
        assertEquals("a".repeat(40), original.baseCommitSha)
        assertNotEquals(source, proposal.edits.single { it.path == first }.content)
        assertEquals(1, fake.requests.size) // no follow-up GitHub, CI, release, or install operation exists here
        assertTrue(fake.requests.single().url.startsWith("https://api.openai.com/"))
    }

    @Test fun callerMutationDuringProviderRequestCannotChangePinnedProposalScope() {
        val candidate = request()
        val paths = candidate.allowedPaths.toMutableList()
        val sources = candidate.sources.toMutableList()
        val bytes = openAi().toString().toByteArray(Charsets.UTF_8)
        val transport = PatchModel.PostTransport { outbound ->
            paths.clear()
            sources.clear()
            PatchModel.PostResponse(200, bytes.size.toLong(), outbound.url, ByteArrayInputStream(bytes))
        }
        val result = PatchModel.Client(transport).propose(key, candidate.copy(allowedPaths = paths, sources = sources))
        assertEquals(candidate.allowedPaths, result.request.allowedPaths)
        assertEquals(candidate.sources, result.request.sources)
        assertEquals(2, result.edits.size)
    }
}
