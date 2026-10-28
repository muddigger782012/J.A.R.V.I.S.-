package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in, real GET and Gemini POST. No fixture transport, source writes or installation tools. */
class LiveGeminiRepositoryContextTest {
    @Test fun realGeminiUsesVerifiedAuthorizedRepositoryContext() {
        val root = File(requireNotNull(System.getProperty("jarvis.repositoryRoot")))
        val report = File(root, "developer/build/reports/live-gemini/acceptance.json")
        report.parentFile.mkdirs()
        val model = System.getenv("JARVIS_GEMINI_TEST_MODEL").orEmpty().ifBlank { "gemini-2.5-flash" }
        val evidence = JSONObject().put("schemaVersion", 1).put("status", "RUNNING")
            .put("provider", "Gemini").put("buildSourceSha", System.getProperty("jarvis.repositoryCommit", ""))
            .put("sourceVerified", false).put("providerResponseVerified", false).put("groundedResponse", false)
        val cancellation = PatchPreviewCancellation()
        val started = System.nanoTime()
        var stage = "CREDENTIAL"
        try {
            val key = System.getenv("GEMINI_API_KEY").orEmpty()
            if (key.isBlank()) {
                evidence.put("status", "BLOCKED").put("reason", "GEMINI_API_KEY is not securely configured")
                fail("BLOCKED: securely configure GEMINI_API_KEY before running live acceptance")
            }
            require(model.matches(Regex("gemini-[a-z0-9][a-z0-9.-]{0,72}")))
            evidence.put("model", model)
            val task = "Review project generation cancellation. In under 180 words, explain how stale replies are rejected. " +
                "Cite the full commit SHA and at least one exact source path from the provided repository context. " +
                "Use only the supplied evidence. Do not propose or perform edits, builds, publication or installation."
            stage = "SOURCE_READ"
            val context = RepositoryIntelligence.codingContext(task, PatchEngine.Target.DEVELOPER,
                System.getenv("JARVIS_GITHUB_READ_TOKEN").orEmpty(), key, cancellation)
            require(context.verified && context.commit != null && context.paths.isNotEmpty())
            val data = context.data(task)
            require(!data.contains(key))
            evidence.put("sourceVerified", true).put("sourceCommitSha", context.commit)
                .put("sourceBranch", RepositoryCodingContext.branch(context.target))
                .put("paths", JSONArray(context.paths)).put("contextSha256", hash(data))
                .put("contextUtf8Bytes", data.toByteArray(Charsets.UTF_8).size)
            stage = "GEMINI_REQUEST"
            // The same production composer, HTTPS transport, key/header policy and response decoder
            // used by Home. A bounded answer must cite evidence, not merely say "connected".
            val reply = CodingAssistantProvider.Client(CodingAssistantProvider.http(cancellation), cancellation = cancellation)
                .ask("Gemini", key, model, task, maxOutputTokens = 4096, context = context)
            evidence.put("providerResponseVerified", true).put("replySha256", hash(reply)).put("replyCharacters", reply.length)
            stage = "RESPONSE_GROUNDING"
            val citesCommit = reply.contains(requireNotNull(context.commit))
            val citesPath = context.paths.any(reply::contains)
            evidence.put("citesCommit", citesCommit).put("citesPath", citesPath)
            require(citesCommit && citesPath)
            evidence.put("groundedResponse", true).put("status", "PASSED")
        } catch (failure: AssertionError) {
            if (evidence.optString("status") != "BLOCKED") evidence.put("status", "FAILED")
            throw failure
        } catch (_: Exception) {
            evidence.put("status", "FAILED")
            // Exceptions/source/provider bodies are deliberately absent from logs and reports.
            fail("Real Gemini repository-context acceptance failed at $stage; inspect the bounded evidence report")
        } finally {
            cancellation.cancel()
            evidence.put("stage", stage).put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            val encoded = evidence.toString(2)
            check(encoded.toByteArray(Charsets.UTF_8).size <= 12_000)
            report.writeText(encoded)
        }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
