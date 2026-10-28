package com.jarvis.developer

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android view/lifecycle interactions on a disposable emulator. No provider or writer call. */
@RunWith(AndroidJUnit4::class)
class DeveloperGuidedUxDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Before fun disposableOnly() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("jarvis_disposable_emulator"))
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun button(activity: MainActivity, text: String) = views(activity.window.decorView).filterIsInstance<Button>().single {
        it.text.toString().removeSuffix("  ▼").removeSuffix("  ▲") == text
    }
    private fun field(activity: MainActivity, hint: String) = views(activity.window.decorView).filterIsInstance<EditText>().single { it.hint.toString() == hint }
    private fun scroll(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }
    private fun settle(scenario: ActivityScenario<MainActivity>) {
        val end = SystemClock.elapsedRealtime() + 10_000
        do {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { val view = scroll(it); ready = view.isLaidOut && !view.isLayoutRequested && view.height > 0 }
            if (ready) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < end)
        fail("Visible destination did not receive layout")
    }

    @Test fun fourDestinationsAndAdvancedCategoriesRemainReachableWithAndroidBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle(scenario)
            scenario.onActivity { activity ->
                for ((label, index) in listOf("Home" to 0, "Projects" to 1, "Activity" to 2, "Settings" to 3)) {
                    button(activity, label).performClick()
                    assertEquals("developer-page-$index", scroll(activity).tag)
                }
                button(activity, "Advanced tools").performClick()
                assertEquals("developer-page-4", scroll(activity).tag)
                listOf("Connections and source browsing", "Code changes and review", "Planning", "Testing and repair",
                    "Self-development", "Release verification", "Assistant diagnostics").forEach { category ->
                    button(activity, category).performClick()
                    assertTrue(button(activity, category).isShown)
                }
                activity.onBackPressed() // close category
                activity.onBackPressed() // Settings
                assertEquals("developer-page-3", scroll(activity).tag)
                assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)
            }
        }
    }

    @Test fun recreationKeepsSafeDraftsAndSelectedTabWithoutRestoringSecretsOrApproval() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                field(activity, PROMPT).setText("Fix a weather date problem")
                field(activity, SOURCE).setText("developer/src/main/java/com/jarvis/developer/RepositoryAnalysis.kt")
                field(activity, "Paste your API key here (not a model name)").setText("fixture-never-save")
                button(activity, "Activity").performClick()
            }
            scenario.recreate(); settle(scenario)
            scenario.onActivity { activity ->
                assertEquals("developer-page-2", scroll(activity).tag)
                assertEquals("Fix a weather date problem", field(activity, PROMPT).text.toString())
                assertTrue(field(activity, SOURCE).text.toString().endsWith("RepositoryAnalysis.kt"))
                assertEquals("", field(activity, "Paste your API key here (not a model name)").text.toString())
                assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)
            }
        }
    }

    @Test fun advancedScrollSurvivesTabChangesAndActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                button(it, "Settings").performClick(); button(it, "Advanced tools").performClick()
                button(it, "Release verification").performClick()
            }
            settle(scenario)
            var position = 0
            scenario.onActivity { activity ->
                val view = scroll(activity)
                view.isSmoothScrollingEnabled = false
                view.scrollTo(0, (view.getChildAt(0).height - view.height).coerceAtLeast(0))
                position = view.scrollY
                assertTrue("Legacy tools must be scrollable", position > 0)
                button(activity, "Activity").performClick()
                activity.onBackPressed()
            }
            settle(scenario)
            scenario.onActivity { assertEquals(position, scroll(it).scrollY) }
            scenario.recreate(); settle(scenario)
            scenario.onActivity {
                assertEquals("developer-page-4", scroll(it).tag)
                assertEquals(position, scroll(it).scrollY)
                assertTrue(button(it, "Generate icon code, commit and check build").isShown)
            }
        }
    }

    @Test fun validationErrorsStayAtTheirSourceAndHomeKeepsItsConversation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, "Settings").performClick(); button(activity, "Advanced tools").performClick()
                button(activity, "Connections and source browsing").performClick()
                button(activity, "Analyze task and preview file plan (read-only)").performClick()
                assertTrue(views(scroll(activity)).filterIsInstance<TextView>().any {
                    it.text.toString() == "Describe a task on Home before analyzing it here."
                })
                button(activity, "Home").performClick()
                assertTrue(views(scroll(activity)).filterIsInstance<TextView>().any {
                    it.text.toString() == "Your assistant’s response will appear here."
                })
            }
        }
    }

    @Test fun realBackClosesKeyboardBeforeNavigatingAndRetainsTheTypedValue() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { button(it, "Settings").performClick() }
            settle(scenario)
            scenario.onActivity { activity ->
                val editor = field(activity, "Paste your API key here (not a model name)")
                editor.setText("fixture-key-not-saved")
                editor.requestFocus()
                @Suppress("DEPRECATION")
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
            }
            fun awaitKeyboard(visible: Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                do {
                    instrumentation.waitForIdleSync()
                    var matches = false
                    scenario.onActivity { activity ->
                        matches = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == visible
                    }
                    if (matches) return
                    SystemClock.sleep(50)
                } while (SystemClock.elapsedRealtime() < deadline)
                fail("Native keyboard visibility did not reach the expected state")
            }
            awaitKeyboard(true)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            awaitKeyboard(false)
            scenario.onActivity {
                assertEquals("developer-page-3", scroll(it).tag)
                assertEquals("fixture-key-not-saved", field(it, "Paste your API key here (not a model name)").text.toString())
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            settle(scenario)
            scenario.onActivity { assertEquals("developer-page-0", scroll(it).tag) }
        }
    }

    companion object {
        private const val PROMPT = "Example: Build me an app to track equipment maintenance."
        private const val SOURCE = "Source path or directory prefix (blank lists all allowed sources)"
    }
}
