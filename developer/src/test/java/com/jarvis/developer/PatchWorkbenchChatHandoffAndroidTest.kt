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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Real inline coding widgets; fake source transport. The imported conversational
 * proposal may only auto-prepare an exact diff, never approve or write a branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PatchWorkbenchChatHandoffAndroidTest {
    private val branch = "feature/jarvis-developer"
    private val sha = "a".repeat(40)
    private val path = "developer/src/main/java/com/jarvis/developer/VoiceLatency.kt"
    private val original = "package com.jarvis.developer\nobject VoiceLatency { fun delay() = 10 }\n"
    private val replacement = "package com.jarvis.developer\nobject VoiceLatency { fun delay() = 5 }\n"

    private fun blob(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-1").digest(
            ("blob ${bytes.size}\u0000").toByteArray(Charsets.UTF_8) + bytes
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private inner class Screen(private val product: PatchEngine.Target = PatchEngine.Target.DEVELOPER) {
        val sourcePath = if (product == PatchEngine.Target.PERSONAL)
            path.replace("developer/src", "app/src").replace("com/jarvis/developer", "com/assistant/core") else path
        val initialSource = if (product == PatchEngine.Target.PERSONAL)
            original.replace("com.jarvis.developer", "com.assistant.core") else original
        val editedSource = if (product == PatchEngine.Target.PERSONAL)
            replacement.replace("com.jarvis.developer", "com.assistant.core") else replacement
        val requestedBranch = if (product == PatchEngine.Target.PERSONAL) "main" else branch
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val prompt = EditText(activity).apply { setText("Fix voice response delay") }
        // The production Activity must keep the primary chat separate from the
        // exact task/scope editor used by PatchWorkbench.
        val chatComposer = EditText(activity).apply { setText("Write the initial code") }
        val key = EditText(activity).apply { setText("example-provider-key") }
        val github = EditText(activity).apply { setText("example-github-token") }
        val model = EditText(activity).apply { setText("gpt-4.1-mini") }
        val provider = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("OpenAI"))
        }
        val output = TextView(activity)
        val status = TextView(activity)
        val queued = ArrayDeque<() -> Unit>()
        var inventoryReads = 0
        var sourceReads = 0
        var providerCalls = 0
        var writes = 0
        var changedSha = false
        val handle: PatchWorkbench.Handle

        init {
            activity.setContentView(root)
            handle = PatchWorkbench.attach(activity, root, prompt, key, provider, model, github,
                output, status, { true }, PatchWorkbench.Services(
                    inventory = { _, requestedBranch, target, cancellation ->
                        cancellation.check(); inventoryReads++
                        assertEquals(this@Screen.requestedBranch, requestedBranch)
                        assertEquals(product, target)
                        RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(sourcePath, blob(initialSource),
                            initialSource.toByteArray(Charsets.UTF_8).size.toLong())), false,
                            if (changedSha) "b".repeat(40) else sha)
                    },
                    readSource = { _, _, requestedPath, cancellation ->
                        cancellation.check(); sourceReads++
                        assertEquals(sourcePath, requestedPath); initialSource
                    },
                    propose = { _, _, _ ->
                        providerCalls++; error("Imported code must not call another AI provider")
                    },
                    commit = { _, _ ->
                        writes++; error("Unapproved code must never be written")
                    },
                    worker = { queued.add(it) }
                ))
            shadowOf(Looper.getMainLooper()).idle()
        }

        private fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
        fun button(text: String): Button = descendants(root).filterIsInstance<Button>()
            .single { it.text.toString().startsWith(text) }
        fun approval(): CheckBox = descendants(root).filterIsInstance<CheckBox>().single()
        fun import(): Boolean = handle.importChatProposal("Fix voice response delay", "Reduce latency",
            listOf(PatchEngine.Edit(sourcePath, editedSource)), sha, product)
        fun finish() { queued.removeFirst().invoke(); shadowOf(Looper.getMainLooper()).idle() }
        fun close() { handle.dispose(); controller.pause().stop().destroy() }
    }

    @Test fun chatProposalAutomaticallyBuildsVerifiedDiffWithoutSecondProviderCallOrWrites() {
        val screen = Screen()
        try {
            assertTrue(screen.import())
            assertEquals(1, screen.queued.size)
            assertFalse(screen.approval().isEnabled)
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
            screen.finish()
            assertEquals(1, screen.inventoryReads)
            assertEquals(1, screen.sourceReads)
            assertTrue(screen.approval().isEnabled)
            assertFalse(screen.approval().isChecked)
            assertFalse(screen.button("Create task branch").isEnabled)
            assertTrue(screen.output.text.toString().contains("Scoped preview ready"))
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
        } finally { screen.close() }
    }

    @Test fun personalChatProposalUsesPinnedMainSourceAndNeverAutoApprovesOrWrites() {
        val screen = Screen(PatchEngine.Target.PERSONAL)
        try {
            assertTrue(screen.import())
            assertEquals(1, screen.queued.size)
            assertFalse(screen.approval().isEnabled)
            screen.finish()
            assertEquals(1, screen.inventoryReads)
            assertEquals(1, screen.sourceReads)
            assertTrue(screen.approval().isEnabled)
            assertFalse(screen.approval().isChecked)
            assertFalse(screen.button("Create task branch").isEnabled)
            assertTrue(screen.output.text.toString().contains("Scoped preview ready"))
            // Developer-only chat fingerprint approval cannot be reused for Personal.
            assertNull(screen.handle.showDeveloperDiff())
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
        } finally { screen.close() }
    }

    @Test fun personalChatCannotImportProtectedAssistantMainActivity() {
        val screen = Screen(PatchEngine.Target.PERSONAL)
        try {
            assertFalse(screen.handle.importChatProposal("Change Personal Jarvis activity", "Unsafe",
                listOf(PatchEngine.Edit("app/src/main/java/com/assistant/core/MainActivity.kt",
                    "package com.assistant.core\\nclass MainActivity {}")), sha, PatchEngine.Target.PERSONAL))
            assertEquals(0, screen.inventoryReads)
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
        } finally { screen.close() }
    }

    @Test fun chatFollowUpCannotInvalidateAnExistingExactDiffButScopeChangesStillDo() {
        val screen = Screen()
        try {
            assertTrue(screen.import())
            screen.finish()
            assertTrue(screen.approval().isEnabled)
            assertFalse(screen.approval().isChecked)
            val reads = screen.sourceReads
            screen.chatComposer.setText("Check Developer build")
            screen.chatComposer.setText("Prepare Developer repair")
            assertTrue(screen.approval().isEnabled)
            assertFalse(screen.approval().isChecked)
            assertTrue(screen.button("Create task branch").isEnabled == false)
            assertEquals(reads, screen.sourceReads)
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
            screen.prompt.setText("This is a different scoped engineering task")
            assertFalse(screen.approval().isEnabled)
            assertTrue(screen.status.text.toString().contains("invalidated"))
        } finally { screen.close() }
    }

    @Test fun currentDeveloperDiffCanBeShownWithoutExtraProviderCallAndRevokesAfterTaskEdit() {
        val screen = Screen()
        try {
            assertNull(screen.handle.showDeveloperDiff())
            assertTrue(screen.import())
            assertNull(screen.handle.showDeveloperDiff())
            screen.finish()
            val review = screen.handle.showDeveloperDiff()
            assertNotNull(review)
            assertTrue(review!!.contains("Complete reviewable diff"))
            assertTrue(review.contains("Approve Developer diff"))
            assertTrue(review.contains("Base commit SHA: " + sha))
            assertEquals(1, screen.sourceReads)
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
            screen.chatComposer.setText("Show Developer diff")
            assertEquals(review, screen.handle.showDeveloperDiff())
            screen.prompt.setText("Different source-bound task")
            assertNull(screen.handle.showDeveloperDiff())
            assertFalse(screen.approval().isEnabled)
            assertEquals(0, screen.writes)
        } finally { screen.close() }
    }

    @Test fun explicitPlanContinuationCannotReplaceActiveReviewedDiff() {
        val screen = Screen()
        try {
            assertTrue(screen.import())
            screen.finish()
            assertTrue(screen.approval().isEnabled)
            assertFalse(screen.handle.continueDeveloperPlan("plan-next", "New plan",
                listOf(path), sha))
            assertTrue(screen.approval().isEnabled)
            assertFalse(screen.approval().isChecked)
            assertEquals(0, screen.writes)
            assertEquals(0, screen.providerCalls)
        } finally { screen.close() }
    }

    @Test fun movedBaseCannotProduceAnApprovableDiffOrInvokeProvider() {
        val screen = Screen()
        try {
            assertTrue(screen.import())
            screen.changedSha = true
            screen.finish()
            assertFalse(screen.approval().isEnabled)
            assertFalse(screen.button("Create task branch").isEnabled)
            assertTrue(screen.status.text.toString().contains("unavailable"))
            assertEquals(0, screen.sourceReads)
            assertEquals(0, screen.providerCalls)
            assertEquals(0, screen.writes)
        } finally { screen.close() }
    }
}
