package com.jarvis.developer

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.jarvis.integration.DevelopmentBridgeContract as Wire
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
class DevelopmentHistoryUiTest {
    @get:Rule val directory = TemporaryFolder()
    private fun seed(count: Int, paused: Int = 0): List<String> = (0 until count).map { index ->
        val queue = DevelopmentJobInbox(directory.root)
        val id = queue.handle("com.assistant.core", true,
            Wire.Request("history-$index", "diagnostics.review.request", "Inspect task $index")).getString("id")
        if (index < paused) queue.controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.PAUSE)
        id
    }
    private inner class Screen(state: Bundle? = null) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(this@Screen.parent) }
        val pending = mutableListOf<() -> Unit>()
        var clearedFocus = 0
        val handle = DevelopmentInboxWorkbench.attach(activity, parent, LinearLayout(activity),
            restoredState = state, onBrowseAll = { clearedFocus++ },
            services = DevelopmentInboxWorkbench.Services(inbox = { DevelopmentJobInbox(directory.root) }, worker = { pending.add(it) }))
        init { activity.setContentView(scroll); idle() }
        fun views() = DeveloperUiState.descendants(parent).toList()
        fun cards() = views().filter { it.tag?.toString()?.startsWith("development-job-card:") == true }
        fun ids() = cards().map { it.tag.toString().removePrefix("development-job-card:") }
        fun button(label: String) = views().filterIsInstance<Button>().first { it.text.toString() == label }
        fun filter() = views().filterIsInstance<Spinner>().single { it.contentDescription == "Development task filter" }
        fun idle() {
            shadowOf(Looper.getMainLooper()).idle()
            scroll.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 360, 640); scroll.viewTreeObserver.dispatchOnGlobalLayout()
        }
        fun complete(index: Int = 0) { pending.removeAt(index).invoke(); idle() }
        override fun close() { controller.pause().stop().destroy() }
    }
    @Test fun showAllCanBrowseEveryOlderTaskWithItsRealName() {
        val ids = seed(17)
        Screen().use { s ->
            s.button("Show all development reviews").performClick(); s.complete()
            assertEquals(ids.asReversed().take(8), s.ids())
            assertTrue(s.views().filterIsInstance<TextView>().any { it.text.toString() == "Inspect task 16" })
            s.button("Older jobs").performClick(); s.complete()
            assertEquals(ids.asReversed().drop(8).take(8), s.ids())
            s.button("Older jobs").performClick(); s.complete()
            assertEquals(listOf(ids.first()), s.ids())
            assertFalse(s.button("Older jobs").isEnabled)
            s.button("Newer jobs").performClick(); s.complete()
            assertEquals(ids.asReversed().drop(8).take(8), s.ids())
        }
    }
    @Test fun aLateUnfilteredReadCannotOverwriteTheSelectedFilter() {
        val ids = seed(12, 4)
        Screen().use { s ->
            s.button("Show all development reviews").performClick()
            s.filter().setSelection(DevelopmentJobHistory.Filter.STOPPED.ordinal); s.idle()
            assertEquals(2, s.pending.size)
            s.complete(1)
            assertEquals(ids.take(4).asReversed(), s.ids())
            s.complete(0)
            assertEquals(ids.take(4).asReversed(), s.ids())
        }
    }
    @Test fun focusedReviewsRemainExactAndShowAllExplicitlyExitsFocus() {
        val ids = seed(12, 4)
        Screen().use { s ->
            s.filter().setSelection(DevelopmentJobHistory.Filter.STOPPED.ordinal); s.idle(); s.complete()
            s.handle.focus(ids.last()); s.complete()
            assertEquals(listOf(ids.last()), s.ids()); assertFalse(s.filter().isEnabled)
            s.button("Show all development reviews").performClick(); s.idle(); s.complete()
            assertEquals(1, s.clearedFocus); assertTrue(s.filter().isEnabled)
            assertEquals(ids.asReversed().take(8), s.ids())
        }
    }
    @Test fun anUnavailableFocusedReviewNeverShowsAnotherTasksControls() {
        seed(2)
        Screen().use { s ->
            s.handle.focus("job_" + "f".repeat(64)); s.complete()
            assertTrue(s.cards().isEmpty())
            assertTrue(s.views().filterIsInstance<TextView>().any { it.text.toString().contains("requested review is unavailable") })
            assertTrue(s.views().filterIsInstance<Button>().none { it.text.toString() == "Cancel task" })
        }
    }
    @Test fun aSerializedHistoryBookmarkRestoresItsFilterAndPageWithoutApprovalData() {
        val ids = seed(17, 17)
        val state = Screen().use { s ->
            s.filter().setSelection(DevelopmentJobHistory.Filter.STOPPED.ordinal); s.idle(); s.complete()
            s.button("Older jobs").performClick(); s.complete()
            assertEquals(ids.asReversed().drop(8).take(8), s.ids())
            s.scroll.scrollTo(0, 80); assertEquals(80, s.scroll.scrollY)
            s.handle.saveState()
        }
        assertEquals(setOf("filter", "offset", "anchor", "requested", "scrollY"), state.keySet())
        val parcel = android.os.Parcel.obtain()
        val restored = try {
            parcel.writeBundle(state); parcel.setDataPosition(0); requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
        Screen(restored).use { s ->
            assertTrue(s.pending.isNotEmpty()); s.complete()
            assertEquals(DevelopmentJobHistory.Filter.STOPPED.ordinal, s.filter().selectedItemPosition)
            assertEquals(ids.asReversed().drop(8).take(8), s.ids())
            assertEquals(80, s.scroll.scrollY)
        }
    }
    @Test fun malformedBookmarksAreClampedAndCannotCarryAnAuthorityToken() {
        val state = DevelopmentJobHistory.restore(Bundle().apply {
            putString("filter", "not-a-filter"); putInt("offset", -1); putString("anchor", "https://example.invalid/token")
            putString("approval", "must-not-be-restored")
        })
        assertEquals(DevelopmentJobHistory.Filter.ALL, state.filter)
        assertEquals(0, state.offset); assertNull(state.anchor)
        assertFalse(state.save().containsKey("approval"))
    }
    @Test fun theMainActivityRestoresBrowsingInsteadOfReopeningAnExitedFocusedReview() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val storage = java.io.File(app.noBackupFilesDir.canonicalFile, "development-inbox")
        val queue = DevelopmentJobInbox(storage)
        val ids = (0 until 12).map { index ->
            val id = queue.handle("com.assistant.core", true,
                Wire.Request("main-history-$index", "diagnostics.review.request", "Main history task $index")).getString("id")
            if (index < 4) queue.controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.PAUSE)
            id
        }
        val saved = Bundle().apply {
            putInt("developer_active_page", 2); putString("developer_artifact_review_job", ids.first())
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            fun views() = DeveloperUiState.descendants(controller.get().window.decorView).toList()
            fun awaitCards(expected: Set<String>) {
                val deadline = System.nanoTime() + 8_000_000_000L
                while (System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    val shown = views().mapNotNull { it.tag?.toString()?.takeIf { tag -> tag.startsWith("development-job-card:") }
                        ?.removePrefix("development-job-card:") }.toSet()
                    if (shown == expected) return
                    Thread.sleep(10)
                }
                fail("Main Activity did not restore the selected history context")
            }
            awaitCards(setOf(ids.first()))
            views().filterIsInstance<Button>().single { it.text.toString() == "Show all development reviews" }.performClick()
            awaitCards(ids.asReversed().take(8).toSet())
            views().filterIsInstance<Spinner>().single { it.contentDescription == "Development task filter" }
                .setSelection(DevelopmentJobHistory.Filter.STOPPED.ordinal)
            awaitCards(ids.take(4).toSet())
            val checkpoint = Bundle(); controller.saveInstanceState(checkpoint)
            assertFalse(checkpoint.containsKey("developer_artifact_review_job"))
            assertNotNull(checkpoint.getBundle("developer_job_history"))
            controller.recreate(); awaitCards(ids.take(4).toSet())
            assertEquals(DevelopmentJobHistory.Filter.STOPPED.ordinal,
                views().filterIsInstance<Spinner>().single { it.contentDescription == "Development task filter" }.selectedItemPosition)
        } finally { controller.pause().stop().destroy(); storage.deleteRecursively() }
    }
}
