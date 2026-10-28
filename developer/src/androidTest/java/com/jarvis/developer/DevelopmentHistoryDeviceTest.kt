package com.jarvis.developer

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android widgets/checkpoints; diagnostic fixtures invoke no remote source or provider operation. */
class DevelopmentHistoryDeviceTest {
    private fun fixture(paused: Boolean = false): Pair<File, List<String>> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir.canonicalFile, "history-ui-" + UUID.randomUUID())
        val queue = DevelopmentJobInbox(directory)
        val ids = (0 until 12).map { index ->
            val id = queue.handle("com.assistant.core", true,
                Wire.Request("device-history-$index", "diagnostics.review.request", "History device task $index")).getString("id")
            if (paused) queue.controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.PAUSE)
            id
        }
        return directory to ids
    }
    private fun await(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var passed = false; scenario.onActivity { passed = condition(it) }
            if (passed) return
            android.os.SystemClock.sleep(20)
        }
        fail("Native task history did not reach the expected state")
    }
    private fun ids(root: LinearLayout) = DeveloperUiState.descendants(root).mapNotNull {
        it.tag?.toString()?.takeIf { tag -> tag.startsWith("development-job-card:") }?.removePrefix("development-job-card:")
    }.toList()
    @Test fun nativePagingCanControlTheNamedOlderTaskWithoutChangingOtherJobs() {
        val (directory, jobs) = fixture()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var root: LinearLayout
                scenario.onActivity { activity ->
                    root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    activity.setContentView(ScrollView(activity).apply { addView(root) })
                    DevelopmentInboxWorkbench.attach(activity, root, LinearLayout(activity),
                        services = DevelopmentInboxWorkbench.Services(inbox = { DevelopmentJobInbox(directory) }))
                    DeveloperUiState.descendants(root).filterIsInstance<Button>().single { it.text.toString() == "Show all development reviews" }.performClick()
                }
                await(scenario) { ids(root) == jobs.asReversed().take(8) }
                scenario.onActivity {
                    DeveloperUiState.descendants(root).filterIsInstance<Button>().first { it.text.toString() == "Older jobs" }.performClick()
                }
                await(scenario) { ids(root) == jobs.take(4).asReversed() }
                scenario.onActivity {
                    val card = DeveloperUiState.descendants(root).single { it.tag == "development-job-card:" + jobs.first() }
                    assertTrue(DeveloperUiState.descendants(card).filterIsInstance<android.widget.TextView>()
                        .any { it.text.toString() == "History device task 0" })
                    DeveloperUiState.descendants(card).filterIsInstance<Button>().single { it.text.toString() == "Cancel task" }.performClick()
                }
                await(scenario) { DevelopmentJobInbox(directory).localSnapshot().single { it.getString("id") == jobs.first() }.getString("state") == "CANCELLED" }
                val states = DevelopmentJobInbox(directory).localSnapshot()
                assertTrue(states.filter { it.getString("id") != jobs.first() }.all { it.getString("state") == "QUEUED" })
                assertTrue(states.none { it.getBoolean("sourceVerified") || it.getBoolean("deviceVerified") })
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun nativeHistoryRestoresItsFilterAndBookmarkAcrossRecreation() {
        val (directory, jobs) = fixture(paused = true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var root: LinearLayout
                lateinit var handle: DevelopmentInboxWorkbench.Handle
                fun attach(state: Bundle?) = scenario.onActivity { activity ->
                    root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    activity.setContentView(ScrollView(activity).apply { addView(root) })
                    handle = DevelopmentInboxWorkbench.attach(activity, root, LinearLayout(activity), restoredState = state,
                        services = DevelopmentInboxWorkbench.Services(inbox = { DevelopmentJobInbox(directory) }))
                }
                attach(null)
                scenario.onActivity {
                    DeveloperUiState.descendants(root).filterIsInstance<Spinner>().single { it.contentDescription == "Development task filter" }
                        .setSelection(DevelopmentJobHistory.Filter.STOPPED.ordinal)
                }
                await(scenario) { ids(root) == jobs.asReversed().take(8) }
                scenario.onActivity { DeveloperUiState.descendants(root).filterIsInstance<Button>().first { it.text.toString() == "Older jobs" }.performClick() }
                await(scenario) { ids(root) == jobs.take(4).asReversed() }
                var state: Bundle? = null; scenario.onActivity { state = handle.saveState() }
                scenario.recreate(); attach(state)
                await(scenario) { ids(root) == jobs.take(4).asReversed() }
                scenario.onActivity { assertEquals(DevelopmentJobHistory.Filter.STOPPED.ordinal,
                    DeveloperUiState.descendants(root).filterIsInstance<Spinner>().single { it.contentDescription == "Development task filter" }.selectedItemPosition) }
            }
        } finally { directory.deleteRecursively() }
    }
}
