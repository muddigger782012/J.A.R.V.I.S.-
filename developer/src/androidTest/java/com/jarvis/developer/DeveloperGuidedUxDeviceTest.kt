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

/** Real Android views/lifecycle on a disposable emulator. Provider fixtures make no live request or source write. */
@RunWith(AndroidJUnit4::class)
class DeveloperGuidedUxDeviceTest {
    private fun projectFixture(activity: MainActivity, project: String, pending: MutableList<() -> Unit>, configure: Boolean = true,
        source: ((String, PatchEngine.Target) -> RepositoryCodingContext.Context)? = null,
        ask: ((String, RepositoryCodingContext.Context) -> String)? = null): ProjectGenerationWorkbench.Handle {
        val owner = MainActivity::class.java.getDeclaredField("projectGeneration").apply { isAccessible = true }
        (owner.get(activity) as? ProjectGenerationWorkbench.Handle)?.dispose()
        val active = MainActivity::class.java.getDeclaredField("activeProject").apply { isAccessible = true }
        active.set(activity, project)
        val provider = views(activity.window.decorView).filterIsInstance<Spinner>().single {
            it.adapter?.count == 2 && it.getItemAtPosition(0).toString() == "Gemini" && it.getItemAtPosition(1).toString() == "OpenAI"
        }
        val target = views(activity.window.decorView).filterIsInstance<Spinner>().single { it.contentDescription == "Application to improve" }
        val key = field(activity, "Paste your API key here (not a model name)")
        val model = field(activity, "Model ID (blank = provider default)")
        if (configure) { key.setText("native-provider-fixture-42"); model.setText("gemini-2.5-pro") }
        val status = views(activity.window.decorView).filterIsInstance<TextView>().single { it.tag == "project-generation-status" }
        val output = views(activity.window.decorView).filterIsInstance<TextView>().single { it.tag == "project-generation-reply" }
        val handle = ProjectGenerationWorkbench.attach(activity, button(activity, "Ask Jarvis / save instructions"),
            button(activity, "Cancel AI reply"), provider, key, model, field(activity, PROMPT), output, status,
            { target.selectedItemPosition }, { target }, { active.get(activity) as? String }, { active.set(activity, it) },
            ProjectGenerationStore(activity.getSharedPreferences("developer_local_projects_v1", android.content.Context.MODE_PRIVATE)),
            { button(activity, "Refresh saved projects").performClick() },
            ProjectGenerationWorkbench.Services(worker = { pending.add(it) }, ask = { _, _, _, task, _, context ->
                ask?.invoke(task, context) ?: "Fixture reply for $task"
            }, context = { task, product, _, _, _ -> source?.invoke(task, product) ?: RepositoryCodingContext.unavailable(task, product) }),
            forget = button(activity, "Forget coding discussion"))
        // Instance-local test transport: the real Activity destruction hook owns its cleanup.
        owner.set(activity, handle)
        return handle
    }

