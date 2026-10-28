package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DevelopmentJobDraftWorkbenchTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = DevelopmentJobDrafts.Key("job_" + "a".repeat(64), 2, PatchEngine.Target.DEVELOPER, "b".repeat(40))
    private fun store() = DevelopmentJobDrafts(directory.root)
    private inner class Screen(val selected: DevelopmentJobDrafts.Key = key, preferCurrent: Boolean = false,
        workerFails: Boolean = false) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val parent = LinearLayout(activity)
        val goal = EditText(activity).apply { setText("Current goal") }
        val paths = EditText(activity).apply { setText("Current path") }
        val pending = mutableListOf<() -> Unit>()
        var current = true
        init {
            parent.addView(goal); parent.addView(paths); activity.setContentView(parent)
            DevelopmentJobDraftWorkbench.attach(activity, parent, store(), selected, runCatching { store().read(selected) },
                goal, paths, preferCurrent, { current }, { if (workerFails) error("private worker fixture") else pending.add(it) },
                { text, action -> Button(activity).apply { this.text = text; setOnClickListener { action() } } },
                { text -> TextView(activity).apply { this.text = text } })
        }
        fun button(id: Int) = DeveloperUiState.descendants(parent).filterIsInstance<Button>().single { it.text == activity.getString(id) }
        fun status() = DeveloperUiState.descendants(parent).filterIsInstance<TextView>().single { it.contentDescription == "Task draft status" }.text.toString()
        fun complete() { pending.removeAt(0).invoke(); shadowOf(Looper.getMainLooper()).idle() }
        override fun close() { controller.pause().stop().destroy() }
    }
    @Test fun aSavedDraftReturnsOnANewScreenAndForgettingKeepsCurrentEdits() {
        Screen().use { s ->
            s.goal.setText("My unfinished task"); s.paths.setText("My unfinished source selection\n")
            s.button(R.string.development_draft_save).performClick(); s.complete()
            assertEquals(s.activity.getString(R.string.development_draft_saved), s.status())
        }
        Screen().use { s ->
            assertEquals("My unfinished task", s.goal.text.toString())
            assertEquals("My unfinished source selection\n", s.paths.text.toString())
            assertEquals(s.activity.getString(R.string.development_draft_restored), s.status())
            s.button(R.string.development_draft_forget).performClick(); s.complete()
            assertEquals("My unfinished task", s.goal.text.toString()); assertNull(store().read(key).text)
        }
        Screen().use { s -> assertEquals("Current goal", s.goal.text.toString()) }
    }
    @Test fun savingAnEarlierSnapshotNeverReplacesTextTypedWhileItRuns() {
        Screen().use { s ->
            s.button(R.string.development_draft_save).performClick()
            s.button(R.string.development_draft_save).performClick(); assertEquals(1, s.pending.size)
            s.goal.setText("Newer owner edits"); s.complete()
            assertEquals("Newer owner edits", s.goal.text.toString())
            assertEquals("Current goal", store().read(key).text!!.goal)
            assertEquals(s.activity.getString(R.string.development_draft_changed_while_saving), s.status())
        }
    }
    @Test fun anOlderTaskStateIsExplainedWithoutApplyingItsText() {
        store().save(key, 0, DevelopmentJobDrafts.Text("Old task text", "Old paths"))
        Screen(key.copy(revision = 3)).use { s ->
            assertEquals("Current goal", s.goal.text.toString())
            assertEquals(s.activity.getString(R.string.development_draft_stale), s.status())
            s.button(R.string.development_draft_save).performClick(); s.complete()
            assertEquals("Current goal", store().read(s.selected).text!!.goal)
            assertNull(store().read(key).text)
        }
    }
    @Test fun concurrentDraftConflictsAreInlineAndCannotOverwriteTheNewerCopy() {
        Screen().use { first -> Screen().use { second ->
            first.goal.setText("First editor"); second.goal.setText("Second editor")
            first.button(R.string.development_draft_save).performClick()
            second.button(R.string.development_draft_save).performClick()
            first.complete(); second.complete()
            assertEquals("First editor", store().read(key).text!!.goal)
            assertEquals("Second editor", second.goal.text.toString())
            assertEquals(second.activity.getString(R.string.development_draft_save_failed), second.status())
            assertFalse(second.button(R.string.development_draft_save).isEnabled)
        } }
    }
    @Test fun storageOrWorkerFailuresAreInlineAndLeaveFieldsEditable() {
        java.io.File(directory.root, "development-job-drafts.bin").writeText("x".repeat(40))
        Screen().use { s ->
            s.goal.setText("I can still review this request")
            assertEquals(s.activity.getString(R.string.development_draft_unavailable), s.status())
            assertFalse(s.button(R.string.development_draft_save).isEnabled)
            assertEquals("I can still review this request", s.goal.text.toString())
        }
        assertTrue(java.io.File(directory.root, "development-job-drafts.bin").delete())
        Screen(workerFails = true).use { s ->
            s.button(R.string.development_draft_save).performClick()
            assertEquals(s.activity.getString(R.string.development_draft_save_failed), s.status())
            assertFalse(s.status().contains("private worker fixture"))
            assertEquals("Current goal", s.goal.text.toString())
            assertNull(store().read(key).text)
        }
    }
    @Test fun staleOrDestroyedCardsCannotReportAWriteAsCurrentSuccess() {
        val s = Screen()
        s.current = false; s.button(R.string.development_draft_save).performClick(); assertTrue(s.pending.isEmpty())
        s.current = true; s.button(R.string.development_draft_save).performClick()
        val before = s.status(); s.close(); s.complete()
        assertEquals(before, s.status()); assertNotNull(store().read(key).text)
        store().save(key, 1, DevelopmentJobDrafts.Text("Other saved copy", "Other saved paths"))
        Screen(preferCurrent = true).use { fresh -> assertEquals("Current goal", fresh.goal.text.toString()) }
    }
}
