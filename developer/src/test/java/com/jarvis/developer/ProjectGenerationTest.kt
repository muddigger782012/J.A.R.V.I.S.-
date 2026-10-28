package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectGenerationTest {
    private fun preferences() = org.robolectric.RuntimeEnvironment.getApplication()
        .getSharedPreferences("generation-fixture-" + UUID.randomUUID(), Context.MODE_PRIVATE)
    private inner class Screen(val prefs: android.content.SharedPreferences = preferences(),
        launchFails: Boolean = false, failure: Exception? = null,
        ask: ((String, String, String, String, PatchPreviewCancellation) -> String)? = null) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val send = Button(activity); val cancel = Button(activity)
        val provider = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Gemini", "OpenAI")) }
        val target = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Developer", "Personal")) }
        val key = EditText(activity).apply { setText("runtime-provider-key-42") }
        val model = EditText(activity).apply { setText("gemini-2.5-pro") }
        val prompt = EditText(activity).apply { setText("First request") }
        val output = TextView(activity).apply { text = "Previous display" }
        val status = TextView(activity)
        val pending = mutableListOf<() -> Unit>()
        var selected: String? = "project-a"
        val store = ProjectGenerationStore(prefs)
        val handle = ProjectGenerationWorkbench.attach(activity, send, cancel, provider, key, model, prompt, output, status,
            { target.selectedItemPosition }, { target }, { selected }, { selected = it }, store, {},
            ProjectGenerationWorkbench.Services(worker = { if (launchFails) error("private launch fixture") else pending.add(it) },
                ask = ask ?: { _, _, _, task, _ -> if (failure != null) throw failure else "Reply for $task" }))
        fun complete() { pending.removeAt(0).invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun record() = JSONObject(prefs.getString("project_project-a", "{}")!!)
        override fun close() { handle.dispose(); controller.pause().stop().destroy() }
    }
    @Test fun cancelledAndSupersededTicketsCannotOverwriteANewerProject() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        val first = store.begin("project-a", "Earlier instructions", 0)!!
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(first, "generation_cancelled"))
        val next = ProjectGenerationStore(prefs).begin("project-a", "New instructions", 1)!!
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(first, "generated", "Late response"))
        var record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        assertEquals("New instructions", record.getString("description")); assertEquals(1, record.getInt("target"))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(next, "generated", "Current response"))
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(next, "generation_failed"))
        record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        assertEquals("Current response", record.getString("response")); assertFalse(record.has("generation_request"))
        assertEquals(true, ProjectGenerationStore.replyMatches(record))
        assertTrue(store.saveLocal("project-a", "Different instructions", 0))
        assertEquals(false, ProjectGenerationStore.replyMatches(JSONObject(prefs.getString("project_project-a", "{}")!!)))
    }
    @Test fun corruptLegacyRecordsArePreservedWithoutStartingGeneration() {
        val prefs = preferences(); prefs.edit().putString("project_project-a", "invalid-json-private-fixture").commit()
        val store = ProjectGenerationStore(prefs)
        assertNull(store.begin("project-a", "New task", 0)); assertFalse(store.saveLocal("project-a", "New task", 0))
        assertEquals("invalid-json-private-fixture", prefs.getString("project_project-a", ""))
    }
    @Test fun offlineSavingInvalidatesPendingRepliesAndPreservesLegacyResponses() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        prefs.edit().putString("project_project-a", JSONObject().put("id", "project-a").put("description", "Old task")
            .put("target", 0).put("response", "Previous saved reply").put("legacy_note", "Keep this metadata").toString()).commit()
        val pending = store.begin("project-a", "Pending task", 0)!!
        assertTrue(store.saveLocal("project-a", "Offline revision", 1))
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(pending, "generated", "Do not apply"))
        val record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        assertEquals("Previous saved reply", record.getString("response")); assertEquals("Keep this metadata", record.getString("legacy_note"))
        assertEquals("saved", record.getString("state")); assertEquals(1, record.getInt("target"))
        assertNull(ProjectGenerationStore.replyMatches(record))
    }
    @Test fun cancellationAndRetryRejectLateCallbacksAndKeepTheNewInputsBusy() {
        Screen().use { s ->
            s.send.performClick(); s.send.performClick(); assertEquals(1, s.pending.size)
            assertFalse(s.prompt.isEnabled); assertFalse(s.target.isEnabled); assertTrue(s.cancel.isEnabled)
            s.cancel.performClick(); assertTrue(s.prompt.isEnabled); assertTrue(s.target.isEnabled)
            s.prompt.setText("Second request"); s.send.performClick(); assertEquals(2, s.pending.size)
            s.complete(); assertFalse(s.send.isEnabled); assertTrue(s.cancel.isEnabled)
            assertEquals("Previous display", s.output.text.toString()); assertEquals("generating", s.record().getString("state"))
            s.complete(); assertTrue(s.send.isEnabled); assertFalse(s.cancel.isEnabled)
            assertEquals("Reply for Second request", s.output.text.toString()); assertEquals("generated", s.record().getString("state"))
        }
    }
    @Test fun cancellingAnActiveProviderSignalsCleanupWithoutWaitingForItsReturn() {
        val started = CountDownLatch(1); val stopped = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            Screen(ask = { _, _, _, _, cancellation ->
                cancellation.registerStop { stopped.countDown() }.use { started.countDown(); release.await(5, TimeUnit.SECONDS); "Late reply" }
            }).use { s ->
                s.send.performClick(); val job = pool.submit { s.pending.removeAt(0).invoke() }
                assertTrue(started.await(5, TimeUnit.SECONDS)); s.cancel.performClick()
                assertTrue(s.send.isEnabled); assertTrue(stopped.await(5, TimeUnit.SECONDS))
                release.countDown(); job.get(5, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
                assertEquals("generation_cancelled", s.record().getString("state")); assertEquals("Previous display", s.output.text.toString())
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun disposalRecordsInterruptionAndDoesNotAutomaticallyRestartTheRequest() {
        val s = Screen(); s.send.performClick(); val prefs = s.prefs; s.close(); s.complete()
        assertEquals("generation_interrupted", s.record().getString("state"))
        Screen(prefs).use { reopened ->
            assertTrue(reopened.pending.isEmpty()); assertTrue(reopened.send.isEnabled)
            assertEquals("First request", reopened.record().getString("description"))
        }
    }
    @Test fun launchAndProviderFailuresLeaveSavedInstructionsAndRetryControlsAvailable() {
        Screen(launchFails = true).use { s ->
            s.send.performClick(); assertTrue(s.send.isEnabled); assertFalse(s.cancel.isEnabled)
            assertEquals("generation_failed", s.record().getString("state"))
            assertEquals(s.activity.getString(R.string.project_generation_start_failed), s.status.text.toString())
        }
        Screen(failure = IllegalStateException("runtime-provider-key-42 private body")).use { s ->
            s.send.performClick(); s.complete(); assertTrue(s.send.isEnabled)
            assertFalse(s.output.text.contains("runtime-provider-key-42")); assertFalse(s.output.text.contains("private body"))
            assertEquals("First request", s.record().getString("description"))
            assertEquals("gemini-2.5-pro", s.model.text.toString())
        }
    }
    @Test fun configuredCredentialsInInstructionsAreRejectedBeforePersistenceOrSending() {
        Screen().use { s ->
            s.prompt.setText("My key is runtime-provider-key-42")
            s.send.performClick(); assertTrue(s.prefs.all.isEmpty()); assertTrue(s.pending.isEmpty())
            assertEquals(s.activity.getString(R.string.project_generation_remove_key), s.status.text.toString())
        }
    }
    @Test fun anotherEditorCanSupersedeARequestWithoutReceivingItsOldReply() {
        Screen().use { s ->
            s.send.performClick()
            val newer = ProjectGenerationStore(s.prefs).begin("project-a", "Other window instructions", 1)!!
            s.complete(); assertEquals("Previous display", s.output.text.toString())
            assertEquals(s.activity.getString(R.string.project_generation_changed), s.status.text.toString())
            assertEquals("Other window instructions", s.record().getString("description"))
            assertEquals(newer.request, s.record().getString("generation_request"))
        }
    }
    @Test fun projectStatesNeverGrantBuildReleaseOrSourceApproval() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        val ticket = store.begin("project-a", "Inspect this code", 0)!!
        assertThrows(IllegalArgumentException::class.java) { store.finish(ticket, "published", "Unverified text") }
        val record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        record.put("generation_process", "earlier-process")
        assertEquals("Interrupted; retry when ready", ProjectGenerationStore.stateLabel(record))
        assertFalse(record.has("approval")); assertFalse(record.has("build")); assertFalse(record.has("signature"))
        for (name in listOf("ProjectGenerationStore", "ProjectGenerationWorkbench"))
            assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/$name.kt"))
    }
}
