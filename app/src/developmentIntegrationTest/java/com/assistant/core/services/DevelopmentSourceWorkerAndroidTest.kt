package com.assistant.core.services

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeClient
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Actual fixed-origin public GitHub reads on a disposable emulator, without credentials or writes. */
class DevelopmentSourceWorkerAndroidTest {
    @Test fun admittedSourceTaskProducesPinnedPartialContextWithoutClaimingACompletedRepair() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = DevelopmentBridgeClient(context)
        val request = Wire.Request(UUID.randomUUID().toString(), "source.personal.analysis.request",
            "Inspect Personal Jarvis conversation and microphone reliability")
        val admitted = client.request(request)
        assertEquals("OK", admitted.getString("code"))
        val job = admitted.getJSONObject("data").getString("id")
        val deadline = SystemClock.elapsedRealtime() + 210_000
        var result = admitted.getJSONObject("data")
        while (result.getString("state") in setOf("QUEUED", "RUNNING") && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(500)
            val response = client.request(Wire.Request(UUID.randomUUID().toString(), "task.status.request", jobId = job))
            assertEquals("OK", response.getString("code")); result = response.getJSONObject("data")
        }
        if (result.getString("state") == "RUNNING") {
            // Disposable debug emulator only: collect the actual blocked thread location.
            fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
                .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() } }
            runCatching {
                val pid = shell("pidof com.jarvis.developer")
                if (pid.matches(Regex("[0-9]+"))) shell("run-as com.jarvis.developer kill -3 $pid")
            }
        }
        assertEquals("Verified source is required. Last phase: ${result.optString("phase", "unknown")}; measured transition: ${result.optLong("phaseElapsedMillis")} ms.", "REVIEW_READY", result.getString("state"))
        val analysis = result.getJSONObject("analysis")
        assertTrue(analysis.getString("commit").matches(Regex("[a-f0-9]{40}")))
        assertTrue(analysis.getInt("inspected") > 0); assertTrue(analysis.getInt("planned") > 0)
        assertTrue(analysis.getBoolean("partial"))
        for (stage in Wire.Stage.values()) assertEquals("PENDING", result.getJSONObject("verification").getString(stage.name))
        assertFalse(result.getBoolean("sourceVerified")); assertFalse(result.getBoolean("deviceVerified"))
    }
}
