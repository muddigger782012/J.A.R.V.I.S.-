package com.jarvis.developer

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

/**
 * Framework measurement and lifecycle simulation, not native graphics or Android process death.
 * Night mode is Android's configuration only; the standalone Developer app has no Jarvis palette
 * integration. No network control is clicked, and all credential/review sentinels are fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityConfigurationAndroidTest {
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun clearLocalLaunchState() {
        application().getSharedPreferences("developer_health", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun closeActivity() {
        controller?.let {
            if (!it.get().isDestroyed) it.pause().stop().destroy()
        }
        controller = null
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-notnight-mdpi")
    fun narrowPortraitAtNormalFontKeepsControlsWithinContentAndLastActionReachable() {
        assertConfigurationGeometry(widthDp = 320, heightDp = 640, fontScale = 1f, night = false)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-notnight-mdpi")
    fun narrowPortraitAtDoubleFontKeepsControlsWithinContentAndLastActionReachable() {
        assertConfigurationGeometry(widthDp = 320, heightDp = 640, fontScale = 2f, night = false)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-night-mdpi")
    fun narrowSystemNightAtDoubleFontKeepsControlsWithinContentAndLastActionReachable() {
        assertConfigurationGeometry(widthDp = 320, heightDp = 640, fontScale = 2f, night = true)
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land-notnight-mdpi")
    fun shortLandscapeAtDoubleFontKeepsControlsWithinContentAndLastActionReachable() {
        assertConfigurationGeometry(widthDp = 640, heightDp = 320, fontScale = 2f, night = false)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-port-notnight-mdpi")
    fun serializedBundleRestoredIntoNewControllerClearsFakeCredentialAndReviewDraftsWithoutApproval() {
        RuntimeEnvironment.setFontScale(1f)
        val original = start()
        val fakeProvider = "fixture-bundle-provider-never-persist"
        val fakeGithub = "fixture-bundle-github-never-persist"
        val draftFields = mapOf(
            PROVIDER_KEY to fakeProvider,
            GITHUB_TOKEN to fakeGithub,
            "Example: Build me an app to track equipment maintenance." to "Fixture draft only; no provider request",
            TASK_ID to "fixture-bundle-review",
            "Enter exact eligible review SHA-256" to "b".repeat(64)
        )
        openTools(original)
        draftFields.forEach { (hint, value) -> field(original, hint).setText(value) }
        idleMain()
        assertApprovalsUnavailable(original)
        assertNoCredentialTextPersisted(original, fakeProvider, fakeGithub)

        // Serialize a saved framework Bundle and reconstruct another Activity/controller in the
        // same Application. This does not kill an Android process or create a reviewed proposal.
        val state = Bundle()
        controller!!.pause().saveInstanceState(state).stop().destroy()
        val serializedState = Parcel.obtain().let { parcel ->
            try {
                parcel.writeBundle(state)
                parcel.marshall()
            } finally {
                parcel.recycle()
            }
        }
        assertTrue("Saved Bundle was serialized", serializedState.isNotEmpty())
        val restoredState = Parcel.obtain().let { parcel ->
            try {
                parcel.unmarshall(serializedState, 0, serializedState.size)
                parcel.setDataPosition(0)
                requireNotNull(parcel.readBundle(MainActivity::class.java.classLoader))
            } finally {
                parcel.recycle()
            }
        }
        val replacement = Robolectric.buildActivity(MainActivity::class.java)
        controller = replacement
        replacement.create(restoredState).start().restoreInstanceState(restoredState).resume().visible()
        idleMain()
        val restored = replacement.get()

        openTools(restored)
        assertNotSame(original, restored)
        assertNotSame(state, restoredState)
        assertSame("Framework simulation retains the Application", original.application, restored.application)
        draftFields.keys.filter { it != TASK_ID }.forEach { hint ->
            assertEquals("Only safe instruction draft restored for $hint",
                if (hint == "Example: Build me an app to track equipment maintenance.") draftFields.getValue(hint) else "",
                field(restored, hint).text.toString())
        }
        val freshTaskId = field(restored, TASK_ID).text.toString()
        assertFalse("Unreviewed task ID draft restored", freshTaskId == draftFields.getValue(TASK_ID))
        assertTrue("Task ID is newly initialized", freshTaskId.matches(Regex("task-[a-f0-9]{16}")))
        assertTrue(views<TextView>(restored).any {
            it.text.toString().startsWith("A complete diff, immutable base SHA and proposal fingerprint will appear here for review.")
        })
        assertApprovalsUnavailable(restored)
        assertNoCredentialTextPersisted(restored, fakeProvider, fakeGithub)
        assertEquals(-1, restored.getSharedPreferences("developer_health", Context.MODE_PRIVATE)
            .getInt("confirmed_version", -1))
    }

    private fun assertConfigurationGeometry(widthDp: Int, heightDp: Int, fontScale: Float, night: Boolean) {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = start()
        val configuration = activity.resources.configuration
        val metrics = activity.resources.displayMetrics
        assertEquals("Effective width profile", widthDp, configuration.screenWidthDp)
        assertEquals("Effective height profile", heightDp, configuration.screenHeightDp)
        assertEquals("Effective orientation", if (widthDp > heightDp)
            Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT,
            configuration.orientation)
        assertEquals("Effective font scale", fontScale, configuration.fontScale, 0.001f)
        assertEquals("Effective system night mode", if (night) Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("Explicit mdpi density", 1f, metrics.density, 0.001f)
        assertEquals("Scaled density follows font preference", metrics.density * fontScale,
            metrics.scaledDensity, 0.001f)

        // Supply an explicit real measurement/layout pass: a visible Robolectric Activity alone
        // need not receive window bounds. Geometry assertions below reject zero-size layouts.
        val widthPx = (widthDp * metrics.density).toInt()
        val heightPx = (heightDp * metrics.density).toInt()
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, widthPx, heightPx)
        idleMain()
        // Inspect the Tools page where the long, security-gated action list lives.
        // Expanding it exercises the layout most likely to overflow at large font sizes.
        assertTrue(button(activity, "Settings").performClick())
        assertTrue(button(activity, "Advanced tools").performClick())
        idleMain()
        assertTrue(button(activity, "Assistant diagnostics").performClick())
        idleMain()
        // Opening a collapsed section changes measured height; force a new layout pass.
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, widthPx, heightPx)
        idleMain()
        val scroll = views<ScrollView>(activity).single { it.visibility == View.VISIBLE }
        val root = scroll.getChildAt(0) as LinearLayout
        assertTrue("Measured scroll viewport width", scroll.width > 0)
        assertTrue("Measured scroll viewport height", scroll.height > 0)
        assertTrue("Content requires vertical scrolling", root.height > scroll.height)
        assertTrue("Content fits viewport horizontally", root.width > 0 &&
            root.width <= scroll.width - scroll.paddingLeft - scroll.paddingRight)

        // Check all direct content blocks and interactive descendants, including disabled gates.
        // Single-line EditText hints may scroll internally; no text-render/pixel claim is made.
        for (index in 0 until root.childCount) {
            assertWithinHorizontalContent(root, root.getChildAt(index))
        }
        val controls = descendants(root).filter {
            isEffectivelyVisible(it, root) && (it is Button || it is EditText || it is Spinner)
        }.toList()
        assertTrue("Interactive controls exist", controls.isNotEmpty())
        controls.forEach { control ->
            assertTrue("Nonzero control width: ${describe(control)}", control.width > 0)
            assertTrue("Nonzero control height: ${describe(control)}", control.height > 0)
            assertWithinHorizontalContent(root, control)
        }

        assertApprovalsUnavailable(activity)
        val lastAction = button(activity, "Generate icon code, commit and check build")
        assertTrue("Final action remains inside Tools content", descendants(root).any { it === lastAction })
        scroll.isSmoothScrollingEnabled = false
        assertTrue("ScrollView can reach bottom", scroll.fullScroll(View.FOCUS_DOWN))
        idleMain()
        assertTrue("ScrollView moved down", scroll.scrollY > 0)
        // The last action lives in a collapsed accordion section. Expand that section
        // before checking reachability, rather than testing a hidden child at scroll bottom.
        assertTrue(button(activity, "Release verification").performClick())
        idleMain()
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, widthPx, heightPx)
        idleMain()
        assertTrue("Last action is displayed", isEffectivelyVisible(lastAction, root))
        // Later categories remain below this action. Bring its full bounds into view instead
        // of assuming it is the final child of the whole Advanced tools page.
        lastAction.requestRectangleOnScreen(Rect(0, 0, lastAction.width, lastAction.height), true)
        idleMain()
        val actionBounds = Rect(0, 0, lastAction.width, lastAction.height)
        scroll.offsetDescendantRectToMyCoords(lastAction, actionBounds)
        val viewportTop = scroll.scrollY + scroll.paddingTop
        val viewportBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        assertTrue("Last action top is reachable", actionBounds.top >= viewportTop)
        assertTrue("Last action bottom is reachable", actionBounds.bottom <= viewportBottom)
        assertApprovalsUnavailable(activity)
    }

    // A child can report VISIBLE while an ancestor section is GONE.
    // Only assert geometry for controls actually displayed in the selected section.
    private fun isEffectivelyVisible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun assertWithinHorizontalContent(root: LinearLayout, view: View) {
        if (view.visibility != View.VISIBLE) return
        val bounds = Rect(0, 0, view.width, view.height)
        root.offsetDescendantRectToMyCoords(view, bounds)
        assertTrue("Escaped left content edge: ${describe(view)}", bounds.left >= root.paddingLeft)
        assertTrue("Escaped right content edge: ${describe(view)}", bounds.right <= root.width - root.paddingRight)
    }

    private fun start(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idleMain()
        return controller!!.get()
    }

    private fun openTools(activity: Activity) {
        assertTrue(button(activity, "Settings").performClick())
        assertTrue(button(activity, "Advanced tools").performClick())
        idleMain()
        assertTrue(button(activity, "Release verification").performClick())
        idleMain()
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
        listOf(activity.filesDir, activity.noBackupFilesDir,
            File(activity.applicationInfo.dataDir, "shared_prefs"))
            .flatMap { directory -> directory.walkTopDown().filter { it.isFile }.toList() }
            .forEach { file ->
                val contents = file.readText(Charsets.UTF_8)
                sentinels.forEach { sentinel ->
                    assertFalse("Credential text persisted in ${file.name}", contents.contains(sentinel))
                }
            }
    }

    private fun describe(view: View): String = "${view.javaClass.simpleName}: " + when (view) {
        is EditText -> view.hint?.toString().orEmpty()
        is TextView -> view.text.toString()
        else -> ""
    }
    private fun application(): Application = RuntimeEnvironment.getApplication()
    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()
    private fun button(activity: Activity, text: String): Button =
        views<Button>(activity).single {
            val label = it.text.toString()
            label == text || label == "$text  ▼" || label == "$text  ▲"
        }
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

    companion object {
        private const val PROVIDER_KEY = "Paste your API key here (not a model name)"
        private const val GITHUB_TOKEN = "GitHub fine-grained token (not saved)"
        private const val TASK_ID = "Task ID (letters, digits, hyphens or underscores)"
    }
}
