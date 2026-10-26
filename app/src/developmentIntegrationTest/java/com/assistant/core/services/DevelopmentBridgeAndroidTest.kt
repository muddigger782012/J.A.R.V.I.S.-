package com.assistant.core.services

import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

/** Installed independent APKs and real Binder transactions; only disposable emulator data is used. */
class DevelopmentBridgeAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun id() = UUID.randomUUID().toString()
    private fun request(kind: String, body: String = "", job: String = "", revision: Long? = null) =
        Wire.Request(id(), kind, body, jobId = job, expectedRevision = revision)
    private fun call(value: Wire.Request): JSONObject = DevelopmentBridgeClient(context).request(value)
    private fun ok(value: Wire.Request): JSONObject = call(value).also { assertEquals("OK", it.getString("code")) }.getJSONObject("data")
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() } }

    @Test fun actualCompanionNegotiatesReadOnlyCapabilitiesAndAllFiveGatesRemainUnverified() {
        val capabilities = ok(request("capabilities.request"))
        assertEquals(1, capabilities.getInt("protocol")); assertFalse(capabilities.getBoolean("sourceExecution"))
        assertFalse(capabilities.getBoolean("installation"))
        val review = ok(request("diagnostics.review.request", "Emulator microphone observation; no source change approved."))
        assertEquals("QUEUED", review.getString("state"))
        assertFalse(review.getBoolean("sourceVerified")); assertFalse(review.getBoolean("deviceVerified"))
        for (stage in Wire.Stage.values()) assertEquals("PENDING", review.getJSONObject("verification").getString(stage.name))
    }

    @Test fun lostAcknowledgementAndDeveloperProcessDeathDoNotDuplicateTheReview() {
        val request = request("diagnostics.review.request", "Process recovery acceptance observation.")
        val admitted = ok(request)
        val before = shell("pidof com.jarvis.developer")
        assertTrue(before.isNotEmpty())
        shell("am force-stop com.jarvis.developer")
        shell("am start -W -n com.jarvis.developer/com.jarvis.developer.MainActivity")
        val replay = ok(request)
        val after = shell("pidof com.jarvis.developer")
        assertTrue(after.isNotEmpty()); assertNotEquals(before, after)
        assertEquals(admitted.getString("id"), replay.getString("id")); assertTrue(replay.getBoolean("duplicate"))
        assertEquals("QUEUED", ok(request("task.status.request", job = admitted.getString("id"))).getString("state"))
    }

    @Test fun pauseResumeCancelRetryAndStaleRequestsUsePersistentRevisions() {
        val review = ok(request("diagnostics.review.request", "Queue control test, no execution.")); val job = review.getString("id")
        assertEquals("PAUSED", ok(request("task.pause.request", job = job, revision = 0)).getString("state"))
        assertEquals("QUEUED", ok(request("task.resume.request", job = job, revision = 1)).getString("state"))
        val cancel = request("task.cancel.request", job = job, revision = 2)
        assertEquals("CANCELLED", ok(cancel).getString("state"))
        assertEquals("QUEUED", ok(request("task.retry.request", job = job, revision = 3)).getString("state"))
        assertEquals("QUEUED", ok(cancel).getString("state"))
        assertEquals("CONFLICT", call(cancel.copy(requestId = id())).getString("code"))
    }

    @Test fun changedReviewCannotReuseAnIdAndLargeTransfersAreRejected() {
        val request = request("diagnostics.review.request", "First immutable scope.")
        ok(request)
        assertEquals("CONFLICT", call(request.copy(body = "Changed immutable scope.")).getString("code"))
        assertThrows(Wire.Rejected::class.java) { call(request.copy(requestId = id(), body = "界".repeat(4000))) }
    }
}
