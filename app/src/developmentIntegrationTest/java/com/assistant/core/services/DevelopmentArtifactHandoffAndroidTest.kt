package com.assistant.core.services

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.assistant.core.MainActivity
import com.assistant.core.R
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real Assistant -> authenticated Binder -> existing Developer screen. APK/source setup is the earlier explicit fixture. */
class DevelopmentArtifactHandoffAndroidTest {
    /** Disposable-fixture diagnostics only: geometry and known UI labels, never arbitrary field text. */
    private fun reportReviewVisibility(instrumentation: android.app.Instrumentation) {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        try {
            val report = JSONObject().put("activeBefore", automation.rootInActiveWindow?.packageName?.toString())
            info.flags = previousFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            val windows = JSONArray()
            var inspected = 0
            var recorded = 0
            fun nodes(node: android.view.accessibility.AccessibilityNodeInfo, items: JSONArray) {
                if (++inspected > 240 || recorded >= 48) return
                val description = node.contentDescription?.toString().orEmpty()
                val text = node.text?.toString().orEmpty()
                val known = text in setOf("Home", "Projects", "Activity", "Settings") ||
                    text.startsWith("Verified APK review from Assistant") || text.startsWith("Review history could not be read") ||
                    text.startsWith("The requested review is unavailable") || description == "Development history position"
                if (known || description == "Verified APK review from Assistant" || node.className?.toString() == "android.widget.ScrollView") {
                    val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                    items.put(JSONObject().put("class", node.className?.toString()).put("bounds", bounds.toShortString())
                        .put("visible", node.isVisibleToUser).put("description", description.take(100))
                        .put("label", if (known) text.take(160) else ""))
                    recorded++
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { nodes(it, items) }
            }
            for (window in automation.windows.take(8)) {
                val bounds = android.graphics.Rect(); window.getBoundsInScreen(bounds)
                val items = JSONArray(); window.root?.let { nodes(it, items) }
                windows.put(JSONObject().put("package", window.root?.packageName?.toString()).put("type", window.type)
                    .put("active", window.isActive).put("focused", window.isFocused).put("bounds", bounds.toShortString()).put("nodes", items))
            }
            report.put("windows", windows).put("activeAfter", automation.rootInActiveWindow?.packageName?.toString())
            val encoded = report.toString()
            check(encoded.toByteArray(Charsets.UTF_8).size <= 20_000)
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("review_visibility_debug", encoded) })
        } catch (_: Exception) {
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("review_visibility_debug", "Bounded window diagnostics unavailable") })
        } finally {
            info.flags = previousFlags; automation.serviceInfo = info
        }
    }
    @Test fun actualAssistantOpensTheExactOlderJobReviewWithoutRequestingInstallation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("jarvisDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        fun call(kind: String, body: String = "", job: String = ""): JSONObject {
            val response = DevelopmentBridgeClient(context).request(Wire.Request(UUID.randomUUID().toString(), kind, body, jobId = job))
            assertEquals("OK", response.getString("code")); return response.getJSONObject("data")
        }
        val retainedRequest = Wire.Request("native-confirmed-apk", "source.personal.analysis.request", "Improve conversation tone")
        val admitted = DevelopmentBridgeClient(context).request(retainedRequest)
        assertEquals("OK", admitted.getString("code"))
        val before = admitted.getJSONObject("data")
        assertTrue(before.getBoolean("duplicate"))
        assertEquals("PACKAGE_PRESENT", before.getJSONObject("artifact").getString("installation"))
        val job = before.getString("id")
        repeat(9) { call("diagnostics.review.request", "Read-only handoff history fixture $it") }
        val newest = call("task.list.request").getJSONArray("jobs")
        assertFalse((0 until newest.length()).any { newest.getJSONObject(it).getString("id") == job })
        // Retain the actual acknowledged request as Assistant's current project context.
        // No fabricated success response or owner credential is supplied.
        val storage = PersonalStateFile(context, "developer-outbox.json")
        storage.access {
            val records = read()?.let(::JSONArray) ?: JSONArray()
            assertTrue(records.length() < 16)
            records.put(JSONObject().put("request", Wire.encode(retainedRequest)).put("transport", "PENDING"))
            assertTrue(write(records.toString()))
        }
        DevelopmentReviewOutbox(storage).record(retainedRequest, admitted)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val foregroundDeadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < foregroundDeadline &&
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != "com.assistant.core") SystemClock.sleep(100)
            assertEquals("com.assistant.core", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            assertEquals("CANCELLED", assertThrows(Wire.Rejected::class.java) {
                DeveloperBridgeOperations(context).reviewApkForJob(job) { false }
            }.code)
            assertEquals(before.getLong("revision"), call("task.status.request", job = job).getLong("revision"))
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.etCommandInput)
                    .setText("Can you open the APK review in Developer?")
                assertTrue(activity.findViewById<android.view.View>(R.id.btnRunAssistantCommand).performClick())
            }
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
            if (!visible) reportReviewVisibility(instrumentation)
            assertTrue("The focused APK review was not visible in Developer's Activity screen", visible)
            assertEquals("com.jarvis.developer", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            assertEquals(before.getJSONObject("artifact").getString("installation"), opened.getJSONObject("artifact").getString("installation"))
            assertFalse(opened.getBoolean("deviceVerified"))
            assertEquals("BLOCKED", opened.getJSONObject("verification").getString("DEVICE_INSTALLED"))
            assertEquals("READY_FOR_REVIEW", opened.getString("state"))
        }
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
        val acknowledged = exchange(Wire.Request("native-confirmed-apk", "source.personal.analysis.request", "Improve conversation tone"))
        assertTrue(acknowledged.getBoolean("duplicate"))
        val job = acknowledged.getString("id")
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
}
