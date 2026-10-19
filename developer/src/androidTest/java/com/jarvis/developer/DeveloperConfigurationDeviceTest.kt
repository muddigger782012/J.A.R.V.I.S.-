package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One native configuration check, invoked separately for each strictly named host-applied profile.
 * Compiling this class is not evidence of emulator installation or execution. The host must apply
 * the fixed 640x1280px/320dpi display plus font/night/orientation settings before launching this
 * class; this test never forces View measurement or layout. System night mode is not Jarvis palette
 * or pixel-level verification.
 * No network, health-confirmation, repository-write, publisher or installer control is clicked.
 */
@RunWith(AndroidJUnit4::class)
class DeveloperConfigurationDeviceTest {
    private lateinit var profile: Profile

    @Before
    fun validateHostProfileAndResetOnlyPrivateLaunchWarningState() {
        profile = Profile.fromArguments(InstrumentationRegistry.getArguments())
        assertTrue("Private launch-warning state must reset successfully",
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSharedPreferences("developer_health", Context.MODE_PRIVATE)
                .edit().clear().commit())
    }

    @Test
    fun appliedHostConfigurationKeepsNativeControlsContainedAndLastActionReachable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitEffectiveConfigurationAndNativeLayout(scenario)
            scenario.onActivity { activity ->
                assertEffectiveConfiguration(activity)
                val scroll = views<ScrollView>(activity).single()
                val root = scroll.getChildAt(0) as LinearLayout
                assertTrue("Content must require vertical scrolling", root.height > scroll.height)
                assertTrue("Content must fit the actual viewport horizontally",
                    root.width > 0 && root.left >= scroll.paddingLeft &&
                        root.right <= scroll.width - scroll.paddingRight)

                for (index in 0 until root.childCount) {
                    assertWithinHorizontalContent(root, root.getChildAt(index))
                }
                val controls = descendants(root).filter {
                    it.visibility == View.VISIBLE && (it is Button || it is EditText || it is Spinner)
                }.toList()
                assertTrue("Visible interactive controls must exist", controls.isNotEmpty())
                controls.forEach { control ->
                    assertTrue("Nonzero control width: ${describe(control)}", control.width > 0)
                    assertTrue("Nonzero control height: ${describe(control)}", control.height > 0)
                    assertWithinHorizontalContent(root, control)
                }
                assertCredentialsEmptyAndApprovalsUnavailable(activity)
                val lastAction = button(activity, LAST_ACTION)
                assertTrue("Icon action must remain the final content child",
                    root.getChildAt(root.childCount - 1) === lastAction)
                scroll.isSmoothScrollingEnabled = false
                assertTrue("Native ScrollView must scroll toward its final action",
                    scroll.fullScroll(View.FOCUS_DOWN))
            }

