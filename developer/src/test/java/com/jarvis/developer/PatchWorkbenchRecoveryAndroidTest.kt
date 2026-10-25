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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual review widgets with explicit simulated provider/writer responses; no live credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PatchWorkbenchRecoveryAndroidTest {
    @get:Rule val temporary = TemporaryFolder()
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
        var inventories = 0
        var previewSourceReads = 0
        var afterInventory: (() -> Unit)? = null
        var afterProposal: (() -> Unit)? = null
        var writes = 0
        var inspections = 0
        var feedbacks = 0
        val feedbackTokens = ArrayList<PatchPreviewCancellation>()
        val followUpTokens = ArrayList<PatchPreviewCancellation>()
        var afterFeedback: (() -> Unit)? = null
        var afterFollowUp: (() -> Unit)? = null
        var feedbackFailure: BuildFeedback.Failure? = null
        var wrongFeedback = 0
        var followUpReads = 0
        var movedFollowUp = false
        var wrongFollowUp = false
        var failedBranch: String? = null
        var found = true
        var wrongOutcome = false
        var healthy = true
        val historyDirectory = java.io.File(temporary.newFolder(), "history")
        val handle: PatchWorkbench.Handle
        init {
            activity.setContentView(root)
            val provider = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
            }
            val services = PatchWorkbench.Services(
                inventory = { _, branch, target, _ ->
                    inventories++
                    assertEquals(PatchEngine.Target.PERSONAL, target)
                    afterInventory?.invoke()
                    if (branch == "main") inventory() else {
                        assertEquals(failedBranch, branch)
                        inventory(source.replace("false", "true"), created)
                    }
                },
                readSource = { _, inventory, selected, _ ->
                    previewSourceReads++
                    assertEquals(path, selected)
                    if (inventory.commitSha == base) source else { assertEquals(created, inventory.commitSha); source.replace("false", "true") }
                },
                propose = { _, request, _ ->
                    providers++
                    val before = request.sources.single { it.path == path }.content!!
                    val after = if (before.contains("false")) before.replace("false", "true")
                        else before.replace("enabled = true", "enabled = true; val recovered = true")
                    PatchEngine.parse(request, JSONObject().put("summary", "Improve the scoped example")
                        .put("edits", JSONArray().put(JSONObject().put("path", path)
                            .put("content", after))).toString()).also { afterProposal?.invoke() }
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
                feedback = { _, result, cancellation ->
                    feedbacks++
                    feedbackTokens += cancellation
                    afterFeedback?.invoke()
                    feedbackFailure?.let { throw it }
                    BuildDiagnostics.parse(if (wrongFeedback == 2) "e".repeat(40) else result.commitSha,
                        if (wrongFeedback == 1) "feature/jarvis-developer" else result.branch, 23, 1, "completed",
                        if (wrongFeedback == 3) "success" else "failure",
                        if (wrongFeedback == 3) "" else "e: $path:2:1 Unresolved reference: objective\n" +
                            (if (wrongFeedback == 4) "Could not GET dependency: HTTP 503\n" else ""),
                        if (wrongFeedback == 1) PatchEngine.Target.DEVELOPER else result.target)
                },
                followUpSource = { _, result, report, selected, cancellation ->
                    followUpReads++; failedBranch = result.branch
                    followUpTokens += cancellation
                    afterFollowUp?.invoke()
                    if (wrongFollowUp) PersonalRepair.prepare(PersonalRepair.Draft("Outstanding review marker", path, 2,
                        "source", base, "b".repeat(64)), "main", inventory(), source)
                    else {
                        val current = source.replace("false", "true")
                        PersonalRepair.prepareFollowUp(result, report, selected,
                            inventory(current, if (movedFollowUp) base else created), current)
                    }
                },
                history = RepairHistoryWorkbench.Services(directory = { historyDirectory },
                    feedback = { _, entry, _ ->
                        feedbacks++
                        BuildDiagnostics.parse(entry.commitSha, entry.branch, 23, 1, "completed", "failure", "", entry.target)
                    }, worker = { pending.add(it) }),
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
        fun inventory(text: String = source, revision: String = base) = RepositoryAnalysis.Inventory(
            listOf(RepositoryAnalysis.SourceFile(path, blob(text), text.toByteArray().size.toLong())), false, revision)
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun button(prefix: String) = views(root).filterIsInstance<Button>().single { it.text.startsWith(prefix) }
        val approval get() = views(root).filterIsInstance<CheckBox>().single()
        val followUpFile get() = views(root).filterIsInstance<Spinner>().single { it.contentDescription == "Failed Personal source file" }
        fun selectFollowUp() { followUpFile.setSelection(1); shadowOf(Looper.getMainLooper()).idle() }
        fun diagnose() {
            recover()
            button("Read exact task build").performClick(); finish()
        }
        fun recover() { interrupted(); button("Verify interrupted").performClick(); finish() }
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

    @Test fun cancellingQueuedTaskFeedbackPreservesTheCommitButRequiresAnExplicitFreshRead() {
        val h = Harness()
        try {
            h.recover()
            h.button("Read exact task build").performClick()
            h.button("Cancel task diagnostics").performClick()
            assertFalse(h.button("Cancel task diagnostics").isEnabled)
            assertFalse(h.button("Read exact task build").isEnabled)
            assertFalse(h.button("Generate scoped").isEnabled)
            h.button("Read exact task build").performClick(); assertEquals(1, h.pending.size)
            h.finish()
            assertEquals(0, h.feedbacks); assertTrue(h.status.text.contains("read cancelled"))
            assertFalse(h.followUpFile.isEnabled); assertFalse(h.button("Stage follow-up").isEnabled)
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            assertTrue(h.button("Read exact task build").isEnabled)
            h.button("Read exact task build").performClick(); h.finish()
            assertEquals(1, h.feedbacks); assertEquals(1, h.providers); assertEquals(1, h.writes)
            assertTrue(h.output.text.contains("Unresolved reference; check imports"))
            assertFalse(h.approval.isEnabled)
        } finally { h.close() }
    }

    @Test fun aCancelledLateBuildReportCannotRestoreFollowUpScopeOrApproval() {
        val h = Harness()
        try {
            h.recover(); h.afterFeedback = { h.button("Cancel task diagnostics").performClick() }
            h.button("Read exact task build").performClick(); h.finish()
            assertTrue(h.feedbackTokens.single().isCancelled)
            assertFalse(h.output.text.contains("Unresolved reference; check imports"))
            assertFalse(h.followUpFile.isEnabled); assertFalse(h.button("Stage follow-up").isEnabled)
            assertFalse(h.approval.isEnabled); assertEquals(1, h.feedbacks)
            assertEquals(1, h.providers); assertEquals(1, h.writes); assertTrue(h.pending.isEmpty())
            h.afterFeedback = null
            h.button("Read exact task build").performClick(); h.finish()
            assertEquals(2, h.feedbackTokens.size)
            assertNotSame(h.feedbackTokens[0], h.feedbackTokens[1]); assertFalse(h.feedbackTokens[1].isCancelled)
            assertTrue(h.followUpFile.isEnabled)
        } finally { h.close() }
    }

    @Test fun credentialModelAndTaskEditsCancelQueuedTaskFeedbackWithoutAnyReadOrRestoredApproval() {
        for (edit in listOf("credential", "model", "task")) {
            val h = Harness()
            try {
                h.recover(); h.button("Read exact task build").performClick()
                when (edit) {
                    "credential" -> h.token.setText("different-private-token")
                    "model" -> h.model.setText("gpt-4.1")
                    else -> h.task.setText("A different task")
                }
                assertFalse(h.button("Cancel task diagnostics").isEnabled)
                h.finish()
                assertEquals(0, h.feedbacks); assertTrue(h.status.text.contains("read cancelled"))
                assertFalse(h.output.text.contains("different-private-token"))
                assertFalse(h.followUpFile.isEnabled); assertFalse(h.approval.isEnabled)
                assertEquals(1, h.providers); assertEquals(1, h.writes)
            } finally { h.close() }
        }
    }

    @Test fun disposingTaskDiagnosticsPreventsBothReadKindsAndDiscardsTheirCallbacks() {
        for (followUp in listOf(false, true)) {
            val h = Harness()
            try {
                if (followUp) { h.diagnose(); h.selectFollowUp() } else h.recover()
                h.button(if (followUp) "Read selected failed" else "Read exact task build").performClick()
                h.handle.dispose(); h.handle.dispose()
                val before = h.output.text.toString()
                h.finish()
                assertEquals(before, h.output.text.toString())
                assertEquals(if (followUp) 1 else 0, h.feedbacks); assertEquals(0, h.followUpReads)
                assertFalse(h.approval.isEnabled); assertFalse(h.approval.isChecked)
                assertEquals(1, h.providers); assertEquals(1, h.writes)
            } finally { h.close() }
        }
    }

    @Test fun cancellingQueuedFailedSourcePreservesFeedbackAndRequiresAnotherReadBeforeStaging() {
        val h = Harness()
        try {
            h.diagnose(); h.selectFollowUp()
            h.button("Read selected failed").performClick(); h.button("Cancel task diagnostics").performClick()
            assertFalse(h.button("Read selected failed").isEnabled)
            assertFalse(h.button("Stage follow-up").isEnabled)
            h.finish()
            assertEquals(0, h.followUpReads); assertFalse(h.button("Stage follow-up").isEnabled)
            assertFalse(h.approval.isEnabled)
            h.button("Read selected failed").performClick(); h.finish()
            assertEquals(1, h.followUpReads); assertTrue(h.button("Stage follow-up").isEnabled)
            h.button("Stage follow-up").performClick(); h.preview()
            assertEquals(2, h.providers); assertEquals(1, h.writes)
            assertTrue(h.approval.isEnabled); assertFalse(h.approval.isChecked)
            h.button("Create task branch").performClick()
            assertTrue(h.pending.isEmpty()); assertEquals(1, h.writes)
        } finally { h.close() }
    }

    @Test fun cancelledLateFailedSourceCannotBeStagedOrReuseConsumedApproval() {
        val h = Harness()
        try {
            h.diagnose(); h.selectFollowUp()
            h.afterFollowUp = { h.button("Cancel task diagnostics").performClick() }
            h.button("Read selected failed").performClick(); h.finish()
            assertTrue(h.followUpTokens.single().isCancelled)
            assertFalse(h.button("Stage follow-up").isEnabled)
            assertTrue(h.status.text.contains("read cancelled"))
            assertFalse(h.approval.isEnabled); assertEquals(1, h.followUpReads)
            h.button("Stage follow-up").performClick()
            assertTrue(h.pending.isEmpty()); assertEquals(1, h.providers); assertEquals(1, h.writes)
        } finally { h.close() }
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    fun cancellingAfterTheWorkerPostsItsCallbackStillDiscardsBothKindsOfResult() {
        for (followUp in listOf(false, true)) {
            val h = Harness()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                if (followUp) { h.diagnose(); h.selectFollowUp() } else h.recover()
                h.button(if (followUp) "Read selected failed" else "Read exact task build").performClick()
                val operation = h.pending.removeFirst()
                executor.submit { operation() }.get(3, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue(h.button("Cancel task diagnostics").isEnabled)
                h.button("Cancel task diagnostics").performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(h.status.text.contains("read cancelled"))
                assertFalse(h.button("Stage follow-up").isEnabled); assertFalse(h.approval.isEnabled)
                assertFalse(h.output.text.contains("Unresolved reference; check imports"))
                assertTrue(h.pending.isEmpty()); assertEquals(1, h.providers); assertEquals(1, h.writes)
            } finally { executor.shutdownNow(); h.close() }
        }
    }

    @Test fun taskReadCancellationCannotStopOrRetryAClaimedWriteOrItsOutcomeCheck() {
        val h = Harness()
        try {
            h.preview(); h.approval.isChecked = true
            h.button("Create task branch").performClick()
            assertFalse(h.button("Cancel task diagnostics").isEnabled)
            h.button("Cancel task diagnostics").performClick(); h.finish()
            assertEquals(1, h.writes); assertFalse(h.approval.isChecked)
            assertTrue(h.button("Verify interrupted").isEnabled)
            h.button("Verify interrupted").performClick()
            assertFalse(h.button("Cancel task diagnostics").isEnabled)
            h.button("Cancel task diagnostics").performClick(); h.finish()
            assertEquals(1, h.inspections); assertEquals(1, h.writes); assertEquals(1, h.providers)
            assertTrue(h.output.text.contains("Approval remains consumed"))
            assertFalse(h.approval.isEnabled); assertTrue(h.pending.isEmpty())
        } finally { h.close() }
    }

    @Test fun cancelBeforeTheQueuedPreviewPreventsAllReadsProvidersAndWrites() {
        val h = Harness()
        try {
            h.button("Generate scoped").performClick()
            assertTrue(h.button("Cancel source").isEnabled)
            h.button("Cancel source").performClick()
            assertFalse(h.button("Cancel source").isEnabled)
            assertFalse(h.button("Generate scoped").isEnabled)
            assertEquals(1, h.pending.size)
            h.finish()
            assertEquals(0, h.inventories); assertEquals(0, h.providers); assertEquals(0, h.writes)
            assertFalse(h.approval.isEnabled); assertFalse(h.approval.isChecked)
            assertTrue(h.status.text.contains("Preview cancelled"))
            assertTrue(h.button("Generate scoped").isEnabled)
        } finally { h.close() }
    }

    @Test fun cancelAfterInventoryPreventsSourceAndProviderRequests() {
        val h = Harness()
        try {
            h.afterInventory = { h.button("Cancel source").performClick() }
            h.preview()
            assertEquals(1, h.inventories); assertEquals(0, h.previewSourceReads)
            assertEquals(0, h.providers); assertEquals(0, h.writes)
            assertFalse(h.approval.isEnabled)
        } finally { h.close() }
    }

    @Test fun cancelWhileTheProviderReturnsDiscardsItsDiffAndStillRequiresANewApproval() {
        val h = Harness()
        try {
            h.afterProposal = { h.button("Cancel source").performClick() }
            h.preview()
            assertEquals(1, h.providers); assertEquals(0, h.writes)
            assertFalse(h.approval.isEnabled); assertFalse(h.approval.isChecked)
            assertTrue(h.status.text.contains("Preview cancelled"))
            h.afterProposal = null
            h.preview()
            assertEquals(2, h.providers)
            assertTrue(h.approval.isEnabled); assertFalse(h.approval.isChecked)
            h.button("Create task branch").performClick(); assertTrue(h.pending.isEmpty())
            assertEquals(0, h.writes)
        } finally { h.close() }
    }

    @Test fun cancellationAfterTheWorkerPostsItsResultStillDiscardsTheLateMainThreadCallback() {
        val h = Harness()
        try {
            h.button("Generate scoped").performClick()
            val operation = h.pending.removeFirst()
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
            val completed = java.util.concurrent.CountDownLatch(1)
            Thread {
                try { operation() } catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }.start()
            assertTrue(completed.await(3, java.util.concurrent.TimeUnit.SECONDS)); assertNull(failure.get())
            h.button("Cancel source").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, h.providers); assertEquals(0, h.writes)
            assertFalse(h.approval.isEnabled); assertFalse(h.approval.isChecked)
            assertTrue(h.status.text.contains("Preview cancelled"))
        } finally { h.close() }
    }

    @Test fun changedInputsCancelQueuedProviderWorkAndDestroyedViewsCannotRestoreAReview() {
        val h = Harness()
        h.button("Generate scoped").performClick()
        h.model.setText("gpt-4.1")
        h.finish()
        assertEquals(0, h.inventories); assertEquals(0, h.providers)
        h.button("Generate scoped").performClick()
        h.handle.cancelPreview(); h.close(); h.finish()
        assertEquals(0, h.inventories); assertEquals(0, h.providers); assertEquals(0, h.writes)
        assertFalse(h.approval.isChecked)
    }

    @Test fun previewCancellationIsUnavailableForAnApprovedCommitAndCannotPretendItWasUndone() {
        val h = Harness()
        try {
            h.preview(); h.approval.isChecked = true
            h.button("Create task branch").performClick()
            assertFalse(h.button("Cancel source").isEnabled)
            h.handle.cancelPreview()
            h.finish()
            assertEquals(1, h.writes)
            assertTrue(h.button("Verify interrupted").isEnabled)
            assertFalse(h.button("Check CI").isEnabled)
            assertFalse(h.approval.isChecked)
        } finally { h.close() }
    }

    @Test fun disposingTheCodingWorkbenchCancelsItsHistoryReadBeforeAnyRequest() {
        val h = Harness()
        try {
            val entry = RepairHistoryStore.Bookmark(PatchEngine.Target.PERSONAL,
                PatchEngine.taskBranch("saved", "b".repeat(64), PatchEngine.Target.PERSONAL), created)
            RepairHistoryStore(h.historyDirectory).save(entry)
            h.button("Load private repair history").performClick(); h.finish()
            val choice = h.views(h.root).filterIsInstance<Spinner>().single { it.contentDescription == "Saved repair commit bookmark" }
            choice.setSelection(1); shadowOf(Looper.getMainLooper()).idle()
            h.button("Recheck selected saved").performClick()
            h.handle.dispose(); h.close(); h.finish()
            assertEquals(0, h.feedbacks); assertEquals(0, h.providers); assertEquals(0, h.writes)
            assertFalse(h.approval.isChecked)
            assertEquals(listOf(entry), RepairHistoryStore(h.historyDirectory).load())
        } finally { h.close() }
    }

    @Test fun savedHistoryInvalidatesNewerApprovalWithoutRestoringTrustedCommitOrFollowUpScope() {
        val h = Harness()
        try {
            RepairHistoryStore(h.historyDirectory).save(RepairHistoryStore.Bookmark(PatchEngine.Target.PERSONAL,
                PatchEngine.taskBranch("saved", "b".repeat(64), PatchEngine.Target.PERSONAL), created))
            h.preview(); h.approval.isChecked = true
            h.button("Load private repair history").performClick()
            assertFalse(h.approval.isChecked); assertFalse(h.button("Create task branch").isEnabled)
            h.finish()
            val choice = h.views(h.root).filterIsInstance<Spinner>().single { it.contentDescription == "Saved repair commit bookmark" }
            choice.setSelection(1); shadowOf(Looper.getMainLooper()).idle()
            h.button("Recheck selected saved").performClick(); h.finish()
            assertEquals(1, h.feedbacks); assertEquals(0, h.writes); assertEquals(1, h.providers)
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            assertFalse(h.button("Check CI").isEnabled)
            assertFalse(h.button("Read exact task build").isEnabled)
            assertFalse(h.button("Read selected failed").isEnabled)
            assertFalse(h.button("Stage follow-up").isEnabled)
            h.button("Save current verified").performClick()
            assertTrue(h.pending.isEmpty())
        } finally { h.close() }
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

    @Test fun personalFailureDetailsAreExplicitReadOnlyAndCannotRestoreApprovalOrInventTestSuccess() {
        val h = Harness()
        try {
            h.interrupted(); h.button("Verify interrupted").performClick(); h.finish()
            assertEquals(0, h.feedbacks)
            h.button("Read exact task build").performClick(); h.finish()
            assertEquals(1, h.feedbacks); assertEquals(1, h.providers); assertEquals(1, h.writes)
            assertTrue(h.output.text.contains("Product: Personal Jarvis"))
            assertTrue(h.output.text.contains("Unresolved reference; check imports"))
            assertTrue(h.output.text.contains("No complete JUnit summary"))
            assertTrue(h.output.text.contains("Evidence: incomplete"))
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            assertTrue(h.pending.isEmpty())
        } finally { h.close() }
    }

    @Test fun typedFeedbackFailureReplacesOldOutputAndConsumesNewerApprovalWithoutQueuingARepair() {
        val h = Harness()
        try {
            h.interrupted(); h.button("Verify interrupted").performClick(); h.finish()
            h.button("Read exact task build").performClick(); h.finish()
            assertTrue(h.output.text.contains("Unresolved reference; check imports"))
            h.model.setText("gpt-4.1"); h.preview(); h.approval.isChecked = true
            h.feedbackFailure = BuildFeedback.Failure(BuildFeedback.Stage.JOB_METADATA, BuildFeedback.Problem.HTTP, 403).apply {
                initCause(IllegalStateException("private token and signed server address"))
            }
            h.button("Read exact task build").performClick(); h.finish()
            assertTrue(h.output.text.contains("reading the job inventory")); assertTrue(h.output.text.contains("HTTP 403"))
            assertFalse(h.output.text.contains("Unresolved reference; check imports"))
            assertFalse(h.output.text.contains("private token"))
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            assertFalse(h.button("Read selected failed").isEnabled)
            assertFalse(h.button("Stage follow-up").isEnabled)
            assertEquals(1, h.writes); assertEquals(2, h.providers)
        } finally { h.close() }
    }

    @Test fun feedbackForAnotherProductOrCommitCannotBeDisplayedAsTheTaskDiagnosis() {
        for (wrong in listOf(1, 2)) {
            val h = Harness()
            try {
                h.interrupted(); h.button("Verify interrupted").performClick(); h.finish()
                h.wrongFeedback = wrong
                h.button("Read exact task build").performClick(); h.finish()
                assertTrue(h.status.text.contains("remain unverified"))
                assertFalse(h.output.text.contains("Unresolved reference; check imports"))
                assertFalse(h.approval.isEnabled); assertEquals(1, h.writes)
            } finally { h.close() }
        }
    }

    @Test fun feedbackConsumesNewerPreviewApprovalAndDestroyedViewsDiscardItsCallback() {
        val h = Harness()
        try {
            h.interrupted(); h.button("Verify interrupted").performClick(); h.finish()
            h.model.setText("gpt-4.1"); h.preview(); h.approval.isChecked = true
            assertTrue(h.button("Create task branch").isEnabled)
            h.button("Read exact task build").performClick()
            assertFalse(h.approval.isChecked); assertFalse(h.approval.isEnabled)
            h.close(); h.finish()
            assertFalse(h.output.text.contains("Unresolved reference; check imports"))
            assertEquals(1, h.writes)
        } finally { h.close() }
    }

    @Test fun failedPersonalSourceNeedsExplicitSelectionReadStageAndANewDiffApproval() {
        val h = Harness()
        try {
            h.diagnose()
            assertEquals(0, h.followUpReads); assertFalse(h.button("Read selected failed").isEnabled)
            h.selectFollowUp(); assertEquals(0, h.followUpReads)
            h.button("Read selected failed").performClick(); h.finish()
            assertEquals(1, h.followUpReads); assertTrue(h.button("Stage follow-up").isEnabled)
            assertFalse(h.approval.isEnabled); assertEquals(1, h.providers); assertEquals(1, h.writes)
            h.button("Stage follow-up").performClick()
            assertFalse(h.button("Stage follow-up").isEnabled); assertFalse(h.approval.isChecked)
            assertTrue(h.task.text.contains("Follow-up for reported failed Personal CI run"))
            h.preview()
            assertEquals(2, h.providers); assertEquals(1, h.writes)
            assertTrue(h.approval.isEnabled); assertFalse(h.approval.isChecked)
            h.button("Create task branch").performClick()
            assertTrue(h.pending.isEmpty()); assertEquals(1, h.writes)
        } finally { h.close() }
    }

    @Test fun movedOrWrongFollowUpSourceCannotBeStaged() {
        for (moved in listOf(true, false)) {
            val h = Harness()
            try {
                h.diagnose(); h.selectFollowUp(); h.movedFollowUp = moved; h.wrongFollowUp = !moved
                h.button("Read selected failed").performClick(); h.finish()
                assertFalse(h.button("Stage follow-up").isEnabled)
                assertTrue(h.status.text.contains("could not be verified"))
                assertFalse(h.approval.isEnabled); assertEquals(1, h.providers); assertEquals(1, h.writes)
            } finally { h.close() }
        }
    }

    @Test fun changedInputsAndDestroyedActivitiesDiscardQueuedFollowUpReads() {
        val h = Harness()
        try {
            h.diagnose(); h.selectFollowUp(); h.button("Read selected failed").performClick()
            h.model.setText("gpt-4.1"); h.finish()
            assertFalse(h.button("Stage follow-up").isEnabled)
            assertTrue(h.status.text.contains("read cancelled")); assertEquals(0, h.followUpReads)
            h.button("Read selected failed").performClick(); h.close(); h.finish()
            assertFalse(h.button("Stage follow-up").isEnabled); assertEquals(1, h.writes)
        } finally { h.close() }
    }

    @Test fun recheckingFeedbackOrCiDiscardsTheOldFollowUpReview() {
        val h = Harness()
        try {
            h.diagnose(); h.selectFollowUp(); h.button("Read selected failed").performClick(); h.finish()
            assertTrue(h.button("Stage follow-up").isEnabled)
            h.button("Read exact task build").performClick()
            assertFalse(h.button("Stage follow-up").isEnabled); h.finish()
            h.selectFollowUp(); h.button("Read selected failed").performClick(); h.finish()
            assertTrue(h.button("Stage follow-up").isEnabled)
            h.button("Check CI").performClick()
            assertFalse(h.button("Stage follow-up").isEnabled); h.finish()
            assertFalse(h.followUpFile.isEnabled); assertFalse(h.approval.isChecked)
        } finally { h.close() }
    }

    @Test fun passingAndInfrastructureFeedbackCannotQueueAFollowUpSourceRead() {
        for (state in listOf(3, 4)) {
            val h = Harness()
            try {
                h.wrongFeedback = state; h.diagnose()
                assertFalse(h.followUpFile.isEnabled); assertFalse(h.button("Read selected failed").isEnabled)
                h.button("Read selected failed").performClick()
                assertTrue(h.pending.isEmpty()); assertEquals(0, h.followUpReads); assertEquals(1, h.writes)
            } finally { h.close() }
        }
    }

    @Test fun emptyFollowUpSelectorCallbackDoesNotInvalidateAnUnrelatedReviewedDiff() {
        val h = Harness()
        try {
            h.preview(); h.approval.isChecked = true
            assertTrue(h.button("Create task branch").isEnabled)
            h.followUpFile.onItemSelectedListener!!.onNothingSelected(h.followUpFile)
            assertTrue(h.approval.isChecked)
            assertTrue(h.button("Create task branch").isEnabled)
            assertEquals(0, h.writes)
        } finally { h.close() }
    }

    private fun blob(source: String): String {
        val bytes = source.toByteArray(); val hash = MessageDigest.getInstance("SHA-1")
        hash.update("blob ${bytes.size}\u0000".toByteArray())
        return hash.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
