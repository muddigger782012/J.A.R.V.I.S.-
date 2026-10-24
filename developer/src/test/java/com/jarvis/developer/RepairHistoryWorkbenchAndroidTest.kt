package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.util.ArrayDeque
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
@Config(sdk = [28], manifest = Config.NONE)
class RepairHistoryWorkbenchAndroidTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = PatchEngine.Target.PERSONAL
    private val proposal = "b".repeat(64)
    private val entry = RepairHistoryStore.Bookmark(target, PatchEngine.taskBranch("sample", proposal, target), "d".repeat(40))
    private fun commit() = PatchPublisher.CommitResult("sample", "OpenAI", "manual-model", proposal,
        listOf("app/src/main/java/com/assistant/core/services/PersonalityExample.kt"), "a".repeat(40),
        entry.commitSha, entry.branch, "private source diff that must never be persisted", "main", target)

    private inner class Harness(val directory: File) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity)
        val token = EditText(activity).apply { setText("simulated-ephemeral-token") }
        val pending = ArrayDeque<() -> Unit>()
        var current: PatchPublisher.CommitResult? = null
        var reads = 0
        var starts = 0
        var finishes = 0
        var permitted = true
        var mismatch = 0
        init {
            activity.setContentView(root)
            RepairHistoryWorkbench.attach(activity, root, token, { current },
                { if (permitted) { starts++; true } else false }, { finishes++ },
                RepairHistoryWorkbench.Services(directory = { directory }, feedback = { authentication, selected ->
                    reads++
                    assertEquals("simulated-ephemeral-token", authentication)
                    assertEquals(entry, selected)
                    if (mismatch == 4) error("private token and transport content must not be shown")
                    BuildDiagnostics.parse(if (mismatch == 1) "e".repeat(40) else selected.commitSha,
                        if (mismatch == 2) PatchEngine.taskBranch("other", proposal, target) else selected.branch,
                        23, 1, "completed", "failure", "", if (mismatch == 3) PatchEngine.Target.DEVELOPER else target)
                }, worker = { pending.add(it) }))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun button(prefix: String) = views(root).filterIsInstance<Button>().single { it.text.startsWith(prefix) }
        fun text() = views(root).filterIsInstance<TextView>().last().text.toString()
        val choice get() = views(root).filterIsInstance<Spinner>().single()
        fun finish() { pending.removeFirst().invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun loadAndSelect() {
            button("Load private").performClick(); finish()
            choice.setSelection(1); shadowOf(Looper.getMainLooper()).idle()
        }
        fun close() { if (!activity.isDestroyed) controller.pause().stop().destroy() }
    }

    @Test fun attachmentAndLocalHistoryNeverReadBuildsAndRequireExplicitChoiceAfterRestart() {
        val directory = File(temporary.newFolder(), "history")
        val first = Harness(directory)
        try {
            assertFalse(directory.exists()); assertTrue(first.pending.isEmpty())
            first.button("Save current").performClick()
            assertTrue(first.pending.isEmpty()); assertFalse(directory.exists())
            first.current = commit()
            first.button("Save current").performClick(); first.finish()
            assertEquals(listOf(entry), RepairHistoryStore(directory).load())
            assertEquals(0, first.reads)
            assertFalse(File(directory, "repair-history.bin").readText(Charsets.ISO_8859_1).contains("private source diff"))
        } finally { first.close() }
        val reopened = Harness(directory)
        try {
            assertNull(reopened.current); assertTrue(reopened.pending.isEmpty()); assertEquals(0, reopened.reads)
            reopened.button("Load private").performClick(); reopened.finish()
            assertFalse(reopened.button("Recheck selected").isEnabled)
            assertEquals(0, reopened.reads)
            reopened.choice.setSelection(1); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(reopened.button("Recheck selected").isEnabled); assertEquals(0, reopened.reads)
            reopened.button("Recheck selected").performClick(); reopened.finish()
            assertEquals(1, reopened.reads); assertNull(reopened.current)
            assertTrue(reopened.text().contains("does not verify a prior approval"))
            reopened.button("Save current").performClick()
            assertTrue(reopened.pending.isEmpty())
            assertEquals(reopened.starts, reopened.finishes)
        } finally { reopened.close() }
    }

    @Test fun mismatchedReportsAndTransportFailuresAreNeverRenderedOrTurnedIntoRepairs() {
        for (mismatch in 1..4) {
            val directory = temporary.newFolder()
            RepairHistoryStore(directory).save(entry)
            val h = Harness(directory)
            try {
                h.loadAndSelect(); h.mismatch = mismatch
                h.button("Recheck selected").performClick(); h.finish()
                assertTrue(h.text().contains("could not be verified"))
                assertFalse(h.text().contains("private token"))
                assertFalse(h.text().contains("Build URL:"))
                assertNull(h.current); assertEquals(1, h.reads)
            } finally { h.close() }
        }
    }

    @Test fun activeOperationIsSerializedAndDestroyedActivityDiscardsItsCallback() {
        val directory = temporary.newFolder()
        RepairHistoryStore(directory).save(entry)
        val h = Harness(directory)
        h.loadAndSelect()
        h.permitted = false
        h.button("Recheck selected").performClick(); assertTrue(h.pending.isEmpty())
        assertTrue(h.text().contains("Another coding operation is active"))
        h.permitted = true
        h.button("Recheck selected").performClick()
        assertFalse(h.button("Recheck selected").isEnabled)
        h.button("Load private").performClick(); assertEquals(1, h.pending.size)
        h.close(); val before = h.text(); h.finish()
        assertEquals(before, h.text()); assertEquals(1, h.finishes)
    }

    @Test fun damagedHistoryRequiresExplicitClearWithoutAutomaticReadsOrStaleSelection() {
        val directory = temporary.newFolder()
        File(directory, "repair-history.bin").writeText("damaged")
        val h = Harness(directory)
        try {
            h.button("Load private").performClick(); h.finish()
            assertTrue(h.text().contains("could not be verified"))
            assertFalse(h.button("Recheck selected").isEnabled)
            h.button("Clear private").performClick(); h.finish()
            assertTrue(RepairHistoryStore(directory).load().isEmpty())
            assertFalse(h.button("Recheck selected").isEnabled)
            assertEquals(0, h.reads)
        } finally { h.close() }
    }

    @Test fun selectionChangedDuringAQueuedReadDiscardsItsBuildResponse() {
        val directory = temporary.newFolder()
        RepairHistoryStore(directory).save(entry)
        val h = Harness(directory)
        try {
            h.loadAndSelect()
            h.button("Recheck selected").performClick()
            // Programmatic callbacks can still occur while a spinner is disabled.
            h.choice.setSelection(0); shadowOf(Looper.getMainLooper()).idle()
            h.finish()
            assertTrue(h.text().contains("old build response was discarded"))
            assertFalse(h.text().contains("Build URL:"))
            assertFalse(h.button("Recheck selected").isEnabled)
            assertEquals(1, h.reads)
        } finally { h.close() }
    }
}
