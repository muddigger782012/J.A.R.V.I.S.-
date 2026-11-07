package com.jarvis.voicebox

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceboxClientTest {
    private fun serve(path: String, code: Int = 200, bytes: ByteArray, block: (VoiceboxClient, () -> String) -> Unit) {
        var request = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            request = exchange.requestBody.bufferedReader().readText()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block(VoiceboxClient("http://127.0.0.1:${server.address.port}")) { request } }
        finally { server.stop(0) }
    }
    @Test fun profilesUseActualHostAndPreserveNames() {
        serve("/profiles", bytes = """[{"id":"abc","name":"Brandon"}]""".toByteArray()) { client, _ ->
            assertEquals(listOf("abc" to "Brandon"), client.profiles())
        }
    }
    @Test fun speechReturnsWavAndEscapesText() {
        val wav = ByteArray(44).also { "RIFF".toByteArray().copyInto(it); "WAVE".toByteArray().copyInto(it, 8) }
        serve("/v1/audio/speech", bytes = wav) { client, body ->
            assertArrayEquals(wav, client.speech("Hello \"Brandon\"\nReady?", "George"))
            val sent = JSONObject(body())
            assertEquals("Hello \"Brandon\"\nReady?", sent.getString("input"))
            assertEquals("George", sent.getString("voice"))
            assertEquals("wav", sent.getString("response_format"))
            assertEquals("tts-1", sent.getString("model"))
        }
    }
    @Test fun errorsDoNotEchoServerContent() {
        serve("/v1/audio/speech", 503, "private diagnostic".toByteArray()) { client, _ ->
            val failure = runCatching { client.speech("Hi", "George") }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("503")); assertFalse(failure.message!!.contains("private"))
        }
    }
    @Test fun invalidAudioCannotBePlayed() {
        serve("/v1/audio/speech", bytes = ByteArray(100)) { client, _ ->
            assertTrue(runCatching { client.speech("Hi", "George") }.isFailure)
        }
    }
    @Test fun cancelledClientNeverConnects() {
        val client = VoiceboxClient("http://127.0.0.1:1")
        client.cancel()
        assertEquals("Voicebox request cancelled", runCatching { client.profiles() }.exceptionOrNull()!!.message)
    }
    @Test fun unconfiguredOrOversizedSpeechRejectedBeforeNetwork() {
        val client = VoiceboxClient("http://127.0.0.1:1")
        for ((text, voice) in listOf("" to "x", "hi" to "", "x".repeat(10001) to "x"))
            assertTrue(runCatching { client.speech(text, voice) }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun endpointRejectsPlaintextRemoteCredentialsAndQuery() {
        for (url in listOf("http://192.168.1.2:17493", "https://user:secret@host", "https://host?token=x", "https://host/path", "file:///tmp/voice", "https://host#fragment"))
            assertTrue(url, runCatching { VoiceboxClient.validateEndpoint(url) }.isFailure)
        assertEquals("https://voicebox.example", VoiceboxClient.validateEndpoint(" https://voicebox.example/ "))
    }
    @Test fun redirectsAreNotFollowed() {
        serve("/profiles", 302, "redirect".toByteArray()) { client, _ ->
            assertTrue(runCatching { client.profiles() }.exceptionOrNull()!!.message!!.contains("302"))
        }
    }
}
