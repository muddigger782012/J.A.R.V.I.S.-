package com.assistant.core.services

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Bounded per-chat context. It cannot approve actions or add learned commands. */
class ConversationContext(private val directory: File) {
    data class Token(val id: String, val generation: Long)
    private var current = Token("", 0)
    @Synchronized fun bind(id: String, seed: List<Pair<String, String>> = emptyList(), force: Boolean = false): Token {
        require(id.length in 1..200)
        if (id != current.id || force) current = Token(id, current.generation + 1)
        if (seed.isNotEmpty() && snapshot().isEmpty()) seed.takeLast(12).forEach { remember(current, it.first, it.second) }
        return current
    }
    @Synchronized fun token() = current
    @Synchronized fun accepts(token: Token) = token == current && token.id.isNotBlank()
    private fun file(id: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "chat-context-$hash.json")
    }
    @Synchronized fun snapshot(): List<Pair<String, String>> = read(current.id)
    private fun read(id: String): List<Pair<String, String>> = if (id.isBlank()) emptyList() else runCatching {
        val raw = file(id).readText(); require(raw.length <= 65536)
        val array = JSONArray(raw)
        (0 until minOf(array.length(), 12)).mapNotNull { i ->
            val row = array.getJSONObject(i)
            val user = row.getString("user"); val assistant = row.getString("assistant")
            (user to assistant).takeIf { allowed(user) && user.length <= 1800 && assistant.length <= 3000 }
        }
    }.getOrDefault(emptyList())
    @Synchronized fun remember(token: Token, user: String, assistant: String): Boolean {
        if (!accepts(token) || !allowed(user)) return false
        return runCatching {
            synchronized(lock) {
                directory.mkdirs()
                RandomAccessFile(File(directory, "chat-context.lock"), "rw").use { mutex -> mutex.channel.lock().use {
                    val rows = (read(token.id) + (user.take(1800) to assistant.take(3000))).takeLast(12)
                    val array = JSONArray().apply { rows.forEach { (u, a) -> put(JSONObject().put("user", u).put("assistant", a)) } }
                    val destination = file(token.id); val temporary = File(directory, destination.name + ".tmp")
                    FileOutputStream(temporary).use { it.write(array.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    directory.listFiles { f -> f.name.startsWith("chat-context-") && f.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
                } }
            }
            true
        }.getOrDefault(false)
    }
    companion object {
        private val lock = Any()
        fun allowed(user: String): Boolean {
            val text = user.lowercase(java.util.Locale.ROOT).trim()
            if (text.isBlank() || CapabilityDiscovery.isManagementText(user) || LearnedCommandReviewCommands.page(user) != null ||
                text in setOf("assistant memory", "what do you remember about my apps and timers", "learning status", "show learning status", "how many commands have you learned")) return false
            if (text.contains("intelligence plan") && !text.startsWith("teach ") && !text.startsWith("forget ")) return true
            return !text.startsWith("show saved") && !text.startsWith("show learned") && !text.startsWith("show assistant memory") &&
                !text.startsWith("show result") && !text.startsWith("show diagnostics") && !text.startsWith("list routines") &&
                !CapabilityDiscovery.isManagementText(user) && !text.startsWith("review learned") && !text.startsWith("forget ") && !text.startsWith("teach ")
        }
        fun pairs(lines: List<String>): List<Pair<String, String>> {
            val rows = mutableListOf<Pair<String, String>>()
            var user: String? = null
            lines.forEach { line ->
                if (line.startsWith("You:")) user = line.removePrefix("You:").trim()
                else if (line.startsWith("J.A.R.V.I.S.:")) {
                    val question = user
                    if (question != null && allowed(question)) rows.add(question to line.removePrefix("J.A.R.V.I.S.:").trim())
                    user = null
                }
            }
            return rows.takeLast(12)
        }
    }
}
