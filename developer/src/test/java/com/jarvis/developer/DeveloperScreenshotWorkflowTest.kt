package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeveloperScreenshotWorkflowTest {
    private val key = "fixture-provider-secret-12345"
    private val task = "Diagnose the Developer error shown in this screenshot"

    private fun image(): DeveloperScreenshot {
        val bmp = Bitmap.createBitmap(580, 1100, Bitmap.Config.ARGB_8888)
        return try { DeveloperScreenshot.fromBitmap(bmp) } finally { bmp.recycle() }
    }

    @Test fun imageImportProducesSmallMetadataFreeJpeg() {
        val shot = image()
        assertTrue(shot.jpegBytes.size in 100..DeveloperScreenshot.MAX_JPEG_BYTES)
        assertEquals(0xff, shot.jpegBytes[0].toInt() and 0xff)
        assertEquals(0xd8, shot.jpegBytes[1].toInt() and 0xff)
    }

    @Test fun bothProvidersReceiveSelectedScreenshotButNeverAFilePathOrWriteTool() {
        val image = image()
        for (provider in listOf("Gemini", "OpenAI")) {
            var request: CodingAssistantProvider.PostRequest? = null
            val result = if (provider == "Gemini")
                JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
                    .put("content", JSONObject().put("role", "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", "Visible login error.")))))
            else JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                .put("type", "message").put("role", "assistant").put("status", "completed")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                    .put("text", "Visible login error.")))))
            val bytes = result.toString().toByteArray()
            val transport = CodingAssistantProvider.PostTransport { outbound ->
                request = outbound
                CodingAssistantProvider.PostResponse(200, bytes.size.toLong(), outbound.url,
                    ByteArrayInputStream(bytes))
            }
            val context = RepositoryCodingContext.unavailable(task, PatchEngine.Target.DEVELOPER)
            val answer = CodingAssistantProvider.Client(transport).ask(
                provider, key, "", task, context = context, screenshot = image)
            assertEquals("Visible login error.", answer)
            val sent = requireNotNull(request)
            val json = JSONObject(String(sent.body, Charsets.UTF_8))
            assertFalse(json.toString().contains(key))
            assertFalse(json.has("tools"))
            val instruction = if (provider == "Gemini")
                json.getJSONObject("systemInstruction").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
            else json.getString("instructions")
            assertTrue(instruction.contains("untrusted"))
            val encoded = if (provider == "Gemini") {
                val parts = json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                assertTrue(parts.getJSONObject(0).getString("text").contains("Diagnose"))
                parts.getJSONObject(parts.length() - 1).getJSONObject("inlineData")
                    .also { assertEquals("image/jpeg", it.getString("mimeType")) }.getString("data")
            } else {
                assertFalse(json.getBoolean("store"))
                val parts = json.getJSONArray("input").getJSONObject(0).getJSONArray("content")
                val imagePart = parts.getJSONObject(parts.length() - 1)
                assertEquals("input_image", imagePart.getString("type"))
                imagePart.getString("image_url").removePrefix("data:image/jpeg;base64,")
            }
            assertArrayEquals(image.jpegBytes, Base64.decode(encoded, Base64.NO_WRAP))
            assertTrue(sent.body.size <= CodingAssistantProvider.MAX_MULTIMODAL_REQUEST_BYTES)
        }
    }

    @Test fun screenshotChatRequestsAreAnalysisOnlyEvenWhenOwnerSaysFix() {
        val ctrl = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = ctrl.get()
        val send = Button(activity)
        val cancel = Button(activity)
        val provider = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Gemini"))
        }
        val target = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, listOf("Developer"))
        }
        val prompt = EditText(activity).apply { setText("Fix Developer login error shown in screenshot") }
        val secret = EditText(activity).apply { setText(key) }
        val model = EditText(activity)
        val output = TextView(activity)
        val status = TextView(activity)
        val prefs = activity.getSharedPreferences("screenshot-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val jobs = mutableListOf<() -> Unit>()
        val image = image()
        var modelReads = 0
        var screenshotReads = 0
        var reviews = 0
        var consumed = 0
        val handle = ProjectGenerationWorkbench.attach(activity, send, cancel, provider,
            secret, model, prompt, output, status, { target.selectedItemPosition }, { target },
            { "image-project" }, { }, ProjectGenerationStore(prefs), {},
            ProjectGenerationWorkbench.Services(
                worker = { jobs.add(it) },
                ask = { _, _, _, _, _, _ -> modelReads++; error("Text-only path must not be called") },
                askScreenshot = { _, _, _, _, _, _, attachment ->
                    screenshotReads++
                    assertSame(image, attachment)
                    "Screenshot shows a credential validation error; inspect provider settings."
                },
                context = { taskText, product, _, _, _ ->
                    RepositoryCodingContext.unavailable(taskText, product)
                }),
            requestPatch = { true },
            stageReview = { _, _, _, _, _ -> reviews++; true },
            screenshot = { image },
            onScreenshotSent = { assertSame(image, it); consumed++ })
        try {
            send.performClick()
            assertEquals(1, jobs.size)
            jobs.removeAt(0).invoke()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, modelReads)
            assertEquals(1, screenshotReads)
            assertEquals(0, reviews)
            assertEquals(1, consumed)
            assertTrue(output.text.toString().contains("credential validation error"))
            assertTrue(status.text.toString().contains("No source changes"))
            val saved = prefs.all.toString()
            assertFalse(saved.contains("base64"))
            assertFalse(saved.contains(key))
            assertFalse(saved.contains("image/jpeg"))
        } finally {
            handle.dispose()
            ctrl.pause().stop().destroy()
        }
    }
}
