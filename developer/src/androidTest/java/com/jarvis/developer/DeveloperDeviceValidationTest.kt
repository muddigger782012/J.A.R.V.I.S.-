package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bounded native view/lifecycle checks for a separately approved test device.
 * Compiling this suite is not evidence of device execution or installation.
 * Inventory uses the production Android HTTP route for public GitHub GETs with an empty token.
 * No provider, repository write, signing, publishing, installer or health-confirmation action is invoked.
 */
@RunWith(AndroidJUnit4::class)
class DeveloperDeviceValidationTest {
    @Before
    fun resetOnlyPrivateLaunchWarningState() {
        // This resets test launch-warning state; it does not confirm that a version is healthy.
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("developer_health", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun startupRequiresFreshDiffAndCandidateReviewBeforeAnyApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                assertTrue(button(activity, "Browse source inventory (read-only)").isEnabled)
                assertTrue(button(activity, "Read exact baseline debug CI (GET only)").isEnabled)
                assertTrue(button(activity, "Read signed candidate and recovery evidence (GET only)").isEnabled)
                assertEquals("", field(activity, PROVIDER_KEY).text.toString())
                assertEquals("", field(activity, GITHUB_TOKEN).text.toString())
                assertApprovalsUnavailable(activity)
            }
        }
    }

    @Test
    fun recreationClearsFakeCredentialsAndReviewDraftWithoutRestoringApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                // Fake sentinels only; no network-capable control is clicked.
                field(activity, PROVIDER_KEY).setText("instrumented-fixture-provider-key")
                field(activity, GITHUB_TOKEN).setText("instrumented-fixture-github-token")
                field(activity, REVIEW_FINGERPRINT).setText("a".repeat(64))
                assertApprovalsUnavailable(activity)
            }

            scenario.recreate()
            waitForIdle()

            scenario.onActivity { activity ->
                assertEquals("", field(activity, PROVIDER_KEY).text.toString())
                assertEquals("", field(activity, GITHUB_TOKEN).text.toString())
                assertEquals("", field(activity, REVIEW_FINGERPRINT).text.toString())
                assertApprovalsUnavailable(activity)
            }
        }
    }

    @Test
    fun nativeScrollContainerCanReachItsBottomWithoutGrantingApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForIdle()
            scenario.onActivity { activity ->
                val scroll = views<ScrollView>(activity).single()
                assertTrue("Activity viewport must be laid out", scroll.height > 0)
                assertTrue("The complete workbench must exceed the viewport", scroll.getChildAt(0).height > scroll.height)
                scroll.isSmoothScrollingEnabled = false
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            waitForIdle()

            scenario.onActivity { activity ->
                val scroll = views<ScrollView>(activity).single()
                val content = scroll.getChildAt(0)
                assertTrue("The workbench must actually scroll", scroll.scrollY > 0)
                assertTrue("The bottom of the workbench must be reachable",
                    scroll.scrollY + scroll.height - scroll.paddingBottom >= content.bottom)
                assertApprovalsUnavailable(activity)
            }
        }
    }

    @Test
    fun publicInventoryUsesAndroidHttpAndDisplaysImmutableSourceWithoutApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForIdle()
            val completed = CountDownLatch(1)
            lateinit var status: TextView
            lateinit var output: TextView
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    val message = text?.toString().orEmpty()
                    if (message.startsWith("Read-only preview complete at ") || message == "Repository preview failed") {
                        completed.countDown()
                    }
                }
            }
            scenario.onActivity { activity ->
                assertEquals("", field(activity, PROVIDER_KEY).text.toString())
                assertEquals("", field(activity, GITHUB_TOKEN).text.toString())
                field(activity, "Source path or directory prefix (blank lists all allowed sources)")
                    .setText("developer/")
                status = views<TextView>(activity).single {
                    it.text.toString() == "Standalone OpenAI coding client • Hybrid mode"
                }
                output = views<TextView>(activity).single {
                    it.text.toString().startsWith("Coding responses appear here.")
                }
                status.addTextChangedListener(watcher)
                assertTrue(button(activity, "Browse source inventory (read-only)").performClick())
            }

            val finished = completed.await(60, TimeUnit.SECONDS)

            scenario.onActivity { activity ->
                status.removeTextChangedListener(watcher)
                assertTrue("Public inventory did not finish within 60 seconds", finished)
                val report = output.text.toString()
                assertTrue("Public GET inventory failed: ${report.take(300)}",
                    report.startsWith("READ-ONLY SOURCE INVENTORY\n"))
                val source = Regex("(?m)^Commit: ([a-f0-9]{40})$").find(report)
                assertTrue("Inventory must cite a full immutable source SHA", source != null)
                assertEquals("Read-only preview complete at ${source!!.groupValues[1].take(12)}",
                    status.text.toString())
                assertTrue("Inventory must identify the relevant Developer source file",
                    report.contains("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"))
                assertEquals("", field(activity, PROVIDER_KEY).text.toString())
                assertEquals("", field(activity, GITHUB_TOKEN).text.toString())
                assertApprovalsUnavailable(activity)
            }
        }
    }

    private fun assertApprovalsUnavailable(activity: Activity) {
        listOf(
            "Create task branch and commit approved files (writes)",
            "Stage this exact source scope for a NEW diff review",
            "Compare baseline, candidate and verified recovery evidence",
            "Record exact approved promotion request (metadata only)"
        ).forEach { label -> assertFalse(label, button(activity, label).isEnabled) }
        listOf(
            "I reviewed this exact diff and approve creating its isolated task branch",
            "I reviewed this exact eligible candidate and approve recording its promotion request"
        ).forEach { label ->
            val approval = views<CheckBox>(activity).single { it.text.toString() == label }
            assertFalse(label, approval.isEnabled)
            assertFalse(label, approval.isChecked)
        }
    }

    private fun waitForIdle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    private fun button(activity: Activity, text: String): Button =
        views<Button>(activity).single { it.text.toString() == text }
    private fun field(activity: Activity, hint: String): EditText =
        views<EditText>(activity).single { it.hint?.toString() == hint }
    private inline fun <reified T : View> views(activity: Activity): List<T> =
        descendants(activity.window.decorView).filterIsInstance<T>().toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    companion object {
        private const val PROVIDER_KEY = "Selected provider API key (not saved)"
        private const val GITHUB_TOKEN = "GitHub fine-grained token (not saved)"
        private const val REVIEW_FINGERPRINT = "Enter exact eligible review SHA-256"
    }
}
