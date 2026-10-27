package com.assistant.core.services

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.MainActivity
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Assistant -> authenticated Binder -> existing Developer screen. APK/source setup is the earlier explicit fixture. */
class DevelopmentArtifactHandoffAndroidTest {
    @Test fun actualAssistantOpensTheExactOlderJobReviewWithoutRequestingInstallation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("jarvisDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        fun call(kind: String, body: String = "", job: String = ""): JSONObject {
            val response = DevelopmentBridgeClient(context).request(Wire.Request(UUID.randomUUID().toString(), kind, body, jobId = job))
            assertEquals("OK", response.getString("code")); return response.getJSONObject("data")
        }
        val jobs = call("task.list.request").getJSONArray("jobs")
        val before = (0 until jobs.length()).map { jobs.getJSONObject(it) }.last {
            it.optJSONObject("artifact")?.optString("installation") == "PACKAGE_PRESENT"
        }
        val job = before.getString("id")
        lastReviewedJob = job
        repeat(9) { call("diagnostics.review.request", "Read-only handoff history fixture $it") }
        val newest = call("task.list.request").getJSONArray("jobs")
        assertFalse((0 until newest.length()).any { newest.getJSONObject(it).getString("id") == job })
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val foregroundDeadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < foregroundDeadline &&
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.assistant.core") SystemClock.sleep(100)
        assertEquals("com.assistant.core", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        assertEquals("CANCELLED", assertThrows(Wire.Rejected::class.java) {
            DeveloperBridgeOperations(context).reviewApkForJob(job) { false }
        }.code)
        assertEquals(before.getLong("revision"), call("task.status.request", job = job).getLong("revision"))
        val message = DeveloperBridgeOperations(context).reviewApkForJob(job)
        assertTrue(message.contains("no installation was requested"))
        val deadline = SystemClock.uptimeMillis() + 45_000
        var opened: JSONObject? = null
        var visible = false
        while (SystemClock.uptimeMillis() < deadline) {
            opened = call("task.status.request", job = job)
            fun hasReview(node: android.view.accessibility.AccessibilityNodeInfo): Boolean =
                node.contentDescription?.toString() == "Verified APK review from Assistant" && node.isVisibleToUser ||
                    (0 until node.childCount).any { i -> node.getChild(i)?.let(::hasReview) == true }
            visible = instrumentation.uiAutomation.rootInActiveWindow?.let(::hasReview) == true
            if (opened.optJSONObject("artifactReview")?.optBoolean("consumed") == true && visible) break
            SystemClock.sleep(150)
        }
        assertTrue("The exact job's authenticated handoff was not recorded", opened!!.getJSONObject("artifactReview").getBoolean("consumed"))
        assertTrue("The focused APK review was not visible in Developer's preserved Tools screen", visible)
        assertEquals("com.jarvis.developer", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        assertEquals(before.getJSONObject("artifact").getString("installation"), opened.getJSONObject("artifact").getString("installation"))
        assertFalse(opened.getBoolean("deviceVerified"))
        assertEquals("BLOCKED", opened.getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertEquals("READY_FOR_REVIEW", opened.getString("state"))
    }

    @Test fun authenticatedTicketSurvivesDeveloperProcessStopAndCannotBeReplayed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("jarvisDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        fun exchange(request: Wire.Request): JSONObject = DevelopmentBridgeClient(context).request(request).also {
            assertEquals("OK", it.getString("code"))
        }.getJSONObject("data")
        fun status(job: String) = exchange(Wire.Request(UUID.randomUUID().toString(), "task.status.request", jobId = job))
        val jobs = exchange(Wire.Request(UUID.randomUUID().toString(), "task.list.request")).getJSONArray("jobs")
        // This test may run before or after the older-job selection case.
        // The persisted installation fixture is selected from Developer's full local job,
        // retained as the previous focused review when it is outside the latest-eight list.
        val job = (0 until jobs.length()).map { jobs.getJSONObject(it) }.lastOrNull {
            it.optJSONObject("artifact")?.optString("installation") == "PACKAGE_PRESENT"
        }?.getString("id") ?: requireNotNull(lastReviewedJob)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val foregroundDeadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < foregroundDeadline &&
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.assistant.core") SystemClock.sleep(100)
        assertEquals("com.assistant.core", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val current = status(job)
        val handoff = exchange(Wire.Request(UUID.randomUUID().toString(), Wire.ARTIFACT_REVIEW,
            jobId = job, expectedRevision = current.getLong("revision")))
        val intent = Intent(Wire.ARTIFACT_REVIEW_ACTION)
            .setComponent(android.content.ComponentName(Wire.DEVELOPER_PACKAGE, "com.jarvis.developer.MainActivity"))
            .putExtra(Wire.ARTIFACT_REVIEW_EXTRA, handoff.getJSONObject("artifactReview").getString("ticket"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("am force-stop com.jarvis.developer")).bufferedReader().use { it.readText() }
        context.startActivity(intent)
        val deadline = SystemClock.uptimeMillis() + 45_000
        var opened = status(job)
        while (!opened.getJSONObject("artifactReview").getBoolean("consumed") && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(150); opened = status(job)
        }
        assertTrue(opened.getJSONObject("artifactReview").getBoolean("consumed"))
        val revision = opened.getLong("revision")
        fun rejectedDeliveries(): Int = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("logcat -d -s DeveloperReview:I")).bufferedReader().use { reader ->
                reader.lineSequence().count { "artifact.handoff.rejected" in it }
            }
        val rejections = rejectedDeliveries()
        context.startActivity(intent)
        val replayDeadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < replayDeadline && rejectedDeliveries() == rejections) SystemClock.sleep(150)
        assertTrue("Developer did not process and reject the replayed delivery", rejectedDeliveries() > rejections)
        assertEquals(revision, status(job).getLong("revision"))
        assertEquals("PACKAGE_PRESENT", status(job).getJSONObject("artifact").getString("installation"))
        assertFalse(status(job).getBoolean("deviceVerified"))
    }
    companion object { private var lastReviewedJob: String? = null }
}
