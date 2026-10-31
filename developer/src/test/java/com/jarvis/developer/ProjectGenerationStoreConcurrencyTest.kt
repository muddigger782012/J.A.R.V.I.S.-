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

    @Test fun failedGenerationClearsPreviousAnswer() {
        val ticket = requireNotNull(store.begin("project1", "task", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(ticket, "generated", "answer"))
        val retry = requireNotNull(store.begin("project1", "retry", 0))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(retry, "generation_failed"))
        val prefs = context.getSharedPreferences("generation_concurrency_test", Context.MODE_PRIVATE)
        assertFalse(JSONObject(requireNotNull(prefs.getString("project_project1", null))).has("response"))
    }
}
