package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

/** Attachments are data for the answer model; they never enter device-action routing. */
data class ChatAttachment(val name: String, val mime: String, val file: File)

object AttachmentPolicy {
    const val MAX_FILES = 4
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 12 * 1024 * 1024
    private val images = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    private val extensions = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp", "gif" to "image/gif", "pdf" to "application/pdf")
    private val textExtensions = setOf("txt", "md", "csv", "tsv", "json", "xml", "log", "kt", "java", "py", "js", "ts", "html", "css", "yaml", "yml")
    fun mime(name: String, reported: String?): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extensions[ext] ?: when {
            ext in textExtensions -> "text/plain"
            reported in images || reported in setOf("image/heic", "image/heif") -> reported
            reported == "application/pdf" -> reported
            reported?.startsWith("text/") == true -> "text/plain"
            else -> null
        }
    }
    fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            size += count
            require(size <= limit) { "File exceeds the ${if (limit < 1024 * 1024) "300 KB" else "8 MB"} limit." }
            output.write(buffer, 0, count)
        }
        require(size > 0) { "The selected file is empty." }
        return output.toByteArray()
    }
    fun parts(question: String, conversation: String, files: List<ChatAttachment>): JSONArray {
        require(files.size <= MAX_FILES)
        require(files.sumOf { it.file.length() } <= MAX_TOTAL_BYTES)
        val parts = JSONArray().put(JSONObject().put("text", if (conversation.isBlank()) question else "Recent conversation:\n$conversation\n\nCurrent question:\n$question"))
        files.forEach { attachment ->
            val bytes = attachment.file.inputStream().use { readBounded(it) }
            parts.put(JSONObject().put("text", "Attached file: ${attachment.name}"))
            if (attachment.mime == "text/plain") {
                require(bytes.size <= 300_000) { "Text files must be under 300 KB. Split this file and retry." }
                parts.put(JSONObject().put("text", "BEGIN ATTACHED FILE DATA\n${bytes.toString(Charsets.UTF_8)}\nEND ATTACHED FILE DATA"))
            } else {
                require(attachment.mime in images || attachment.mime == "application/pdf")
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", attachment.mime).put("data", Base64.getEncoder().encodeToString(bytes))))
            }
        }
        return parts
    }
}
