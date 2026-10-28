package com.jarvis.developer

import android.widget.EditText
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integration-safe smoke tests for the production-based navigation and guided workbench. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeveloperGuidedUxTest {
    @Test fun guidedWorkbenchIsReachableFromProductionActivity() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val views = DeveloperUiState.descendants(activity.window.decorView).toList()
            assertTrue(views.filterIsInstance<android.widget.Spinner>()
                .any { it.contentDescription == "Application to improve" })
            assertTrue(views.filterIsInstance<android.widget.Button>()
                .any { it.text.toString() == "Analyze my request (read-only)" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun ordinaryDraftsRestoreButCredentialFieldsAreExcluded() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = LinearLayout(activity)
        val prompt = EditText(activity).apply { hint = "Example: Build me an app to track equipment maintenance."; setText("Improve offline routing") }
        val secret = EditText(activity).apply { hint = "Paste your API key here (not a model name)"; setText("secret-not-saved") }
        root.addView(prompt)
        root.addView(secret)
        val state = DeveloperUiState.save(root)
        prompt.setText("")
        secret.setText("")
        DeveloperUiState.restore(root, state)
        assertEquals("Improve offline routing", prompt.text.toString())
        assertEquals("", secret.text.toString())
    }
}
