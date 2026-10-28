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
    private fun evidence(task: String, target: PatchEngine.Target): RepositoryCodingContext.Context {
        val source = "class VoiceNetwork { fun voiceLatency() = 5 }\n// $readToken $secret"
        val bytes = source.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()) }
        val blob = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val actualPath = if (target == PatchEngine.Target.PERSONAL) path.replace("developer/src", "app/src")
            .replace("com/jarvis/developer", "com/assistant/core") else path
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(actualPath, blob, bytes.size.toLong())), false, commit)
        val plan = RepositoryAnalysis.analyze(task, inventory) { source }
        return RepositoryCodingContext.fromAnalysis(task, target, inventory, plan, mapOf(actualPath to source), listOf(secret, readToken))
    }
    private inner class Screen : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val send = Button(activity); val cancel = Button(activity)
        val provider = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Gemini", "OpenAI")) }
        val target = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Developer", "Personal")) }
        val key = EditText(activity).apply { setText(secret) }
        val model = EditText(activity).apply { setText("gemini-2.5-pro") }
        val prompt = EditText(activity).apply { setText("Repair voice network latency") }
        val output = TextView(activity); val status = TextView(activity)
        val prefs = activity.getSharedPreferences("context-fixture-" + java.util.UUID.randomUUID(), Context.MODE_PRIVATE)
        val pending = mutableListOf<() -> Unit>()
        val requests = mutableListOf<JSONObject>()
        val reads = mutableListOf<Pair<String, PatchEngine.Target>>()
        var reader: (String, PatchEngine.Target) -> RepositoryCodingContext.Context = ::evidence
        val handle = ProjectGenerationWorkbench.attach(activity, send, cancel, provider, key, model, prompt, output, status,
            { target.selectedItemPosition }, { target }, { "context-project" }, {}, ProjectGenerationStore(prefs), {},
            ProjectGenerationWorkbench.Services(worker = { pending.add(it) }, context = { task, product, token, apiKey, stop ->
                assertEquals(readToken, token); assertEquals(secret, apiKey); stop.check()
                reads.add(task to product); reader(task, product)
            }, ask = { provider, apiKey, selectedModel, task, stop, context ->
                CodingAssistantProvider.Client(CodingAssistantProvider.PostTransport { request ->
                    requests.add(JSONObject(String(request.body, Charsets.UTF_8)))
                    val response = if (provider == "Gemini") JSONObject().put("candidates", JSONArray().put(JSONObject()
                        .put("finishReason", "STOP").put("content", JSONObject().put("role", "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", "Use the voice evidence."))))))
                    else JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                        .put("type", "message").put("role", "assistant").put("status", "completed")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Use the voice evidence.")))))
                    val bytes = response.toString().toByteArray()
                    CodingAssistantProvider.PostResponse(200, bytes.size.toLong(), request.url, ByteArrayInputStream(bytes))
                }, cancellation = stop).ask(provider, apiKey, selectedModel, task, context = context)
            }), githubToken = { readToken })
        fun complete() { pending.removeAt(0).invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun record() = prefs.getString("project_context-project", "")!!
        override fun close() { handle.dispose(); controller.pause().stop().destroy() }
    }

    @Test fun homeSendsActualSourceEvidenceWithManualModelsAndSavesOnlyReplyProvenance() {
        for (provider in listOf("Gemini", "OpenAI")) Screen().use { s ->
            if (provider == "OpenAI") { s.provider.setSelection(1); s.model.setText("gpt-4.1-mini") }
            s.send.performClick(); assertTrue(s.requests.isEmpty()); s.complete()
            val request = s.requests.single().toString()
            assertTrue(request.contains(commit)); assertTrue(request.contains(path)); assertTrue(request.contains("voiceLatency"))
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
            assertTrue(sent.contains("unavailable")); assertFalse(sent.contains("verified_excerpts"))
            assertTrue(s.output.text.toString().startsWith("Repository context unavailable."))
            assertFalse(sent.contains("reflected")); assertFalse(s.record().contains(secret)); assertFalse(s.record().contains(readToken))
        }
    }

    @Test fun cancellingDuringSourceCollectionNeverStartsTheProviderOrStoresTheContext() {
        Screen().use { s ->
            s.reader = { task, target -> s.cancel.performClick(); evidence(task, target) }
            s.send.performClick(); s.complete()
            assertTrue(s.requests.isEmpty()); assertEquals("generation_cancelled", JSONObject(s.record()).getString("state"))
            assertTrue(s.send.isEnabled); assertFalse(s.record().contains(commit)); assertFalse(s.record().contains("response"))
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
}
