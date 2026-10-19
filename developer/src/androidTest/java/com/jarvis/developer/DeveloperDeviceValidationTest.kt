package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
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
                openSettings(activity)
                assertFieldEmpty(activity, PROVIDER_KEY, "Provider credential must start empty")
                openTools(activity)
                assertTrue(button(activity, "Browse source inventory (read-only)").isEnabled)
                assertTrue(button(activity, "Read exact baseline debug CI (GET only)").isEnabled)
                assertTrue(button(activity, "Read signed candidate and recovery evidence (GET only)").isEnabled)
                assertFieldEmpty(activity, GITHUB_TOKEN, "GitHub credential must start empty")
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
                openSettings(activity)
                assertFieldEmpty(activity, PROVIDER_KEY, "Do not overwrite a configured provider credential")
                field(activity, PROVIDER_KEY).setText("instrumented-fixture-provider-key")
                openTools(activity)
                assertFieldEmpty(activity, GITHUB_TOKEN, "Do not overwrite a configured GitHub credential")
                field(activity, GITHUB_TOKEN).setText("instrumented-fixture-github-token")
                field(activity, REVIEW_FINGERPRINT).setText("a".repeat(64))
                assertApprovalsUnavailable(activity)
            }

            scenario.recreate()
            waitForIdle()

            scenario.onActivity { activity ->
                openSettings(activity)
                assertFieldEmpty(activity, PROVIDER_KEY, "Unsaved provider draft must clear on recreation")
                openTools(activity)
                assertFieldEmpty(activity, GITHUB_TOKEN, "GitHub draft must clear on recreation")
                assertFieldEmpty(activity, REVIEW_FINGERPRINT, "Review draft must clear on recreation")
                assertApprovalsUnavailable(activity)
            }
        }
    }

    @Test
    fun nativeScrollContainerCanReachItsBottomWithoutGrantingApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openTools(it) }
            awaitToolsLayout(scenario)
            scenario.onActivity { activity ->
                val scroll = visibleToolsScroll(activity)
                assertTrue("Activity viewport must be laid out", scroll.height > 0)
                assertTrue("The complete workbench must exceed the viewport", scroll.getChildAt(0).height > scroll.height)
                scroll.isSmoothScrollingEnabled = false
                assertFinalToolsAction(activity, scroll)
                assertTrue("Tools viewport must scroll toward its final action", scroll.fullScroll(View.FOCUS_DOWN))
            }
            awaitToolsLayout(scenario)

            scenario.onActivity { activity ->
                val scroll = visibleToolsScroll(activity)
                val content = scroll.getChildAt(0)
                assertTrue("The workbench must actually scroll", scroll.scrollY > 0)
                assertTrue("The bottom of the workbench must be reachable",
                    scroll.scrollY + scroll.height - scroll.paddingBottom >= content.bottom)
                val lastAction = assertFinalToolsAction(activity, scroll)
                val bounds = Rect(0, 0, lastAction.width, lastAction.height)
                scroll.offsetDescendantRectToMyCoords(lastAction, bounds)
                assertTrue("Final Tools action must be fully reachable in its visible viewport",
                    bounds.top >= scroll.scrollY + scroll.paddingTop &&
                        bounds.bottom <= scroll.scrollY + scroll.height - scroll.paddingBottom)
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
                openSettings(activity)
                assertFieldEmpty(activity, PROVIDER_KEY, "Public inventory must not use a provider credential")
                openTools(activity)
                assertFieldEmpty(activity, GITHUB_TOKEN, "Public inventory must use an empty GitHub credential")
                field(activity, "Source path or directory prefix (blank lists all allowed sources)")
                    .setText("developer/")
                status = views<TextView>(activity).single {
                    it.text.toString() == "Local task plans loaded; no repository request was made."
                }
                output = views<TextView>(activity).single {
                    it.text.toString() == "Your assistant’s response will appear here."
                }
                status.addTextChangedListener(watcher)
                assertTrue(button(activity, "Browse source inventory (read-only)").performClick())
            }

            // Match the production reader's bounded three-minute session budget.
            val finished = completed.await(180, TimeUnit.SECONDS)

            scenario.onActivity { activity ->
                status.removeTextChangedListener(watcher)
                assertTrue("Public inventory did not finish within 180 seconds", finished)
                val report = output.text.toString()
                assertTrue("Public GET inventory failed: ${report.take(300)}",
                    report.startsWith("READ-ONLY SOURCE INVENTORY\n"))
                val source = Regex("(?m)^Commit: ([a-f0-9]{40})$").find(report)
                assertTrue("Inventory must cite a full immutable source SHA", source != null)
                assertEquals("Read-only preview complete at ${source!!.groupValues[1].take(12)}",
                    status.text.toString())
                assertTrue("Inventory must identify the relevant Developer source file",
                    report.contains("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"))
                assertFieldEmpty(activity, PROVIDER_KEY, "Public inventory must leave the provider credential empty")
                assertFieldEmpty(activity, GITHUB_TOKEN, "Public inventory must leave the GitHub credential empty")
                assertApprovalsUnavailable(activity)
            }
        }
    }

    private fun openSettings(activity: Activity) {
        val tab = button(activity, "Settings")
        assertTrue("Settings navigation must be shown", tab.isShown)
        if (!tab.isSelected) assertTrue("Settings navigation must respond", tab.performClick())
        assertTrue("Settings navigation must be selected", tab.isSelected)
        assertTrue("Provider draft must be on the visible Settings page", field(activity, PROVIDER_KEY).isShown)
    }

    private fun openTools(activity: Activity) {
        val tab = button(activity, "Tools")
        assertTrue("Tools navigation must be shown", tab.isShown)
        if (!tab.isSelected) assertTrue("Tools navigation must respond", tab.performClick())
        assertTrue("Tools navigation must be selected", tab.isSelected)
        val disclosure = views<Button>(activity).single {
            it.text.toString() in listOf("Show developer controls", "Hide developer controls")
        }
        assertTrue("Developer disclosure must be on the visible Tools page", disclosure.isShown)
        if (disclosure.text.toString() == "Show developer controls") {
            assertTrue("Developer disclosure must respond", disclosure.performClick())
        }
        assertEquals("Hide developer controls", disclosure.text.toString())
        assertTrue("Expanded Tools must expose the final action", button(activity, LAST_ACTION).isShown)
        visibleToolsScroll(activity)
    }

    private fun visibleToolsScroll(activity: Activity): ScrollView {
        val pages = views<ScrollView>(activity)
        assertEquals("Current owner UI must retain its four page viewports", 4, pages.size)
        val shown = pages.filter { it.isShown }
        assertEquals("Exactly one page viewport must be shown", 1, shown.size)
        assertTrue("Tools must be the selected page", button(activity, "Tools").isSelected)
        val scroll = shown.single()
        assertTrue("The visible viewport must contain the Tools action",
            descendants(scroll).any { it === button(activity, LAST_ACTION) })
        return scroll
    }

    private fun assertFinalToolsAction(activity: Activity, scroll: ScrollView): Button {
        val action = button(activity, LAST_ACTION)
        val root = scroll.getChildAt(0) as ViewGroup
        val controls = action.parent as ViewGroup
        assertTrue("Expanded developer controls must be the final Tools section",
            root.getChildAt(root.childCount - 1) === controls)
        assertTrue("Icon action must remain the final expanded Tools child",
            controls.getChildAt(controls.childCount - 1) === action)
        assertTrue("Final Tools action must have actual nonzero geometry", action.width > 0 && action.height > 0)
        return action
    }

    private fun awaitToolsLayout(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000L
        var lastFailure: AssertionError? = null
        do {
            waitForIdle()
            var laidOut = false
            scenario.onActivity { activity ->
                try {
                    openTools(activity)
                    val scroll = visibleToolsScroll(activity)
                    val content = scroll.getChildAt(0)
                    laidOut = scroll.isLaidOut && !scroll.isLayoutRequested &&
                        scroll.width > 0 && scroll.height > 0 &&
                        content.isLaidOut && !content.isLayoutRequested &&
                        content.width > 0 && content.height > 0
                } catch (failure: AssertionError) {
                    lastFailure = failure
                }
            }
            if (laidOut) return
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(minOf(50L, remaining))
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Visible expanded Tools did not receive actual layout within 10000ms").apply {
            lastFailure?.let { initCause(it) }
        }
    }

    private fun assertFieldEmpty(activity: Activity, hint: String, label: String) {
        // Preserve exact emptiness without printing a configured credential or fake sentinel on failure.
        assertTrue(label, field(activity, hint).text.isEmpty())
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
        private const val PROVIDER_KEY = "Paste your API key here (not a model name)"
        private const val GITHUB_TOKEN = "GitHub fine-grained token (not saved)"
        private const val REVIEW_FINGERPRINT = "Enter exact eligible review SHA-256"
        private const val LAST_ACTION = "Generate icon code, commit and check build"
    }
}
