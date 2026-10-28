package com.jarvis.developer

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Disposable emulator only. Source/CI are explicit fixtures; APK inspection and Android installation are real. */
class ArtifactInstallationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun guard() {
        check(InstrumentationRegistry.getArguments().getString("jarvisDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Installer acceptance requires a disposable emulator" }
    }
    private fun await(message: String, seconds: Int = 45, condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + seconds * 1000L
        while (android.os.SystemClock.uptimeMillis() < end) { if (condition()) return; Thread.sleep(100) }
        assertTrue(message, condition())
    }
    private fun status(job: String) = JarvisPackageInstaller.inbox(context).localSnapshot().single { it.getString("id") == job }
    @Suppress("DEPRECATION")
    private fun fixture(requestId: String, packageName: String = "com.assistant.core"): Pair<String, DevelopmentArtifact.Record> {
        val queue = JarvisPackageInstaller.inbox(context)
        val developer = packageName == "com.jarvis.developer"
        require(developer || packageName == "com.assistant.core")
        val request = Wire.Request(requestId, if (developer) "source.developer.analysis.request" else "source.personal.analysis.request", "Improve conversation tone")
        val job = queue.handle("com.assistant.core", true, request).getString("id")
        val path = if (developer) "developer/src/main/java/com/jarvis/developer/ConversationTone.kt" else "app/src/main/java/com/assistant/core/services/ConversationTone.kt"
        val text = "package ${if (developer) "com.jarvis.developer" else "com.assistant.core.services"}\nobject ConversationTone { val enabled = false }\n"
        val bytes = text.toByteArray(); val base = "a".repeat(40); val commit = "d".repeat(40)
        val blob = MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val inventory = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, blob, bytes.size.toLong())), false, base)
        val lease = queue.claimAnalysis(job, true)
            ?: error("Fixture analysis could not be claimed: state=${status(job).optString("state")} revision=${status(job).optLong("revision")}")
        val plan = RepositoryAnalysis.analyze(request.body, inventory) { text }
        check(queue.finishAnalysis(lease, plan)) {
            val snapshot = status(job)
            "Fixture analysis lease lost: state=${snapshot.optString("state")} revision=${snapshot.optLong("revision")} " +
                "phase=${snapshot.optString("phase")} request=${requestId}"
        }
        val draft = queue.stageEngineering(job, status(job).getLong("revision"), listOf(path), true, "Test the APK installation path in this disposable emulator")
        val proposal = PatchEngine.parse(PatchEngine.Request(draft.taskId, draft.task, draft.base, draft.paths,
            listOf(PatchEngine.SourceFile(path, blob, text)), "OpenAI", "gpt-4.1-mini", draft.branch, draft.target),
            JSONObject().put("summary", "Explicit source fixture only").put("edits", JSONArray().put(JSONObject()
                .put("path", path).put("content", text.replace("false", "true")))).toString())
        val write = queue.claimEngineering(draft, PatchEngine.approve(proposal, proposal.id), true)
        val r = write.receipt
        check(queue.finishEngineering(write, PatchPublisher.CommitResult(r.taskId, r.provider, r.model, r.proposalId,
            r.edits.map { it.path }, r.base, commit, r.branch, "", r.baseBranch, r.target)))
        val counts = JSONObject().put("tests", 1).put("failures", 0).put("errors", 0).put("skipped", 0)
        val suite = if (developer) "com.jarvis.developer.ConversationToneTest" else "PersonalJUnitEvidence"
        val summary = JSONObject().put("sourceCommit", commit).put("totals", counts)
            .put("suites", JSONArray().put(JSONObject(counts.toString()).put("name", suite).put("sha256", "c".repeat(64))))
            .put("cases", JSONArray().put(JSONObject().put("suite", suite).put("name", if (developer) "conversation" else "b".repeat(64)).put("result", "passed")))
        queue.recordEngineeringBuild(queue.claimEngineeringRead(job, true)!!,
            BuildDiagnostics.parse(commit, r.branch, 42, 1, "completed", "success", "JARVIS_JUNIT_SUMMARY=$summary", if (developer) PatchEngine.Target.DEVELOPER else PatchEngine.Target.PERSONAL))
        val source = queue.beginArtifact(job, status(job).getLong("revision"), true)
        val installed = context.packageManager.getPackageInfo(packageName, 0)
        val actual = File(installed.applicationInfo.sourceDir)
        val hash = DevelopmentArtifactFiles.sha256(actual)
        val record = DevelopmentArtifact.Record(commit, 42, 1, 99, "a".repeat(64), hash, actual.length(), installed.packageName,
            if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong(), installed.versionName!!,
            DevelopmentArtifactInspection.verifiedSigners(actual, Build.VERSION.SDK_INT).single(), "debug")
        val destination = DevelopmentArtifactFiles(context.cacheDir).apk(hash)
        val staging = DevelopmentArtifactFiles(context.cacheDir).temporaryApk()
        try {
            actual.copyTo(staging, overwrite = true)
            DevelopmentArtifactInspection.inspect(context, staging, record)
            if (!destination.exists()) check(staging.renameTo(destination))
        } finally { staging.delete() }
        DevelopmentArtifactInspection.inspect(context, destination, record)
        check(queue.recordArtifact(source, record))
        return job to record
    }
    @Test fun stagesASeparateDeveloperApkSoAssistantInstallationAcceptanceCannotKillItsOwnRunner() {
        guard()
        val (job, record) = fixture("native-voice-install-apk", "com.jarvis.developer")
        assertEquals("com.jarvis.developer", record.packageName)
        assertEquals("PASSED", status(job).getJSONObject("verification").getString("SIGNED_APK"))
        assertNull(JarvisPackageInstaller.inbox(context).installationRecord(job))
    }
    @Test fun realInstalledApkChecksRecordCurrentCoverageAndRejectADifferentVersion() {
        guard()
        val (job, record) = fixture("native-installed-checks", "com.jarvis.developer")
        val queue = JarvisPackageInstaller.inbox(context)
        val request = Wire.Request(UUID.randomUUID().toString(), Wire.INSTALLED_APK_CHECK,
            jobId = job, expectedRevision = status(job).getLong("revision"))
        val source = queue.installedCheckSource("com.assistant.core", true, request)
        val report = InstalledApkCheck.run(context, record)
        assertEquals(InstalledApkCheck.Problem.NONE, report.problem)
        assertTrue(report.checks.values.all { it == InstalledApkCheck.Result.PASSED })
        val recorded = queue.recordInstalledCheck(source, report)
        assertTrue(recorded.getJSONObject("artifact").getJSONObject("installedCheck").getBoolean("fresh"))
        assertEquals("BLOCKED", recorded.getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertFalse(recorded.getBoolean("deviceVerified")); assertNull(queue.installationRecord(job))
        val changed = InstalledApkCheck.run(context, record.copy(versionCode = record.versionCode + 1))
        assertEquals(InstalledApkCheck.Result.FAILED, changed.checks[InstalledApkCheck.Check.VERSION])
        assertEquals(InstalledApkCheck.Result.PASSED, changed.checks[InstalledApkCheck.Check.APK_BYTES])
    }
    private fun installButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.packageName?.toString()?.let { "packageinstaller" in it || "permissioncontroller" in it } != true) return null
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.isEnabled && node.text?.toString()?.trim()?.lowercase() in setOf("install", "update")) {
                var current = node
                repeat(3) { if (current.isClickable) return current; current = current.parent ?: return@repeat }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        return find(root)
    }
    @Test fun actualAndroidConfirmationAndInstalledBytesAreRecordedWithoutClaimingDeviceAcceptance() {
        guard()
        val (job, record) = fixture("native-confirmed-apk")
        shell("appops set com.jarvis.developer REQUEST_INSTALL_PACKAGES deny")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                instrumentation.waitForIdleSync()
                activity.onActivity { owner ->
                    assertFalse(JarvisPackageInstaller.canRequestInstall(owner))
                    JarvisPackageInstaller.installVerified(owner, job, status(job).getLong("revision"), record) { }
                }
                assertTrue(JarvisPackageInstaller.inbox(context).pendingInstallations().isEmpty())
                assertEquals("PENDING", status(job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
                var observedForeground = "<no active window>"
                try {
                    await("Android installation-source settings did not open", 45) {
                        observedForeground = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                            ?: "<no active window>"
                        observedForeground == "com.android.settings"
                    }
                } catch (failure: AssertionError) {
                    val activityState = shell("dumpsys activity activities")
                        .lineSequence().filter { it.contains("mResumedActivity") ||
                            it.contains("topResumedActivity") || it.contains("Resumed:") }
                        .take(8).joinToString(" | ").take(1200)
                    throw AssertionError(
                        "Android installation-source settings did not open; foreground=$observedForeground; " +
                            "activityState=$activityState", failure)
                }
                shell("input keyevent KEYCODE_BACK")
                await("Developer did not resume after Android settings") {
                    instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.jarvis.developer"
                }
                shell("appops set com.jarvis.developer REQUEST_INSTALL_PACKAGES allow")
                activity.onActivity { owner ->
                    assertTrue(JarvisPackageInstaller.canRequestInstall(owner))
                    JarvisPackageInstaller.installVerified(owner, job, status(job).getLong("revision"), record) { }
                }
                await("Android did not request user confirmation") {
                    status(job).optJSONObject("artifact")?.optString("installation") == "PENDING_USER_ACTION"
                }
                activity.onActivity { owner ->
                    runCatching { JarvisPackageInstaller.continueConfirmation(owner, JarvisPackageInstaller.inbox(context).installationRecord(job)!!) }
                }
                var button: AccessibilityNodeInfo? = null
                await("Android's Install or Update control was not visible") {
                    button = instrumentation.uiAutomation.rootInActiveWindow?.let(::installButton); button != null
                }
                assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("Android success and actual installed APK bytes were not recorded", 75) {
                    status(job).optJSONObject("artifact")?.optString("installation") == "PACKAGE_PRESENT"
                }
                assertTrue(DevelopmentArtifactInspection.packagePresent(context, record))
                assertFalse(status(job).getBoolean("deviceVerified"))
                assertEquals("BLOCKED", status(job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
                println("Actual PackageInstaller confirmation, callback and installed APK digest verified; device acceptance remains separate.")
            }
        } finally {
            // The host restores the permission after instrumentation exits. On Android 14,
            // revocation deliberately kills the installer app, including its test runner.
        }
    }
    @Test fun cancellingAnOwnedUncommittedSessionRejectsStaleCallbacksAndRecoveryDoesNotInstall() {
        guard()
        val (job, record) = fixture("native-cancelled-apk"); val queue = JarvisPackageInstaller.inbox(context)
        val initial = queue.beginInstallation(job, status(job).getLong("revision"), record.apkSha256, true)
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(record.packageName) })
        val request = queue.bindInstallation(initial, id)
        // Matching bytes already installed must not complete an owned live reinstall session.
        JarvisPackageInstaller.recover(context)
        assertEquals("OUTCOME_UNKNOWN", status(job).getJSONObject("artifact").getString("installation"))
        JarvisPackageInstaller.cancel(context, request)
        assertEquals("CANCELLED", status(job).getJSONObject("artifact").getString("installation"))
        await("Android did not finish abandoning the owned session", 5) { installer.getSessionInfo(id) == null }
        JarvisPackageInstaller.receive(context, Intent().setData(Uri.parse("jarvis-job-install://callback/${request.nonce}"))
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, id).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        JarvisPackageInstaller.recover(context)
        assertEquals("CANCELLED", status(job).getJSONObject("artifact").getString("installation"))
        assertEquals("BLOCKED", status(job).getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertTrue(queue.pendingInstallations().none { it.job == job })
    }
}
