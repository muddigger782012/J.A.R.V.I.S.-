package com.jarvis.developer

import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native widgets and durable storage are real; scheduler effects are injected, with no remote execution. */
class DevelopmentJobControlsDeviceTest {
    private fun fixture(): Pair<File, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir.canonicalFile, "owner-controls-" + UUID.randomUUID())
        val job = DevelopmentJobInbox(directory).handle("com.assistant.core", true,
            Wire.Request(UUID.randomUUID().toString(), "diagnostics.review.request", "Inspect application error"))
        return directory to job.getString("id")
    }
    @Test fun pauseAndResumePersistAcrossNativeActivityRecreationWithoutApproval() {
        val (directory, id) = fixture()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fun click(label: String) {
                    val completed = CountDownLatch(1)
                    val inbox = DevelopmentJobInbox(directory)
                    val job = inbox.localSnapshot().single()
                    scenario.onActivity { activity ->
                        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                        activity.setContentView(panel)
                        DevelopmentJobControls.attach(activity, panel, inbox, job,
                            inbox.localControls(id, job.getLong("revision"), "supervised", true),
                            { "supervised" }, { true }, { completed.countDown() },
                            DevelopmentJobControls.Services(accepted = { _, _, _ -> }))
                        val button = DeveloperUiState.descendants(panel).filterIsInstance<Button>().single { it.text.toString() == label }
                        assertTrue(button.isEnabled)
                        assertTrue(button.minHeight >= (48 * activity.resources.displayMetrics.density).toInt())
                        button.performClick()
                    }
                    assertTrue("Native owner control did not complete", completed.await(8, TimeUnit.SECONDS))
                }
                click("Pause task")
                assertEquals("PAUSED", DevelopmentJobInbox(directory).localSnapshot().single().getString("state"))
                scenario.recreate()
                click("Resume task")
                val state = DevelopmentJobInbox(directory).localSnapshot().single()
                assertEquals("QUEUED", state.getString("state"))
                assertEquals(1, state.getInt("attempts"))
                assertFalse(state.getBoolean("sourceVerified")); assertFalse(state.getBoolean("deviceVerified"))
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun staleNativeControlsShowAnInlineConflictWithoutChangingTheNewState() {
        val (directory, id) = fixture()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val inbox = DevelopmentJobInbox(directory)
                val old = inbox.localSnapshot().single()
                lateinit var panel: LinearLayout
                scenario.onActivity { activity ->
                    panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    activity.setContentView(panel)
                    DevelopmentJobControls.attach(activity, panel, inbox, old,
                        inbox.localControls(id, 0, "supervised", true), { "supervised" }, { true }, {},
                        DevelopmentJobControls.Services(accepted = { _, _, _ -> fail("Stale control scheduled effects") }))
                }
                inbox.controlLocal(id, 0, "supervised", true, DevelopmentJobInbox.LocalControl.PAUSE)
                scenario.onActivity {
                    DeveloperUiState.descendants(panel).filterIsInstance<Button>().single { it.text.toString() == "Cancel task" }.performClick()
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
                var conflict = false
                while (!conflict && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { conflict = DeveloperUiState.descendants(panel).filterIsInstance<TextView>()
                        .any { it.text.toString().contains("This task changed") } }
                    if (!conflict) android.os.SystemClock.sleep(20)
                }
                assertTrue("Stale native controls must show an inline conflict", conflict)
                assertEquals("PAUSED", inbox.localSnapshot().single().getString("state"))
                assertEquals(1L, inbox.localSnapshot().single().getLong("revision"))
            }
        } finally { directory.deleteRecursively() }
    }
}
