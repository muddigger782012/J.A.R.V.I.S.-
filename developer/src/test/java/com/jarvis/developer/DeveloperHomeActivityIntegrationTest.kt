package com.jarvis.developer

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the actual Activity, not a mock screen. AI/network and repository
 * writes are intentionally never invoked; offline conversation must still
 * work while every source-write and install gate stays closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeveloperHomeActivityIntegrationTest {
    private var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    @Before fun clearDrafts() {
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("developer_local_projects_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun close() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun activity(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }

    private fun all(activity: MainActivity): List<View> =
        DeveloperUiState.descendants(activity.window.decorView).toList()

    private fun button(activity: MainActivity, name: String): Button =
        all(activity).filterIsInstance<Button>().single {
            it.text.toString().removeSuffix("  ▼").removeSuffix("  ▲") == name
        }

    private fun composer(activity: MainActivity): LinearLayout =
        all(activity).filterIsInstance<LinearLayout>().single {
            it.tag == "developer-home-sticky-composer"
        }

    private fun transcript(activity: MainActivity): LinearLayout =
        all(activity).filterIsInstance<LinearLayout>().single {
            it.tag == "developer-home-conversation"
        }

    @Test fun actualHomeUsesStickyChatAndKeepsExactReviewLocked() {
        val activity = activity()
        val composer = composer(activity)
        val send = button(activity, "Ask Jarvis / save instructions")
        val prompt = all(activity).filterIsInstance<EditText>().single {
            it.hint?.toString() == "Example: Build me an app to track equipment maintenance."
        }
        assertTrue(composer.isShown)
        assertSame(composer, send.parent)
        assertSame(composer, prompt.parent)
        assertTrue(transcript(activity).isShown)
        assertFalse(button(activity, "Review proposed changes").isEnabled)
        assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)

        // The working offline pathway sends a real message through the same
        // ProjectGenerationWorkbench without ever creating a patch or approval.
        prompt.setText("What are three improvements for Personal Jarvis?")
        button(activity, "Send").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DeveloperUiState.descendants(transcript(activity))
            .filterIsInstance<TextView>().any {
                it.text.toString().contains("What are three improvements")
            })
        assertFalse(button(activity, "Review proposed changes").isEnabled)
        assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)

        button(activity, "Settings").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, composer.visibility)
        button(activity, "Advanced tools").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        button(activity, "Home").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, composer.visibility)
    }

    @Test fun trackKeepsPrivateRepairHistoryAccessibleAfterRecreationWithoutRestoringApproval() {
        val first = activity()
        assertFalse(button(first, "Load private repair history (local only)").isShown)
        button(first, "Track").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val history = all(first).filterIsInstance<LinearLayout>().single {
            it.tag == "developer-private-repair-history"
        }
        assertTrue(history.isShown)
        assertTrue(button(first, "Load private repair history (local only)").isShown)
        assertFalse(button(first, "Recheck selected saved build diagnostics (GET only)").isEnabled)
        assertFalse(button(first, "Create task branch and commit approved files (writes)").isEnabled)

        controller!!.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val restored = controller!!.get()
        val restoredHistory = all(restored).filterIsInstance<LinearLayout>().single {
            it.tag == "developer-private-repair-history"
        }
        assertTrue(restoredHistory.isShown)
        assertTrue(button(restored, "Load private repair history (local only)").isShown)
        assertFalse(button(restored, "Recheck selected saved build diagnostics (GET only)").isEnabled)
        val approval = all(restored).filterIsInstance<android.widget.CheckBox>()
            .single { it.text.toString() == "I reviewed this exact diff and approve creating its isolated task branch" }
        assertFalse(approval.isChecked)
        assertFalse(approval.isEnabled)
        assertFalse(button(restored, "Create task branch and commit approved files (writes)").isEnabled)
    }

    @Test fun recreationRetainsConversationAndProjectButNeverRestoresApproval() {
        val activity = activity()
        val prompt = all(activity).filterIsInstance<EditText>().single {
            it.hint?.toString() == "Example: Build me an app to track equipment maintenance."
        }
        val target = composer(activity).findViewWithTag<Spinner>("developer-home-target")
        target.setSelection(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, all(activity).filterIsInstance<Spinner>().single {
            it.contentDescription?.toString() == "Application to improve"
        }.selectedItemPosition)

        prompt.setText("Explain Personal Jarvis reminder scheduling")
        button(activity, "Send").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val before = all(activity).filterIsInstance<TextView>()
            .single { it.tag == "project-generation-reply" }.text.toString()
        assertTrue(before.isNotBlank())

        controller!!.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        val restored = controller!!.get()
        val current = all(restored).filterIsInstance<TextView>()
            .single { it.tag == "project-generation-reply" }.text.toString()
        assertEquals(before.take(1800), current)
        assertTrue(DeveloperUiState.descendants(transcript(restored))
            .filterIsInstance<TextView>().any {
                it.text.toString().contains("Explain Personal Jarvis reminder scheduling")
            })
        assertEquals(1, composer(restored).findViewWithTag<Spinner>("developer-home-target").selectedItemPosition)
        assertFalse(button(restored, "Review proposed changes").isEnabled)
        assertFalse(button(restored, "Create task branch and commit approved files (writes)").isEnabled)
    }
}
