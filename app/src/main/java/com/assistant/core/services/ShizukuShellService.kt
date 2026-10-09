package com.assistant.core.services

import android.content.Context
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class ShizukuShellResult(
    val success: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val command: String,
    val usedRishMode: Boolean,
    val helperPath: String
)

data class ParsedRishCommand(
    val command: String,
    val args: List<String>,
    val directExec: Boolean,
    val usedRishMode: Boolean,
    val interactiveOnly: Boolean
)

class RunningShizukuCommand(
    private val process: IRemoteProcess,
    private val workerFuture: Future<*>,
    private val executor: java.util.concurrent.ExecutorService
) {
    fun stop() {
        try {
            process.destroy()
        } catch (_: Throwable) {
            // ignore
        } finally {
            workerFuture.cancel(true)
            executor.shutdownNow()
        }
    }
}

class ShizukuShellService(private val context: Context) {

    fun ensureRishHelperFile(): File {
        val file = File(context.filesDir, ".rish")
        if (!file.exists()) {
            file.writeText(
                """
                # J.A.R.V.I.S. Shizuku helper
                # This app supports rish-style commands in the Shizuku tab.
                # Examples:
                #   .rish -c "id"
                #   .rish -c "pm list packages | head"
                #   .rish exec /system/bin/getprop ro.build.version.release
                #
                # Interactive rish shell is not available inside this app UI.
                """.trimIndent()
            )
            file.setReadable(true, false)
        }
        return file
    }

    fun parseRishCommand(rawCommand: String): ParsedRishCommand {
        val raw = rawCommand.trim()
        if (!raw.startsWith(".rish")) {
            return ParsedRishCommand(
                command = raw.ifBlank { "id" },
                args = listOf("sh", "-c", raw.ifBlank { "id" }),
                directExec = false,
                usedRishMode = false,
                interactiveOnly = false
            )
        }

        val payload = raw.removePrefix(".rish").trim()
        if (payload.isBlank()) {
            return ParsedRishCommand(
                command = "",
                args = emptyList(),
                directExec = false,
                usedRishMode = true,
                interactiveOnly = true
            )
        }

        if (payload.startsWith("exec ")) {
            val execCommand = payload.removePrefix("exec").trim()
            val parts = splitArgs(execCommand)
            return ParsedRishCommand(
                command = execCommand,
                args = parts,
                directExec = true,
                usedRishMode = true,
                interactiveOnly = parts.isEmpty()
            )
        }

        val shellCommand = when {
            payload.startsWith("-c ") -> payload.removePrefix("-c").trim()
            else -> payload
        }
        return ParsedRishCommand(
            command = shellCommand.ifBlank { "id" },
            args = listOf("sh", "-c", shellCommand.ifBlank { "id" }),
            directExec = false,
            usedRishMode = true,
            interactiveOnly = false
        )
    }

    fun run(parsed: ParsedRishCommand, timeoutMs: Long = 15000): ShizukuShellResult {
        val helperPath = ensureRishHelperFile().absolutePath
        val service = requireShizukuService()
        val process = service.newProcess(
            parsed.args.toTypedArray(),
            null,
            "/"
        )

        val outStream = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
        val errStream = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val stdoutFuture = executor.submit(Callable { readStream(outStream) })
            val stderrFuture = executor.submit(Callable { readStream(errStream) })

            val finished = process.waitForTimeout(timeoutMs, "MILLISECONDS")
            val exitCode = if (finished) process.exitValue() else {
                process.destroy()
                -1
            }

            val stdout = awaitString(stdoutFuture)
            val stderr = awaitString(stderrFuture)

            return ShizukuShellResult(
                success = finished && exitCode == 0,
                exitCode = exitCode,
                stdout = stdout,
                stderr = if (!finished) "Command timed out after ${timeoutMs}ms.\n$stderr" else stderr,
                command = parsed.command,
                usedRishMode = parsed.usedRishMode,
                helperPath = helperPath
            )
        } finally {
            executor.shutdownNow()
        }
    }

    fun runStreaming(
        parsed: ParsedRishCommand,
        timeoutMs: Long = 120000,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
        onCompleted: (exitCode: Int, timedOut: Boolean) -> Unit,
        onError: (String) -> Unit
    ): RunningShizukuCommand {
        val service = requireShizukuService()
        val process = service.newProcess(parsed.args.toTypedArray(), null, "/")
        val outStream = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
        val errStream = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.submit {
            try {
                val stdoutReader = Thread {
                    readStreamLines(outStream) { line -> onStdout(line) }
                }
                val stderrReader = Thread {
                    readStreamLines(errStream) { line -> onStderr(line) }
                }
                stdoutReader.start()
                stderrReader.start()

                val finished = process.waitForTimeout(timeoutMs, "MILLISECONDS")
                val exitCode = if (finished) process.exitValue() else {
                    process.destroy()
                    -1
                }

                stdoutReader.join(1500)
                stderrReader.join(1500)
                onCompleted(exitCode, !finished)
            } catch (error: Throwable) {
                onError(error.message ?: "Streaming shell execution failed.")
            }
        }
        return RunningShizukuCommand(process, worker, executor)
    }

    private fun requireShizukuService(): IShizukuService {
        val method = Shizuku::class.java.getDeclaredMethod("requireService")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null) as IShizukuService
    }

    private fun readStream(inputStream: InputStream): String {
        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun readStreamLines(inputStream: InputStream, onLine: (String) -> Unit) {
        inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        }
    }

    private fun awaitString(future: Future<String>): String {
        return try {
            future.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun splitArgs(command: String): List<String> {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return emptyList()
        return trimmed.split(Regex("\\s+"))
    }
}
