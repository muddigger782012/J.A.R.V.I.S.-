package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual widget/listener integration with modeled GET responses; no live credentials or writer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PersonalRepairWorkbenchAndroidTest {
    private val path = "app/src/main/java/com/assistant/core/services/AssistantPersonality.kt"
    private val source = "package com.assistant.core.services\nobject AssistantPersonality\n"
    private val sha = "a".repeat(40)
    private fun shared() = "Review a static diagnostic for muddigger782012/J.A.R.V.I.S.-.\n" +
        "Review signal: Outstanding review marker.\nSource reference: $path:2 (source).\n" +
        "Claimed revision: $sha. Source inventory: ${"b".repeat(64)}.\n"
    private inner class Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity)
        val draft = EditText(activity).apply { setText(shared()) }
        val output = TextView(activity)
        val status = TextView(activity)
        val tasks = ArrayDeque<() -> Unit>()
        var reads = 0
        var staged: PersonalRepair.Review? = null
        init {
            activity.setContentView(root)
            val patches = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { true }, { staged = it; true })
            val services = PersonalRepairWorkbench.Services(read = { _, parsed, branch, tests ->
                reads++
                val bytes = source.toByteArray(); val hash = MessageDigest.getInstance("SHA-1")
                hash.update("blob ${bytes.size}\u0000".toByteArray())
                val blob = hash.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                PersonalRepair.prepare(parsed, branch, RepositoryAnalysis.Inventory(listOf(
                    RepositoryAnalysis.SourceFile(path, blob, bytes.size.toLong())), false, sha), source, tests)
            }, worker = { tasks.add(it) })
            PersonalRepairWorkbench.attach(activity, root, draft, EditText(activity), output, status, patches, services)
        }
        private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun button(prefix: String) = views(root).filterIsInstance<Button>().single { it.text.startsWith(prefix) }
        fun field(hint: String) = views(root).filterIsInstance<EditText>().single { it.hint.toString() == hint }
        fun finish() { tasks.removeFirst().invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun close() { if (!activity.isDestroyed) controller.pause().stop().destroy() }
    }

    @Test fun receivingDraftNeverReadsOrApprovesAndStagingRequiresAnExplicitVerifiedReview() {
        val h = Harness()
        try {
            assertEquals(0, h.reads); assertTrue(h.tasks.isEmpty()); assertNull(h.staged)
            assertFalse(h.button("Stage Personal").isEnabled)
            h.button("Read Personal").performClick()
            assertEquals(0, h.reads); assertEquals(1, h.tasks.size)
            h.finish()
            assertTrue(h.button("Stage Personal").isEnabled); assertNull(h.staged)
            h.button("Stage Personal").performClick()
            assertEquals(sha, h.staged!!.sourceSha)
            assertEquals(listOf(path), h.staged!!.scope)
            assertTrue(h.status.text.contains("approve its exact diff"))
        } finally { h.close() }
    }

    @Test fun changedInputAndDestroyedActivityDiscardOutstandingReads() {
        val h = Harness()
        try {
            h.button("Read Personal").performClick()
            h.field("Personal source branch").setText("feature/self-diagnostics-phase5-readonly")
            h.finish()
            assertFalse(h.button("Stage Personal").isEnabled)
            assertTrue(h.status.text.contains("stale")); assertNull(h.staged)
            h.button("Read Personal").performClick()
            h.close(); h.finish()
            assertNull(h.staged)
        } finally { h.close() }
    }

    @Test fun invalidShareAndProtectedTestsFailBeforeAnyReaderOrProviderRequest() {
        val h = Harness()
        try {
            h.draft.setText(shared().replace("AssistantPersonality.kt", "PermissionManager.kt"))
            h.button("Read Personal").performClick()
            assertTrue(h.tasks.isEmpty()); assertEquals(0, h.reads)
            h.draft.setText(shared())
            h.field("Optional new Personal test paths, one per line (maximum three)").setText("developer/src/test/java/com/jarvis/developer/ExampleTest.kt")
            h.button("Read Personal").performClick()
            assertTrue(h.tasks.isEmpty()); assertEquals(0, h.reads); assertNull(h.staged)
        } finally { h.close() }
    }

    @Test fun discardAndRecreationRequireFreshSourceReview() {
        val h = Harness()
        try {
            h.button("Read Personal").performClick(); h.finish()
            h.button("Discard Personal").performClick()
            assertFalse(h.button("Stage Personal").isEnabled)
            assertNull(h.staged)
        } finally { h.close() }
        val fresh = Harness()
        try { assertFalse(fresh.button("Stage Personal").isEnabled); assertEquals(0, fresh.reads) }
        finally { fresh.close() }
    }
}
