package com.jarvis.developer

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectGenerationStoreConcurrencyTest {
    private lateinit var store: ProjectGenerationStore
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = ProjectGenerationStore(preferences)
    }

    @Test fun projectListingExcludesUnrelatedPreferencesAndSortsWorkstreams() {
        assertTrue(store.saveLocal("zeta", "offline task", 0))
        assertTrue(store.saveLocal("alpha", "offline task", 1))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        prefs.edit().putString("provider_key", "not a project").putString("project_bad.name", "{}").commit()
        assertEquals(listOf("alpha", "zeta"), store.projectIds())
    }

    @Test fun priorProcessGeneratingRecordCannotBeOverwrittenBeforeRecovery() {
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        prefs.edit().putString("project_orphan", JSONObject()
            .put("id", "orphan").put("description", "original").put("target", 0)
            .put("state", "generating").put("generation_process", "previous-process")
            .put("generation_request", "old-ticket").toString()).commit()
        assertFalse(store.saveLocal("orphan", "replacement", 0))
        assertNull(store.begin("orphan", "replacement", 0))
        assertTrue(store.recoverInterrupted("orphan"))
        assertTrue(store.saveLocal("orphan", "replacement", 0))
    }

    @Test fun recoverAllHandlesMultipleProjectsAndLeavesActiveRequestAlone() {
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        requireNotNull(store.begin("active", "live task", 0))
        for (id in listOf("orphan1", "orphan2")) {
            prefs.edit().putString("project_$id",
                JSONObject().put("id", id).put("description", "old task").put("target", 0)
                    .put("state", "generating").put("generation_request", "old")
                    .put("generation_process", "previous-process").toString()).commit()
        }
        assertEquals(2, store.recoverAllInterrupted())
        assertEquals(0, store.recoverAllInterrupted())
        assertEquals("generating", JSONObject(requireNotNull(prefs.getString("project_active", null))).getString("state"))
    }

    @Test fun oldProcessGenerationCanBeRecoveredAndRetried() {
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        prefs.edit().putString("project_project1",
            JSONObject().put("id", "project1").put("description", "task")
                .put("target", 0).put("state", "generating")
                .put("generation_request", "old-ticket")
                .put("generation_process", "previous-process")
                .put("response", "obsolete").toString()).commit()
        assertTrue(store.recoverInterrupted("project1"))
        val recovered = JSONObject(requireNotNull(prefs.getString("project_project1", null)))
        assertEquals("generation_interrupted", recovered.getString("state"))
        assertFalse(recovered.has("generation_request"))
        assertFalse(recovered.has("response"))
        assertNotNull(store.begin("project1", "retry", 0))
    }

    @Test fun currentProcessRequestCannotBeRecoveredAsInterrupted() {
        requireNotNull(store.begin("project1", "task", 0))
        assertFalse(store.recoverInterrupted("project1"))
    }

    @Test fun oldProcessCannotCompleteItsGenerationTicket() {
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        val ticket = requireNotNull(store.begin("project1", "task", 0))
        val record = JSONObject(requireNotNull(prefs.getString("project_project1", null)))
        record.put("generation_process", "previous-process")
        prefs.edit().putString("project_project1", record.toString()).commit()
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(ticket, "generated", "old"))
        assertTrue(store.recoverInterrupted("project1"))
    }

    @Test fun oversizedProviderReplyFailsAndCanBeRetried() {
        val ticket = requireNotNull(store.begin("project1", "task", 0))
        assertEquals(ProjectGenerationStore.Result.FAILED,
            store.finish(ticket, "generated", "x".repeat(256_001)))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        val record = JSONObject(requireNotNull(prefs.getString("project_project1", null)))
        assertEquals("generation_failed", record.getString("state"))
        assertFalse(record.has("response"))
        assertNotNull(store.begin("project1", "retry", 0))
    }

    @Test fun secondRequestForSameProjectIsRejected() {
        val first = requireNotNull(store.begin("project1", "first task", 0))
        assertNull(store.begin("project1", "second task", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(first, "generated", "answer"))
    }

    @Test fun differentProjectsCanGenerateConcurrently() {
        val first = requireNotNull(store.begin("project1", "first task", 0))
        val second = requireNotNull(store.begin("project2", "second task", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(second, "generated", "second answer"))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(first, "generated", "first answer"))
    }

    @Test fun localSaveCannotReplaceActiveGeneration() {
        val ticket = requireNotNull(store.begin("project1", "first task", 0))
        assertFalse(store.saveLocal("project1", "new task", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(ticket, "generated", "answer"))
    }

    @Test fun editingProjectClearsPreviousAnswer() {
        assertTrue(store.saveLocal("project1", "initial", 0))
        val ticket = requireNotNull(store.begin("project1", "initial", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(ticket, "generated", "old answer"))
        assertTrue(store.saveLocal("project1", "revised", 0))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        val record = JSONObject(requireNotNull(prefs.getString("project_project1", null)))
        assertFalse(record.has("response"))
        assertFalse(record.has("response_description"))
    }

    @Test fun retryImmediatelyClearsPreviousAnswer() {
        val first = requireNotNull(store.begin("project1", "first", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(first, "generated", "old"))
        requireNotNull(store.begin("project1", "second", 0))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        val record = JSONObject(requireNotNull(prefs.getString("project_project1", null)))
        assertEquals("generating", record.getString("state"))
        assertFalse(record.has("response"))
        assertFalse(record.has("response_description"))
    }

    @Test fun staleCompletionCannotOverwriteNewGeneration() {
        val first = requireNotNull(store.begin("project1", "first", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(first, "generation_cancelled"))
        val second = requireNotNull(store.begin("project1", "second", 0))
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(first, "generated", "stale"))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(second, "generated", "fresh"))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        assertEquals("fresh", JSONObject(requireNotNull(prefs.getString("project_project1", null))).getString("response"))
    }

    @Test fun failedGenerationClearsPreviousAnswer() {
        val ticket = requireNotNull(store.begin("project1", "task", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(ticket, "generated", "answer"))
        val retry = requireNotNull(store.begin("project1", "retry", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(retry, "generation_failed"))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        assertFalse(JSONObject(requireNotNull(prefs.getString("project_project1", null))).has("response"))
    }
}
