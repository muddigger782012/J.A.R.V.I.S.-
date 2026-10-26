package com.jarvis.developer

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** App-private, content-addressed cache. No repository or remote name becomes a local path. */
internal class DevelopmentArtifactFiles(cache: File) {
    private val anchor = cache.canonicalFile
    val root: File get() {
        require(anchor.isDirectory && !Files.isSymbolicLink(anchor.toPath()))
        val folder = File(anchor, "development-artifacts")
        require(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile == folder.absoluteFile)
        require(folder.isDirectory || folder.mkdir())
        return folder
    }
    fun temporary(): File = File.createTempFile("read-", ".part", root)
    fun apk(digest: String): File {
        require(digest.matches(Regex("[a-f0-9]{64}")))
        return File(root, "$digest.apk").also {
            require(!Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root)
        }
    }
    fun checked(record: DevelopmentArtifact.Record): File = apk(record.apkSha256).also {
        if (!Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) || it.length() != record.bytes ||
            sha256(it) != record.apkSha256) throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.STORAGE)
    }
    companion object {
        fun sha256(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
