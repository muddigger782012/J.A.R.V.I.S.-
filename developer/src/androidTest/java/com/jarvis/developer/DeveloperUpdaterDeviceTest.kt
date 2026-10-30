package com.jarvis.developer

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/** Disposable emulator only. UI fixtures never invoke Android installation; the public APK probe is read-only. */
class DeveloperUpdaterDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun disposable() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("jarvis_disposable_emulator"))
    }
    private fun tap(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val matches = root?.findAccessibilityNodeInfosByText(label).orEmpty()
            val control = matches.firstOrNull { it.text?.toString()?.equals(label, ignoreCase = true) == true && it.isEnabled }
            if (control != null && control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync(); return
            }
            SystemClock.sleep(50)
        }
        fail("Visible update action was unavailable")
    }
    private class Fixture(val work: ArrayDeque<() -> Unit>, val cache: File) : DeveloperUpdateSource {
        val release = DeveloperUpdateRelease.Release(7, 9, "a".repeat(40), 185, 10, "b".repeat(64))
        var downloads = 0; var checks = 0; var installs = 0
        var file: File? = null
        override fun latest() = release
        override fun download(release: DeveloperUpdateRelease.Release): File {
            downloads++
            return File.createTempFile("explicit-native-update-", ".apk", cache).apply { writeText("unsigned modeled UI payload"); file = this }
        }
        override fun verify(file: File, release: DeveloperUpdateRelease.Release) { checks++ }
        fun services() = DeveloperUpdater.Services(source = { _, _ -> this }, installed = { 184 }, permission = { true },
            install = { _, _ -> installs++; true }, worker = { _, operation -> work.add(operation); Unit })
    }
    private fun drain(work: ArrayDeque<() -> Unit>) {
        assertFalse(work.isEmpty()); work.removeFirst().invoke(); instrumentation.waitForIdleSync()
    }
    @Test fun realDialogRequiresDistinctDownloadAndInstallAndAcceptsItsOwnedFocusedWindow() {
        disposable()
        val work = ArrayDeque<() -> Unit>(); val fixture = Fixture(work, instrumentation.targetContext.cacheDir)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { DeveloperUpdater.check(it, {}, fixture.services()) }
                drain(work); assertEquals(0, fixture.downloads); assertEquals(0, fixture.installs)
                tap("Download"); drain(work); assertEquals(1, fixture.downloads); assertEquals(0, fixture.installs)
                tap("Install"); drain(work); assertEquals(1, fixture.checks); assertEquals(1, fixture.installs)
                assertTrue(fixture.file!!.isFile)
            }
        } finally { fixture.file?.delete() }
    }
    @Test fun realCancelDuringDownloadDiscardsLatePayloadWithoutInstallation() {
        disposable()
        val work = ArrayDeque<() -> Unit>(); val fixture = Fixture(work, instrumentation.targetContext.cacheDir)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { DeveloperUpdater.check(it, {}, fixture.services()) }
                drain(work); tap("Download"); tap("Cancel"); drain(work)
                assertEquals(0, fixture.installs); assertFalse(fixture.file!!.exists())
            }
        } finally { fixture.file?.delete() }
    }
    @Test fun actualPublishedApkPassesDigestSignatureAndArchiveChecksButCannotReplaceDebugIdentity() {
        disposable()
        val context = instrumentation.targetContext
        val cancellation = PatchPreviewCancellation()
        val reader = DeveloperUpdateDownload(context.cacheDir, { file, release ->
            assertEquals(setOf(DeveloperUpdateRelease.SIGNER), DevelopmentArtifactInspection.verifiedSigners(file, android.os.Build.VERSION.SDK_INT))
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            assertNotNull(archive); assertEquals(DeveloperUpdateRelease.PACKAGE, archive!!.packageName)
            assertEquals(release.version, DevelopmentArtifactInspection.version(archive)); assertEquals(release.versionName, archive.versionName)
            assertEquals(DevelopmentArtifact.Problem.SIGNATURE, assertThrows(DevelopmentArtifact.Failure::class.java) {
                DevelopmentArtifactInspection.inspectUpdate(context, file, release)
            }.problem)
        }, cancellation)
        var file: File? = null
        try {
            val release = reader.latest(); file = reader.download(release); reader.verify(file, release)
            println("JARVIS_UPDATE_READONLY_APK_VERIFIED source=${release.source} version=${release.version} sha256=${release.sha256} installed=false")
        } finally { cancellation.cancel(); file?.delete() }
    }
}
