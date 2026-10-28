package com.jarvis.developer

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DevelopmentInboxRefreshTest {
    @Test fun refreshingAJobRetainsOwnerDraftsAndRestoresScrollAfterLayout() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val directory = File(activity.noBackupFilesDir.canonicalFile, "development-inbox")
        val parent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(parent) }
        activity.setContentView(scroll)
        val inbox = DevelopmentJobInbox(directory)
        val request = Wire.Request(UUID.randomUUID().toString(), "source.developer.analysis.request", "Improve conversation tone")
        val path = "developer/src/main/java/com/jarvis/developer/ConversationTone.kt"
        val job = inbox.handle("com.assistant.core", true, request).getString("id")
        val source = RepositoryAnalysis.SourceFile(path, "a".repeat(40), 20)
        val file = RepositoryAnalysis.PlannedFile(source, listOf("Task reference"), "Inspect logic", "Review required",
            emptyList(), emptyList(), emptyList())
        inbox.finishAnalysis(inbox.claimAnalysis(job, true)!!,
            RepositoryAnalysis.AnalysisPlan(request.body, "b".repeat(40), listOf(file), emptyList(), listOf(path), 1))
        try {
            val patch = PatchWorkbench.Handle({ _, _, _, _, _ -> false }, { _, _ -> false }, { true })
            DevelopmentInboxWorkbench.attach(activity, parent, LinearLayout(activity), patch) {}
            fun views() = DeveloperUiState.descendants(parent).toList()
            fun field(hint: String) = views().filterIsInstance<EditText>().single { it.hint.toString() == hint }
            fun refresh() = views().filterIsInstance<Button>().single { it.text.toString() == "Refresh development reviews" }.performClick()
            fun layout() {
                scroll.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, 360, 240)
            }
            fun awaitFields() {
                val deadline = System.nanoTime() + 8_000_000_000L
                while (System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle(); layout()
                    if (views().filterIsInstance<EditText>().any { it.hint.toString() == "Requested engineering change" ||
                            it.contentDescription == "Requested engineering change" }) return
                    Thread.sleep(10)
                }
                fail("Job fields did not finish refreshing")
            }
            refresh(); awaitFields()
            field("Describe the change you want Developer to make").setText("Keep my revised instructions")
            field("Exact analyzed paths for this task (one per line)").setText(path + "\n")
            layout(); scroll.scrollTo(0, 80); assertEquals(80, scroll.scrollY)
            refresh(); layout(); awaitFields()
            assertEquals("Keep my revised instructions", field("Describe the change you want Developer to make").text.toString())
            assertEquals(path + "\n", field("Exact analyzed paths for this task (one per line)").text.toString())
            assertEquals(80, scroll.scrollY)
        } finally {
            controller.pause().stop().destroy()
            directory.deleteRecursively()
        }
    }
}
