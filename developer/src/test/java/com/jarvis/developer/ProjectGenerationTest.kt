package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.widget.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectGenerationTest {
    private fun preferences() = org.robolectric.RuntimeEnvironment.getApplication()
        .getSharedPreferences("generation-fixture-" + UUID.randomUUID(), Context.MODE_PRIVATE)
    private inner class Screen(val prefs: android.content.SharedPreferences = preferences(),
        launchFails: Boolean = false, failure: Exception? = null,
        progressRead: (() -> String)? = null,
        ask: ((String, String, String, String, PatchPreviewCancellation) -> String)? = null,
        repair: (() -> Boolean)? = null,
        buildCheck: (() -> Boolean)? = null,
        save: (() -> Boolean)? = null,
        readSaved: (() -> Boolean)? = null,
        approve: ((String) -> Boolean)? = null,
        showDiff: (() -> String?)? = null,
        nextPlan: (() -> DeveloperPlanContinuation.Suggestion)? = null,
        stagePlan: ((DeveloperPlanContinuation.Packet) -> Boolean)? = null,
        planAnalysis: DeveloperPlanAnalysisReview = DeveloperPlanAnalysisReview(),
        completion: DeveloperPlanCompletionReview = DeveloperPlanCompletionReview(),
        savedCompletion: DeveloperSavedCompletionReview = DeveloperSavedCompletionReview(),
        checkedCi: ((String, PatchPublisher.CommitResult) -> PatchPublisher.CiStatus)? = null,
        currentCommit: (() -> PatchPublisher.CommitResult?)? = null,
        githubCredential: String = "",
        canReadSavedCompletion: () -> Boolean = { true },
        releaseInspect: ((String) -> DeveloperReleaseControl.Candidate)? = null,
        releasePost: ((String, String) -> DeveloperReleaseControl.Candidate)? = null) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val send = Button(activity); val cancel = Button(activity)
        val provider = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Gemini", "OpenAI")) }
        val target = Spinner(activity).apply { adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Developer", "Personal")) }
        val key = EditText(activity).apply { setText("runtime-provider-key-42") }
        val model = EditText(activity).apply { setText("gemini-2.5-pro") }
        val prompt = EditText(activity).apply { setText("First request") }
        val output = TextView(activity).apply { text = "Previous display" }
        val status = TextView(activity)
        val pending = mutableListOf<() -> Unit>()
        var selected: String? = "project-a"
        val store = ProjectGenerationStore(prefs)
        val handle = ProjectGenerationWorkbench.attach(activity, send, cancel, provider, key, model, prompt, output, status,
            { target.selectedItemPosition }, { target }, { selected }, { selected = it }, store, {},
            ProjectGenerationWorkbench.Services(worker = { if (launchFails) error("private launch fixture") else pending.add(it) },
                ask = { providerName, apiKey, modelName, task, cancellation, _ ->
                    ask?.invoke(providerName, apiKey, modelName, task, cancellation)
                        ?: if (failure != null) throw failure else "Reply for $task"
                }, context = { task, product, _, _, _ -> RepositoryCodingContext.unavailable(task, product) },
                progress = { _ -> progressRead?.invoke() ?: "Verified local progress fixture" },
                releaseReadiness = { token -> releaseInspect?.invoke(token)
                    ?: error("Unexpected live release-readiness request") },
                releaseDispatch = { token, sha -> releasePost?.invoke(token, sha)
                    ?: error("Unexpected live release publication request") },
                nextPlan = { _ -> nextPlan?.invoke() ?: DeveloperPlanContinuation.Suggestion("No saved plans") },
                planAnalysis = planAnalysis,
                completion = completion,
                savedCompletion = savedCompletion,
                ci = { token, commit -> checkedCi?.invoke(token, commit)
                    ?: PatchPublisher.CiStatus(PatchPublisher.CiState.MISSING) }),
            requestDeveloperRepair = { repair?.invoke() ?: false },
            requestDeveloperBuild = { buildCheck?.invoke() ?: false },
            saveDeveloperTask = { save?.invoke() ?: false },
            readSavedDeveloperBuild = { readSaved?.invoke() ?: false },
            approveDeveloperDiff = { prefix -> approve?.invoke(prefix) ?: false },
            showDeveloperDiff = { showDiff?.invoke() },
            continueDeveloperPlan = { id, task, paths, base ->
                stagePlan?.invoke(DeveloperPlanContinuation.Packet(id, task, paths, base)) ?: false
            }, currentDeveloperCommit = { currentCommit?.invoke() },
            githubToken = { githubCredential },
            mayReadSavedCompletion = canReadSavedCompletion)
        fun complete() { pending.removeAt(0).invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun record() = JSONObject(prefs.getString("project_project-a", "{}")!!)
        override fun close() { handle.dispose(); controller.pause().stop().destroy() }
    }
    @Test fun releaseApprovalIsExactOneUseAndNeverInvokesModelOrSourceWriter() {
        val sha = "a".repeat(40)
        val candidate = DeveloperReleaseControl.Candidate(sha, 1234L)
        var checked = 0
        var dispatched = 0
        Screen(githubCredential = "github_pat_test_fixture_12345678",
            releaseInspect = { token ->
                assertTrue(token.startsWith("github_pat_"))
                checked++; candidate
            }, releasePost = { token, exact ->
                assertTrue(token.startsWith("github_pat_"))
                assertEquals(sha, exact)
                dispatched++; candidate
            }, ask = { _, _, _, _, _ -> error("Release commands must not use the coding provider") }).use { s ->
            s.prompt.setText("Publish Developer update $sha")
            s.send.performClick()
            assertEquals(0, checked); assertEquals(0, dispatched)
            assertTrue(s.pending.isEmpty())
            assertTrue(s.output.text.contains("fresh passing exact-commit check"))

            s.prompt.setText("Check Developer release readiness")
            s.send.performClick()
            assertEquals(1, s.pending.size)
            assertFalse(s.send.isEnabled)
            assertEquals(0, dispatched)
            s.complete()
            assertEquals(1, checked)
            assertTrue(s.output.text.contains(sha))
            assertTrue(s.output.text.contains("No publication"))
            assertTrue(s.send.isEnabled)

            s.prompt.setText("Publish Developer update $sha")
            s.send.performClick()
            assertEquals(1, s.pending.size)
            s.complete()
            assertEquals(1, dispatched)
            assertTrue(s.output.text.contains("workflow request"))
            assertTrue(s.output.text.contains("NOT installed"))
            assertTrue(s.prefs.all.isEmpty())

            s.send.performClick() // Explicit publication request cannot be reused.
            assertEquals(1, dispatched)
            assertTrue(s.output.text.contains("fresh passing exact-commit check"))
        }
    }

    @Test fun releaseCheckCannotPublishWhenTargetIsPersonalOrGitHubMissing() {
        var checks = 0
        Screen(githubCredential = "", releaseInspect = { checks++; error("Must not run") }).use { s ->
            s.prompt.setText("Check Developer release readiness")
            s.send.performClick()
            assertTrue(s.output.text.contains("Connect GitHub"))
            assertTrue(s.pending.isEmpty())
            s.target.setSelection(1)
            s.send.performClick()
            assertTrue(s.output.text.contains("unavailable for the Personal"))
            assertEquals(0, checks)
        }
    }

    @Test fun savedCompletionCannotStartDuringBusyOrUncertainCodingState() {
        val f = DeveloperSavedCompletionReviewTest.createFixture()
        var readCount = 0
        val saved = DeveloperSavedCompletionReview({ f.history }, { f.plans },
            { _, _, _ -> readCount++; f.report }, { 1003L })
        Screen(savedCompletion = saved, currentCommit = { null },
            githubCredential = "test-only-github-token",
            canReadSavedCompletion = { false }).use { s ->
            s.prompt.setText("Review Developer completion")
            s.send.performClick()
            assertTrue(s.pending.isEmpty())
            assertEquals(0, readCount)
            assertTrue(s.output.text.contains("uncertain commit"))
            assertEquals(TaskPlanner.Status.PENDING,
                f.store.loadAll().single().steps.last().status)
        }
    }

    @Test fun savedCommitCanReenterFinalReviewFromChatAfterRestartWithoutRestoringApproval() {
        val f = DeveloperSavedCompletionReviewTest.createFixture()
        var reads = 0
        val restored = DeveloperSavedCompletionReview({ f.history }, { f.plans },
            { token, bookmark, cancellation ->
                assertEquals("test-only-github-token", token)
                assertEquals(f.bookmark, bookmark)
                cancellation.check()
                reads++
                f.report
            }, { 1003L })
        // No in-memory PatchWorkbench CommitResult survives restart. Only
        // the user-saved read-only bookmark, a persisted plan, and GitHub CI.
        Screen(savedCompletion = restored, currentCommit = { null },
            githubCredential = "test-only-github-token",
            ask = { _, _, _, _, _ -> error("Saved review may not call the AI provider") }).use { s ->
            s.prompt.setText("Confirm Developer completion review")
            s.send.performClick()
            s.complete()
            assertTrue(s.output.text.contains("No fresh saved"))
            assertEquals(TaskPlanner.Status.PENDING, f.store.loadAll().single().steps.last().status)
            s.prompt.setText("Review Developer completion")
            s.send.performClick()
            assertFalse(s.send.isEnabled)
            s.complete()
            assertTrue(s.output.text.contains("Saved Developer task recovered"))
            assertTrue(s.output.text.contains(f.bookmark.commitSha))
            s.prompt.setText("Confirm Developer completion review")
            s.send.performClick()
            s.complete()
            assertTrue(s.output.text.contains("LOCAL final review acknowledged"))
            assertEquals(TaskPlanner.Status.DONE, f.store.loadAll().single().steps.last().status)
            assertEquals(2, reads) // Rechecked before confirmation.
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun primaryChatReviewsLiveCiBeforeAcknowledgingLocalCompletion() {
        val path = DeveloperPlanCompletionReviewTest.path
        val base = DeveloperPlanCompletionReviewTest.base
        val sha = DeveloperPlanCompletionReviewTest.sha
        val proposalId = DeveloperPlanCompletionReviewTest.proposal
        val ci = DeveloperPlanCompletionReviewTest.ci
        val directory = java.nio.file.Files.createTempDirectory("chat-completion-").toFile()
        val storage = TaskPlanStore(directory)
        val plan = TaskPlanner.Plan("plan-chat-final", "Developer voice reliability", base,
            "d".repeat(64), listOf(path), listOf(
                TaskPlanner.Step("analyze", "Analyze", "Scope", emptyList(), emptyList()),
                TaskPlanner.Step("implement-1", "Implement", "Change", listOf(path), listOf("analyze")),
                TaskPlanner.Step("validate", "Validate", "CI", emptyList(), listOf("implement-1")),
                TaskPlanner.Step("review", "Review", "Final review", emptyList(), listOf("validate"))
            ), listOf(TaskPlanner.Decision(1, null, "Created plan", 1000L)), 0L, false, 1000L)
        storage.save(plan, null)
        val reviewed = TaskPlanner.transition(plan, "analyze", TaskPlanner.Status.DONE,
            "Owner reviewed analysis", 1001L)
        storage.save(reviewed, 0L)
        val id = requireNotNull(DeveloperPlanContinuation.suggest(listOf(reviewed)).packet).id
        val commit = PatchPublisher.CommitResult(id, "Gemini", "gemini-2.5-pro",
            proposalId, listOf(path), base, sha,
            PatchEngine.taskBranch(id, proposalId, PatchEngine.Target.DEVELOPER),
            "approved diff", "feature/jarvis-developer", PatchEngine.Target.DEVELOPER)
        assertNotNull(DeveloperPlanBuildCheckpoint.record(storage, commit, ci, 1002L))
        val finalReview = DeveloperPlanCompletionReview({ directory }, { 1003L })
        var ciReads = 0
        Screen(completion = finalReview, currentCommit = { commit },
            githubCredential = "test-only-github-token",
            checkedCi = { _, exact ->
                assertEquals(commit, exact)
                ciReads++
                ci
            }, ask = { _, _, _, _, _ -> error("Review command may not call an AI provider") }).use { s ->
            s.prompt.setText("Confirm Developer completion review")
            s.send.performClick()
            s.complete()
            assertTrue(s.output.text.contains("No current"))
            assertEquals(TaskPlanner.Status.PENDING, storage.loadAll().single().steps.last().status)
            s.prompt.setText("Review Developer completion")
            s.send.performClick()
            assertFalse(s.send.isEnabled)
            s.complete()
            assertTrue(s.output.text.contains("Fresh exact-source debug CI"))
            assertEquals(TaskPlanner.Status.PENDING, storage.loadAll().single().steps.last().status)
            s.prompt.setText("Confirm Developer completion review")
            s.send.performClick()
            s.complete()
            assertTrue(s.output.text.contains("final LOCAL review acknowledged"))
            assertEquals(TaskPlanner.Status.DONE, storage.loadAll().single().steps.last().status)
            assertEquals(3, ciReads)
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun personalProductCannotReadOrApproveDeveloperCompletion() {
        var reads = 0
        Screen(currentCommit = { reads++; null }).use { s ->
            s.target.setSelection(1)
            s.prompt.setText("Confirm Developer completion review")
            s.send.performClick()
            assertTrue(s.pending.isEmpty())
            assertEquals(0, reads)
            assertTrue(s.output.text.contains("unavailable for Personal"))
        }
    }

    @Test fun continueDeveloperPlanFromChatStartsOnlyTheNextScopedPreview() {
        var stages = 0
        val packet = DeveloperPlanContinuation.Packet("plan-1-abcdef1234567890",
            "Implement approved scope", listOf("developer/src/main/java/com/jarvis/developer/Example.kt"),
            "a".repeat(40))
        Screen(nextPlan = { DeveloperPlanContinuation.Suggestion("Next ready implementation checkpoint", packet) },
            stagePlan = { exact ->
                assertEquals(packet, exact)
                stages++
                true
            }, ask = { _, _, _, _, _ -> error("Chat continuation cannot start generic AI") }).use { s ->
            s.prompt.setText("Continue Developer plan")
            s.send.performClick()
            assertEquals(1, s.pending.size)
            assertFalse(s.send.isEnabled)
            assertEquals(0, stages)
            s.complete()
            assertEquals(1, stages)
            assertTrue(s.output.text.contains("preview STARTED"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
            assertTrue(s.send.isEnabled)
        }
    }

    @Test fun nonEditableCheckpointOrCancelledPlanDoesNotStartCoding() {
        var stages = 0
        Screen(nextPlan = { DeveloperPlanContinuation.Suggestion("Manual analyze checkpoint pending") },
            stagePlan = { stages++; true }).use { s ->
            s.prompt.setText("Continue Developer plan")
            s.send.performClick()
            s.complete()
            assertEquals(0, stages)
            assertTrue(s.output.text.contains("Manual analyze checkpoint"))
            assertTrue(s.prefs.all.isEmpty())
        }
        Screen(nextPlan = { error("corrupt private checkpoint") },
            stagePlan = { stages++; true }).use { s ->
            s.prompt.setText("Continue Developer plan")
            s.send.performClick()
            s.complete()
            assertEquals(0, stages)
            assertTrue(s.output.text.contains("could not be verified"))
            assertFalse(s.output.text.contains("corrupt"))
        }
        Screen(nextPlan = { error("must not read after cancellation") },
            stagePlan = { stages++; true }).use { s ->
            s.prompt.setText("Continue Developer plan")
            s.send.performClick()
            s.cancel.performClick()
            s.complete()
            assertEquals(0, stages)
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun continuedPlanCannotCrossIntoPersonalProduct() {
        var readCount = 0
        Screen(nextPlan = { readCount++; DeveloperPlanContinuation.Suggestion("some plan") }).use { s ->
            s.target.setSelection(1)
            s.prompt.setText("Continue Developer plan")
            s.send.performClick()
            assertTrue(s.pending.isEmpty())
            assertEquals(0, readCount)
            assertTrue(s.output.text.contains("unavailable for Personal"))
        }
    }

    @Test fun chatCanReviewApproveAnalysisAndAutomaticallyStartNextScopedPreview() {
        val directory = java.nio.file.Files.createTempDirectory("developer-full-chat-plan-").toFile()
        val store = TaskPlanStore(directory)
        val path = "developer/src/main/java/com/jarvis/developer/VoiceLatency.kt"
        val plan = TaskPlanner.Plan(
            "plan-chat", "Fix Developer voice latency", "a".repeat(40), "b".repeat(64),
            listOf(path), listOf(
                TaskPlanner.Step("analyze", "Confirm source analysis", "Review plan evidence", emptyList(), emptyList()),
                TaskPlanner.Step("implement-1", "Change voice latency", "Use only the selected source",
                    listOf(path), listOf("analyze")),
                TaskPlanner.Step("validate", "Validate", "Verify exact CI", emptyList(), listOf("implement-1")),
                TaskPlanner.Step("review", "Review", "Check release gates", emptyList(), listOf("validate"))
            ), listOf(TaskPlanner.Decision(1, null, "Analysis completed for immutable source", 1000)),
            0L, false, 1000L)
        store.save(plan, null)
        val approval = DeveloperPlanAnalysisReview({ directory }, { 1002L })
        var stages = 0
        Screen(planAnalysis = approval, nextPlan = { DeveloperPlanContinuation.suggest(store.loadAll()) },
            stagePlan = { packet ->
                assertEquals(listOf(path), packet.paths)
                assertEquals(plan.baseCommitSha, packet.base)
                stages++
                true
            }, ask = { _, _, _, _, _ -> error("No AI provider invocation for plan checkpoint commands") }).use { s ->
            s.prompt.setText("Continue Developer plan")
            s.send.performClick()
            s.complete()
            assertTrue(s.output.text.contains("requires explicit evidence/review"))
            assertEquals(0, stages)
            s.prompt.setText("Approve Developer plan analysis")
            s.send.performClick()
            assertTrue(s.output.text.contains("No current"))
            s.prompt.setText("Review Developer plan")
            s.send.performClick()
            assertTrue(s.output.text.contains("Pinned source"))
            assertTrue(s.output.text.contains(path))
            assertEquals(0L, store.loadAll().single().revision)
            s.prompt.setText("Approve Developer plan analysis")
            s.send.performClick()
            assertTrue(s.output.text.contains("acknowledged and saved"))
            assertEquals(1L, store.loadAll().single().revision)
            assertEquals(TaskPlanner.Status.DONE, store.loadAll().single().steps[0].status)
            // No second "Continue Developer plan" chat instruction: approval already
            // authorizes read-only source proposal preparation, not a source write.
            assertEquals(1, stages)
            assertTrue(s.output.text.contains("preview STARTED automatically"))
            assertTrue(s.output.text.contains("No source write is approved"))
            assertTrue(s.pending.isEmpty())
            assertTrue(s.prefs.all.isEmpty())
            assertEquals(TaskPlanner.Status.PENDING, store.loadAll().single().steps[1].status)
            s.prompt.setText("Approve Developer plan analysis")
            s.send.performClick()
            assertTrue(s.output.text.contains("No current"))
            assertEquals(1, stages)
        }
    }

    @Test fun autoPreviewRejectsAnotherPlansPacketAfterOwnerAnalysisApproval() {
        val directory = java.nio.file.Files.createTempDirectory("developer-plan-handoff-gate-").toFile()
        val store = TaskPlanStore(directory)
        val path = "developer/src/main/java/com/jarvis/developer/Latency.kt"
        val plan = TaskPlanner.Plan("plan-owner", "Repair Developer latency", "a".repeat(40),
            "b".repeat(64), listOf(path), listOf(
                TaskPlanner.Step("analyze", "Confirm", "Review plan", emptyList(), emptyList()),
                TaskPlanner.Step("implement-1", "Edit", "Edit source", listOf(path), listOf("analyze")),
                TaskPlanner.Step("validate", "Validate", "Test source", emptyList(), listOf("implement-1")),
                TaskPlanner.Step("review", "Review", "Review CI", emptyList(), listOf("validate"))
            ), listOf(TaskPlanner.Decision(1, null, "Created plan", 1000L)),
            0, false, 1000L)
        store.save(plan, null)
        val review = DeveloperPlanAnalysisReview({ directory }, { 1002L })
        var stages = 0
        val unrelated = DeveloperPlanContinuation.Packet(
            "other-task-1234567890123456", "Other source change", listOf(path),
            plan.baseCommitSha, planId = "another-plan")
        Screen(planAnalysis = review,
            nextPlan = { DeveloperPlanContinuation.Suggestion("Other plan", unrelated) },
            stagePlan = { stages++; true }).use { s ->
            s.prompt.setText("Review Developer plan")
            s.send.performClick()
            assertTrue(s.output.text.contains("Pinned source"))
            s.prompt.setText("Approve Developer plan analysis")
            s.send.performClick()
            assertEquals(TaskPlanner.Status.DONE, store.loadAll().single().steps[0].status)
            assertEquals(0, stages)
            assertTrue(s.output.text.contains("different plan"))
            assertTrue(s.output.text.contains("No source preview was started"))
        }
    }

    @Test fun explicitSavedTaskChatCommandsDoNotCallProviderOrPersistProjectDiscussion() {
        var saves = 0
        var reads = 0
        Screen(save = { saves++; true }, readSaved = { reads++; true },
            ask = { _, _, _, _, _ -> error("Provider must never run for saved build") }).use { s ->
            s.prompt.setText("Save Developer task")
            s.send.performClick()
            assertEquals(1, saves)
            assertEquals(0, reads)
            assertTrue(s.output.text.contains("private local history"))
            s.prompt.setText("Check saved Developer build")
            s.send.performClick()
            assertEquals(1, reads)
            assertTrue(s.output.text.contains("exact CI evidence"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
    }

    @Test fun savedTaskChatDoesNotLeakIntoPersonalJarvisOrInventMissingBookmarks() {
        var calls = 0
        Screen(save = { calls++; true }, readSaved = { calls++; true }).use { s ->
            s.target.setSelection(1)
            s.prompt.setText("Check saved Developer build")
            s.send.performClick()
            assertEquals(0, calls)
            assertTrue(s.output.text.contains("unavailable for the Personal"))
        }
        Screen(save = { calls++; false }).use { s ->
            s.prompt.setText("Save Developer task")
            s.send.performClick()
            assertEquals(1, calls)
            assertTrue(s.output.text.contains("No eligible Developer task"))
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun chatBuildCheckUsesExistingExactCommitReadWithoutAIOrWrites() {
        var checks = 0
        Screen(buildCheck = { checks++; true }, ask = { _, _, _, _, _ -> error("No AI request allowed") }).use { s ->
            s.prompt.setText("Check Developer build")
            s.send.performClick()
            assertEquals(1, checks)
            assertTrue(s.output.text.contains("exact Developer task commit"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
            assertTrue(s.send.isEnabled)
        }
    }

    @Test fun chatBuildCheckRefusesWrongTargetAndMissingTask() {
        var calls = 0
        Screen(buildCheck = { calls++; true }).use { s ->
            s.target.setSelection(1)
            s.prompt.setText("Check Developer CI")
            s.send.performClick()
            assertEquals(0, calls)
            assertTrue(s.output.text.contains("unavailable for the Personal"))
        }
        Screen(buildCheck = { calls++; false }).use { s ->
            s.prompt.setText("Check Developer build")
            s.send.performClick()
            assertEquals(1, calls)
            assertTrue(s.output.text.contains("No completed Developer task commit"))
            assertTrue(s.pending.isEmpty())
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun fullVerifiedDiffCanBeShownThroughMainChatWithoutProviderOrSourceWrite() {
        var reads = 0
        val full = "Task: scoped change\nComplete reviewable diff:\n- old\n+ new"
        Screen(showDiff = { reads++; full },
            ask = { _, _, _, _, _ -> error("Diff review must not call AI") }).use { s ->
            s.prompt.setText("Show Developer diff")
            s.send.performClick()
            assertEquals(1, reads)
            assertEquals(full, s.output.text.toString())
            assertTrue(s.status.text.contains("No write started"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
    }

    @Test fun missingDiffAndWrongProductReturnNoReviewOrApproval() {
        var reads = 0
        Screen(showDiff = { reads++; "private developer diff" }).use { s ->
            s.target.setSelection(1)
            s.prompt.setText("Show Developer diff")
            s.send.performClick()
            assertEquals(0, reads)
            assertTrue(s.output.text.contains("not available for the Personal"))
            assertFalse(s.output.text.contains("private developer diff"))
        }
        Screen(showDiff = { reads++; null }).use { s ->
            s.prompt.setText("Show Developer diff")
            s.send.performClick()
            assertEquals(1, reads)
            assertTrue(s.output.text.contains("No complete, current"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
    }

    @Test fun plainLanguageApprovalPassesTheBoundInternalReviewMarkerOnly() {
        var calls = 0
        Screen(approve = { marker ->
            calls++
            assertEquals(DeveloperDiffApprovalCommand.REVIEWED, marker)
            true
        }, ask = { _, _, _, _, _ -> error("No provider call permitted") }).use { s ->
            s.prompt.setText("Approve Developer diff")
            s.send.performClick()
            assertEquals(1, calls)
            assertTrue(s.output.text.contains("Exact Developer diff approved"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
    }

    @Test fun exactTypedDiffApprovalUsesWorkbenchOnlyAndNeverCallsProvider() {
        var calls = 0
        Screen(approve = { prefix ->
            calls++
            assertEquals("0123456789abcdef", prefix)
            true
        }, ask = { _, _, _, _, _ -> error("No provider call permitted") }).use { s ->
            s.prompt.setText("Approve Developer diff 0123456789abcdef")
            s.send.performClick()
            assertEquals(1, calls)
            assertTrue(s.output.text.contains("Exact Developer diff approved"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
    }

    @Test fun incompleteAndPersonalDiffApprovalCannotInvokeWrite() {
        var calls = 0
        Screen(approve = { calls++; true }).use { s ->
            s.prompt.setText("Approve Developer diff invalid")
            s.send.performClick()
            assertEquals(0, calls)
            assertTrue(s.output.text.contains("Show Developer diff"))
            s.target.setSelection(1)
            s.prompt.setText("Approve Developer diff 0123456789abcdef")
            s.send.performClick()
            assertEquals(0, calls)
            assertTrue(s.output.text.contains("unavailable for the Personal"))
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun explicitChatRepairRequestCallsOnlyScopedReviewHandoff() {
        var calls = 0
        Screen(repair = { calls++; true }, ask = { _, _, _, _, _ -> error("AI chat must not start") }).use { s ->
            s.prompt.setText("Prepare Developer repair")
            s.send.performClick()
            assertEquals(1, calls)
            assertEquals(0, s.pending.size)
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.output.text.contains("NEW pinned-source repair diff"))
            assertTrue(s.output.text.contains("explicitly approve"))
            assertTrue(s.send.isEnabled)
            assertFalse(s.cancel.isEnabled)
        }
    }

    @Test fun chatRepairCannotCrossToPersonalOrClaimMissingFailureEvidence() {
        var calls = 0
        Screen(repair = { calls++; true }).use { s ->
            s.target.setSelection(1)
            s.prompt.setText("Repair the failed Developer build")
            s.send.performClick()
            assertEquals(0, calls)
            assertTrue(s.output.text.contains("not available for the Personal"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
        Screen(repair = { calls++; false }).use { s ->
            s.prompt.setText("Prepare Developer repair")
            s.send.performClick()
            assertEquals(1, calls)
            assertTrue(s.output.text.contains("No verified, in-scope"))
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.pending.isEmpty())
        }
    }

    @Test fun localProgressRequestRunsWithoutProviderOrPersistingAnApproval() {
        var reports = 0
        Screen(progressRead = { reports++; "Local plans: 1; next step: review" }).use { s ->
            s.prompt.setText("Show development progress")
            s.send.performClick()
            assertEquals(1, s.pending.size)
            assertEquals(0, reports)
            assertFalse(s.send.isEnabled)
            assertTrue(s.cancel.isEnabled)
            assertTrue(s.prefs.all.isEmpty())
            s.complete()
            assertEquals(1, reports)
            assertEquals("Local plans: 1; next step: review", s.output.text.toString())
            assertTrue(s.send.isEnabled)
            assertFalse(s.cancel.isEnabled)
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.status.text.contains("Read-only"))
        }
    }

    @Test fun cancelledLocalProgressDropsStaleResultsAndRestoresChat() {
        var reads = 0
        Screen(progressRead = { reads++; "Late stale local progress" }).use { s ->
            s.prompt.setText("Show my development tasks")
            s.send.performClick()
            s.cancel.performClick()
            s.complete()
            assertEquals(0, reads)
            assertEquals("Previous display", s.output.text.toString())
            assertTrue(s.send.isEnabled)
            assertTrue(s.prefs.all.isEmpty())
            assertTrue(s.status.text.contains("cancelled"))
        }
    }

    @Test fun localProgressFailureDoesNotPretendThatZeroTasksExist() {
        Screen(progressRead = { error("private corrupted store fixture") }).use { s ->
            s.prompt.setText("Show development status")
            s.send.performClick(); s.complete()
            assertTrue(s.output.text.contains("could not be verified"))
            assertFalse(s.output.text.contains("private corrupted"))
            assertTrue(s.send.isEnabled)
            assertTrue(s.prefs.all.isEmpty())
        }
    }

    @Test fun cancelledAndSupersededTicketsCannotOverwriteANewerProject() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        val first = store.begin("project-a", "Earlier instructions", 0)!!
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(first, "generation_cancelled"))
        val next = ProjectGenerationStore(prefs).begin("project-a", "New instructions", 1)!!
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(first, "generated", "Late response"))
        var record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        assertEquals("New instructions", record.getString("description")); assertEquals(1, record.getInt("target"))
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(next, "generated", "Current response"))
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(next, "generation_failed"))
        record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        assertEquals("Current response", record.getString("response")); assertFalse(record.has("generation_request"))
        assertEquals(true, ProjectGenerationStore.replyMatches(record))
        assertTrue(store.saveLocal("project-a", "Different instructions", 0))
        assertEquals(false, ProjectGenerationStore.replyMatches(JSONObject(prefs.getString("project_project-a", "{}")!!)))
    }
    @Test fun corruptLegacyRecordsArePreservedWithoutStartingGeneration() {
        val prefs = preferences(); prefs.edit().putString("project_project-a", "invalid-json-private-fixture").commit()
        val store = ProjectGenerationStore(prefs)
        assertNull(store.begin("project-a", "New task", 0)); assertFalse(store.saveLocal("project-a", "New task", 0))
        assertEquals("invalid-json-private-fixture", prefs.getString("project_project-a", ""))
    }
    @Test fun offlineSavingInvalidatesPendingRepliesAndPreservesLegacyResponses() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        prefs.edit().putString("project_project-a", JSONObject().put("id", "project-a").put("description", "Old task")
            .put("target", 0).put("response", "Previous saved reply").put("legacy_note", "Keep this metadata").toString()).commit()
        val pending = store.begin("project-a", "Pending task", 0)!!
        assertTrue(store.saveLocal("project-a", "Offline revision", 1))
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(pending, "generated", "Do not apply"))
        val record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        assertEquals("Previous saved reply", record.getString("response")); assertEquals("Keep this metadata", record.getString("legacy_note"))
        assertEquals("saved", record.getString("state")); assertEquals(1, record.getInt("target"))
        assertNull(ProjectGenerationStore.replyMatches(record))
    }
    @Test fun cancellationAndRetryRejectLateCallbacksAndKeepTheNewInputsBusy() {
        Screen().use { s ->
            s.send.performClick(); s.send.performClick(); assertEquals(1, s.pending.size)
            assertFalse(s.prompt.isEnabled); assertFalse(s.target.isEnabled); assertTrue(s.cancel.isEnabled)
            s.cancel.performClick(); assertTrue(s.prompt.isEnabled); assertTrue(s.target.isEnabled)
            s.prompt.setText("Second request"); s.send.performClick(); assertEquals(2, s.pending.size)
            s.complete(); assertFalse(s.send.isEnabled); assertTrue(s.cancel.isEnabled)
            assertEquals("Previous display", s.output.text.toString()); assertEquals("generating", s.record().getString("state"))
            s.complete(); assertTrue(s.send.isEnabled); assertFalse(s.cancel.isEnabled)
            assertTrue(s.output.text.toString().endsWith("Reply for Second request"))
            assertTrue(s.output.text.toString().contains("Repository context unavailable")); assertEquals("generated", s.record().getString("state"))
        }
    }
    @Test fun cancellingAnActiveProviderSignalsCleanupWithoutWaitingForItsReturn() {
        val started = CountDownLatch(1); val stopped = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            Screen(ask = { _, _, _, _, cancellation ->
                cancellation.registerStop { stopped.countDown() }.use { started.countDown(); release.await(5, TimeUnit.SECONDS); "Late reply" }
            }).use { s ->
                s.send.performClick(); val job = pool.submit { s.pending.removeAt(0).invoke() }
                assertTrue(started.await(5, TimeUnit.SECONDS)); s.cancel.performClick()
                assertTrue(s.send.isEnabled); assertTrue(stopped.await(5, TimeUnit.SECONDS))
                release.countDown(); job.get(5, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
                assertEquals("generation_cancelled", s.record().getString("state")); assertEquals("Previous display", s.output.text.toString())
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun disposalRecordsInterruptionAndDoesNotAutomaticallyRestartTheRequest() {
        val s = Screen(); s.send.performClick(); val prefs = s.prefs; s.close(); s.complete()
        assertEquals("generation_interrupted", s.record().getString("state"))
        Screen(prefs).use { reopened ->
            assertTrue(reopened.pending.isEmpty()); assertTrue(reopened.send.isEnabled)
            assertEquals("First request", reopened.record().getString("description"))
        }
    }
    @Test fun launchAndProviderFailuresLeaveSavedInstructionsAndRetryControlsAvailable() {
        Screen(launchFails = true).use { s ->
            s.send.performClick(); assertTrue(s.send.isEnabled); assertFalse(s.cancel.isEnabled)
            assertEquals("generation_failed", s.record().getString("state"))
            assertEquals(s.activity.getString(R.string.project_generation_start_failed), s.status.text.toString())
        }
        Screen(failure = IllegalStateException("runtime-provider-key-42 private body")).use { s ->
            s.send.performClick(); s.complete(); assertTrue(s.send.isEnabled)
            assertFalse(s.output.text.contains("runtime-provider-key-42")); assertFalse(s.output.text.contains("private body"))
            assertEquals("First request", s.record().getString("description"))
            assertEquals("gemini-2.5-pro", s.model.text.toString())
        }
    }
    @Test fun configuredCredentialsInInstructionsAreRejectedBeforePersistenceOrSending() {
        Screen().use { s ->
            s.prompt.setText("My key is runtime-provider-key-42")
            s.send.performClick(); assertTrue(s.prefs.all.isEmpty()); assertTrue(s.pending.isEmpty())
            assertEquals(s.activity.getString(R.string.project_generation_remove_key), s.status.text.toString())
        }
    }
    @Test fun anotherEditorCanSupersedeARequestWithoutReceivingItsOldReply() {
        Screen().use { s ->
            s.send.performClick()
            val newer = ProjectGenerationStore(s.prefs).begin("project-a", "Other window instructions", 1)!!
            s.complete(); assertEquals("Previous display", s.output.text.toString())
            assertEquals(s.activity.getString(R.string.project_generation_changed), s.status.text.toString())
            assertEquals("Other window instructions", s.record().getString("description"))
            assertEquals(newer.request, s.record().getString("generation_request"))
        }
    }
    @Test fun discussionWritesRequireTheCurrentTicketProjectAndProduct() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        val ticket = store.begin("project-a", "Inspect code", 0)!!
        for (history in listOf(ProjectGenerationDiscussion.empty("project-b", 0), ProjectGenerationDiscussion.empty("project-a", 1))) {
            assertEquals(ProjectGenerationStore.Result.FAILED, store.finish(ticket, "generated", "Reply", history.append("Inspect code", "Reply", emptyList())))
            val record = JSONObject(prefs.getString("project_project-a", "{}")!!)
            assertEquals("generating", record.getString("state")); assertFalse(record.has("discussion"))
        }
        assertTrue(store.forgetDiscussion("project-a"))
        assertEquals(ProjectGenerationStore.Result.STALE, store.finish(ticket, "generated", "Late reply"))
    }

    @Test fun legacyRepliesAreNotAutomaticallyUploadedAndOfflineTargetChangesForgetHistory() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        prefs.edit().putString("project_project-a", JSONObject().put("id", "project-a").put("target", 0)
            .put("response", "Legacy reply remains local").toString()).commit()
        assertEquals(0, store.discussion("project-a", 0, emptyList()).size)
        val ticket = store.begin("project-a", "Current task", 0)!!
        val history = ProjectGenerationDiscussion.empty("project-a", 0).append("Current task", "New reply", emptyList())
        assertEquals(ProjectGenerationStore.Result.SAVED, store.finish(ticket, "generated", "New reply", history))
        assertEquals(1, store.discussion("project-a", 0, emptyList()).size)
        assertTrue(store.saveLocal("project-a", "Offline Personal task", 1))
        assertFalse(JSONObject(prefs.getString("project_project-a", "{}")!!).has("discussion"))
        assertEquals("New reply", JSONObject(prefs.getString("project_project-a", "{}")!!).getString("response"))
    }

    @Test fun projectStatesNeverGrantBuildReleaseOrSourceApproval() {
        val prefs = preferences(); val store = ProjectGenerationStore(prefs)
        val ticket = store.begin("project-a", "Inspect this code", 0)!!
        assertThrows(IllegalArgumentException::class.java) { store.finish(ticket, "published", "Unverified text") }
        val record = JSONObject(prefs.getString("project_project-a", "{}")!!)
        record.put("generation_process", "earlier-process")
        assertEquals("Interrupted; retry when ready", ProjectGenerationStore.stateLabel(record))
        assertFalse(record.has("approval")); assertFalse(record.has("build")); assertFalse(record.has("signature"))
        for (name in listOf("ProjectGenerationStore", "ProjectGenerationWorkbench"))
            assertFalse(PatchEngine.allowedPath("developer/src/main/java/com/jarvis/developer/$name.kt"))
    }
}
