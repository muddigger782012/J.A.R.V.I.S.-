package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectGenerationContextTest {
    private val commit = "a".repeat(40)
    private val path = "developer/src/main/java/com/jarvis/developer/VoiceNetwork.kt"
    private val secret = "configured-provider-fixture-42"
    private val readToken = "configured-github-fixture-42"
    private fun evidence(task: String, target: PatchEngine.Target, revision: String = commit): RepositoryCodingContext.Context {
        val source = "class VoiceNetwork { fun voiceLatency() = 5 }\n// $readToken $secret"
        val bytes = source.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()) }
        val blob = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val actualPath = if (target == PatchEngine.Target.PERSONAL) path.replace("developer/src", "app/src")
            .replace("com/jarvis/developer", "com/assistant/core") else path
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(actualPath, blob, bytes.size.toLong())), false, revision)
        val plan = RepositoryAnalysis.analyze(task, inventory) { source }
        return RepositoryCodingContext.fromAnalysis(task, target, inventory, plan, mapOf(actualPath to source), listOf(secret, readToken))
    }
    private inner class Screen(preferences: android.content.SharedPreferences? = null) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val send = Button(activity); val cancel = Button(activity)
        val provider = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Gemini", "OpenAI")) }
        val target = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Developer", "Personal")) }
        val key = EditText(activity).apply { setText(secret) }
        val model = EditText(activity).apply { setText("gemini-2.5-pro") }
        val prompt = EditText(activity).apply { setText("Repair voice network latency") }
        val output = TextView(activity); val status = TextView(activity); val forget = Button(activity)
        val prefs = preferences ?: activity.getSharedPreferences("context-fixture-" + java.util.UUID.randomUUID(), Context.MODE_PRIVATE)
        val pending = mutableListOf<() -> Unit>()
        val requests = mutableListOf<JSONObject>()
        val reads = mutableListOf<Pair<String, PatchEngine.Target>>()
        var reader: (String, PatchEngine.Target) -> RepositoryCodingContext.Context = { task, target -> evidence(task, target) }
        var selected = "context-project"
        var providerFailure: Exception? = null
        val handle = ProjectGenerationWorkbench.attach(activity, send, cancel, provider, key, model, prompt, output, status,
            { target.selectedItemPosition }, { target }, { selected }, { selected = it }, ProjectGenerationStore(prefs), {},
            ProjectGenerationWorkbench.Services(worker = { pending.add(it) }, context = { task, product, token, apiKey, stop ->
                assertEquals(readToken, token); assertEquals(secret, apiKey); stop.check()
                reads.add(task to product); reader(task, product)
            }, ask = { provider, apiKey, selectedModel, task, stop, context ->
                CodingAssistantProvider.Client(CodingAssistantProvider.PostTransport { request ->
                    requests.add(JSONObject(String(request.body, Charsets.UTF_8)))
                    providerFailure?.let { throw it }
                    val response = if (provider == "Gemini") JSONObject().put("candidates", JSONArray().put(JSONObject()
                        .put("finishReason", "STOP").put("content", JSONObject().put("role", "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", "Use the voice evidence."))))))
                    else JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                        .put("type", "message").put("role", "assistant").put("status", "completed")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Use the voice evidence.")))))
                    val bytes = response.toString().toByteArray()
                    CodingAssistantProvider.PostResponse(200, bytes.size.toLong(), request.url, ByteArrayInputStream(bytes))
                }, cancellation = stop).ask(provider, apiKey, selectedModel, task, context = context)
            }), githubToken = { readToken }, forget = forget)
        fun complete() { pending.removeAt(0).invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun record() = prefs.getString("project_context-project", "")!!
        override fun close() { handle.dispose(); controller.pause().stop().destroy() }
    }

    private fun context(body: JSONObject, provider: String = "Gemini"): JSONObject = JSONObject(if (provider == "Gemini")
        body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text")
        else body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("text"))

    @Test fun followUpsIncludeOnlyTheirProjectDiscussionAndFreshRevisionForBothProviders() {
        for (provider in listOf("Gemini", "OpenAI")) Screen().use { s ->
            if (provider == "OpenAI") { s.provider.setSelection(1); s.model.setText("gpt-4.1-mini") }
            s.send.performClick(); s.complete()
            assertFalse(context(s.requests.single(), provider).has("priorDiscussion"))
            s.reader = { task, target -> evidence(task, target, "b".repeat(40)) }
            s.prompt.setText("What about its tests?"); s.send.performClick(); s.complete()
            val data = context(s.requests.last(), provider)
            assertEquals("b".repeat(40), data.getString("commitSha"))
            val turn = data.getJSONObject("priorDiscussion").getJSONArray("turns").getJSONObject(0)
            assertEquals("Repair voice network latency", turn.getString("ownerRequest"))
            assertEquals("Use the voice evidence.", turn.getString("assistantReply"))
            assertTrue(s.reads.last().first.contains("Repair voice network latency"))
            assertFalse(data.getJSONObject("priorDiscussion").has("project"))
            assertTrue(s.output.text.toString().contains("Earlier coding discussion: 1"))
            assertFalse(s.record().contains(secret)); assertFalse(s.record().contains(readToken))
            assertEquals(2, JSONObject(s.record()).getJSONObject("discussion").getJSONArray("turns").length())
            assertTrue(s.forget.isEnabled)
        }
    }

    @Test fun olderDiscussionNeverClaimsUnavailableCurrentSourceWasInspected() {
        Screen().use { s ->
            s.send.performClick(); s.complete()
            s.reader = { task, target -> RepositoryCodingContext.unavailable(task, target) }
            s.prompt.setText("And the tests?"); s.send.performClick(); s.complete()
            val data = context(s.requests.last())
            assertEquals("unavailable", data.getString("status")); assertFalse(data.has("commitSha")); assertFalse(data.has("files"))
            assertTrue(data.has("priorDiscussion"))
            assertTrue(s.output.text.toString().startsWith("Repository context unavailable."))
            assertTrue(s.output.text.toString().contains("unverified")); assertFalse(s.output.text.toString().contains(commit))
        }
    }

    @Test fun resetRemovesDiscussionAndNextRequestStartsFreshWithoutDiscardingProjectInputs() {
        Screen().use { s ->
            s.send.performClick(); s.complete()
            val oldReply = JSONObject(s.record()).getString("response")
            s.forget.performClick()
            assertFalse(JSONObject(s.record()).has("discussion"))
            assertEquals(oldReply, JSONObject(s.record()).getString("response"))
            assertEquals("Repair voice network latency", s.prompt.text.toString())
            assertEquals(s.activity.getString(R.string.project_generation_discussion_cleared), s.status.text.toString())
            s.prompt.setText("Start a different feature"); s.send.performClick(); assertFalse(s.forget.isEnabled); s.complete()
            assertFalse(context(s.requests.last()).has("priorDiscussion"))
        }
    }

    @Test fun failedCancelledAndSupersededRepliesNeverExtendOrRestoreDiscussion() {
        Screen().use { s ->
            s.send.performClick(); s.complete()
            val stored = JSONObject(s.record()).getJSONObject("discussion").toString()
            s.providerFailure = IllegalStateException("private fixture")
            s.prompt.setText("And tests?"); s.send.performClick(); s.complete()
            assertEquals(stored, JSONObject(s.record()).getJSONObject("discussion").toString())
            s.providerFailure = null
            s.send.performClick(); s.cancel.performClick(); s.complete()
            assertEquals(stored, JSONObject(s.record()).getJSONObject("discussion").toString())
            s.send.performClick(); assertTrue(ProjectGenerationStore(s.prefs).forgetDiscussion("context-project")); s.complete()
            assertFalse(JSONObject(s.record()).has("discussion"))
        }
    }

    @Test fun reloadedDiscussionIsProjectScopedAndTargetChangesClearIt() {
        val preferences = Screen().use { s -> s.send.performClick(); s.complete(); s.prefs }
        Screen(preferences).use { s ->
            s.prompt.setText("And tests?"); s.send.performClick(); s.complete()
            assertTrue(context(s.requests.last()).has("priorDiscussion"))
            s.selected = "another-project"; s.send.performClick(); s.complete()
            assertFalse(context(s.requests.last()).has("priorDiscussion"))
            s.selected = "context-project"; s.target.setSelection(1); s.send.performClick(); s.complete()
            assertFalse(context(s.requests.last()).has("priorDiscussion"))
            s.target.setSelection(0); s.send.performClick(); s.complete()
            assertFalse(context(s.requests.last()).has("priorDiscussion"))
        }
    }

    @Test fun homeSendsActualSourceEvidenceWithManualModelsAndSavesOnlyReplyProvenance() {
        for (provider in listOf("Gemini", "OpenAI")) Screen().use { s ->
            if (provider == "OpenAI") { s.provider.setSelection(1); s.model.setText("gpt-4.1-mini") }
            s.send.performClick(); assertTrue(s.requests.isEmpty()); s.complete()
            val body = s.requests.single()
            val encoded = if (provider == "Gemini") body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text")
                else body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("text")
            val context = JSONObject(encoded)
            val request = body.toString()
            assertEquals(commit, context.getString("commitSha"))
            assertEquals(path, context.getJSONArray("files").getJSONObject(0).getString("path")); assertTrue(encoded.contains("voiceLatency"))
            assertFalse(request.contains(secret)); assertFalse(request.contains(readToken))
            assertTrue(s.output.text.toString().startsWith("Verified repository excerpts:"))
            assertTrue(s.output.text.toString().endsWith("Use the voice evidence."))
            assertFalse(s.record().contains(secret)); assertFalse(s.record().contains(readToken))
            assertFalse(s.record().contains("voiceLatency")); assertFalse(s.record().contains("approval"))
            assertEquals("generated", JSONObject(s.record()).getString("state"))
            assertTrue(s.send.isEnabled); assertFalse(s.cancel.isEnabled)
        }
    }

    @Test fun readFailuresUseExplicitInstructionsOnlyContextAndKeepErrorsSanitized() {
        Screen().use { s ->
            s.reader = { _, _ -> error("reflected untrusted failure $readToken $secret") }
            s.send.performClick(); s.complete()
            val sent = s.requests.single().toString()
            val context = JSONObject(s.requests.single().getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text"))
            assertEquals("unavailable", context.getString("status")); assertFalse(context.has("files")); assertFalse(context.has("commitSha"))
            assertTrue(s.output.text.toString().startsWith("Repository context unavailable."))
            assertFalse(sent.contains("reflected")); assertFalse(s.record().contains(secret)); assertFalse(s.record().contains(readToken))
        }
    }

    @Test fun cancellingDuringSourceCollectionNeverStartsTheProviderOrStoresTheContext() {
        Screen().use { s ->
            s.reader = { task, target -> s.cancel.performClick(); evidence(task, target) }
            s.send.performClick(); s.complete()
            assertTrue(s.requests.isEmpty()); assertEquals("generation_cancelled", JSONObject(s.record()).getString("state"))
            assertTrue(s.send.isEnabled); assertFalse(s.record().contains(commit)); assertEquals("", JSONObject(s.record()).getString("response"))
        }
    }

    @Test fun contextForAnotherTaskOrProductIsRejectedBeforeAnyPostOrInspectionClaim() {
        for (wrongTarget in listOf(false, true)) Screen().use { s ->
            s.reader = { task, target -> if (wrongTarget) evidence(task, PatchEngine.Target.PERSONAL) else evidence("Earlier voice network task", target) }
            s.send.performClick(); s.complete()
            assertTrue(s.requests.isEmpty()); assertEquals("generation_failed", JSONObject(s.record()).getString("state"))
            assertFalse(s.output.text.toString().contains("Verified repository excerpts")); assertTrue(s.send.isEnabled)
        }
    }

    @Test fun offlineSavingMakesNoSourceOrProviderCallAndPersonalContextUsesItsOwnScope() {
        Screen().use { s ->
            s.key.setText(""); s.send.performClick()
            assertTrue(s.pending.isEmpty()); assertTrue(s.reads.isEmpty()); assertTrue(s.requests.isEmpty())
            assertEquals("saved", JSONObject(s.record()).getString("state"))
        }
        Screen().use { s ->
            s.target.setSelection(1); s.send.performClick(); s.complete()
            assertEquals(PatchEngine.Target.PERSONAL, s.reads.single().second)
            val parts = s.requests.single().getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            val context = JSONObject(parts.getJSONObject(1).getString("text"))
            assertEquals("main", context.getString("branch")); assertFalse(context.getJSONArray("files").toString().contains(path))
        }
    }

    @Test fun configuredGithubCredentialsInTaskTextAreRejectedBeforeSavingOrReading() {
        Screen().use { s ->
            s.prompt.setText("Repair the voice task with $readToken"); s.send.performClick()
            assertTrue(s.pending.isEmpty()); assertTrue(s.reads.isEmpty()); assertTrue(s.requests.isEmpty()); assertEquals("", s.record())
        }
    }

    @Test fun malformedSelectedProjectCannotStartOrCrashARequest() {
        Screen().use { s ->
            s.selected = "../invalid-project"; s.send.performClick()
            assertTrue(s.pending.isEmpty()); assertTrue(s.requests.isEmpty()); assertTrue(s.reads.isEmpty())
            assertEquals(s.activity.getString(R.string.project_generation_save_failed), s.status.text.toString())
        }
    }
}
