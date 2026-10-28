package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
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
class DevelopmentJobControlsTest {
    @get:Rule val directory = TemporaryFolder()
    private fun exercise(stale: Boolean = false, destroyed: Boolean = false, schedulerFailure: Boolean = false) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val panel = LinearLayout(activity)
        activity.setContentView(panel)
        val inbox = DevelopmentJobInbox(directory.root)
        val job = inbox.handle("com.assistant.core", true, Wire.Request("ui-test", "diagnostics.review.request", "Inspect error"))
        var work: (() -> Unit)? = null
        var scheduled = 0
        var changed = 0
        DevelopmentJobControls.attach(activity, panel, inbox, job,
            inbox.localControls(job.getString("id"), 0, "supervised", true), { "supervised" }, { true }, { changed++ },
            DevelopmentJobControls.Services(accepted = { _, _, _ ->
                scheduled++; if (schedulerFailure) throw IllegalStateException("private-credential-marker")
            }, worker = { work = it }))
        fun button(text: String) = DeveloperUiState.descendants(panel).filterIsInstance<Button>().single { it.text.toString() == text }
        button("Pause task").performClick(); button("Pause task").performClick()
        assertNotNull(work)
        assertFalse(button("Pause task").isEnabled); assertFalse(button("Cancel task").isEnabled)
        if (stale) inbox.handle("com.assistant.core", true, Wire.Request("external-cancel", "task.cancel.request",
            jobId = job.getString("id"), expectedRevision = 0))
        if (destroyed) controller.pause().stop().destroy()
        work!!.invoke(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(if (stale) "CANCELLED" else "PAUSED", inbox.localSnapshot().single().getString("state"))
        assertEquals(if (stale) 0 else 1, scheduled)
        assertEquals(if (stale || destroyed || schedulerFailure) 0 else 1, changed)
        val messages = DeveloperUiState.descendants(panel).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertFalse(messages.any { it.contains("private-credential-marker") })
        if (stale) assertTrue(messages.any { it.contains("This task changed") })
        if (schedulerFailure) assertTrue(messages.any { it.contains("could not be scheduled") })
        if (!destroyed) controller.pause().stop().destroy()
    }
    @Test fun pauseUsesTheRealCheckpointAndCannotSubmitDuplicateClicks() = exercise()
    @Test fun staleUiCannotControlAChangedJobOrDispatchBackgroundEffects() = exercise(stale = true)
    @Test fun aSavedControlSurvivesActivityDestructionWithoutUpdatingTheOldScreen() = exercise(destroyed = true)
    @Test fun schedulingFailureKeepsTheSavedStateAndReportsASanitizedInlineError() = exercise(schedulerFailure = true)
    @Test fun localOwnerControllersRemainOutsideRuntimePatchScope() {
        assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/DevelopmentJobControls.kt"))
    }
}
