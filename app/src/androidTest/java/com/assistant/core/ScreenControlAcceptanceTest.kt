package com.assistant.core

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Parcel
import android.os.ResultReceiver
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.*
import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only a disposable CI emulator is configured; production has no permission-grant path. */
@RunWith(AndroidJUnit4::class)
class ScreenControlAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun waitUntil(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) { if (check()) return; Thread.sleep(100) }
        assertTrue("Accessibility fixture condition did not become true", check())
    }
    private fun <T> withAccess(enabled: Boolean, block: () -> T): T {
        val services = shell("settings get secure enabled_accessibility_services")
        val setting = shell("settings get secure accessibility_enabled")
        check(services == "null" || services.matches(Regex("[a-zA-Z0-9_./:$-]*")))
        val ownComponent = ComponentName(context, JarvisAccessibilityService::class.java)
        val own = ownComponent.flattenToString()
        val names = services.takeUnless { it == "null" }.orEmpty().split(':')
            .filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != ownComponent }.toMutableList()
        if (enabled) names.add(own)
        try {
            // UiAutomation uses Runtime.exec argv tokenization, not a shell quote parser.
            if (names.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${names.joinToString(":")}")
            shell("settings put secure accessibility_enabled ${if (names.isEmpty()) 0 else 1}")
            assertEquals(names.joinToString(":"), shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" }.orEmpty())
            waitUntil { ScreenAccessClient(context).connected() == enabled }
            return block()
        } finally {
            ScreenAccessClient(context).discard()
            if (services == "null" || services.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $services")
            if (setting == "null") shell("settings delete secure accessibility_enabled")
            else { check(setting in setOf("0", "1")); shell("settings put secure accessibility_enabled $setting") }
        }
    }
    private fun remote(command: String): String {
        val result = AtomicReference<Bundle>(); val done = CountDownLatch(1)
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { result.set(resultData); done.countDown() }
        }
        // Marshal the platform ResultReceiver wrapper, not an instrumentation-only subclass.
        val parcel = Parcel.obtain()
        val transport = try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        context.startService(Intent(context, ScreenControlProbeService::class.java).putExtra("command", command).putExtra("receiver", transport))
        assertTrue(done.await(8, TimeUnit.SECONDS))
        assertNotEquals(Process.myPid(), result.get().getInt("pid"))
        return result.get().getString("reply")!!
    }
    private fun settled() {
        var last = ""
        waitUntil {
            val key = runCatching { AndroidScreenPlatform(context).snapshot().key }.getOrDefault("")
            val stable = key.isNotBlank() && key == last; last = key; stable
        }
    }

    @Test fun realAccessibilityBridgeFromAnotherProcessReviewsTapsRejectsStaleScreensAndScrolls(): Unit = withAccess(true) {
        ActivityScenario.launch(ScreenControlFixtureActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync(); settled()
            assertEquals(DeviceReport.Accessibility.REMOTE_CONNECTED.name, remote("fixture accessibility state"))
            val read = remote("read my screen")
            assertTrue(read, read.contains("Screen control fixture"))
            assertTrue(remote("tap Practice button").contains("Nothing was tapped"))
            scenario.onActivity { assertEquals(0, it.tapCount) }
            val requested = remote("confirm screen action")
            assertTrue(requested, requested.contains("Android accepted"))
            waitUntil { var count = 0; scenario.onActivity { count = it.tapCount }; count == 1 }
            assertTrue(remote("confirm screen action").contains("No current"))
            settled(); assertTrue(remote("tap Nested sample").contains("Review screen tap"))
            assertTrue(remote("confirm screen action").contains("Android accepted"))
            waitUntil { var count = 0; scenario.onActivity { count = it.tapCount }; count == 2 }
            settled(); assertTrue(remote("tap Practice button").contains("Review screen tap"))
            scenario.onActivity { it.changeContext() }; instrumentation.waitForIdleSync(); settled()
            assertTrue(remote("confirm screen action").contains("changed"))
            scenario.onActivity { assertEquals(2, it.tapCount) }
            settled(); remote("tap Practice button")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { entered.countDown(); release.await(6, TimeUnit.SECONDS) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            try {
                assertTrue(remote("confirm screen action").contains("uncertain"))
            } finally { release.countDown() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(2, it.tapCount) }
            assertTrue(remote("confirm screen action").contains("No current"))
            settled(); remote("tap Practice button")
            val cancellationEntered = CountDownLatch(1)
            val cancellationRelease = CountDownLatch(1)
            val confirmationFinished = CountDownLatch(1)
            val confirmationReply = AtomicReference<String>()
            val confirmationFailure = AtomicReference<Throwable>()
            Handler(Looper.getMainLooper()).post {
                cancellationEntered.countDown(); cancellationRelease.await(6, TimeUnit.SECONDS)
            }
            assertTrue(cancellationEntered.await(3, TimeUnit.SECONDS))
            try {
                Thread {
                    try { confirmationReply.set(remote("confirm screen action")) }
                    catch (failure: Throwable) { confirmationFailure.set(failure) }
                    finally { confirmationFinished.countDown() }
                }.start()
                val started = android.os.SystemClock.uptimeMillis()
                val cancelled = ScreenAccessClient(context).reply("cancel screen action")
                assertTrue(cancelled, cancelled.contains("Cancelled"))
                assertTrue("Cancellation waited for the blocked UI queue",
                    android.os.SystemClock.uptimeMillis() - started < 2500)
            } finally { cancellationRelease.countDown() }
            assertTrue(confirmationFinished.await(8, TimeUnit.SECONDS))
            assertNull(confirmationFailure.get())
            assertNotNull(confirmationReply.get())
            assertFalse(confirmationReply.get(), confirmationReply.get().contains("Android accepted"))
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(2, it.tapCount) }
            assertTrue(remote("confirm screen action").contains("No current"))
            assertTrue(remote("tap Duplicate sample").contains("several controls"))
            val scrolled = remote("scroll down")
            assertTrue(scrolled, scrolled.contains("Android accepted"))
            waitUntil { var y = 0; scenario.onActivity { y = it.scroll.scrollY }; y > 0 }
            scenario.onActivity { it.showPassword() }; instrumentation.waitForIdleSync(); settled()
            val protected = remote("read my screen")
            assertTrue(protected, protected.contains("password-entry")); assertFalse(protected.contains("private-fixture-password"))
            assertTrue(remote("tap Practice button").contains("protected"))
            scenario.onActivity { assertEquals(2, it.tapCount) }
        }
    }

    @Test fun disabledAccessibilityAndPrivateProviderCannotSupplyUnreviewedScreenActions(): Unit = withAccess(false) {
        ActivityScenario.launch(ScreenControlFixtureActivity::class.java).use {
            val info = context.packageManager.getProviderInfo(ComponentName(context, ScreenAccessProvider::class.java), 0)
            assertFalse(info.exported); assertFalse(info.grantUriPermissions)
            assertTrue(remote("screen control status").contains("Enable Jarvis"))
            assertTrue(remote("tap Practice button").contains("Enable Jarvis"))
            assertTrue(remote("confirm screen action").contains("No current"))
            assertTrue(remote("tap screen item one then run shell reboot").contains("Nothing was run"))
        }
    }
}
