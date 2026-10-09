package com.assistant.core.services

import android.content.Context
import java.io.File

class CodingService(
    private val context: Context,
    private val fileService: FileService
) {

    fun createProject(name: String): File {
        val root = File(context.filesDir, "projects/$name")
        fileService.ensureDirectory(root)

        val mainFile = File(root, "main.py")
        fileService.writeTextFile(
            mainFile,
            """
            def main():
                print("Hello from J.A.R.V.I.S.")

            if __name__ == "__main__":
                main()
            """.trimIndent()
        )

        return root
    }
}
