package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RepairWorkbenchAndroidTest {
    private val sha = "a".repeat(40)
    private fun baseline(): BuildDiagnostics.Report {
        fun counts() = JSONObject().put("tests", 1).put("failures", 0).put("errors", 0).put("skipped", 0)
        val summary = JSONObject().put("sourceCommit", sha).put("totals", counts())
            .put("suites", JSONArray().put(counts().put("name", "FixtureSuite").put("sha256", "b".repeat(64))))
            .put("cases", JSONArray().put(JSONObject().put("suite", "FixtureSuite").put("name", "case").put("result", "passed")))
        return BuildDiagnostics.parse(sha, "feature/jarvis-developer", 17, 1, "completed", "success", "JARVIS_JUNIT_SUMMARY=$summary")
    }

    private inner class Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity)
        val output = TextView(activity)
        val status = TextView(activity)
        val pending = ArrayDeque<() -> Unit>()
        var reads = 0
        var stages = 0
        var failure: BuildFeedback.Failure? = BuildFeedback.Failure(BuildFeedback.Stage.JOB_METADATA, BuildFeedback.Problem.HTTP, 403)
        init {
            activity.setContentView(root)
            val patches = PatchWorkbench.Handle(prepareAction = { _, _, _, _, _ -> stages++; false },
                repairAction = { _, _ -> stages++; false }, invalidateAction = { true })
            RepairWorkbench.attach(activity, root, EditText(activity).apply { setText("Fix the bounded summary") },
                EditText(activity), output, status, patches, RepairWorkbench.Services(
                    read = { _, branch, source, run ->
                        reads++; assertEquals("feature/jarvis-developer", branch); assertEquals(sha, source)
                        assertTrue(run == 17L || run == 18L)
                        failure?.let { throw it }
                        if (run == 17L) baseline() else BuildDiagnostics.parse(sha, branch, 18, 1, "completed", "failure",
                            "e: developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt:1:1 Unresolved reference: broken\n")
                    }, worker = { pending.add(it) }))
            field("Successful baseline: exact").setText(sha)
            field("Optional baseline workflow").setText("17")
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun field(hint: String) = views(root).filterIsInstance<EditText>().single { it.hint?.startsWith(hint) == true }
        fun button(prefix: String) = views(root).filterIsInstance<Button>().single { it.text.startsWith(prefix) }
        fun finish() { pending.removeFirst().invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun close() { if (!activity.isDestroyed) controller.pause().stop().destroy() }
    }

    @Test fun typedReadFailureShowsActionWithoutCreatingABaselineSessionRetryOrSourceStage() {
        val h = Harness()
        try {
            h.failure!!.initCause(IllegalStateException("private token and signed storage address"))
            assertTrue(h.pending.isEmpty())
            h.button("Read successful baseline").performClick(); h.finish()
            assertTrue(h.output.text.contains("reading the job inventory"))
            assertTrue(h.status.text.contains("HTTP 403")); assertTrue(h.status.text.contains("Check access"))
            assertFalse(h.output.text.contains("private token"))
            assertFalse(h.button("Start NEW session").isEnabled)
            assertFalse(h.button("Reserve ONE repair").isEnabled)
            assertEquals(1, h.reads); assertEquals(0, h.stages)
        } finally { h.close() }
    }

    @Test fun failedRereadClearsPreviouslySuccessfulBaselineAndItsDisplayedEvidence() {
        val h = Harness()
        try {
            h.failure = null
            h.button("Read successful baseline").performClick(); h.finish()
            assertTrue(h.status.text.startsWith("Complete successful baseline retained"))
            assertTrue(h.button("Read exact failing").isEnabled)
            h.failure = BuildFeedback.Failure(BuildFeedback.Stage.WORKFLOW_METADATA, BuildFeedback.Problem.TIMEOUT)
            h.button("Read successful baseline").performClick(); h.finish()
            assertTrue(h.output.text.contains("network request timed out"))
            assertFalse(h.output.text.contains("Evidence: complete"))
            assertFalse(h.button("Read exact failing").isEnabled)
            assertFalse(h.button("Start NEW session").isEnabled)
            assertEquals(2, h.reads); assertEquals(0, h.stages)
        } finally { h.close() }
    }

    @Test fun activeReadPinsInputsAndDestroyedActivityDiscardsQueuedFailureCallbacks() {
        val h = Harness()
        try {
            h.button("Read successful baseline").performClick()
            h.field("Successful baseline: exact").setText("b".repeat(40))
            assertEquals(sha, h.field("Successful baseline: exact").text.toString())
            assertTrue(h.status.text.contains("Finish the active operation"))
            h.finish()
            assertTrue(h.status.text.contains("reading the job inventory"))
            assertTrue(h.output.text.contains("HTTP 403"))
            assertEquals(0, h.stages)
        } finally { h.close() }
        val destroyed = Harness()
        destroyed.button("Read successful baseline").performClick()
        destroyed.close(); val before = destroyed.output.text.toString()
        destroyed.finish()
        assertEquals(before, destroyed.output.text.toString())
        assertEquals(0, destroyed.stages)
    }

    @Test fun freshVerifiedReadRecoversTheExistingSessionWithoutResettingItsAttemptBudget() {
        val h = Harness()
        try {
            h.failure = null
            h.field("Repair allowlist:").setText("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt")
            h.button("Read successful baseline").performClick(); h.finish()
            h.field("Failing / latest repair: exact").setText(sha)
            h.field("Optional failing / latest workflow").setText("18")
            h.button("Read exact failing").performClick(); h.finish()
            h.button("Start NEW session").performClick()
            assertTrue(h.status.text.startsWith("New explicit repair session started"))
            assertTrue(h.button("Reserve ONE repair").isEnabled)
            h.button("Reserve ONE repair").performClick()
            assertTrue(h.views(h.root).filterIsInstance<TextView>().any { it.text.contains("Budget: 1/2;") })
            assertEquals(1, h.stages)
            h.failure = BuildFeedback.Failure(BuildFeedback.Stage.WORKFLOW_METADATA, BuildFeedback.Problem.TIMEOUT)
            h.button("Read exact failing").performClick(); h.finish()
            assertFalse(h.button("Reserve ONE repair").isEnabled)
            h.failure = null
            h.button("Read exact failing").performClick(); h.finish()
            assertTrue(h.button("Reserve ONE repair").isEnabled)
            assertTrue(h.views(h.root).filterIsInstance<TextView>().any { it.text.contains("Budget: 1/2;") })
            assertEquals(4, h.reads); assertEquals(1, h.stages)
        } finally { h.close() }
    }
}
