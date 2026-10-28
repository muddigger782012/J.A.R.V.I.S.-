package com.jarvis.developer

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Host runs this after the installer scenario and a real Developer process stop. Read-only. */
class ArtifactInstallationRecoveryAndroidTest {
    @Test fun installedArtifactEvidenceSurvivesProcessRestartWithoutRepeatingInstallation() {
        check(InstrumentationRegistry.getArguments().getString("jarvisDisposable") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inbox = JarvisPackageInstaller.inbox(context)
        val job = inbox.localSnapshot().last { it.optJSONObject("artifact")?.optString("installation") == "PACKAGE_PRESENT" }
        val record = requireNotNull(inbox.artifactRecord(job.getString("id")))
        val sessions = context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        JarvisPackageInstaller.recover(context)
        assertTrue("Read-only recovery must not create an installer session", sessions.containsAll(
            context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()))
        assertTrue(inbox.pendingInstallations().isEmpty())
        assertTrue(DevelopmentArtifactInspection.packagePresent(context, record))
        val restored = inbox.localSnapshot().single { it.getString("id") == job.getString("id") }
        assertEquals("PACKAGE_PRESENT", restored.getJSONObject("artifact").getString("installation"))
        assertEquals("PASSED", restored.getJSONObject("verification").getString("SIGNED_APK"))
        assertEquals("BLOCKED", restored.getJSONObject("verification").getString("DEVICE_INSTALLED"))
        assertFalse(restored.getBoolean("deviceVerified"))
    }
}
