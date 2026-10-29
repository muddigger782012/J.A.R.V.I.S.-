package com.jarvis.developer

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

/** Actual widgets/lifecycle; source verification and Android handoff are explicit instance-local fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class DeveloperUpdaterAndroidTest {
    private val controllers = mutableListOf<ActivityController<out Activity>>()
    private val work = ArrayDeque<() -> Unit>()
    private val messages = mutableListOf<String>()
    private val release = DeveloperUpdateRelease.Release(7, 9, "a".repeat(40), 185, 10, "b".repeat(64))
    private var installed = 184L
    private var permission = true
    private var foreground = true
    private var opened = true
    private var installations = 0
    private inner class Source : DeveloperUpdateSource {
        var downloads = 0; var checks = 0
        var failure: Exception? = null
        var verifyFailure: Exception? = null
        var token: PatchPreviewCancellation? = null
        var file: File? = null
        override fun latest(): DeveloperUpdateRelease.Release { failure?.let { throw it }; return release }
        override fun download(release: DeveloperUpdateRelease.Release): File {
            downloads++
            return File.createTempFile("explicit-updater-widget-", ".apk").apply { writeText("unsigned modeled UI payload"); file = this }
        }
        override fun verify(file: File, release: DeveloperUpdateRelease.Release) { checks++; verifyFailure?.let { throw it } }
    }
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().also { controllers += it }.get()
    private fun services(source: Source) = DeveloperUpdater.Services(
        source = { _, token -> source.apply { this.token = token } }, installed = { installed }, permission = { permission },
        foreground = { _, _ -> foreground }, install = { _, _ -> installations++; opened },
        worker = { _, operation -> work.add(operation); Unit })
    private fun start(activity: Activity, source: Source) { DeveloperUpdater.check(activity, { messages += it }, services(source)); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun runWork() { assertTrue(work.isNotEmpty()); work.removeFirst().invoke(); idle() }
    private fun dialog(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog().also { assertTrue(it.isShowing) }
    private fun positive() { dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle() }
    private fun negative() { dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle() }
    @After fun cleanup() {
        controllers.forEach { controller ->
            DeveloperUpdater.cancel(controller.get())
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
        work.clear(); idle()
    }

    @Test fun downloadVerificationAndInstallAreSeparateExplicitActionsAndNeverClaimInstallation() {
        val source = Source(); val activity = activity(); start(activity, source)
        assertEquals(0, source.downloads); assertEquals(0, installations)
        runWork(); assertEquals("Download", dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        positive(); assertEquals(0, installations); assertFalse(dialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        runWork(); assertEquals(1, source.downloads); assertEquals(0, installations)
        assertEquals("Install", dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        positive(); assertEquals(0, installations); runWork()
        assertEquals(1, installations); assertEquals(1, source.checks)
        assertTrue(messages.last().contains("installation has not been verified"))
        assertTrue(source.file!!.isFile) // Android may still be reading its granted URI.
        DeveloperUpdater.cancel(activity); assertTrue(source.file!!.isFile); source.file!!.delete()
    }
    @Test fun cancelBeforeDownloadDoesNotReadAnApkOrRequestInstallation() {
        val source = Source(); start(activity(), source); runWork(); negative()
        assertEquals(0, source.downloads); assertEquals(0, installations); assertTrue(source.token!!.isCancelled)
    }
    @Test fun cancelDuringQueuedDownloadDiscardsEvenAnIgnoringProvidersLateFileAndCallback() {
        val source = Source(); start(activity(), source); runWork(); positive(); negative(); val last = messages.last()
        runWork(); assertEquals(last, messages.last()); assertFalse(source.file!!.exists()); assertEquals(0, installations)
    }
    @Test fun cancelDuringInstallRecheckNeverOpensAnInstaller() {
        val source = Source(); start(activity(), source); runWork(); positive(); runWork(); positive(); negative(); runWork()
        assertEquals(0, installations); assertFalse(source.file!!.exists())
    }
    @Test fun replacementCheckRejectsEarlierFailureWithoutDismissingCurrentReview() {
        val activity = activity(); val earlier = Source().apply { failure = IllegalStateException("EXPLICIT_PRIVATE_FIXTURE") }
        val current = Source(); start(activity, earlier); start(activity, current)
        runWork(); assertFalse(messages.last().contains("EXPLICIT_PRIVATE_FIXTURE")); runWork()
        assertTrue(dialog().isShowing); assertTrue(earlier.token!!.isCancelled); assertEquals(0, installations)
    }
    @Test fun realMainActivityDestructionCancelsUpdateAndRejectsQueuedCallbacks() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); controllers += controller
        val source = Source(); start(controller.get(), source); val last = messages.last()
        controller.pause().stop().destroy(); assertTrue(source.token!!.isCancelled)
        runWork(); assertEquals(last, messages.last()); assertEquals(0, installations)
    }
    @Test fun leavingSettingsCancelsTheRealMainActivityUpdateBeforeItsLateDialogCallback() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup(); controllers += controller
        fun views(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun tab(name: String) = views(controller.get().window.decorView).filterIsInstance<android.widget.Button>()
            .single { it.text.toString() == name && it.contentDescription?.toString()?.startsWith(name) == true }
        tab("Settings").performClick(); idle()
        val source = Source(); start(controller.get(), source)
        tab("Home").performClick(); idle(); assertTrue(source.token!!.isCancelled)
        val last = messages.last(); assertTrue(last.contains("review closed")); runWork()
        assertEquals(last, messages.last()); assertEquals(0, installations)
    }
    @Test fun backgroundedReviewRequiresAnotherExplicitInstallAndFreshVerification() {
        val source = Source(); start(activity(), source); runWork(); positive(); runWork()
        foreground = false; positive(); runWork()
        assertEquals(0, installations); assertTrue(messages.last().contains("tap Install again"))
        foreground = true; assertEquals(0, installations); positive(); runWork()
        assertEquals(2, source.checks); assertEquals(1, installations); source.file!!.delete()
    }
    @Test fun missingAndroidPermissionKeepsReviewAndRequiresFreshOwnerActionAfterSettings() {
        val source = Source(); start(activity(), source); runWork(); positive(); runWork()
        permission = false; opened = false; positive(); runWork()
        assertTrue(messages.last().contains("Android settings")); assertTrue(dialog().isShowing)
        assertTrue(source.file!!.exists()); assertEquals(1, source.checks)
        permission = true; opened = true; assertEquals(1, installations)
        positive(); runWork(); assertEquals(2, source.checks); assertEquals(2, installations); source.file!!.delete()
    }
    @Test fun changedPublishedSourceBlocksInstallRemovesItsCacheAndGivesAnActionableMessage() {
        val source = Source(); start(activity(), source); runWork(); positive(); runWork()
        source.verifyFailure = DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.CHANGED)
        positive(); runWork(); assertEquals(0, installations); assertFalse(source.file!!.exists())
        assertTrue(messages.last().contains("changed during review"))
    }
    @Test fun alreadyCurrentOrNewerInstallationDoesNotOfferADowngrade() {
        for (version in listOf(185L, 186L)) {
            installed = version; val source = Source(); start(activity(), source); runWork()
            assertEquals(0, source.downloads); assertTrue(messages.last().contains("No newer Developer update"))
        }
        assertEquals(0, installations)
    }
    @Test fun concurrentUpgradeAfterDownloadIsReportedAccuratelyWithoutAnotherInstall() {
        val source = Source(); start(activity(), source); runWork(); positive(); runWork()
        installed = 185; positive(); runWork()
        assertEquals(0, installations); assertFalse(source.file!!.exists())
        assertTrue(messages.last().contains("already current or newer"))
    }
    @Test fun rawNetworkErrorsCannotReachTheStatusOrLeaveAnInstallAction() {
        val source = Source().apply { failure = IllegalStateException("EXPLICIT_PRIVATE_FIXTURE") }
        start(activity(), source); runWork(); assertFalse(messages.joinToString().contains("EXPLICIT_PRIVATE_FIXTURE"))
        assertEquals(0, installations); assertTrue(source.token!!.isCancelled)
    }
    @Test fun backOnReviewCancelsRatherThanLeavingAHiddenApproval() {
        val source = Source(); start(activity(), source); runWork(); dialog().cancel(); idle()
        assertTrue(source.token!!.isCancelled); assertEquals(0, installations)
    }
}
