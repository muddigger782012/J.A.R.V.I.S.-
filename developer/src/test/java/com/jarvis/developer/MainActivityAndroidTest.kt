package com.jarvis.developer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real framework views/lifecycle only: no provider, GitHub, signing or installer operation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityAndroidTest {
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun clearLocalHealth() {
        application().getSharedPreferences("developer_health", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun closeActivity() {
        controller?.let {
            if (!it.get().isDestroyed) it.pause().stop().destroy()
        }
        controller = null
    }

    @Test
    fun startupAttachesReadOnlyAndReviewControlsWithoutGrantingApproval() {
        val activity = start()

        assertTrue(views<ScrollView>(activity).isNotEmpty())
        assertEquals("", field(activity, PROVIDER_KEY).text.toString())
        assertEquals("", field(activity, GITHUB_TOKEN).text.toString())
        assertTrue(button(activity, "Browse source inventory (read-only)").isEnabled)
        assertTrue(button(activity, "Read source and propose bounded own-code improvements (GET only)").isEnabled)
        assertTrue(button(activity, "Read exact baseline debug CI (GET only)").isEnabled)
        assertTrue(button(activity, "Read signed candidate and recovery evidence (GET only)").isEnabled)
        assertApprovalsUnavailable(activity)

        val health = activity.getSharedPreferences("developer_health", Context.MODE_PRIVATE)
        assertEquals(BuildConfig.VERSION_CODE, health.getInt("last_started_version", -1))
        assertEquals(-1, health.getInt("confirmed_version", -1))
        assertTextPresent(activity, "Version ${BuildConfig.VERSION_CODE} is awaiting your health confirmation.")
    }

    @Test
    fun explicitHealthConfirmationRecordsLocalHealthWithoutApprovingAnyChange() {
        val activity = start()

        assertTrue(button(activity, "Confirm this version works").performClick())
        idleMain()

        val health = activity.getSharedPreferences("developer_health", Context.MODE_PRIVATE)
        assertEquals(BuildConfig.VERSION_CODE, health.getInt("confirmed_version", -1))
        assertEquals(0, health.getInt("quick_restarts", -1))
        assertTextPresent(activity, "Version ${BuildConfig.VERSION_CODE} confirmed working locally.")
        assertApprovalsUnavailable(activity)
    }

    @Test
    fun repeatedUnconfirmedRelaunchPausesLegacyWritesAndUpdatesUntilExplicitConfirmation() {
        application().getSharedPreferences("developer_health", Context.MODE_PRIVATE).edit()
            .putInt("last_started_version", BuildConfig.VERSION_CODE)
            .putInt("last_launch_version", BuildConfig.VERSION_CODE)
            .putLong("last_launch_ms", System.currentTimeMillis())
            .putInt("quick_restarts", 2)
            .commit()
        val activity = start()

        val icon = button(activity, "Generate icon code, commit and check build")
        val updates = button(activity, "Check for Developer updates")
        assertFalse(icon.isEnabled)
        assertFalse(updates.isEnabled)
        assertTrue(views<TextView>(activity).any {
            it.text.toString().startsWith("WARNING: 3 rapid launches on unconfirmed version")
        })

        assertTrue(button(activity, "Confirm this version works").performClick())
        idleMain()

        assertTrue(icon.isEnabled)
        assertTrue(updates.isEnabled)
        assertApprovalsUnavailable(activity)
    }

    @Test
    fun activityRecreationDiscardsCredentialAndReviewDraftsAndRestoresNoApproval() {
        val activity = start()
        // Deliberately fake sentinels. No API request or valid reviewed proposal is created.
        val fakeProvider = "fixture-provider-key-never-persist"
        val fakeGithub = "fixture-github-token-never-persist"
        field(activity, PROVIDER_KEY).setText(fakeProvider)
        field(activity, GITHUB_TOKEN).setText(fakeGithub)
        field(activity, "Enter exact eligible review SHA-256").setText("a".repeat(64))
        idleMain()
        assertApprovalsUnavailable(activity)
        assertNoCredentialTextPersisted(activity, fakeProvider, fakeGithub)

        controller!!.recreate()
        idleMain()
        val recreated = controller!!.get()

        assertNotSame(activity, recreated)
        assertEquals("", field(recreated, PROVIDER_KEY).text.toString())
        assertEquals("", field(recreated, GITHUB_TOKEN).text.toString())
        assertEquals("", field(recreated, "Enter exact eligible review SHA-256").text.toString())
        assertApprovalsUnavailable(recreated)
        assertNoCredentialTextPersisted(recreated, fakeProvider, fakeGithub)
        assertEquals(-1, recreated.getSharedPreferences("developer_health", Context.MODE_PRIVATE)
            .getInt("confirmed_version", -1))
    }

    private fun start(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idleMain()
        return controller!!.get()
    }

    private fun assertApprovalsUnavailable(activity: Activity) {
        listOf(
            "Create task branch and commit approved files (writes)",
            "Check CI for this exact task commit (read-only)",
            "Stage this exact source scope for a NEW diff review",
            "Compare baseline, candidate and verified recovery evidence",
            "Record exact approved promotion request (metadata only)"
        ).forEach { label -> assertFalse(label, button(activity, label).isEnabled) }
        listOf(
            "I reviewed this exact diff and approve creating its isolated task branch",
            "I reviewed this exact eligible candidate and approve recording its promotion request"
        ).forEach { label ->
            val approval = views<CheckBox>(activity).single { it.text.toString() == label }
            assertFalse(label, approval.isEnabled)
            assertFalse(label, approval.isChecked)
        }
    }

    private fun assertNoCredentialTextPersisted(activity: Activity, vararg sentinels: String) {
        val privateDirectories = listOf(activity.filesDir, File(activity.applicationInfo.dataDir, "shared_prefs"))
        privateDirectories.flatMap { directory -> directory.walkTopDown().filter { it.isFile }.toList() }
            .forEach { file ->
                val contents = file.readText(Charsets.UTF_8)
                sentinels.forEach { sentinel ->
                    assertFalse("Credential text persisted in ${file.name}", contents.contains(sentinel))
                }
            }
    }

    private fun application(): Application = RuntimeEnvironment.getApplication()
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
    private fun button(activity: Activity, text: String): Button =
        views<Button>(activity).single { it.text.toString() == text }
    private fun field(activity: Activity, hint: String): EditText =
        views<EditText>(activity).single { it.hint?.toString() == hint }
    private fun assertTextPresent(activity: Activity, text: String) =
        assertTrue(text, views<TextView>(activity).any { it.text.toString() == text })
    private inline fun <reified T : View> views(activity: Activity): List<T> =
        descendants(activity.window.decorView).filterIsInstance<T>().toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    companion object {
        private const val PROVIDER_KEY = "Selected provider API key (not saved)"
        private const val GITHUB_TOKEN = "GitHub fine-grained token (not saved)"
    }
}
