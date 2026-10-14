package com.assistant.core.services

import android.content.Context
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Public, informational plan feed. It has no command, installation or learning authority. */
class IntelligencePlanRepository internal constructor(private val directory: File, private val bundled: () -> String) {
    constructor(context: Context) : this(context.filesDir, { context.assets.open("intelligence-plan.json").bufferedReader().use { it.readText() } })
    private val cache get() = File(directory, "intelligence-plan-cache.json")
    fun snapshot(): IntelligencePlan {
        val shipped = IntelligencePlan.parse(bundled()) ?: error("Invalid bundled intelligence plan")
        val saved = runCatching { IntelligencePlan.parse(cache.readText()) }.getOrNull()
        return saved?.takeIf { it.revision > shipped.revision } ?: shipped
    }
    fun accept(raw: String): String = synchronized(lock) {
        val incoming = IntelligencePlan.parse(raw) ?: return@synchronized "The plan update was invalid. Kept the existing plan."
        val current = snapshot()
        if (incoming.revision < current.revision) return@synchronized "The feed returned an older plan. Kept revision ${current.revision}."
        if (incoming.revision == current.revision) return@synchronized "Plan revision ${current.revision} is already current."
        directory.mkdirs()
        val temporary = File(directory, "intelligence-plan-cache.tmp")
        FileOutputStream(temporary).use { it.write(raw.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        "Updated to plan revision ${incoming.revision}. This updates the progress report, not app capabilities."
    }
    fun refresh(): String {
        val connection = URL(FEED).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
            require(connection.responseCode == 200)
            require(connection.contentLengthLong <= 65536)
            val data = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(data.size() + count <= 65536)
                    data.write(buffer, 0, count)
                }
            }
            return accept(data.toString("UTF-8"))
        } finally { connection.disconnect() }
    }
    companion object {
        const val FEED = "https://raw.githubusercontent.com/muddigger782012/J.A.R.V.I.S.-/main/docs/intelligence-plan.json"
        private val lock = Any()
    }
}
