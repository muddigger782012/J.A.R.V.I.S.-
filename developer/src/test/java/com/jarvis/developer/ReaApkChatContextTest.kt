package com.jarvis.developer

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests the real Android workbench state transitions, without network or APK execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReaApkChatContextTest {

    private class Fixture(val activity: Activity) {
        val analyze = Button(activity)
        val local = Button(activity)
        val proposal = Button(activity)
        val search = Button(activity)
        val inspectClass = Button(activity)
        val inspectMethod = Button(activity)
        val references = Button(activity)
        val cancel = Button(activity)
        val note = TextView(activity)
        val apkBytes = byteArrayOf(80, 75, 3, 4, 1, 2, 3, 4)
        val sha = ReaApkAnalysis.sha256(apkBytes)
        val apk = ReaApkAnalysis.Selected(apkBytes, sha)
        val report = ReaApkAnalysis.Report(sha, "b".repeat(64), "static data", false)
        val handle = ReaApkWorkbench.Handle(
            activity = activity,
            prompt = EditText(activity),
            output = TextView(activity),
            status = TextView(activity),
            analyzeButton = analyze,
            localButton = local,
            proposalButton = proposal,
            classButton = search,
            detailButton = inspectClass,
            methodButton = inspectMethod,
            referencesButton = references,
            cancelButton = cancel,
            attachmentNote = note,
            product = { 0 },
            project = { "developer" },
            ready = { true },
            message = { },
            prepare = { _, _, _, _ -> false },
            onReview = { },
            worker = { _ -> error("These state transitions must not schedule work") }
        )

        fun assign(name: String, value: Any?) {
            val field = handle.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(handle, value)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> value(name: String): T? {
            val field = handle.javaClass.getDeclaredField(name)
            field.isAccessible = true
            return field.get(handle) as T?
        }

        fun setCompleteEvidence() {
            assign("selected", apk)
            assign("report", report)
        }
    }

    @Test fun newChatTurnKeepsCompletedApkEvidenceButDoesNotApproveEdits() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val x = Fixture(controller.get())
            x.setCompleteEvidence()
            x.handle.invalidateOffer()
            assertSame(x.apk, x.value<ReaApkAnalysis.Selected>("selected"))
            assertSame(x.report, x.value<ReaApkAnalysis.Report>("report"))
            assertArrayEquals(byteArrayOf(80, 75, 3, 4, 1, 2, 3, 4), x.apkBytes)
            assertEquals(View.VISIBLE, x.search.visibility)
            assertEquals(View.VISIBLE, x.proposal.visibility)
            assertTrue(x.search.isEnabled)
            assertTrue(x.proposal.isEnabled)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun interruptingActiveAnalysisWipesApkBytesAndDiscardsEvidence() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val x = Fixture(controller.get())
            x.setCompleteEvidence()
            x.assign("busy", true)
            x.handle.invalidateOffer()
            assertNull(x.value<ReaApkAnalysis.Selected>("selected"))
            assertNull(x.value<ReaApkAnalysis.Report>("report"))
            assertArrayEquals(ByteArray(8), x.apkBytes)
            assertFalse(x.search.isEnabled)
            assertEquals(View.GONE, x.proposal.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }


    @Test fun cancelTaskDiscardsPendingResultAndRestoresChatWithoutClaimingHostCancellation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val x = Fixture(controller.get())
            x.setCompleteEvidence()
            x.assign("busy", true)
            val refresh = x.handle.javaClass.getDeclaredMethod("showControls")
            refresh.isAccessible = true
            refresh.invoke(x.handle)
            assertEquals(View.VISIBLE, x.cancel.visibility)
            assertTrue(x.cancel.isEnabled)
            x.handle.cancelPending()
            assertNull(x.value<ReaApkAnalysis.Selected>("selected"))
            assertNull(x.value<ReaApkAnalysis.Report>("report"))
            assertArrayEquals(ByteArray(8), x.apkBytes)
            assertEquals(View.GONE, x.cancel.visibility)
            assertEquals(View.GONE, x.search.visibility)
            assertTrue(x.note.text.toString().contains("Select an APK again"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun newChatAndBackgroundBothClearEvidenceEvenWhenIdle() {
        for (background in listOf(false, true)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                val x = Fixture(controller.get())
                x.setCompleteEvidence()
                if (background) x.handle.onPaused() else x.handle.reset()
                assertNull(x.value<ReaApkAnalysis.Selected>("selected"))
                assertNull(x.value<ReaApkAnalysis.Report>("report"))
                assertArrayEquals(ByteArray(8), x.apkBytes)
                assertEquals(View.GONE, x.search.visibility)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }
}
