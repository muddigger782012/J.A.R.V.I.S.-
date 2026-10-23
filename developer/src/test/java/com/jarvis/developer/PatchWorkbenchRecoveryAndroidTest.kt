package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.security.MessageDigest
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

/** Actual review widgets with explicit simulated provider/writer responses; no live credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PatchWorkbenchRecoveryAndroidTest {
    private val base = "a".repeat(40)
    private val created = "d".repeat(40)
    private inner class Harness {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val task = EditText(activity).apply { setText("Improve the example status") }
        val token = EditText(activity).apply { setText("simulated-github-token") }
        val model = EditText(activity).apply { setText("gpt-4.1-mini") }
        val status = TextView(activity)
        val output = TextView(activity)
        val pending = ArrayDeque<() -> Unit>()
        val path = "app/src/main/java/com/assistant/core/services/PersonalityExample.kt"
        val source = "package com.assistant.core.services\nobject PersonalityExample { val enabled = false }\n"
        var providers = 0
        var writes = 0
        var inspections = 0
        var found = true
        var wrongOutcome = false
        var healthy = true
        val handle: PatchWorkbench.Handle
        init {
            activity.setContentView(root)
            val provider = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
            }
            val services = PatchWorkbench.Services(
                inventory = { _, branch, target ->
                    assertEquals("main", branch); assertEquals(PatchEngine.Target.PERSONAL, target)
                    inventory()
                },
                readSource = { _, inventory, selected -> assertEquals(base, inventory.commitSha); assertEquals(path, selected); source },
                propose = { _, request ->
                    providers++
                    PatchEngine.parse(request, JSONObject().put("summary", "Improve the scoped example")
                        .put("edits", JSONArray().put(JSONObject().put("path", path)
                            .put("content", source.replace("false", "true")))).toString())
                },
                commit = { _, approved ->
                    PatchEngine.validateApproval(approved); writes++
                    throw IllegalStateException("Simulated lost response; private transport content")
                },
                inspectOutcome = { _, proposal ->
                    inspections++
                    if (!found) null else proposal.request.let { request ->
                        PatchPublisher.CommitResult(request.taskId, request.provider,
                            if (wrongOutcome) "different-model" else request.model, proposal.id,
                            proposal.edits.map { it.path }.sorted(), base, created,
                            PatchEngine.branchName(proposal), proposal.diff, request.baseBranch, request.target)
                    }
                },
                ci = { _, _ -> error("CI must remain an explicit later action") },
                worker = { pending.add(it) }
            )
            handle = PatchWorkbench.attach(activity, root, task,
                EditText(activity).apply { setText("simulated-provider-key") }, provider,
                model, token, output, status, { healthy }, services)
            val draft = PersonalRepair.parse("Review a static diagnostic for muddigger782012/J.A.R.V.I.S.-.\n" +
                "Review signal: Outstanding review marker.\nSource reference: $path:2 (source).\n" +
                "Claimed revision: $base. Source inventory: ${"b".repeat(64)}.\n")
            assertTrue(handle.preparePersonal(PersonalRepair.prepare(draft, "main", inventory(), source)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun inventory() = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, blob(source), source.toByteArray().size.toLong())), false, base)
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun button(prefix: String) = views(root).filterIsInstance<Button>().single { it.text.startsWith(prefix) }
        val approval get() = views(root).filterIsInstance<CheckBox>().single()
        fun finish() { pending.removeFirst().invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun preview() { button("Generate scoped").performClick(); finish() }
        fun interrupted() {
            preview(); assertTrue(approval.isEnabled)
            approval.isChecked = true; button("Create task branch").performClick(); finish()
            assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
            assertTrue(button("Verify interrupted").isEnabled)
            assertFalse(status.text.contains("private transport"))
        }
        fun close() { if (!activity.isDestroyed) controller.pause().stop().destroy() }
    }

    @Test fun explicitDiffApprovalIsConsumedAndRecoveryNeverRepeatsProviderOrWriter() {
        val h = Harness()
        try {
            assertFalse(h.button("Verify interrupted").isEnabled)
            h.button("Create task branch").performClick(); assertTrue(h.pending.isEmpty())
            h.interrupted()
            assertEquals(1, h.providers); assertEquals(1, h.writes); assertEquals(0, h.inspections)
            h.button("Verify interrupted").performClick(); h.finish()
            assertEquals(1, h.providers); assertEquals(1, h.writes); assertEquals(1, h.inspections)
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            assertFalse(h.button("Create task branch").isEnabled)
            assertTrue(h.button("Check CI").isEnabled)
            assertTrue(h.output.text.contains("CI not checked. Approval remains consumed."))
            h.preview()
            assertFalse(h.approval.isEnabled)
            assertTrue(h.status.text.contains("consumed approval"))
            assertEquals(1, h.writes)
        } finally { h.close() }
    }

    @Test fun missingOrMismatchedOutcomeCannotRestoreApprovalOrEnableCi() {
        for (missing in listOf(true, false)) {
            val h = Harness()
            try {
                h.interrupted(); h.found = !missing; h.wrongOutcome = !missing
                h.button("Verify interrupted").performClick(); h.finish()
                assertFalse(h.approval.isEnabled); assertFalse(h.button("Check CI").isEnabled)
                assertFalse(h.button("Create task branch").isEnabled)
                assertTrue(h.status.text.contains(if (missing) "not observed" else "unverified"))
                assertEquals(1, h.writes)
            } finally { h.close() }
        }
    }

    @Test fun checkingAnOlderInterruptedAttemptInvalidatesANewerDiffApproval() {
        val h = Harness()
        try {
            h.interrupted(); h.model.setText("gpt-4.1")
            h.preview(); assertTrue(h.approval.isEnabled); h.approval.isChecked = true
            assertTrue(h.button("Create task branch").isEnabled)
            h.button("Verify interrupted").performClick()
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            h.finish()
            assertTrue(h.output.text.contains("gpt-4.1-mini"))
            assertFalse(h.approval.isChecked); assertFalse(h.button("Create task branch").isEnabled)
            assertEquals(2, h.providers); assertEquals(1, h.writes); assertEquals(1, h.inspections)
        } finally { h.close() }
    }

    @Test fun destroyedActivitiesDiscardRecoveryCallbacksAndFreshViewsRestoreNoApproval() {
        val h = Harness()
        h.interrupted(); h.button("Verify interrupted").performClick(); h.close(); h.finish()
        val fresh = Harness()
        try {
            assertFalse(fresh.approval.isEnabled); assertFalse(fresh.button("Verify interrupted").isEnabled)
            assertTrue(fresh.pending.isEmpty()); assertEquals(0, fresh.writes)
        } finally { fresh.close() }
    }

    @Test fun healthAndMissingTokenChecksDoNotDispatchAWriteOrOutcomeRequest() {
        val h = Harness()
        try {
            h.preview(); h.approval.isChecked = true; h.healthy = false
            h.button("Create task branch").performClick()
            assertTrue(h.pending.isEmpty()); assertEquals(0, h.writes)
            h.healthy = true; h.button("Create task branch").performClick(); h.finish()
            h.token.setText(""); h.button("Verify interrupted").performClick()
            assertTrue(h.pending.isEmpty()); assertEquals(0, h.inspections); assertEquals(1, h.writes)
        } finally { h.close() }
    }

    private fun blob(source: String): String {
        val bytes = source.toByteArray(); val hash = MessageDigest.getInstance("SHA-1")
        hash.update("blob ${bytes.size}\u0000".toByteArray())
        return hash.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
