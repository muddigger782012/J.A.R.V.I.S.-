package com.jarvis.developer

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
    private val draftPath = "developer/src/main/java/com/jarvis/developer/ConversationTone.kt"
    private fun analysisFixture(): Pair<File, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir.canonicalFile, "draft-ui-" + UUID.randomUUID())
        val queue = DevelopmentJobInbox(directory)
        val request = Wire.Request(UUID.randomUUID().toString(), "source.developer.analysis.request", "Draft device fixture")
        val id = queue.handle("com.assistant.core", true, request).getString("id")
        val source = RepositoryAnalysis.SourceFile(draftPath, "a".repeat(40), 20)
        val file = RepositoryAnalysis.PlannedFile(source, listOf("Task reference"), "Inspect logic", "Review required", emptyList(), emptyList(), emptyList())
        queue.finishAnalysis(queue.claimAnalysis(id, true)!!,
            RepositoryAnalysis.AnalysisPlan(request.body, "b".repeat(40), listOf(file), emptyList(), listOf(draftPath), 1))
        return directory to id
    }
    private fun draftScreen(scenario: ActivityScenario<MainActivity>, directory: File): LinearLayout {
        lateinit var root: LinearLayout
        scenario.onActivity { activity ->
            root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            activity.setContentView(ScrollView(activity).apply { addView(root) })
            val patch = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { true })
            DevelopmentInboxWorkbench.attach(activity, root, LinearLayout(activity), patch,
                services = DevelopmentInboxWorkbench.Services(inbox = { DevelopmentJobInbox(directory) }, drafts = { DevelopmentJobDrafts(directory) }))
            DeveloperUiState.descendants(root).filterIsInstance<Button>().single { it.text.toString() == "Show all development reviews" }.performClick()
        }
        await(scenario) { DeveloperUiState.descendants(root).filterIsInstance<EditText>().any { it.contentDescription == "Requested engineering change" } }
        return root
    }
    @Test fun savedInstructionsAndPathsRecoverOnANewActivityWithoutAnApproval() {
        val (directory, id) = analysisFixture()
        try {
            val queue = DevelopmentJobInbox(directory)
            val checkpoint = File(directory, "development-inbox.bin").readBytes()
            val key = DevelopmentJobDrafts.key(queue.localSnapshot().single())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val root = draftScreen(scenario, directory)
                scenario.onActivity { activity ->
                    val fields = DeveloperUiState.descendants(root).filterIsInstance<EditText>().toList()
                    fields.single { it.contentDescription == "Requested engineering change" }.setText("Keep my unfinished instructions")
                    fields.single { it.contentDescription == "Engineering task source scope" }.setText(draftPath + "\n")
                    DeveloperUiState.descendants(root).filterIsInstance<Button>().single { it.text == activity.getString(R.string.development_draft_save) }.performClick()
                }
                await(scenario) { DevelopmentJobDrafts(directory).read(key).text?.goal == "Keep my unfinished instructions" }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val root = draftScreen(scenario, directory)
                scenario.onActivity { activity ->
                    val fields = DeveloperUiState.descendants(root).filterIsInstance<EditText>().toList()
                    assertEquals("Keep my unfinished instructions", fields.single { it.contentDescription == "Requested engineering change" }.text.toString())
                    assertEquals(draftPath + "\n", fields.single { it.contentDescription == "Engineering task source scope" }.text.toString())
                    assertTrue(DeveloperUiState.descendants(root).filterIsInstance<android.widget.TextView>()
                        .any { it.text == activity.getString(R.string.development_draft_restored) })
                }
            }
            assertArrayEquals(checkpoint, File(directory, "development-inbox.bin").readBytes())
            val job = queue.localSnapshot().single { it.getString("id") == id }
            assertEquals("REVIEW_READY", job.getString("state")); assertFalse(job.getBoolean("sourceVerified"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun changedJobStateDoesNotApplyAnOlderSavedDraftOrConsumeApproval() {
        val (directory, id) = analysisFixture()
        try {
            val queue = DevelopmentJobInbox(directory)
            val job = queue.localSnapshot().single()
            DevelopmentJobDrafts(directory).save(DevelopmentJobDrafts.key(job), 0, DevelopmentJobDrafts.Text("Old unsent text", "Old paths"))
            queue.stageEngineering(id, job.getLong("revision"), listOf(draftPath), true, "New current goal")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val root = draftScreen(scenario, directory)
                scenario.onActivity { activity ->
                    assertEquals("New current goal", DeveloperUiState.descendants(root).filterIsInstance<EditText>()
                        .single { it.contentDescription == "Requested engineering change" }.text.toString())
                    assertTrue(DeveloperUiState.descendants(root).filterIsInstance<android.widget.TextView>()
                        .any { it.text == activity.getString(R.string.development_draft_stale) })
                }
            }
            assertFalse(queue.localSnapshot().single().getJSONObject("engineering").getBoolean("approvalConsumed"))
            assertFalse(queue.localSnapshot().single().getBoolean("sourceVerified"))
        } finally { directory.deleteRecursively() }
    }
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
