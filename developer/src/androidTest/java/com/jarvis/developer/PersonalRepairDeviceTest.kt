package com.jarvis.developer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable CI emulator only. Public GitHub GETs, no provider/key, source write or approval. */
@RunWith(AndroidJUnit4::class)
class PersonalRepairDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val path = "app/src/main/java/com/assistant/core/services/AssistantPersonality.kt"
    private fun share(file: String = path) = "Review a static diagnostic for muddigger782012/J.A.R.V.I.S.-.\n" +
        "Review signal: Outstanding review marker.\nSource reference: $file:1 (source).\n" +
        "Claimed revision: unspecified. Source inventory: ${"b".repeat(64)}.\n"

    @Before fun disposableOnly() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("jarvis_disposable_emulator"))
        context.getSharedPreferences("developer_health", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun buttons(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<Button>()
    private fun button(activity: MainActivity, text: String) = buttons(activity).single { it.text.toString() == text }
    private fun openTools(activity: MainActivity) {
        button(activity, "Tools").performClick()
        button(activity, "Show developer controls").performClick()
        assertTrue(button(activity, READ).isShown)
    }
    private fun noApproval(activity: MainActivity) {
        val approval = views(activity.window.decorView).filterIsInstance<CheckBox>()
            .single { it.text.toString() == "I reviewed this exact diff and approve creating its isolated task branch" }
        assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
        assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)
    }
    private fun intent(text: String) = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    @Test fun publicSourceReviewStagesPersonalScopeButNeverApprovesOrCallsAProvider() {
        ActivityScenario.launch<MainActivity>(intent(share())).use { scenario ->
            scenario.onActivity { activity ->
                openTools(activity); noApproval(activity)
                assertFalse(button(activity, STAGE).isEnabled)
                button(activity, READ).performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 120_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { ready = button(it, STAGE).isEnabled }
                if (!ready) SystemClock.sleep(100)
            }
            assertTrue("Bounded public-source review must complete successfully", ready)
            scenario.onActivity { activity ->
                button(activity, STAGE).performClick()
                val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.any { it.startsWith("Queued personal task source: main @ ") })
                assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().any { it.text.toString() == path })
                noApproval(activity)
            }
        }
    }

    @Test fun protectedShareIsRejectedBeforeAnySourceRead() {
        ActivityScenario.launch<MainActivity>(intent(share(path.replace("AssistantPersonality.kt", "PermissionManager.kt")))).use { scenario ->
            scenario.onActivity { activity ->
                openTools(activity); button(activity, READ).performClick()
                assertFalse(button(activity, STAGE).isEnabled)
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("No request was sent.") })
                noApproval(activity)
            }
        }
    }

    @Test fun recreationDoesNotRestoreAReviewOrWriteApproval() {
        ActivityScenario.launch<MainActivity>(intent(share())).use { scenario ->
            scenario.onActivity { openTools(it); noApproval(it) }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(button(activity, STAGE).isEnabled)
                noApproval(activity)
            }
        }
    }
    companion object {
        private const val READ = "Read Personal diagnostic source (GET only)"
        private const val STAGE = "Stage Personal source for a NEW exact diff review"
    }
}
