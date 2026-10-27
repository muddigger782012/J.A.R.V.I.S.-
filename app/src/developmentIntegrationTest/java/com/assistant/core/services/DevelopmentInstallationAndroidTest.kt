package com.assistant.core.services

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.MainActivity
import com.assistant.core.R
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Disposable emulator: synthetic source/build setup, actual Assistant, Binder, APK and Android installer. */
class DevelopmentInstallationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun guard() {
        check(InstrumentationRegistry.getArguments().getString("jarvisDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun call(kind: String, job: String = "", revision: Long? = null): JSONObject =
        DevelopmentBridgeClient(context).request(Wire.Request(UUID.randomUUID().toString(), kind,
            jobId = job, expectedRevision = revision)).also { assertEquals("OK", it.getString("code")) }.getJSONObject("data")
    private fun status(job: String) = call("task.status.request", job)
    private fun await(message: String, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 45_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(100) }
        assertTrue(message + diagnostics(), condition())
    }
    private fun fixture(): String {
        guard()
        val request = Wire.Request("native-voice-install-apk", "source.developer.analysis.request", "Improve conversation tone")
        val reply = DevelopmentBridgeClient(context).request(request)
        assertEquals("OK", reply.getString("code")); assertTrue(reply.getJSONObject("data").getBoolean("duplicate"))
        val storage = PersonalStateFile(context, "developer-outbox.json")
        storage.access {
            val old = read()?.let(::JSONArray) ?: JSONArray()
            val rows = JSONArray((0 until old.length()).map { old.getJSONObject(it) }
                .filter { Wire.decode(it.getString("request")) != request })
            assertTrue(rows.length() < 16)
            rows.put(JSONObject().put("request", Wire.encode(request)).put("transport", "PENDING"))
            assertTrue(write(rows.toString()))
        }
        DevelopmentReviewOutbox(storage).record(request, reply)
        val job = reply.getJSONObject("data").getString("id")
        assertEquals("PASSED", status(job).getJSONObject("verification").getString("SIGNED_APK"))
        assertTrue(call("capabilities.request").getBoolean("conversationalArtifactInstall"))
        return job
    }
    private fun send(scenario: ActivityScenario<MainActivity>, text: String) = scenario.onActivity { activity ->
        val button = activity.findViewById<View>(R.id.btnRunAssistantCommand)
        assertTrue(button.isShown)
        activity.findViewById<android.widget.EditText>(R.id.etCommandInput).setText(text)
        assertTrue(button.performClick())
    }
    private fun visibleReply(scenario: ActivityScenario<MainActivity>, text: String): Boolean {
        var found = false
        fun contains(view: View): Boolean = view is TextView && view.isShown && view.text.toString().contains(text) ||
            view is ViewGroup && (0 until view.childCount).any { contains(view.getChildAt(it)) }
        scenario.onActivity {
            val messages = it.findViewById<ViewGroup>(R.id.chatMessages)
            val last = messages.getChildAt(messages.childCount - 1) as? ViewGroup
            found = last != null && (last.getChildAt(0) as? TextView)?.text?.toString() == "J.A.R.V.I.S." && contains(last)
        }
        return found
    }
    private fun lastReply(scenario: ActivityScenario<MainActivity>): String {
        var result = "No reply card"
        scenario.onActivity {
            val messages = it.findViewById<ViewGroup>(R.id.chatMessages)
            val last = messages.getChildAt(messages.childCount - 1) as? ViewGroup
            if (last != null) result = (0 until last.childCount).mapNotNull { n -> (last.getChildAt(n) as? TextView)?.text?.toString() }.joinToString(" | ").take(1200)
        }
        return result
    }
    private fun foreground() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
    private fun installButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.packageName?.toString()?.let { "packageinstaller" in it || "permissioncontroller" in it } != true) return null
        fun find(value: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (value.isVisibleToUser && value.isEnabled && value.text?.toString()?.lowercase() in setOf("install", "update")) {
                var button = value
                repeat(3) { if (button.isClickable) return button; button = button.parent ?: return@repeat }
            }
            for (index in 0 until value.childCount) value.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return find(node)
    }
    @Test fun actualConversationApprovesOneExactApkThenAndroidConfirmsAndVerifiesInstalledBytes() {
        val job = fixture()
        val previous = status(job).getJSONObject("artifact").getString("installation")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("Assistant did not become foreground") { foreground() == "com.assistant.core" }
            try {
                send(scenario, "Can you install the APK that Developer just built?")
                await("The exact APK consent prompt was not displayed") { visibleReply(scenario, "Approve asking Android to install this exact APK?") }
                assertEquals(previous, status(job).getJSONObject("artifact").getString("installation"))
                assertFalse(status(job).getJSONObject("artifactReview").getBoolean("consumed"))
                assertEquals("com.assistant.core", foreground())
                send(scenario, "Approve this APK installation")
                await("Android did not request its separate installation confirmation") {
                    status(job).getJSONObject("artifact").optString("installation") == "PENDING_USER_ACTION"
                }
                var button: AccessibilityNodeInfo? = null
                await("Android's actual Install/Update button was not visible") {
                    button = instrumentation.uiAutomation.rootInActiveWindow?.let(::installButton); button != null
                }
                assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("The receiver did not verify installed package bytes") {
                    status(job).getJSONObject("artifact").getString("installation") == "PACKAGE_PRESENT"
                }
                val installed = status(job)
                assertTrue(installed.getJSONObject("artifactReview").getBoolean("consumed"))
                assertFalse(installed.getBoolean("deviceVerified"))
                assertEquals("BLOCKED", installed.getJSONObject("verification").getString("DEVICE_INSTALLED"))
                println("Actual conversational consent, authenticated handoff, Android confirmation and installed-byte verification passed; physical acceptance remains separate.")
            } finally {
                if (status(job).getJSONObject("artifact").optString("installation") in setOf("PREPARING", "SUBMITTED", "PENDING_USER_ACTION", "OUTCOME_UNKNOWN"))
                    call(Wire.ARTIFACT_INSTALL_CANCEL, job, status(job).getLong("revision"))
            }
        }
    }
    @Test fun cancellationGenericYesAndActivityRecreationCannotAuthorizeInstallation() {
        val job = fixture()
        val previous = status(job).getJSONObject("artifact").getString("installation")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            await("Assistant did not become foreground") { foreground() == "com.assistant.core" }
            fun requestReview() {
                val earlier = status(job).getLong("revision")
                send(scenario, "Install the APK Developer just built")
                await("Fresh installation review did not arrive", { "; job=${status(job)}; latest fixture chat=${lastReply(scenario)}" }) {
                    val current = status(job)
                    current.getLong("revision") > earlier && !current.getJSONObject("artifactReview").getBoolean("consumed") &&
                        visibleReply(scenario, "Approve asking Android to install this exact APK?")
                }
            }
            requestReview()
            val review = status(job).getLong("revision")
            send(scenario, "yes")
            await("Generic yes was not explicitly rejected") { visibleReply(scenario, "A general yes does not approve an APK installation.") }
            assertTrue(status(job).getLong("revision") > review)
            send(scenario, "Approve this APK installation")
            await("Cancelled consent could still be used") { visibleReply(scenario, "No current APK installation review is awaiting approval.") }
            requestReview()
            send(scenario, "Cancel APK installation")
            await("Explicit voice/text cancellation was not recorded") { status(job).getJSONObject("artifactReview").getBoolean("cancelled") }
            requestReview()
            scenario.recreate()
            send(scenario, "Approve this APK installation")
            await("Activity recreation restored consent") { visibleReply(scenario, "No current APK installation review is awaiting approval.") }
            assertEquals(previous, status(job).getJSONObject("artifact").getString("installation"))
            assertEquals("com.assistant.core", foreground())
        }
    }
    @Test fun navigationAndCancelledTicketsCannotBeReplayedAsInstallationConsent() {
        val job = fixture()
        val previous = status(job).getJSONObject("artifact").getString("installation")
        fun rejected(): Int = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("logcat -d -s DeveloperReview:I")).bufferedReader().use { reader ->
                reader.lineSequence().count { "artifact.install.handoff.rejected" in it }
            }
        fun deliver(ticket: String) {
            val count = rejected()
            context.startActivity(Intent(Wire.ARTIFACT_INSTALL_ACTION)
                .setComponent(android.content.ComponentName(Wire.DEVELOPER_PACKAGE, "com.jarvis.developer.MainActivity"))
                .putExtra(Wire.ARTIFACT_REVIEW_EXTRA, ticket)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("Developer did not reject the unauthorized ticket purpose or cancellation") { rejected() > count }
            assertEquals(previous, status(job).getJSONObject("artifact").getString("installation"))
        }
        val navigation = call(Wire.ARTIFACT_REVIEW, job, status(job).getLong("revision"))
        deliver(navigation.getJSONObject("artifactReview").getString("ticket"))
        val proposed = call(Wire.ARTIFACT_INSTALL_REVIEW, job, status(job).getLong("revision"))
        call(Wire.ARTIFACT_INSTALL_CANCEL, job, proposed.getLong("revision"))
        deliver(proposed.getJSONObject("artifactReview").getString("ticket"))
        assertFalse(status(job).getBoolean("deviceVerified"))
    }
}
