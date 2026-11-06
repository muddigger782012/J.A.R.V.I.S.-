package com.jarvis.developer

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeveloperHomeWorkspaceTest {
    @Test fun stickyHomeComposerKeepsExistingControlsAndExactReviewLocked() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val home = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val advanced = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            home.addView(TextView(activity).apply { text = "Original approved reactor" })
            val prompt = EditText(activity).apply { hint = "Existing task input" }
            home.addView(prompt)
            val actions = LinearLayout(activity)
            val send = Button(activity).apply { text = "Ask Jarvis / save instructions" }
            actions.addView(send)
            home.addView(actions)
            val output = TextView(activity).apply { text = "Jarvis is ready." }
            val status = TextView(activity).apply { text = "Waiting for a task" }
            val coding = TextView(activity)
            val review = Button(activity).apply { text = "Review proposed changes"; isEnabled = false }
            val toggle = Button(activity).apply { text = "Open inline code review" }
            val panel = LinearLayout(activity)
            home.addView(status); home.addView(output); home.addView(review)
            home.addView(toggle); home.addView(panel)
            val legacy = Button(activity).apply { text = "Old diagnostic controls" }
            home.addView(legacy)
            var product = -1
            var advancedCalls = 0
            val session = DeveloperHomeWorkspace.install(activity, home, advanced,
                prompt, send, output, status, coding, review, toggle, panel, 0,
                { product = it }, { advancedCalls++ }, null)
            activity.setContentView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(home)
                addView(session.composer)
            })
            assertEquals("Original approved reactor", (home.getChildAt(0) as TextView).text)
            assertEquals("Existing task input", prompt.hint)
            assertSame(session.composer, prompt.parent)
            assertSame(session.composer, send.parent)
            assertSame(home, review.parent)
            assertFalse(review.isEnabled)
            assertFalse(legacy.isShown)
            assertTrue(advanced.findViewWithTag<View>("developer-legacy-home-tools") != null)
            session.sent("Implement an offline list in Personal Jarvis")
            assertTrue((home.findViewWithTag<View>("developer-home-conversation") as LinearLayout)
                .let { DeveloperUiState.descendants(it).filterIsInstance<TextView>().any { t ->
                    t.text.toString().contains("Implement an offline list") } })
            assertEquals("UNDERSTANDING",
                (home.findViewWithTag<View>("developer-home-task-stage") as TextView).text)
            coding.text = "Building verified task branch"
            assertEquals("BUILD / TEST",
                (home.findViewWithTag<View>("developer-home-task-stage") as TextView).text)
            assertFalse(review.isEnabled)
            val picker = session.composer.findViewWithTag<Spinner>("developer-home-target")
            picker.setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, product)
            session.syncTarget(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, picker.selectedItemPosition)
            val state = Bundle()
            session.save(state)
            assertTrue(state.getStringArrayList("developer_home_turns_v1").orEmpty().any { it.contains("Implement") })
            assertFalse(state.keySet().any { it.contains("approval", true) })
            val sendButton = DeveloperUiState.descendants(session.composer)
                .filterIsInstance<Button>().single { it.text.toString() == "Send" }
            assertTrue(sendButton.isShown)
            assertFalse(send.isShown)
            assertTrue(DeveloperUiState.descendants(home).filterIsInstance<Button>()
                .none { it.isShown && it.text.toString() in setOf(
                    "Advanced development tools", "Expand response", "Open inline code review",
                    "Review proposed changes") })
            assertEquals(0, advancedCalls)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun persistedJobEvidenceDisplaysExactVerificationGatesWithoutFalseSuccess() {
        assertNull(DeveloperHomeWorkspace.latestJobEvidence(emptyList()))
        val pending = org.json.JSONObject()
            .put("summary", "Verify Jarvis development task")
            .put("state", "VERIFYING")
            .put("verification", org.json.JSONObject()
                .put("SOURCE_COMMITTED", "PASSED")
                .put("BUILD_SUCCESSFUL", "RUNNING")
                .put("TESTS_PASSED", "BLOCKED")
                .put("SIGNED_APK", "PENDING")
                .put("DEVICE_INSTALLED", "BLOCKED"))
        val displayed = requireNotNull(DeveloperHomeWorkspace.latestJobEvidence(listOf(pending)))
        assertTrue(displayed.contains("VERIFYING"))
        assertTrue(displayed.contains("Source: PASSED"))
        assertTrue(displayed.contains("Build: RUNNING"))
        assertTrue(displayed.contains("Tests: BLOCKED"))
        assertTrue(displayed.contains("Signing: PENDING"))
        assertTrue(displayed.contains("Installed: BLOCKED"))
        assertFalse(displayed.contains("Build: PASSED"))
        // Malformed/incomplete records must never invent passed stages.
        val unknown = requireNotNull(DeveloperHomeWorkspace.latestJobEvidence(listOf(
            org.json.JSONObject().put("state", "QUEUED"))))
        assertTrue(unknown.contains("Build: Unverified"))
        assertFalse(unknown.contains("Build: PASSED"))
    }

    @Test fun statusLabelsAreDerivedFromRealEngineEventsRatherThanInventedPercentages() {
        assertEquals("READING SOURCE", DeveloperHomeWorkspace.stageLabel("Reading repository inventory"))
        assertEquals("REVIEW", DeveloperHomeWorkspace.stageLabel("Proposal staged for review"))
        assertEquals("BUILD / TEST", DeveloperHomeWorkspace.stageLabel("Building task branch"))
        assertEquals("ATTENTION REQUIRED", DeveloperHomeWorkspace.stageLabel("GitHub access denied"))
        assertEquals("READY", DeveloperHomeWorkspace.stageLabel("Your AI development assistant"))
    }
}
