package com.jarvis.voicebox

import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** JVM-only REST adapter; reusable by a desktop host without Android dependencies. */
class VoiceboxClient(endpoint: String) {
    private val base = validateEndpoint(endpoint)
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; connection?.disconnect() }
    fun profiles(): List<Pair<String, String>> {
        val array = JSONArray(String(request("/profiles"), Charsets.UTF_8))
        return (0 until array.length()).map { array.getJSONObject(it).let { p -> p.getString("id") to p.getString("name") } }
    }
    fun speech(text: String, voice: String): ByteArray {
        require(text.isNotBlank() && text.length <= 10000) { "Voicebox accepts 1–10,000 characters per reply" }
        require(voice.isNotBlank()) { "Choose a Voicebox profile first" }
        val body = JSONObject().put("model", "tts-1").put("input", text)
            .put("voice", voice).put("response_format", "wav").toString()
        val audio = request("/v1/audio/speech", body)
        check(audio.size >= 44 && String(audio, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(audio, 8, 4, Charsets.US_ASCII) == "WAVE") { "Voicebox returned invalid WAV audio" }
        return audio
    }
    private fun request(path: String, body: String? = null): ByteArray {
        check(!cancelled) { "Voicebox request cancelled" }
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        connection = c
        try {
            check(!cancelled) { "Voicebox request cancelled" }
            c.connectTimeout = 5000; c.readTimeout = if (body == null) 10000 else 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("X-Voicebox-Client-Id", "jarvis")
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(c.responseCode in 200..299) { "Voicebox HTTP ${c.responseCode}. Check the host, profile and installed model." }
            val limit = if (body == null) 1024 * 1024 else 32 * 1024 * 1024
            return c.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!cancelled) { "Voicebox request cancelled" }
                    val count = stream.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= limit) { "Voicebox response exceeds size limit" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
        } finally { c.disconnect(); connection = null }
    }
    companion object {
        fun validateEndpoint(value: String): String {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (uri.path.isNullOrEmpty() || uri.path == "/") &&
                (uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))) {
                "Use an HTTPS host URL, or http://127.0.0.1:17493 on the same device"
            }
            return uri.toString().trimEnd('/')
        }
    }
}
