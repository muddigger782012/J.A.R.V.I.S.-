package com.assistant.core.services

import android.content.Context
import java.io.File

class FileService(private val context: Context) {

    fun ensureDirectory(directory: File): File {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    fun writeTextFile(file: File, content: String) {
        file.parentFile?.let { ensureDirectory(it) }
        file.writeText(content)
    }

    fun readTextFile(file: File): String? {
        return if (file.exists() && file.isFile) file.readText() else null
    }

    fun listProjectFiles(projectRoot: File): List<File> {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return emptyList()
        return projectRoot.walkTopDown().filter { it.isFile }.toList()
    }

    fun projectsRoot(): File {
        return ensureDirectory(File(context.filesDir, "projects"))
    }
}
