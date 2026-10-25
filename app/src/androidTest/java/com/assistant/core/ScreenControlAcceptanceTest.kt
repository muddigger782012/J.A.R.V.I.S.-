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
    private fun launchFixture(): ActivityScenario<ScreenControlFixtureActivity> {
        if (InstrumentationRegistry.getArguments().getString("jarvis_disposable_emulator") == "true") {
            // Cold Google emulator launchers can leave their own ANR dialog above the fixture.
            // Reset only that emulator component; never dismiss errors from the app under test.
            shell("am force-stop com.google.android.apps.nexuslauncher")
        }
        return ActivityScenario.launch(ScreenControlFixtureActivity::class.java)
    }
    private fun settled() {
        var last = ""
        waitUntil {
            val key = runCatching { AndroidScreenPlatform(context).snapshot().let {
                if (it.packageName == context.packageName) it.key else ""
            } }.getOrDefault("")
            val stable = key.isNotBlank() && key == last; last = key; stable
        }
    }

    @Test fun actualCaptureDiagnosticsSeparateDeadlinesCancellationAndSuccessfulReadsWithoutScreenContent(): Unit = withAccess(true) {
        launchFixture().use { scenario ->
            instrumentation.waitForIdleSync(); settled()
            val platform = AndroidScreenPlatform(context)
            val timedOut = assertThrows(ScreenReadFailure::class.java) {
                AndroidScreenPlatform.withDeadline(android.os.SystemClock.uptimeMillis() - 1) { platform.snapshot() }
            }
            assertEquals(ScreenReadFailure.Reason.BUSY, timedOut.reason)
            assertEquals(0, platform.readDiagnostics()!!.nodes)
            val cancelled = assertThrows(ScreenReadFailure::class.java) {
                AndroidScreenPlatform.withDeadline(android.os.SystemClock.uptimeMillis() + 3000, { true }) { platform.snapshot() }
            }
            assertEquals(ScreenReadFailure.Reason.CANCELLED, cancelled.reason)
            assertEquals(0, platform.readDiagnostics()!!.nodes)
            assertTrue(platform.snapshot().text.contains("Screen control fixture"))
            val successful = requireNotNull(platform.readDiagnostics())
            assertNull(successful.reason); assertTrue(successful.nodes > 0)
            assertEquals(successful, platform.readDiagnostics())
            assertTrue(remote("read my screen").contains("Screen control fixture"))
            val report = remote("screen read diagnostics")
            assertTrue(report, report.contains("Last screen-read attempt: completed"))
            assertTrue(report, report.contains("not the current screen"))
            assertFalse(report.contains("Screen control fixture")); assertFalse(report.contains("Practice button"))
            assertFalse(report.contains(context.packageName))
            scenario.onActivity { assertEquals(0, it.tapCount) }
        }
    }

    @Test fun realAccessibilityBridgeFromAnotherProcessReviewsTapsRejectsStaleScreensAndScrolls(): Unit = withAccess(true) {
        launchFixture().use { scenario ->
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
            val protectedReport = remote("screen read status")
            assertTrue(protectedReport, protectedReport.contains("protected screen; no content exposed"))
            assertTrue(protectedReport, protectedReport.contains("Examined nodes: 0"))
            assertFalse(protectedReport.contains("private-fixture-password"))
            assertTrue(remote("tap Practice button").contains("protected"))
            scenario.onActivity { assertEquals(2, it.tapCount) }
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                scenario.onActivity { it.showSensitiveContent() }; instrumentation.waitForIdleSync(); settled()
                val marked = remote("read my screen")
                // Android may filter sensitive nodes before a non-tool service can receive them.
                // Public surrounding text remains readable; protected values and actions must not.
                assertFalse(marked, marked.contains("private-fixture-sensitive-content"))
                assertFalse(marked, marked.contains("Private sensitive control", ignoreCase = true))
                val hidden = remote("tap Private sensitive control")
                assertFalse(hidden, hidden.contains("Review screen tap"))
                remote("confirm screen action")
                scenario.onActivity { assertEquals(2, it.tapCount) }
            }
        }
    }

    @Test fun disabledAccessibilityAndPrivateProviderCannotSupplyUnreviewedScreenActions(): Unit = withAccess(false) {
        launchFixture().use {
            val info = context.packageManager.getProviderInfo(ComponentName(context, ScreenAccessProvider::class.java), 0)
            assertFalse(info.exported); assertFalse(info.grantUriPermissions)
            assertTrue(remote("screen control status").contains("Enable Jarvis"))
            assertTrue(remote("tap Practice button").contains("Enable Jarvis"))
            assertTrue(remote("confirm screen action").contains("No current"))
            assertTrue(remote("tap screen item one then run shell reboot").contains("Nothing was run"))
        }
    }

    @Test fun realScreenTaskChecksEachPageAndCannotReuseAnEarlierStepConfirmation(): Unit = withAccess(true) {
        launchFixture().use { scenario ->
            scenario.onActivity { it.showTaskStart() }; instrumentation.waitForIdleSync(); settled()
            assertTrue(remote("help me open Menu then Downloads and verify Downloads page").contains("approve screen task"))
            assertTrue(remote("approve screen task").contains("confirm screen step one"))
            scenario.onActivity { assertEquals(0, it.taskTapCount) }
            remote("yes"); remote("confirm screen action")
            scenario.onActivity { assertEquals(0, it.taskTapCount) }
            assertTrue(remote("confirm screen step one").contains("Android accepted step 1"))
            waitUntil { var count = 0; scenario.onActivity { count = it.taskTapCount }; count == 1 }
            remote("confirm screen step one"); scenario.onActivity { assertEquals(1, it.taskTapCount) }
            settled(); val nextStep = remote("continue screen task"); assertTrue("Expected step two review, got: $nextStep; task=${ScreenAccessClient(context).taskState()}; screen=${AndroidScreenPlatform(context).snapshot()}", nextStep.contains("confirm screen step two"))
            remote("confirm screen step one"); scenario.onActivity { assertEquals(1, it.taskTapCount) }
            assertTrue(remote("confirm screen step two").contains("Android accepted step 2"))
            waitUntil { var count = 0; scenario.onActivity { count = it.taskTapCount }; count == 2 }
            settled(); assertTrue(remote("continue screen task").contains("Verified the requested visible screen text"))
            assertFalse(ScreenAccessClient(context).taskState().active)
            remote("confirm screen step two"); scenario.onActivity { assertEquals(2, it.taskTapCount) }
        }
    }

    @Test fun realRolesAndSectionLabelsAreLocalAndNotificationCancellationStopsAReviewedTask(): Unit = withAccess(true) {
        launchFixture().use { scenario ->
            // A disposable emulator grants its own notification permission; production never does.
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            scenario.onActivity { it.showTaskAccounts() }; instrumentation.waitForIdleSync(); settled()
            val snapshot = AndroidScreenPlatform(context).snapshot()
            val accounts = snapshot.controls.filter { it.label.equals("Account", ignoreCase = true) }
            assertEquals("Fixture controls: ${snapshot.controls}; blocked=${snapshot.blocked}; text=${snapshot.text}", 2, accounts.size)
            assertTrue(accounts.all { it.role == ScreenSemantics.Role.BUTTON })
            assertEquals(setOf("Work", "Personal"), accounts.map { it.contextLabel }.toSet())
            assertFalse(snapshot.controls.single { it.label.equals("Unavailable", ignoreCase = true) }.enabled)
            assertFalse(snapshot.appLabel.isBlank())
            remote("screen task tap button named Account under Work and verify Work account page")
            remote("approve screen task")
            val delayedConfirmation = ScreenAccessClient(context).prepare("confirm screen step one")
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            waitUntil { manager.activeNotifications.any { it.id == ScreenTaskNotifications.ID } }
            val oldCancel = manager.activeNotifications.single { it.id == ScreenTaskNotifications.ID }
                .notification.actions.single { it.title.toString() == "Cancel screen task" }.actionIntent
            remote("screen task tap button named Account under Personal and verify Personal account page")
            assertTrue(remote("approve screen task").contains("under Personal"))
            assertTrue(ScreenAccessClient(context).reply(delayedConfirmation).contains("review changed"))
            scenario.onActivity { assertEquals(0, it.taskTapCount) }
            val oldCancelFinished = CountDownLatch(1)
            oldCancel.send(context, 0, null, { _, _, _, _, _ -> oldCancelFinished.countDown() }, Handler(Looper.getMainLooper()))
            assertTrue("Old notification cancellation was not delivered promptly", oldCancelFinished.await(8, TimeUnit.SECONDS))
            assertTrue("An old notification cancelled the replacement task", ScreenAccessClient(context).taskState().active)
            scenario.onActivity { assertEquals(0, it.taskTapCount) }
            waitUntil { manager.activeNotifications.any { it.id == ScreenTaskNotifications.ID } }
            val notification = manager.activeNotifications.single { it.id == ScreenTaskNotifications.ID }.notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
            val cancel = notification.actions.single { it.title.toString() == "Cancel screen task" }
            val receiver = context.packageManager.getReceiverInfo(ComponentName(context, ScreenTaskCancelReceiver::class.java), 0)
            assertFalse(receiver.exported); assertTrue(receiver.processName.endsWith(":wake"))
            cancel.actionIntent.send()
            waitUntil {
                val state = ScreenAccessClient(context).taskState()
                if (state.active) {
                    android.util.Log.d("JarvisScreenAcceptance", "Awaiting notification cancellation: $state")
                }
                !state.active
            }
            remote("confirm screen step one")
            scenario.onActivity { assertEquals(0, it.taskTapCount) }
            waitUntil { manager.activeNotifications.none { it.id == ScreenTaskNotifications.ID } }
            settled()
            remote("screen task tap button named Account under Personal and verify Personal account page")
            remote("approve screen task"); remote("confirm screen step one")
            waitUntil { var count = 0; scenario.onActivity { count = it.taskTapCount }; count == 1 }
            settled(); assertTrue(remote("continue screen task").contains("Verified the requested visible screen text"))
        }
    }
    @Test fun separateConnectionManagerLauncherAndScreenRemainUsableWithoutAutomaticProviderProbes(): Unit = withAccess(true) {
        launchFixture().use {
            instrumentation.waitForIdleSync(); settled()
            val discovery = AppDiscovery(AndroidInstalledApps(context), open = { action ->
                val intent = context.packageManager.getLaunchIntentForPackage(action.parameters.getValue("package"))!!
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "Requested the reviewed Connection Manager launcher"
            })
            val bridge = AndroidConnectionAssistance(context) { discovery.find(ConnectionAssistance.MANAGER_PACKAGE) }
            val review = ConnectionAssistance.reply(ConnectionAssistance.Kind.MANAGER, bridge)
            assertTrue(review, review.contains("separate app"))
            assertTrue(review, review.contains(ConnectionAssistance.MANAGER_PACKAGE))
            assertEquals(context.packageName, AndroidScreenPlatform(context).snapshot().packageName)
            assertTrue(discovery.reply(AppDiscovery.Command(AppDiscovery.Kind.OPEN_CHOICE, number = 1)).contains("Requested"))
            waitUntil { runCatching {
                val screen = AndroidScreenPlatform(context).snapshot()
                screen.packageName == ConnectionAssistance.MANAGER_PACKAGE && !screen.blocked &&
                    screen.text.any { text -> text.contains("CONNECTION MANAGER") } &&
                    screen.text.any { text -> text.contains("NOT TESTED") }
            }.getOrDefault(false) }
            val managerRead = remote("read my screen")
            assertTrue("Expected Connection Manager content from remote voice bridge, got: $managerRead", managerRead.contains("CONNECTION MANAGER"))
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val service = requireNotNull(JarvisAccessibilityService.active())
                val caching = service.isCacheEnabled
                try {
                    assertTrue(service.setCacheEnabled(false))
                    // Changing framework cache policy creates a new cold-read condition.
                    // Wait for two equal fresh snapshots before the single remote assertion;
                    // retain the production deadline and fail if the real manager never settles.
                    var previous = ""
                    waitUntil {
                        val current = runCatching { AndroidScreenPlatform(context).snapshot().takeIf { screen ->
                            screen.packageName == ConnectionAssistance.MANAGER_PACKAGE && !screen.blocked &&
                                screen.text.any { text -> text.contains("CONNECTION MANAGER") }
                        }?.key.orEmpty() }.getOrDefault("")
                        val stable = current.isNotBlank() && current == previous
                        previous = current
                        stable
                    }
                    val withoutCache = remote("read my screen")
                    val diagnostics = remote("screen read diagnostics")
                    assertTrue("Disabled caching must retain the supported fresh-node read: $withoutCache; $diagnostics", withoutCache.contains("CONNECTION MANAGER"))
                    assertTrue(diagnostics, diagnostics.contains("Android cache: disabled"))
                    assertFalse(diagnostics.contains("CONNECTION MANAGER")); assertFalse(diagnostics.contains("NOT TESTED"))
                } finally { service.setCacheEnabled(caching) }
            }
            assertFalse(ScreenAccessClient(context).taskState().active)
        }
    }
}
