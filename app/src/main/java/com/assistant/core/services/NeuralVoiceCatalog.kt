package com.assistant.core.services

import java.io.File

/** Speaker IDs from sherpa's kokoro-en-v0_19 mapping, not the v1.0 mapping. */
object NeuralVoiceCatalog {
    data class Voice(val id: String, val label: String, val speaker: Int)
    val voices = listOf(
        Voice("kokoro-v019:george", "George · British male", 9),
        Voice("kokoro-v019:lewis", "Lewis · British male", 10),
        Voice("kokoro-v019:michael", "Michael · American male", 6)
    )
    fun resolve(id: String) = voices.firstOrNull { it.id == id } ?: voices.first()
    fun safeDestination(root: File, archivePath: String): File {
        require(!archivePath.startsWith("/") && !archivePath.contains('\\'))
        val parts = archivePath.split('/')
        require(parts.first() == "kokoro-en-v0_19" && parts.none { it == ".." })
        val file = File(root, parts.drop(1).joinToString("/")).canonicalFile
        require(file == root.canonicalFile || file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }
}
