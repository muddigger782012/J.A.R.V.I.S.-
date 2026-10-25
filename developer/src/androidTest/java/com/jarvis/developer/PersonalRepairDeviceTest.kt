package com.jarvis.developer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Looper
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

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
        RepairHistoryStore(File(context.noBackupFilesDir.canonicalFile, "developer-repair-history")).clear()
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

    @Test fun savedHistorySurvivesRecreationWithoutRestoringSourceScopeOrApproval() {
        val directory = File(context.noBackupFilesDir.canonicalFile, "developer-repair-history")
        val target = PatchEngine.Target.PERSONAL
        val entry = RepairHistoryStore.Bookmark(target,
            PatchEngine.taskBranch("modeled-history", "b".repeat(64), target), "d".repeat(40))
        RepairHistoryStore(directory).save(entry)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                openTools(activity); noApproval(activity)
                assertFalse(button(activity, "Recheck selected saved build diagnostics (GET only)").isEnabled)
                button(activity, "Load private repair history (local only)").performClick()
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity -> loaded = views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.startsWith("Loaded 1 private commit bookmarks.") } }
                if (!loaded) SystemClock.sleep(50)
            }
            assertTrue("Local history load must complete", loaded)
            scenario.onActivity { activity ->
                val choice = views(activity.window.decorView).filterIsInstance<Spinner>()
                    .single { it.contentDescription == "Saved repair commit bookmark" }
                assertEquals(2, choice.count); assertEquals(0, choice.selectedItemPosition)
                choice.setSelection(1)
                noApproval(activity)
                assertFalse(button(activity, "Check CI for this exact task commit (read-only)").isEnabled)
                assertFalse(button(activity, "Stage follow-up for a NEW exact diff review").isEnabled)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(button(it, "Recheck selected saved build diagnostics (GET only)").isEnabled) }
            scenario.recreate()
            scenario.onActivity { activity ->
                noApproval(activity)
                assertFalse(button(activity, "Recheck selected saved build diagnostics (GET only)").isEnabled)
                button(activity, "Save current verified task commit bookmark").performClick()
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.text.startsWith("No verified task commit is available") })
            }
        }
        assertEquals(listOf(entry), RepairHistoryStore(directory).load())
    }

    @Test fun modeledInterruptedRepairRequiresApprovalAndRecoversWithoutAnotherWrite() {
        var providerCalls = 0
        var writes = 0
        var inspections = 0
        var feedbackCalls = 0
        var sourceReads = 0
        var queueReads = false
        val pendingReads = java.util.ArrayDeque<() -> Unit>()
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
                val failedSource = source.replace("false", "true")
                val failedBytes = failedSource.toByteArray(); val failedHash = MessageDigest.getInstance("SHA-1")
                failedHash.update("blob ${failedBytes.size}\u0000".toByteArray())
                val failedBlob = failedHash.digest(failedBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                val failedInventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(file,
                    failedBlob, failedBytes.size.toLong())), false, "d".repeat(40))
                var failedBranch: String? = null
                val output = TextView(activity)
                val status = TextView(activity)
                val provider = Spinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
                }
                val services = PatchWorkbench.Services(
                    inventory = { _, branch, target, _ ->
                        assertEquals(PatchEngine.Target.PERSONAL, target)
                        if (branch == "main") inventory else { assertEquals(failedBranch, branch); failedInventory }
                    },
                    readSource = { _, snapshot, selected, _ ->
                        assertEquals(file, selected)
                        if (snapshot.commitSha == sha) source else failedSource
                    },
                    propose = { _, request, _ ->
                        providerCalls++
                        val before = request.sources.single().content!!
                        val after = if (before.contains("false")) before.replace("false", "true")
                            else before.replace("enabled = true", "enabled = true; val recovered = true")
                        PatchEngine.parse(request, JSONObject().put("summary", "Improve the example")
                            .put("edits", JSONArray().put(JSONObject().put("path", file)
                                .put("content", after))).toString())
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
                    feedback = { _, result, _ ->
                        feedbackCalls++
                        BuildDiagnostics.parse(result.commitSha, result.branch, 23, 1, "completed", "failure",
                            "e: $file:2:1 Unresolved reference: objective\n", result.target)
                    },
                    followUpSource = { _, result, report, selected, _ ->
                        sourceReads++; failedBranch = result.branch
                        PersonalRepair.prepareFollowUp(result, report, selected, failedInventory, failedSource)
                    },
                    worker = { if (queueReads) pendingReads.add(it) else it() }
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
                queueReads = true
                button(activity, "Read exact task build diagnostics (GET only)").performClick()
                assertTrue(button(activity, "Cancel task diagnostics / source read").isEnabled)
                button(activity, "Cancel task diagnostics / source read").performClick()
                assertFalse(button(activity, "Read exact task build diagnostics (GET only)").isEnabled)
                assertFalse(button(activity, "Cancel task diagnostics / source read").isEnabled)
                pendingReads.removeFirst().invoke()
                assertEquals(0, feedbackCalls)
                assertTrue(status.text.contains("read cancelled"))
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
                queueReads = false
                button(activity, "Read exact task build diagnostics (GET only)").performClick()
                assertEquals(1, feedbackCalls); assertEquals(1, providerCalls); assertEquals(1, writes)
                assertTrue(output.text.contains("Product: Personal Jarvis"))
                assertTrue(output.text.contains("Unresolved reference; check imports"))
                assertTrue(output.text.contains("Evidence: incomplete"))
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
                assertEquals(0, sourceReads)
                views(root).filterIsInstance<Spinner>().single { it.contentDescription == "Failed Personal source file" }.setSelection(1)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val approval = views(activity.window.decorView).filterIsInstance<CheckBox>().single()
                queueReads = true
                button(activity, "Read selected failed Personal source (GET only)").performClick()
                button(activity, "Cancel task diagnostics / source read").performClick()
                assertFalse(button(activity, "Read selected failed Personal source (GET only)").isEnabled)
                assertFalse(button(activity, "Stage follow-up for a NEW exact diff review").isEnabled)
                pendingReads.removeFirst().invoke()
                assertEquals(0, sourceReads)
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
                queueReads = false
                button(activity, "Read selected failed Personal source (GET only)").performClick()
                assertEquals(1, sourceReads); assertEquals(1, providerCalls); assertEquals(1, writes)
                assertTrue(button(activity, "Stage follow-up for a NEW exact diff review").isEnabled)
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
                button(activity, "Stage follow-up for a NEW exact diff review").performClick()
                assertEquals(1, providerCalls); assertEquals(1, writes)
                assertFalse(approval.isChecked); assertFalse(approval.isEnabled)
                button(activity, "Generate scoped multi-file diff preview").performClick()
                assertEquals(2, providerCalls); assertEquals(1, writes)
                assertTrue(approval.isEnabled); assertFalse(approval.isChecked)
                button(activity, "Create task branch and commit approved files (writes)").performClick()
                assertEquals(1, writes)
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

    @Test fun previewCancellationStopsQueuedRequestsAndRestoresNoWriteApproval() {
        var reads = 0
        var providers = 0
        var writes = 0
        val pending = java.util.ArrayDeque<() -> Unit>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                activity.setContentView(root)
                val provider = Spinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
                }
                val status = TextView(activity)
                val services = PatchWorkbench.Services(
                    inventory = { _, _, _, _ -> reads++; error("Cancelled read must never start") },
                    readSource = { _, _, _, _ -> reads++; error("Cancelled source request must never start") },
                    propose = { _, _, _ -> providers++; error("Cancelled provider request must never start") },
                    commit = { _, _ -> writes++; error("Unapproved write must never start") },
                    worker = { pending.add(it) }
                )
                PatchWorkbench.attach(activity, root, EditText(activity).apply { setText("Review the source summary") },
                    EditText(activity).apply { setText("simulated-provider-key") }, provider,
                    EditText(activity).apply { setText("gpt-4.1-mini") }, EditText(activity),
                    TextView(activity), status, { true }, services)
                views(root).filterIsInstance<EditText>().single {
                    it.hint?.startsWith("Exact allowed source paths") == true
                }.setText("developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt")
                button(activity, "Generate scoped multi-file diff preview").performClick()
                assertTrue(button(activity, "Cancel source / proposal preview").isEnabled)
                button(activity, "Cancel source / proposal preview").performClick()
                assertEquals(1, pending.size)
                assertFalse(button(activity, "Cancel source / proposal preview").isEnabled)
                assertFalse(button(activity, "Generate scoped multi-file diff preview").isEnabled)
                pending.removeFirst().invoke()
                assertTrue(status.text.startsWith("Preview cancelled"))
                assertTrue(button(activity, "Generate scoped multi-file diff preview").isEnabled)
                noApproval(activity)
            }
            scenario.recreate()
            scenario.onActivity { noApproval(it) }
        }
        assertEquals(0, reads); assertEquals(0, providers); assertEquals(0, writes)
    }

    @Test fun buildReadFailureShowsSafeGuidanceAndCannotStartOrStageARepair() {
        var requests = 0
        var bodyReads = 0
        var closed = 0
        var stages = 0
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                activity.setContentView(root)
                val output = TextView(activity)
                val status = TextView(activity)
                val transport = BuildFeedback.Transport { url, _, _ ->
                    requests++
                    BuildFeedback.Response(403, 100, url, object : java.io.InputStream() {
                        override fun read(): Int {
                            bodyReads++
                            error("private token and signed server address must remain unread")
                        }
                    }) { closed++ }
                }
                val patches = PatchWorkbench.Handle(prepareAction = { _, _, _, _, _ -> stages++; false },
                    repairAction = { _, _ -> stages++; false }, invalidateAction = { true })
                RepairWorkbench.attach(activity, root, EditText(activity), EditText(activity), output, status, patches,
                    RepairWorkbench.Services(read = { token, branch, sha, run ->
                        BuildFeedback.Client(transport).read(token, branch, sha, run)
                    }, worker = { it() }))
                views(root).filterIsInstance<EditText>().single {
                    it.hint?.startsWith("Successful baseline: exact") == true
                }.setText("a".repeat(40))
                views(root).filterIsInstance<EditText>().single {
                    it.hint?.startsWith("Optional baseline workflow") == true
                }.setText("17")
                button(activity, "Read successful baseline CI and regression expectations (GET only)").performClick()
                assertTrue(status.text.contains("reading workflow metadata"))
                assertTrue(status.text.contains("HTTP 403")); assertTrue(status.text.contains("Check access"))
                assertFalse(output.text.contains("private token"))
                assertFalse(button(activity, "Start NEW session: reset to 2 attempts and a 30-minute budget").isEnabled)
                assertFalse(button(activity, "Reserve ONE repair attempt and stage a NEW diff review").isEnabled)
            }
            scenario.recreate()
            scenario.onActivity { noApproval(it) }
        }
        assertEquals(1, requests); assertEquals(0, bodyReads); assertEquals(1, closed); assertEquals(0, stages)
    }
    @Test fun cancellingASavedBuildReadClosesItsBlockedStreamAndDiscardsTheResult() {
        val directory = File(context.noBackupFilesDir.canonicalFile, "developer-repair-history")
        val target = PatchEngine.Target.PERSONAL
        val entry = RepairHistoryStore.Bookmark(target,
            PatchEngine.taskBranch("cancelled-history", "b".repeat(64), target), "d".repeat(40))
        RepairHistoryStore(directory).save(entry)
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val disconnects = AtomicInteger()
        val closedOnMain = AtomicBoolean()
        var operation: (() -> Unit)? = null
        val pending = java.util.ArrayDeque<() -> Unit>()
        var worker: Thread? = null
        var dispose: (() -> Unit)? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    activity.setContentView(root)
                    val transport = BuildFeedback.Transport { url, _, _ ->
                        requests.incrementAndGet()
                        BuildFeedback.Response(200, -1, url, object : java.io.InputStream() {
                            override fun read(): Int = error("Buffered read expected")
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                                reading.countDown()
                                check(closed.await(5, TimeUnit.SECONDS))
                                check(release.await(5, TimeUnit.SECONDS))
                                throw java.io.IOException("private transport detail")
                            }
                            override fun close() {
                                closedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                                closed.countDown()
                            }
                        }) { disconnects.incrementAndGet() }
                    }
                    dispose = RepairHistoryWorkbench.attach(activity, root, EditText(activity), { null }, { true }, {},
                        RepairHistoryWorkbench.Services(directory = { directory }, feedback = { token, bookmark, cancellation ->
                            BuildFeedback.Client(transport, cancellation).read(token, bookmark.branch, bookmark.commitSha, 17, bookmark.target)
                        }, worker = { pending.add(it) }))
                    button(activity, "Load private repair history (local only)").performClick()
                    pending.removeFirst().invoke()
                    views(root).filterIsInstance<Spinner>().single().setSelection(1)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(button(activity, "Recheck selected saved build diagnostics (GET only)").isEnabled)
                    button(activity, "Recheck selected saved build diagnostics (GET only)").performClick()
                    operation = pending.removeFirst()
                }
                worker = Thread { operation!!.invoke() }.apply { start() }
                assertTrue("Reader must reach the blocked stream", reading.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertTrue(button(activity, "Cancel saved build read").isEnabled)
                    button(activity, "Cancel saved build read").performClick()
                    assertFalse(button(activity, "Cancel saved build read").isEnabled)
                    assertFalse(button(activity, "Recheck selected saved build diagnostics (GET only)").isEnabled)
                }
                assertTrue("Cancellation must close the stream off the UI thread", closed.await(5, TimeUnit.SECONDS))
                assertFalse(closedOnMain.get())
                release.countDown(); worker!!.join(5_000); assertFalse(worker!!.isAlive)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                    assertTrue(labels.any { it.startsWith("Saved build read cancelled.") })
                    assertFalse(labels.any { it.contains("private transport detail") || it.contains("Build URL:") })
                    assertTrue(button(activity, "Recheck selected saved build diagnostics (GET only)").isEnabled)
                    dispose!!.invoke()
                }
                scenario.recreate()
                scenario.onActivity { noApproval(it) }
            }
        } finally { release.countDown(); closed.countDown(); worker?.join(5_000) }
        assertEquals(1, requests.get()); assertEquals(1, disconnects.get())
        assertEquals(listOf(entry), RepairHistoryStore(directory).load())
    }

    companion object {
        private const val READ = "Read Personal diagnostic source (GET only)"
        private const val STAGE = "Stage Personal source for a NEW exact diff review"
    }
}
