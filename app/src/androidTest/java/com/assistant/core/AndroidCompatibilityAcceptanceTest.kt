package com.assistant.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.AndroidAssistantService
import com.assistant.core.services.AndroidSpeechCapturePlatform
import com.assistant.core.services.SpeechCaptureRouting
import com.assistant.core.services.VoiceForegroundService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android compatibility/security checks; never starts listening or a voice pipeline. */
@RunWith(AndroidJUnit4::class)
class AndroidCompatibilityAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun disposable() = assertEquals("true", InstrumentationRegistry.getArguments().getString("jarvis_disposable_emulator"))

    @Test fun visibleAppQueriesUseTheSupportedSdkOverloadWithoutLaunchingAnything() {
        disposable()
        val launches = mutableListOf<Intent>()
        val intercepted = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launches.add(intent) }
        }
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        val service = AndroidAssistantService(intercepted)
        assertTrue(service.launchAppByLabel(label))
        assertEquals(context.packageName, launches.single().component!!.packageName)
        launches.clear()
        val amazon = service.amazonMusic(false)
        assertTrue(amazon, amazon.startsWith("I couldn't find Amazon Music") || amazon == "Opening Amazon Music.")
        assertTrue(launches.size <= 1)
    }

    @Test fun recognizerCreationRejectsUnsupportedDeviceCaptureWithoutUsingTheMicrophone() {
        disposable()
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onResults(results: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        instrumentation.runOnMainSync {
            val platform = AndroidSpeechCapturePlatform(context)
            platform.create(SpeechCaptureRouting.Engine.SYSTEM, listener).destroy()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                assertFalse(platform.deviceAvailable())
                val error = runCatching { platform.create(SpeechCaptureRouting.Engine.ON_DEVICE, listener) }.exceptionOrNull()
                assertTrue("Unsupported capture must reject before calling a newer SDK API", error is IllegalStateException)
                assertEquals("On-device recognition requires Android 12", error!!.message)
            } else if (platform.deviceAvailable()) {
                platform.create(SpeechCaptureRouting.Engine.ON_DEVICE, listener).destroy()
            }
        }
    }

    @Test fun privateDynamicEventsAcceptTheAppButRejectTheShellUid() {
        disposable()
        val action = "com.assistant.core.acceptance.PRIVATE_EVENT_" + UUID.randomUUID().toString().replace("-", "")
        val received = AtomicInteger()
        val own = CountDownLatch(1)
        val foreign = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (received.incrementAndGet() == 1) own.countDown() else foreign.countDown()
            }
        }
        instrumentation.runOnMainSync {
            ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        try {
            context.sendBroadcast(Intent(action).setPackage(context.packageName))
            assertTrue("A genuine app event must still be delivered", own.await(5, TimeUnit.SECONDS))
            // UiAutomation runs this sender as shell, not the app's UID. Fixed action/package
            // references contain only generated hex and the known target package; no source/log input is executed.
            assertTrue(context.packageName.matches(Regex("com\\.assistant\\.core(?:\\.beta)?")))
            val command = "am broadcast -a $action -p ${context.packageName}"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { reader ->
                var lines = 0
                while (reader.readLine() != null) { check(++lines <= 20) }
            }
            assertFalse("Another UID must not inject private voice events", foreign.await(500, TimeUnit.MILLISECONDS))
            assertEquals(1, received.get())
        } finally { instrumentation.runOnMainSync { context.unregisterReceiver(receiver) } }
    }

    @Test fun manifestKeepsMicrophoneAndSpecialAccessWhileMakingOptionalHardwareExplicit() {
        disposable()
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_CONFIGURATIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        for (permission in listOf("android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.WRITE_SETTINGS", "android.permission.PACKAGE_USAGE_STATS")) assertTrue(permission, permission in permissions)
        for (name in listOf("android.hardware.camera", "android.hardware.telephony")) {
            val feature = info.reqFeatures.orEmpty().single { it.name == name }
            assertEquals(0, feature.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
        }
        val service = pm.getServiceInfo(ComponentName(context, VoiceForegroundService::class.java), 0)
        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        }
    }
}
