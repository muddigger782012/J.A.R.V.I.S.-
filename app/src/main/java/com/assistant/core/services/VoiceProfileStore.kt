package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

data class VoicePractice(val heard: String, val intended: String, val language: String, val mode: String, val hinted: Boolean) {
    val matched get() = VoiceProfileStore.normalize(heard) == VoiceProfileStore.normalize(intended)
    fun json() = JSONObject().put("heard", heard).put("intended", intended).put("language", language)
        .put("mode", mode).put("hinted", hinted)
}

/** Recognition vocabulary only. This store has no command routing or execution dependencies. */
class VoiceProfileStore(private val directory: File) {
    private val file get() = File(directory, "my-voice-profile.json")
    fun entries(): List<VoicePractice> = runCatching {
        val array = JSONArray(file.readText())
        (0 until minOf(array.length(), LIMIT)).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val heard = item.optString("heard")
            val intended = item.optString("intended")
            val language = item.optString("language")
            val mode = item.optString("mode")
            if (!valid(heard) || !valid(intended) || !validLanguage(language) || mode !in listOf("device", "default")) null
            else VoicePractice(heard, intended, language, mode, item.optBoolean("hinted"))
        }
    }.getOrDefault(emptyList())

    fun approve(heard: String, intended: String, language: String, mode: String, hinted: Boolean): Boolean {
        if (!valid(heard) || !valid(intended) || !validLanguage(language) || mode !in listOf("device", "default")) return false
        return update { (it + VoicePractice(heard.trim(), intended.trim(), language, mode, hinted)).takeLast(LIMIT) }
    }
    fun remove(index: Int): Boolean = update { it.filterIndexed { i, _ -> i != index } }
    fun clear(): Boolean = update { emptyList() }
    fun hints(language: String): List<String> = entries().asReversed().filter { it.language == language }
        .map { it.intended }.distinctBy { normalize(it) }.take(50)

    private fun update(change: (List<VoicePractice>) -> List<VoicePractice>): Boolean = synchronized(lock) {
        runCatching {
            RandomAccessFile(File(directory, "my-voice-profile.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val array = JSONArray().apply { change(entries()).forEach { put(it.json()) } }
                    val temporary = File(directory, "my-voice-profile.tmp")
                    FileOutputStream(temporary).use { stream ->
                        stream.write(array.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync()
                    }
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            true
        }.getOrDefault(false)
    }
    companion object {
        private val lock = Any()
        const val LIMIT = 200
        fun valid(text: String) = text.trim().length in 1..160 && text.none { it.isISOControl() }
        fun validLanguage(text: String) = text.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))
        fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .trim().replace(Regex("\\s+"), " ")
    }
}