            awaitEffectiveConfigurationAndNativeLayout(scenario)
            scenario.onActivity { activity ->
                assertEffectiveConfiguration(activity)
                val scroll = views<ScrollView>(activity).single()
                val lastAction = button(activity, LAST_ACTION)
                assertTrue("Native ScrollView must have moved downward", scroll.scrollY > 0)
                val actionBounds = Rect(0, 0, lastAction.width, lastAction.height)
                scroll.offsetDescendantRectToMyCoords(lastAction, actionBounds)
                val viewportTop = scroll.scrollY + scroll.paddingTop
                val viewportBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
                assertTrue("Final action top must be within the native viewport",
                    actionBounds.top >= viewportTop)
                assertTrue("Final action bottom must be within the native viewport",
                    actionBounds.bottom <= viewportBottom)
                assertCredentialsEmptyAndApprovalsUnavailable(activity)
                assertEquals("Geometry checks must not confirm health", -1,
                    activity.getSharedPreferences("developer_health", Context.MODE_PRIVATE)
                        .getInt("confirmed_version", -1))
            }
        }
    }

    private fun assertEffectiveConfiguration(activity: Activity) {
        val configuration = activity.resources.configuration
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getRealMetrics(display)
        assertEquals("Applied full-display density for ${profile.id}", HOST_DENSITY_DPI,
            display.densityDpi)
        assertEquals("Applied app-resource density for ${profile.id}", HOST_DENSITY_DPI,
            activity.resources.displayMetrics.densityDpi)
        assertEquals("Applied full-display width for ${profile.id}",
            profile.displayWidthDp * HOST_DENSITY_DPI / 160, display.widthPixels)
        assertEquals("Applied full-display height for ${profile.id}",
            profile.displayHeightDp * HOST_DENSITY_DPI / 160, display.heightPixels)
        assertEquals("Applied font scale for ${profile.id}", profile.fontScale,
            configuration.fontScale, 0.001f)
        assertEquals("Applied Android system night mode for ${profile.id}", profile.nightMode,
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("Applied orientation for ${profile.id}", profile.orientation,
            configuration.orientation)
        assertAvailableDimension("width", profile.displayWidthDp, configuration.screenWidthDp)
        assertAvailableDimension("height", profile.displayHeightDp, configuration.screenHeightDp)
    }

    private fun assertAvailableDimension(name: String, displayDp: Int, availableDp: Int) {
        // Android's available app area may exclude status/navigation bars from the host display.
        assertTrue("Applied $name for ${profile.id}: display=$displayDp available=$availableDp",
            availableDp in (displayDp - MAX_SYSTEM_BAR_DP)..displayDp)
    }

    private fun awaitEffectiveConfigurationAndNativeLayout(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.uptimeMillis() + LAYOUT_TIMEOUT_MS
        var previousActivity: Activity? = null
        var matchingObservations = 0
        var lastFailedAssertion: AssertionError? = null
        var lastCheckpoint = "Awaiting the exact host configuration and a native layout"
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                try {
                    // Host settings can return before display rotation, resource replacement and
                    // Activity recreation settle. A laid-out portrait view is not a landscape pass.
                    // These are the same strict assertions repeated below the wait, without forcing
                    // measurement or accepting a different density/font/night/orientation profile.
                    assertEffectiveConfiguration(activity)
                    assertNativeLayout(activity)
                    matchingObservations = if (previousActivity === activity) matchingObservations + 1 else 1
                    previousActivity = activity
                    lastCheckpoint = "Awaiting a second matching configuration/layout observation in the same Activity"
                } catch (failure: AssertionError) {
                    matchingObservations = 0
                    previousActivity = null
                    lastFailedAssertion = failure
                    // Only the fixed labels and numeric configuration/layout assertions above may
                    // enter this diagnostic. Never inspect or retain EditText or other UI contents.
                    lastCheckpoint = failure.message?.substringBefore(" expected:")?.take(240)
                        ?: "Configuration/layout assertion failed without a label"
                }
            }
            if (matchingObservations >= REQUIRED_MATCHING_OBSERVATIONS) return
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(minOf(LAYOUT_POLL_MS, remaining))
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Native host configuration/layout did not settle for ${profile.id} " +
            "within ${LAYOUT_TIMEOUT_MS}ms. Last checkpoint: $lastCheckpoint").apply {
            // Retain the original fixed assertion's source line and label, rather than hiding it
            // behind a generic timeout. The cause contains no credential or arbitrary view text.
            lastFailedAssertion?.let { initCause(it) }
        }
    }

    private fun assertNativeLayout(activity: Activity) {
        val viewports = views<ScrollView>(activity)
        assertEquals("Exactly one native scroll viewport is required", 1, viewports.size)
        val scroll = viewports.single()
        assertEquals("Native scroll viewport must contain one content view", 1, scroll.childCount)
        val content = scroll.getChildAt(0)
        assertTrue("Native scroll viewport must be laid out", scroll.isLaidOut)
        assertFalse("Native scroll viewport must have no pending layout", scroll.isLayoutRequested)
        assertTrue("Native scroll viewport must have nonzero width and height", scroll.width > 0 && scroll.height > 0)
        assertTrue("Native content must be laid out", content.isLaidOut)
        assertFalse("Native content must have no pending layout", content.isLayoutRequested)
        assertTrue("Native content must have nonzero width and height", content.width > 0 && content.height > 0)
    }

    private fun assertWithinHorizontalContent(root: LinearLayout, view: View) {
        if (view.visibility != View.VISIBLE) return
        val bounds = Rect(0, 0, view.width, view.height)
        root.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue("Escaped left content edge: ${describe(view)}", bounds.left >= root.paddingLeft)
        assertTrue("Escaped right content edge: ${describe(view)}",
            bounds.right <= root.width - root.paddingRight)
    }

    private fun assertCredentialsEmptyAndApprovalsUnavailable(activity: Activity) {
        assertEquals("Provider credential must remain empty", "", field(activity, PROVIDER_KEY).text.toString())
        assertEquals("GitHub credential must remain empty", "", field(activity, GITHUB_TOKEN).text.toString())
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

    private fun describe(view: View): String = "${view.javaClass.simpleName}: " + when (view) {
        is EditText -> view.hint?.toString().orEmpty()
        is TextView -> view.text.toString()
        else -> ""
    }
    private fun button(activity: Activity, text: String): Button =
        views<Button>(activity).single { it.text.toString() == text }
    private fun field(activity: Activity, hint: String): EditText =
        views<EditText>(activity).single { it.hint?.toString() == hint }
    private inline fun <reified T : View> views(activity: Activity): List<T> =
        descendants(activity.window.decorView).filterIsInstance<T>().toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private data class Profile(
        val id: String,
        val fontScaleArgument: String,
        val nightArgument: String,
        val orientationArgument: String,
        val displayWidthDp: Int,
        val displayHeightDp: Int
    ) {
        val fontScale: Float get() = fontScaleArgument.toFloat()
        val nightMode: Int get() = if (nightArgument == "yes") Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO
        val orientation: Int get() = if (orientationArgument == "landscape")
            Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT

        companion object {
            fun fromArguments(arguments: Bundle): Profile {
                val id = requireNotNull(arguments.getString("profile_id")) { "Missing profile_id" }
                val profile = when (id) {
                    "narrow_normal" -> Profile(id, "1.0", "no", "portrait", 320, 640)
                    "narrow_large" -> Profile(id, "2.0", "no", "portrait", 320, 640)
                    "narrow_large_night" -> Profile(id, "2.0", "yes", "portrait", 320, 640)
                    "landscape_large" -> Profile(id, "2.0", "no", "landscape", 640, 320)
                    else -> throw IllegalArgumentException("Unknown profile_id: $id")
                }
                mapOf(
                    "expected_font_scale" to profile.fontScaleArgument,
                    "expected_night" to profile.nightArgument,
                    "expected_orientation" to profile.orientationArgument,
                    "expected_display_width_dp" to profile.displayWidthDp.toString(),
                    "expected_display_height_dp" to profile.displayHeightDp.toString()
                ).forEach { (name, expected) ->
                    require(arguments.getString(name) == expected) {
                        "Missing or invalid $name for profile $id; required $expected"
                    }
                }
                return profile
            }
        }
    }

    companion object {
        private const val PROVIDER_KEY = "Selected provider API key (not saved)"
        private const val GITHUB_TOKEN = "GitHub fine-grained token (not saved)"
        private const val LAST_ACTION = "Generate icon code, commit and check build"
        private const val MAX_SYSTEM_BAR_DP = 100
        private const val HOST_DENSITY_DPI = 320
        private const val LAYOUT_TIMEOUT_MS = 10_000L
        private const val LAYOUT_POLL_MS = 50L
        private const val REQUIRED_MATCHING_OBSERVATIONS = 2
    }
}
