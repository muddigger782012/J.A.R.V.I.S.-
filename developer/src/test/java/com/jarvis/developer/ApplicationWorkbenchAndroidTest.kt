package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual Android controls with instance-only picker/writer fakes; no document or device is touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ApplicationWorkbenchAndroidTest {
    private val screens = mutableListOf<Screen>()

    @After fun closeScreens() {
        screens.forEach {
            it.handle.dispose()
            if (!it.activity.isDestroyed) it.controller.pause().stop().destroy()
        }
    }

    @Test fun attachmentHasNoPreviewApprovalPickerOrDocumentWrite() {
        val screen = screen()
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.export.isEnabled)
        assertTrue(screen.launches.isEmpty())
        assertTrue(screen.writes.isEmpty())
        assertFalse(screen.handle.onActivityResult(23, Activity.RESULT_OK, document()))
    }

    @Test fun eachPlatformPreviewsEveryFileBeforeExactApprovalExportsMatchingBoundedZip() {
        ApplicationPlatform.values().forEach { platform ->
            val screen = screen()
            val plan = screen.generate(platform)
            val previewTexts = screen.texts()
            assertTrue(previewTexts.any { it.contains(plan.fingerprint) })
            plan.files.forEach { file ->
                assertTrue(file.path, previewTexts.any { it.contains(file.path) && it.contains(file.content) })
            }
            assertFalse(screen.export.isEnabled)
            screen.approve(plan)
            screen.export.performClick()
            val (intent, requestCode) = screen.launches.single()
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals("application/zip", intent.type)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertEquals("SampleApplication.zip", intent.getStringExtra(Intent.EXTRA_TITLE))
            assertTrue(screen.writes.isEmpty())
            assertTrue(screen.handle.onActivityResult(requestCode, Activity.RESULT_OK, document()))
            val (uri, bytes) = screen.writes.single()
            assertEquals(DOCUMENT_URI, uri)
            assertTrue(bytes.size in 1..1_048_576)
            val actualFiles = linkedMapOf<String, String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    assertFalse(entry.isDirectory)
                    assertFalse(actualFiles.containsKey(entry.name))
                    actualFiles[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertEquals(plan.files.associate { it.path to it.content }, actualFiles)
            assertFalse(screen.approval.isChecked)
            assertEquals("", screen.confirmation.text.toString())
            assertFalse(screen.export.isEnabled)
            assertTrue(screen.status.contains("starter ZIP saved"))
        }
    }

    @Test fun checkedBoxAndWrongFingerprintCannotOpenPicker() {
        val screen = screen()
        val plan = screen.generate()
        screen.approval.isChecked = true
        assertFalse(screen.export.isEnabled)
        screen.confirmation.setText("0".repeat(64))
        assertFalse(screen.approval.isChecked)
        screen.approval.isChecked = true
        assertFalse(screen.export.isEnabled)
        screen.export.performClick()
        assertTrue(screen.launches.isEmpty())
        screen.confirmation.setText(plan.fingerprint)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.export.isEnabled)
        screen.approval.isChecked = true
        assertTrue(screen.export.isEnabled)
        assertTrue(screen.writes.isEmpty())
    }

    @Test fun everyApplicationInputInvalidatesFullReviewAndApproval() {
        listOf(TITLE, PROJECT, PACKAGE, SPEC).forEach { hint ->
            val screen = screen()
            val plan = screen.generate()
            screen.approve(plan)
            screen.field(hint).setText(screen.field(hint).text.toString() + " changed")
            assertFalse(hint, screen.approval.isEnabled)
            assertFalse(hint, screen.approval.isChecked)
            assertFalse(hint, screen.export.isEnabled)
            assertEquals("", screen.confirmation.text.toString())
            assertFalse(screen.texts().any { it.contains(plan.fingerprint) })
            assertTrue(screen.launches.isEmpty())
        }
    }

    @Test fun platformEditInvalidatesReviewBeforeAnyExport() {
        val screen = screen()
        val plan = screen.generate()
        screen.approve(plan)
        screen.platform.setSelection(ApplicationPlatform.BACKEND.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.export.isEnabled)
        assertFalse(screen.texts().any { it.contains(plan.fingerprint) })
        assertTrue(screen.launches.isEmpty())
    }

    @Test fun checklistSelectionPreviewsRealFilesAndExportsOnlyAfterFreshExactReview() {
        val screen = screen()
        val counter = screen.generate()
        screen.approve(counter)
        screen.behavior.setSelection(AndroidApplicationBehavior.CHECKLIST.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.export.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertEquals("", screen.confirmation.text.toString())
        assertEquals("Sample application", screen.field(TITLE).text.toString())
        screen.generateButton.performClick()
        val plan = ApplicationScaffolds.plan(counter.specification.copy(androidBehavior = AndroidApplicationBehavior.CHECKLIST))
        assertTrue(screen.texts().any { it.contains(plan.fingerprint) && it.contains("ChecklistState.kt") })
        screen.approve(plan); screen.export.performClick()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertEquals(1, screen.writes.size)
        assertTrue(screen.status.contains("starter ZIP saved"))
    }

    @Test fun behaviorChangeDiscardsPendingExportAndNonAndroidSelectionResetsIt() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.behavior.setSelection(AndroidApplicationBehavior.CHECKLIST.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertTrue(screen.writes.isEmpty())
        assertFalse(screen.export.isEnabled)
        screen.platform.setSelection(ApplicationPlatform.BACKEND.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, screen.behavior.visibility)
        assertEquals(0, screen.behavior.selectedItemPosition)
        screen.platform.setSelection(ApplicationPlatform.ANDROID.ordinal)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, screen.behavior.visibility)
        assertEquals(0, screen.behavior.selectedItemPosition)
        assertEquals("Sample application", screen.field(TITLE).text.toString())
    }

    @Test fun editedInputsDiscardPendingPickerResultAndRequireFreshReview() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.field(SPEC).setText("A revised sample application specification")
        assertFalse(screen.generateButton.isEnabled)
        assertFalse(screen.export.isEnabled)
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertTrue(screen.writes.isEmpty())
        assertTrue(screen.status.contains("discarded"))
        assertTrue(screen.generateButton.isEnabled)
        assertFalse(screen.approval.isEnabled)
    }

    @Test fun cancellingPickerConsumesApprovalAndWritesNothing() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_CANCELED, document())
        assertTrue(screen.writes.isEmpty())
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.export.isEnabled)
        assertEquals("", screen.confirmation.text.toString())
        assertTrue(screen.status.contains("cancelled"))
    }

    @Test fun missingFileOrNetworkDestinationsAreRefusedWithoutDocumentWrite() {
        listOf(null, Uri.parse("file:///tmp/unapproved.zip"), Uri.parse("https://example.invalid/app.zip"),
            Uri.parse("content:/missing-authority")).forEach { uri ->
            val screen = screen()
            screen.approve(screen.generate())
            screen.export.performClick()
            screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK,
                if (uri == null) null else Intent().setData(uri))
            assertTrue(screen.writes.isEmpty())
            assertFalse(screen.export.isEnabled)
            assertFalse(screen.approval.isChecked)
            assertTrue(screen.status.contains("refused"))
        }
    }

    @Test fun duplicatePickerResultCannotWriteDocumentTwice() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertEquals(1, screen.writes.size)
        assertFalse(screen.export.isEnabled)
    }

    @Test fun destroyedActivityDiscardsPendingPickerResult() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        val priorStatus = screen.status
        screen.controller.pause().stop().destroy()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertTrue(screen.writes.isEmpty())
        assertEquals(priorStatus, screen.status)
        assertFalse(screen.export.isEnabled)
    }

    @Test fun disposedHandleDiscardsPendingPickerResult() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.handle.dispose()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertTrue(screen.writes.isEmpty())
        assertFalse(screen.export.isEnabled)
    }

    @Test fun pickerLaunchFailureConsumesApprovalAndDoesNotExposeExceptionText() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.failLaunch = true
        screen.export.performClick()
        assertTrue(screen.writes.isEmpty())
        assertFalse(screen.export.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.texts().any { it.contains(FAKE_PRIVATE_ERROR) })
        assertTrue(screen.status.contains("could not start"))
    }

    @Test fun writerFailureIsReportedAsPossiblyIncompleteWithoutRetryingOrExposingError() {
        val screen = screen()
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.failWrite = true
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertEquals(1, screen.writeAttempts)
        assertTrue(screen.writes.isEmpty())
        assertFalse(screen.export.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertFalse(screen.texts().any { it.contains(FAKE_PRIVATE_ERROR) })
        assertTrue(screen.status.contains("may be incomplete"))
    }

    @Test fun approvalIsConsumedBeforeQueuedWriteAndInputEditCancelsUnstartedWork() {
        val screen = screen()
        screen.queueWork = true
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertEquals(1, screen.work.size)
        assertFalse(screen.export.isEnabled)
        assertFalse(screen.approval.isChecked)
        assertEquals("", screen.confirmation.text.toString())
        assertTrue(screen.writes.isEmpty())
        screen.field(TITLE).setText("Revised application title")
        val invalidatedStatus = screen.status
        screen.finishWork()
        assertEquals(0, screen.writeAttempts)
        assertTrue(screen.writes.isEmpty())
        assertEquals(invalidatedStatus, screen.status)
        assertTrue(screen.generateButton.isEnabled)
    }

    @Test fun destroyingActivityDiscardsQueuedWriteAndCompletionCallback() {
        val screen = screen()
        screen.queueWork = true
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        val priorStatus = screen.status
        screen.controller.pause().stop().destroy()
        screen.finishWork()
        assertEquals(0, screen.writeAttempts)
        assertEquals(priorStatus, screen.status)
        assertTrue(screen.writes.isEmpty())
    }

    @Test fun queuedDocumentWriteHasOneBoundedAttemptAndNeedsNewApprovalForAnotherExport() {
        val screen = screen()
        screen.queueWork = true
        screen.approve(screen.generate())
        screen.export.performClick()
        screen.handle.onActivityResult(screen.requestCode, Activity.RESULT_OK, document())
        assertFalse(screen.generateButton.isEnabled)
        assertTrue(screen.writes.isEmpty())
        screen.finishWork()
        assertEquals(1, screen.writeAttempts)
        assertTrue(screen.writes.single().second.size in 1..1_048_576)
        assertFalse(screen.export.isEnabled)
        assertTrue(screen.generateButton.isEnabled)
        assertTrue(screen.status.contains("starter ZIP saved"))
    }

    @Test fun invalidSpecificationNeverReusesAnEarlierApprovedPreview() {
        val screen = screen()
        val plan = screen.generate()
        screen.approve(plan)
        screen.field(PACKAGE).setText("com.jarvis.developer")
        screen.generateButton.performClick()
        assertFalse(screen.export.isEnabled)
        assertFalse(screen.approval.isEnabled)
        assertFalse(screen.texts().any { it.contains(plan.fingerprint) })
        assertTrue(screen.status.contains("Preview unavailable"))
        assertTrue(screen.launches.isEmpty())
        assertTrue(screen.writes.isEmpty())
    }

    @Test fun newSafWriteAndScaffoldApprovalBoundariesAreOutsideGeneratedPatchScope() {
        listOf("ApplicationApprovalWorkbench.kt", "ApplicationScaffoldApproval.kt", "AndroidChecklistScaffold.kt").forEach { file ->
            val path = "developer/src/main/java/com/jarvis/developer/$file"
            assertFalse(path, PatchEngine.allowedPath(path))
            assertFalse(path, SelfDevelopment.allowedPath(path))
        }
    }

    private fun screen() = Screen().also { screens.add(it) }

    private class Screen {
        val controller: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()
        val activity: Activity = controller.get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val launches = mutableListOf<Pair<Intent, Int>>()
        val writes = mutableListOf<Pair<Uri, ByteArray>>()
        var failLaunch = false
        var failWrite = false
        var queueWork = false
        val work = ArrayDeque<() -> Unit>()
        var writeAttempts = 0
        val handle: ApplicationApprovalWorkbench.Handle
        init {
            activity.setContentView(ScrollView(activity).apply { addView(root) })
            handle = ApplicationApprovalWorkbench.attach(activity, root,
                ApplicationApprovalWorkbench.Services(
                    launch = { intent, code ->
                        if (failLaunch) throw IOException(FAKE_PRIVATE_ERROR)
                        launches.add(intent to code)
                    },
                    write = { uri, bytes ->
                        writeAttempts++
                        if (failWrite) throw IOException(FAKE_PRIVATE_ERROR)
                        writes.add(uri to bytes.copyOf())
                    },
                    worker = { task -> if (queueWork) work.add(task) else task() }))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun field(hint: String): EditText = descendants(root).filterIsInstance<EditText>()
            .single { it.hint.toString() == hint }
        val platform: Spinner get() = descendants(root).filterIsInstance<Spinner>().single { it.contentDescription == "Application platform" }
        val behavior: Spinner get() = descendants(root).filterIsInstance<Spinner>().single { it.contentDescription == "Android application behavior" }
        val approval: CheckBox get() = descendants(root).filterIsInstance<CheckBox>().single()
        val confirmation: EditText get() = field(CONFIRMATION)
        val generateButton: Button get() = button("Generate application preview")
        val export: Button get() = button("Export reviewed application ZIP")
        val requestCode: Int get() = launches.single().second
        val status: String get() = descendants(root).filterIsInstance<TextView>().last().text.toString()
        private fun button(label: String) = descendants(root).filterIsInstance<Button>().single { it.text.toString() == label }
        fun texts() = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
        fun generate(platform: ApplicationPlatform = ApplicationPlatform.ANDROID): ApplicationScaffoldPlan {
            val spec = ApplicationSpecification("Sample application", "A small application that displays a greeting.",
                platform, "SampleApplication", "org.example.sample")
            field(TITLE).setText(spec.title)
            field(PROJECT).setText(spec.projectName)
            field(PACKAGE).setText(spec.packageName)
            field(SPEC).setText(spec.writtenSpec)
            this.platform.setSelection(platform.ordinal)
            shadowOf(Looper.getMainLooper()).idle()
            generateButton.performClick()
            assertTrue(status, approval.isEnabled)
            return ApplicationScaffolds.plan(spec)
        }
        fun approve(plan: ApplicationScaffoldPlan) {
            confirmation.setText(plan.fingerprint)
            assertFalse(approval.isChecked)
            approval.isChecked = true
            assertTrue(export.isEnabled)
        }
        fun finishWork() {
            assertEquals(1, work.size)
            work.removeFirst().invoke()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    companion object {
        private const val TITLE = "Application title (up to 80 characters)"
        private const val PROJECT = "Project name (for example MyApplication)"
        private const val PACKAGE = "Independent package (for example org.example.myapp)"
        private const val SPEC = "Written application specification (up to 4000 characters)"
        private const val CONFIRMATION = "Enter exact application preview SHA-256"
        private const val FAKE_PRIVATE_ERROR = "fixture-private-error-never-display"
        private val DOCUMENT_URI = Uri.parse("content://fixture.documents/document/approved.zip")
        private fun document() = Intent().setData(DOCUMENT_URI)
        private fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
