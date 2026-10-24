package com.jarvis.developer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Disposable CI emulator: public source/CI GETs plus a modeled repair/recovery interaction. No live writer/key. */
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

    @Test fun modeledInterruptedRepairRequiresApprovalAndRecoversWithoutAnotherWrite() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                activity.setContentView(root)
                val file = "app/src/main/java/com/assistant/core/services/PersonalityExample.kt"
                val source = "package com.assistant.core.services\nobject PersonalityExample { val enabled = false }\n"
                val bytes = source.toByteArray(); val hash = MessageDigest.getInstance("SHA-1")
                hash.update("blob ${bytes.size}\u0000".toByteArray())
                val blob = hash.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                val sha = "a".repeat(40)
                val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(file, blob, bytes.size.toLong())), false, sha)
                var providerCalls = 0
                var writes = 0
                var inspections = 0
                var feedbackCalls = 0
                val output = TextView(activity)
                val status = TextView(activity)
                val provider = Spinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
                }
                val services = PatchWorkbench.Services(
                    inventory = { _, branch, target ->
                        assertEquals("main", branch); assertEquals(PatchEngine.Target.PERSONAL, target); inventory
                    },
                    readSource = { _, _, selected -> assertEquals(file, selected); source },
                    propose = { _, request ->
                        providerCalls++
                        PatchEngine.parse(request, JSONObject().put("summary", "Improve the example")
                            .put("edits", JSONArray().put(JSONObject().put("path", file)
                                .put("content", source.replace("false", "true")))).toString())
                    },
                    commit = { _, approved ->
                        PatchEngine.validateApproval(approved); writes++
                        throw IllegalStateException("Simulated interrupted response")
                    },
                    inspectOutcome = { _, proposal ->
                        inspections++
                        proposal.request.let { request ->
                            PatchPublisher.CommitResult(request.taskId, request.provider, request.model, proposal.id,
                                proposal.edits.map { it.path }.sorted(), sha, "d".repeat(40),
                                PatchEngine.branchName(proposal), proposal.diff, request.baseBranch, request.target)
                        }
                    },
                    ci = { _, _ -> error("CI must be explicitly requested later") },
                    feedback = { _, result ->
                        feedbackCalls++
                        BuildDiagnostics.parse(result.commitSha, result.branch, 23, 1, "completed", "failure",
                            "e: $file:2:1 Unresolved reference: objective\n", result.target)
                    },
                    worker = { it() }
                )
                val handle = PatchWorkbench.attach(activity, root, EditText(activity),
                    EditText(activity).apply { setText("simulated-provider-key") }, provider,
                    EditText(activity).apply { setText("gpt-4.1-mini") },
                    EditText(activity).apply { setText("simulated-github-token") }, output, status, { true }, services)
                val draft = PersonalRepair.parse(share(file).replace("unspecified", sha))
                assertTrue(handle.preparePersonal(PersonalRepair.prepare(draft, "main", inventory, source)))
                val approval = views(root).filterIsInstance<CheckBox>().single()
                assertEquals(0, providerCalls); assertEquals(0, writes); assertEquals(0, inspections)
                button(activity, "Generate scoped multi-file diff preview").performClick()
                assertTrue(approval.isEnabled); assertFalse(approval.isChecked)
                button(activity, "Create task branch and commit approved files (writes)").performClick()
                assertEquals(0, writes)
                approval.isChecked = true
                button(activity, "Create task branch and commit approved files (writes)").performClick()
                assertEquals(1, writes); assertFalse(approval.isEnabled); assertFalse(approval.isChecked)
                button(activity, "Verify interrupted commit outcome (read-only)").performClick()
                assertEquals(1, providerCalls); assertEquals(1, writes); assertEquals(1, inspections)
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
                assertTrue(button(activity, "Check CI for this exact task commit (read-only)").isEnabled)
                assertTrue(output.text.contains("CI not checked. Approval remains consumed."))
                assertEquals(0, feedbackCalls)
                button(activity, "Read exact task build diagnostics (GET only)").performClick()
                assertEquals(1, feedbackCalls); assertEquals(1, providerCalls); assertEquals(1, writes)
                assertTrue(output.text.contains("Product: Personal Jarvis"))
                assertTrue(output.text.contains("Unresolved reference; check imports"))
                assertTrue(output.text.contains("Evidence: incomplete"))
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
            }
        }
    }

    @Test fun publicPersonalCiMetadataIsVerifiedWithoutInventingCompleteTestEvidence() {
        // An immutable completed Personal run; public metadata may outlive protected/expired logs.
        val sha = "ede9c4c8c0498e9bec15a6f09bd409600d6be79a"
        val report = BuildFeedback.read("", "feature/self-diagnostics-phase5-readonly", sha,
            39978603584L, PatchEngine.Target.PERSONAL)
        assertEquals(sha, report.commitSha)
        assertEquals(39978603584L, report.runId)
        assertEquals(PatchEngine.Target.PERSONAL, report.target)
        assertEquals("completed", report.status)
        assertEquals("success", report.conclusion)
        // This Personal workflow has no source-bound JUnit inventory. Never upgrade its metadata.
        assertFalse(report.evidenceComplete)
        assertTrue(report.limitations.any { it.contains("No complete JUnit summary") })
        assertTrue(BuildDiagnostics.render(report).contains("Personal feedback is read-only"))
    }
    companion object {
        private const val READ = "Read Personal diagnostic source (GET only)"
        private const val STAGE = "Stage Personal source for a NEW exact diff review"
    }
}