    @Test fun nativeFollowUpSurvivesNavigationAndCanBeForgottenAfterRecreation() {
        val project = "native-discussion-" + java.util.UUID.randomUUID()
        val pending = mutableListOf<() -> Unit>()
        val sent = mutableListOf<org.json.JSONObject>()
        val prefs = instrumentation.targetContext.getSharedPreferences("developer_local_projects_v1", android.content.Context.MODE_PRIVATE)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle(scenario)
                scenario.onActivity { activity ->
                    projectFixture(activity, project, pending, ask = { task, context ->
                        sent.add(org.json.JSONObject(context.data(task))); "Recorded discussion fixture reply"
                    })
                    views(activity.window.decorView).filterIsInstance<Spinner>()
                        .single { it.contentDescription == "Application to improve" }.setSelection(0)
                    field(activity, PROMPT).setText("Repair voice network latency")
                    button(activity, "Ask Jarvis / save instructions").performClick(); pending.removeAt(0).invoke()
                    button(activity, "Projects").performClick(); button(activity, "Home").performClick()
                    field(activity, PROMPT).setText("What about the tests?")
                    button(activity, "Ask Jarvis / save instructions").performClick()
                    assertFalse(button(activity, "Forget coding discussion").isEnabled)
                    pending.removeAt(0).invoke()
                    assertTrue(button(activity, "Forget coding discussion").isEnabled)
                    assertFalse(sent[0].has("priorDiscussion"))
                    assertEquals("Repair voice network latency", sent[1].getJSONObject("priorDiscussion")
                        .getJSONArray("turns").getJSONObject(0).getString("ownerRequest"))
                    assertEquals("unavailable", sent[1].getString("status")); assertFalse(sent[1].has("commitSha"))
                }
                scenario.recreate(); settle(scenario)
                scenario.onActivity { activity ->
                    assertEquals("What about the tests?", field(activity, PROMPT).text.toString())
                    button(activity, "Forget coding discussion").performClick()
                    val record = org.json.JSONObject(prefs.getString("project_$project", "{}")!!)
                    assertFalse(record.has("discussion")); assertFalse(record.has("approval"))
                    assertEquals("What about the tests?", record.getString("description"))
                    assertTrue(record.getString("response").contains("Recorded discussion fixture reply"))
                    assertFalse(record.toString().contains("native-provider-fixture-42"))
                    assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)
                }
            }
        } finally { prefs.edit().remove("project_$project").commit() }
    }
    @Test fun nativeHomeRetainsVerifiedReplyProvenanceAndLabelsInstructionsOnlyFallback() {
        val project = "native-context-" + java.util.UUID.randomUUID()
        val pending = mutableListOf<() -> Unit>()
        val prefs = instrumentation.targetContext.getSharedPreferences("developer_local_projects_v1", android.content.Context.MODE_PRIVATE)
        val commit = "a".repeat(40)
        val path = "developer/src/main/java/com/jarvis/developer/VoiceNetwork.kt"
        val source = "class VoiceNetwork { fun voiceLatency() = 5 }"
        val bytes = source.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-1").apply { update("blob ${bytes.size}\u0000".toByteArray()) }
        val blob = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        var available = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle(scenario)
                scenario.onActivity { activity ->
                    projectFixture(activity, project, pending, source = { task, target ->
                        if (!available) RepositoryCodingContext.unavailable(task, target) else {
                            val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, blob, bytes.size.toLong())), false, commit)
                            RepositoryCodingContext.fromAnalysis(task, target, inventory,
                                RepositoryAnalysis.analyze(task, inventory) { source }, mapOf(path to source), emptyList())
                        }
                    })
                    views(activity.window.decorView).filterIsInstance<Spinner>()
                        .single { it.contentDescription == "Application to improve" }.setSelection(0)
                    field(activity, PROMPT).setText("Repair voice network latency")
                    button(activity, "Ask Jarvis / save instructions").performClick(); pending.removeAt(0).invoke()
                    var record = org.json.JSONObject(prefs.getString("project_$project", "{}")!!)
                    assertTrue(record.getString("response").contains(commit)); assertTrue(record.getString("response").contains(path))
                    assertFalse(record.getString("response").contains("voiceLatency")); assertFalse(record.has("approval"))
                    assertFalse(record.toString().contains("native-provider-fixture-42"))
                    button(activity, "Projects").performClick(); button(activity, "Home").performClick()
                    val output = views(activity.window.decorView).filterIsInstance<TextView>().single { it.tag == "project-generation-reply" }
                    assertTrue(output.text.toString().contains(commit)); assertTrue(button(activity, "Ask Jarvis / save instructions").isEnabled)
                    available = false
                    button(activity, "Ask Jarvis / save instructions").performClick(); pending.removeAt(0).invoke()
                    record = org.json.JSONObject(prefs.getString("project_$project", "{}")!!)
                    assertTrue(record.getString("response").startsWith("Repository context unavailable."))
                    assertFalse(record.getString("response").contains(commit)); assertFalse(record.has("approval"))
                    assertTrue(pending.isEmpty())
                }
            }
        } finally { prefs.edit().remove("project_$project").commit() }
    }
    @Test fun nativeCancellationAndRetryKeepNewerInputsBusyAndIgnoreTheOldReply() {
        val project = "native-generation-" + java.util.UUID.randomUUID()
        val pending = mutableListOf<() -> Unit>()
        val prefs = instrumentation.targetContext.getSharedPreferences("developer_local_projects_v1", android.content.Context.MODE_PRIVATE)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle(scenario)
                scenario.onActivity { activity ->
                    projectFixture(activity, project, pending)
                    field(activity, PROMPT).setText("First native instructions")
                    button(activity, "Ask Jarvis / save instructions").performClick()
                    assertFalse(field(activity, PROMPT).isEnabled)
                    val started = SystemClock.elapsedRealtime()
                    button(activity, "Cancel AI reply").performClick()
                    println("JARVIS_PROJECT_CANCEL_CALLBACK_MS=" + (SystemClock.elapsedRealtime() - started))
                    assertTrue(field(activity, PROMPT).isEnabled)
                    field(activity, PROMPT).setText("New native instructions")
                    button(activity, "Ask Jarvis / save instructions").performClick()
                    pending.removeAt(0).invoke()
                    assertFalse(button(activity, "Ask Jarvis / save instructions").isEnabled)
                    assertTrue(button(activity, "Cancel AI reply").isEnabled)
                    pending.removeAt(0).invoke()
                    assertTrue(button(activity, "Ask Jarvis / save instructions").isEnabled)
                    assertFalse(button(activity, "Cancel AI reply").isEnabled)
                    assertEquals("gemini-2.5-pro", field(activity, "Model ID (blank = provider default)").text.toString())
                }
                val record = org.json.JSONObject(prefs.getString("project_$project", "{}")!!)
                assertEquals("New native instructions", record.getString("description"))
                assertTrue(record.getString("response").contains("Repository context unavailable"))
                assertTrue(record.getString("response").endsWith("Fixture reply for New native instructions"))
                assertEquals("generated", record.getString("state")); assertFalse(record.has("approval"))
            }
        } finally { prefs.edit().remove("project_$project").commit() }
    }
    @Test fun nativeActivityDestructionInterruptsGenerationWithoutRestoringCredentialsOrRetrying() {
        val project = "native-generation-" + java.util.UUID.randomUUID()
        val pending = mutableListOf<() -> Unit>()
        val prefs = instrumentation.targetContext.getSharedPreferences("developer_local_projects_v1", android.content.Context.MODE_PRIVATE)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle(scenario)
                scenario.onActivity { activity ->
                    projectFixture(activity, project, pending)
                    field(activity, PROMPT).setText("Keep these interrupted instructions")
                    button(activity, "Ask Jarvis / save instructions").performClick()
                }
                scenario.recreate(); settle(scenario)
                scenario.onActivity { activity ->
                    pending.removeAt(0).invoke()
                    assertTrue(field(activity, PROMPT).isEnabled)
                    assertEquals("Keep these interrupted instructions", field(activity, PROMPT).text.toString())
                    assertEquals("", field(activity, "Paste your API key here (not a model name)").text.toString())
                    assertTrue(pending.isEmpty()); assertFalse(button(activity, "Cancel AI reply").isEnabled)
                }
                val record = org.json.JSONObject(prefs.getString("project_$project", "{}")!!)
                assertEquals("generation_interrupted", record.getString("state"))
                assertEquals("Keep these interrupted instructions", record.getString("description"))
                assertFalse(record.has("generation_request")); assertFalse(record.toString().contains("native-provider-fixture-42"))
            }
        } finally { prefs.edit().remove("project_$project").commit() }
    }
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
            scenario.onActivity { val view = scroll(it); ready = it.hasWindowFocus() && view.isLaidOut && !view.isLayoutRequested && view.height > 0 }
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
