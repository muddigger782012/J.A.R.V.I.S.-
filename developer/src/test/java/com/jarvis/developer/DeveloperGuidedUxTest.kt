package com.jarvis.developer

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeveloperGuidedUxTest {
    private var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null
    @Before fun resetLocalProjects() {
        org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("developer_local_projects_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { controller?.pause()?.stop()?.destroy() }
    private fun start(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle(); return controller!!.get()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(activity: MainActivity) = DeveloperUiState.descendants(activity.window.decorView).toList()
    private fun button(activity: MainActivity, label: String) = views(activity).filterIsInstance<Button>().single {
        it.text.toString().removeSuffix("  ▼").removeSuffix("  ▲") == label
    }
    private fun field(activity: MainActivity, hint: String) = views(activity).filterIsInstance<EditText>().single { it.hint.toString() == hint }
    private fun page(activity: MainActivity) = views(activity).filterIsInstance<ScrollView>().single { it.visibility == View.VISIBLE }.tag

    @Test fun fourTabsAdvancedToolsAndBackRetainDraftsWithoutRestoringApproval() {
        val activity = start()
        val description = "Repair the saved weather date selection"
        field(activity, PROMPT).setText(description)
        button(activity, "Settings").performClick(); button(activity, "Advanced tools").performClick(); idle()
        assertEquals("developer-page-4", page(activity))
        assertTrue(button(activity, "Settings").isSelected)
        assertEquals(1f, button(activity, "Settings").alpha, 0f)
        assertTrue(button(activity, "Settings").contentDescription.toString().endsWith(", selected"))
        button(activity, "Connections and source browsing").performClick()
        field(activity, SOURCE).setText("developer/src/main/java/com/jarvis/developer/RepositoryAnalysis.kt")
        activity.onBackPressed(); idle() // close category
        activity.onBackPressed(); idle() // back to Settings
        assertEquals("developer-page-3", page(activity))
        activity.onBackPressed(); idle()
        assertEquals("developer-page-0", page(activity))
        button(activity, "Review progress").performClick(); idle()
        assertEquals("developer-page-2", page(activity))
        assertFalse(button(activity, "Create task branch and commit approved files (writes)").isEnabled)
        assertFalse(views(activity).filterIsInstance<CheckBox>().single { it.text.toString().startsWith("I reviewed this exact diff") }.isChecked)
        assertEquals(description, field(activity, PROMPT).text.toString())
        assertTrue(views(activity).filterIsInstance<Button>().none { it.text.toString() == "Tools" })
    }

    @Test fun projectRestorationRetainsTheChosenAppAndInstructionsWithoutAnAiKey() {
        val activity = start()
        val target = views(activity).filterIsInstance<Spinner>().single { it.contentDescription == "Application to improve" }
        target.setSelection(1); idle()
        field(activity, PROMPT).setText("Improve my offline memory")
        button(activity, "Ask Jarvis / save instructions").performClick(); idle()
        target.setSelection(0); idle()
        button(activity, "Projects").performClick()
        views(activity).filterIsInstance<Button>().single { it.text.toString().startsWith("Improve my offline memory (") }.performClick()
        idle()
        assertEquals(1, target.selectedItemPosition)
        assertEquals("Improve my offline memory", field(activity, PROMPT).text.toString())
        assertEquals("developer-page-0", page(activity))
    }

    @Test fun serializedLifecycleRestoresInstructionScopeAndLocationButNoCredentialsOrAuthority() {
        val activity = start()
        field(activity, PROMPT).setText("Improve offline reasoning")
        field(activity, SOURCE).setText("developer/src/main/java/com/jarvis/developer/RepositoryAnalysis.kt")
        field(activity, "Paste your API key here (not a model name)").setText("fixture-never-persist")
        button(activity, "Projects").performClick(); idle()
        controller!!.recreate(); idle()
        val restored = controller!!.get()
        assertEquals("developer-page-1", page(restored))
        assertEquals("Improve offline reasoning", field(restored, PROMPT).text.toString())
        assertTrue(field(restored, SOURCE).text.toString().endsWith("RepositoryAnalysis.kt"))
        assertEquals("", field(restored, "Paste your API key here (not a model name)").text.toString())
        assertFalse(button(restored, "Create task branch and commit approved files (writes)").isEnabled)
    }

    @Test fun sourceValidationStaysNextToActionAndDoesNotReplaceHomeConversation() {
        val activity = start()
        button(activity, "Settings").performClick(); button(activity, "Advanced tools").performClick()
        button(activity, "Connections and source browsing").performClick()
        button(activity, "Analyze task and preview file plan (read-only)").performClick(); idle()
        val feedback = views(activity).filterIsInstance<TextView>().single { it.text.toString() == "Describe a task on Home before analyzing it here." }
        assertTrue(DeveloperUiState.descendants(views(activity).single { it.tag == "developer-page-4" }).any { it === feedback })
        assertTrue(views(activity).filterIsInstance<TextView>().any { it.text.toString() == "Your assistant’s response will appear here." })
    }

    @Test fun connectionEvidenceRejectsChangedKeyModelProviderAndOutOfOrderCallbacks() {
        val state = ConnectionVerification()
        state.select("Gemini", "fixture-key", "gemini-2.5-flash")
        val first = state.begin()
        assertTrue(state.finish(first, true, "Gemini connection successful."))
        assertEquals(ConnectionVerification.State.CONNECTED, state.state)
        state.select("Gemini", "fixture-key", "gemini-2.5-flash")
        assertEquals(ConnectionVerification.State.CONNECTED, state.state) // save same connection
        val stale = state.begin()
        state.select("Gemini", "changed-fixture", "gemini-2.5-flash")
        assertFalse(state.finish(stale, true, "stale"))
        assertEquals(ConnectionVerification.State.UNTESTED, state.state)
        val latest = state.begin()
        assertTrue(state.finish(latest, false, "Provider denied access."))
        assertEquals(ConnectionVerification.State.FAILED, state.state)
        state.select("Gemini", "changed-fixture", "gemini-2.5-pro")
        assertEquals(ConnectionVerification.State.UNTESTED, state.state)
        state.select("OpenAI", "changed-fixture", "")
        assertEquals(ConnectionVerification.State.UNTESTED, state.state)
    }

    @Test fun successfulConnectionTestUpdatesHomeAndSettingsAndEditsInvalidateBoth() {
        val activity = start()
        field(activity, "Paste your API key here (not a model name)").setText("fixture-not-a-real-key")
        val evidence = activity.connectionVerification
        val ticket = evidence.begin()
        assertTrue(evidence.finish(ticket, true, "Gemini connection successful."))
        assertEquals(2, views(activity).filterIsInstance<TextView>().count { it.text.toString() == "Gemini connection successful." })
        field(activity, "Model ID (blank = provider default)").setText("gemini-2.5-pro")
        assertEquals(ConnectionVerification.State.UNTESTED, evidence.state)
        assertTrue(views(activity).filterIsInstance<TextView>().none { it.text.toString() == "Gemini connection successful." })
        assertFalse(evidence.finish(ticket, true, "stale successful connection"))
    }

    @Test fun guidedAnalysisSuggestsPermittedScopeAndBlocksProtectedSourcesWithoutWrites() {
        val activity = start()
        val home = LinearLayout(activity)
        val task = EditText(activity).apply { setText("Improve parsing") }
        var staged: List<String>? = null
        var opened = false
        var mayInvalidate = true
        val patch = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { mayInvalidate },
            guidedAction = { _, _, paths, _, _ -> staged = paths; true })
        val ordinary = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
        val protected = "developer/src/main/java/com/jarvis/developer/MainActivity.kt"
        val files = listOf(ordinary, protected).map { path -> RepositoryAnalysis.PlannedFile(
            RepositoryAnalysis.SourceFile(path, "a".repeat(40), 20), listOf("Task reference"), "Inspect logic", "Review required", emptyList(), emptyList(), emptyList()) }
        GuidedDevelopmentWorkbench.attach(activity, home, task, patch, 0, { opened = true },
            GuidedDevelopmentWorkbench.Services(analyze = { description, _, _ ->
                RepositoryAnalysis.AnalysisPlan(description, "b".repeat(40), files, emptyList(), listOf(ordinary, protected), 2)
            }, worker = { it() }))
        fun action(text: String) = DeveloperUiState.descendants(home).filterIsInstance<Button>().single { it.text.toString() == text }
        action("Analyze my request (read-only)").performClick(); idle()
        val choices = DeveloperUiState.descendants(home).filterIsInstance<CheckBox>().toList()
        assertTrue(choices.single { it.tag == ordinary }.isChecked)
        assertFalse(choices.single { it.tag == protected }.isEnabled)
        assertNull(staged)
        action("Review suggested changes in Activity").performClick(); idle()
        assertEquals(listOf(ordinary), staged); assertTrue(opened)
        task.setText("Different request")
        assertFalse(action("Review suggested changes in Activity").isEnabled)
        mayInvalidate = false
        val target = DeveloperUiState.descendants(home).filterIsInstance<Spinner>().single()
        target.setSelection(1); idle()
        assertEquals("Busy operations retain their reviewed product context", 0, target.selectedItemPosition)
    }

    @Test fun cancelledGuidedAnalysisCannotRepopulateReviewFromLateCallback() {
        val activity = start()
        val home = LinearLayout(activity)
        val task = EditText(activity).apply { setText("Inspect parsing") }
        var worker: (() -> Unit)? = null
        val patch = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { true })
        GuidedDevelopmentWorkbench.attach(activity, home, task, patch, 0, {},
            GuidedDevelopmentWorkbench.Services(analyze = { text, _, _ ->
                RepositoryAnalysis.AnalysisPlan(text, "b".repeat(40), emptyList(), emptyList(), emptyList(), 0)
            }, worker = { worker = it }))
        fun action(text: String) = DeveloperUiState.descendants(home).filterIsInstance<Button>().single { it.text.toString() == text }
        action("Analyze my request (read-only)").performClick()
        action("Cancel analysis").performClick()
        worker!!(); idle()
        assertFalse(action("Review suggested changes in Activity").isEnabled)
        assertTrue(DeveloperUiState.descendants(home).filterIsInstance<TextView>().any { it.text.toString() == "Analysis cancelled. Nothing was approved or changed." })
    }

    @Test fun personalGuidedAnalysisStagesOnePermittedProductionFileWithItsPinnedSource() {
        val activity = start()
        val panel = LinearLayout(activity)
        val task = EditText(activity).apply { setText("Correct forecast date selection") }
        val first = "app/src/main/java/com/assistant/core/services/ForecastDates.kt"
        val second = "app/src/main/java/com/assistant/core/services/WeatherLocation.kt"
        val excluded = listOf("app/src/main/java/com/assistant/core/MainActivity.kt",
            "app/src/test/java/com/assistant/core/services/ForecastDatesTest.kt",
            "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt")
        val base = "b".repeat(40)
        var analyzed: PatchEngine.Target? = null
        var staged: Triple<List<String>, String, PatchEngine.Target>? = null
        var reviews = 0
        val patch = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { true },
            guidedAction = { _, description, paths, source, target ->
                assertEquals(task.text.toString(), description)
                staged = Triple(paths, source, target); true
            })
        GuidedDevelopmentWorkbench.attach(activity, panel, task, patch, 1, { reviews++ },
            GuidedDevelopmentWorkbench.Services(analyze = { description, product, cancellation ->
                cancellation.check(); analyzed = product
                val files = (listOf(first, second) + excluded).map { path -> RepositoryAnalysis.PlannedFile(
                    RepositoryAnalysis.SourceFile(path, "a".repeat(40), 20), listOf("Task reference"),
                    "Inspect logic", "Review required", emptyList(), emptyList(), emptyList()) }
                RepositoryAnalysis.AnalysisPlan(description, base, files, emptyList(), listOf(first, second), 5)
            }, worker = { it() }))
        fun action(text: String) = DeveloperUiState.descendants(panel).filterIsInstance<Button>().single { it.text.toString() == text }
        idle(); action("Analyze my request (read-only)").performClick(); idle()
        assertEquals(PatchEngine.Target.PERSONAL, analyzed)
        val choices = DeveloperUiState.descendants(panel).filterIsInstance<CheckBox>().toList()
        assertEquals(listOf(first), choices.filter { it.isChecked }.map { it.tag })
        excluded.forEach { path -> assertFalse(choices.single { it.tag == path }.isEnabled) }
        choices.single { it.tag == second }.isChecked = true
        assertEquals(listOf(second), choices.filter { it.isChecked }.map { it.tag })
        assertNull(staged)
        action("Review suggested changes in Activity").performClick(); idle()
        assertEquals(Triple(listOf(second), base, PatchEngine.Target.PERSONAL), staged)
        assertEquals(1, reviews)
    }

    @Test fun changingApplicationsDuringSourceAnalysisCancelsItsReadAndRejectsTheOldScope() {
        val activity = start()
        val panel = LinearLayout(activity)
        val task = EditText(activity).apply { setText("Inspect source parsing") }
        lateinit var target: Spinner
        var read: PatchPreviewCancellation? = null
        var staged = false
        val patch = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { true },
            guidedAction = { _, _, _, _, _ -> staged = true; true })
        target = GuidedDevelopmentWorkbench.attach(activity, panel, task, patch, 0, {},
            GuidedDevelopmentWorkbench.Services(analyze = { description, product, cancellation ->
                assertEquals(PatchEngine.Target.DEVELOPER, product)
                read = cancellation
                target.setSelection(1); idle()
                // A provider can return even after cancellation; the UI must still reject it.
                val path = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
                val file = RepositoryAnalysis.PlannedFile(RepositoryAnalysis.SourceFile(path, "a".repeat(40), 20),
                    emptyList(), "Inspect logic", "Review required", emptyList(), emptyList(), emptyList())
                RepositoryAnalysis.AnalysisPlan(description, "b".repeat(40), listOf(file), emptyList(), listOf(path), 1)
            }, worker = { it() })).first
        fun action(text: String) = DeveloperUiState.descendants(panel).filterIsInstance<Button>().single { it.text.toString() == text }
        idle(); action("Analyze my request (read-only)").performClick(); idle()
        assertTrue(read!!.isCancelled)
        assertEquals(1, target.selectedItemPosition)
        assertTrue(DeveloperUiState.descendants(panel).filterIsInstance<CheckBox>().none())
        assertFalse(action("Review suggested changes in Activity").isEnabled)
        assertFalse(staged)
    }

    companion object {
        const val PROMPT = "Example: Build me an app to track equipment maintenance."
        const val SOURCE = "Source path or directory prefix (blank lists all allowed sources)"
    }
}
