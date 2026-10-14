package com.assistant.core.services

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

sealed class UpdateCheckResult {
    data class Available(val candidate: UpdateCandidate) : UpdateCheckResult() {
        val metadata: UpdateMetadata get() = candidate.metadata
        val artifact: UpdateArtifact get() = candidate.artifact
    }
    data class UpToDate(val channel: UpdateChannel, val versionName: String) : UpdateCheckResult()
    data class Failure(val failure: UpdateFailure) : UpdateCheckResult()
}

sealed class UpdateDownloadResult {
    /** Receipt for a verified archive; verify again after leaving the app for installation permission. */
    data class Downloaded(val file: File, val candidate: UpdateCandidate) : UpdateDownloadResult()
    data class Failure(val failure: UpdateFailure) : UpdateDownloadResult()
}

/** Blocking operations belong on the Update button's existing background worker, never the main thread. */
class ApkUpdateService(
    context: Context,
    accessToken: String? = null,
    private val transport: UpdateHttpTransport = UpdateHttpTransport(accessToken)
) {
    private val appContext = context.applicationContext
    private val updateDirectory get() = File(appContext.cacheDir, "updates")

    fun checkForUpdate(): UpdateCheckResult = try {
        val installed = installedIdentity(appContext)
        val channel = UpdateChannel.forApplicationId(installed.applicationId)
        val metadata = UpdatePolicy.parseMetadata(transport.fetchMetadata(channel), channel)
        val candidate = UpdatePolicy.selectCandidate(metadata, installed)
        if (candidate == null) UpdateCheckResult.UpToDate(channel, metadata.versionName)
        else UpdateCheckResult.Available(candidate)
    } catch (error: Exception) {
        UpdateCheckResult.Failure(failure(error, UpdateFailureCode.NETWORK))
    }

    fun downloadAndVerify(
        available: UpdateCheckResult.Available,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): UpdateDownloadResult {
        var partial: File? = null
        var complete: File? = null
        return try {
            val candidate = available.candidate
            val installed = installedIdentity(appContext)
            val selected = UpdatePolicy.selectCandidate(candidate.metadata, installed)
                ?: throw UpdateException(UpdateFailureCode.VERSION_MISMATCH, "This update is no longer newer than the installed app.")
            if (selected != candidate) throw UpdateException(UpdateFailureCode.INVALID_METADATA, "The selected update artifact has changed.")
            val directory = updateDirectory
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw UpdateException(UpdateFailureCode.STORAGE, "The update cache could not be created.")
            }
            cleanExpiredFiles(directory)
            if (StatFs(directory.absolutePath).availableBytes < candidate.artifact.sizeBytes + 64L * 1024 * 1024) {
                throw UpdateException(UpdateFailureCode.STORAGE, "There is not enough free storage to download the update. Free some space and try again.")
            }
            val basename = "${candidate.metadata.channel.id}-${UUID.randomUUID()}"
            partial = File(directory, "$basename.part")
            complete = File(directory, "$basename.apk")
            transport.download(candidate, partial, onProgress)
            UpdatePolicy.validateArchive(candidate, installedIdentity(appContext), inspectArchive(appContext, partial))
            if (!partial.renameTo(complete)) {
                throw UpdateException(UpdateFailureCode.STORAGE, "The verified APK could not be saved for installation.")
            }
            UpdateDownloadResult.Downloaded(complete, candidate)
        } catch (error: Exception) {
            partial?.delete()
            complete?.delete()
            UpdateDownloadResult.Failure(failure(error, UpdateFailureCode.NETWORK))
        }
    }

    /** No network: detects tampering, cache loss, and an intervening installation before the system installer. */
    fun verifyDownloaded(downloaded: UpdateDownloadResult.Downloaded): UpdateDownloadResult = try {
        if (!isOwnedFile(downloaded.file) || !downloaded.file.isFile) {
            throw UpdateException(UpdateFailureCode.INVALID_APK, "The downloaded update is no longer available. Tap Update App to download it again.")
        }
        verifyFileIntegrity(downloaded.file, downloaded.candidate.artifact)
        UpdatePolicy.validateArchive(downloaded.candidate, installedIdentity(appContext), inspectArchive(appContext, downloaded.file))
        downloaded
    } catch (error: Exception) {
        // Activity recreation interrupts its worker. Keep the receipt's archive for the next
        // activity to verify from the beginning; cancellation does not establish corruption.
        if (error !is UpdateException || error.code != UpdateFailureCode.CANCELLED) discard(downloaded.file)
        UpdateDownloadResult.Failure(failure(error, UpdateFailureCode.INVALID_APK))
    }

    fun discard(file: File) {
        if (isOwnedFile(file)) file.delete()
    }

    private fun isOwnedFile(file: File): Boolean = runCatching {
        file.canonicalFile.parentFile == updateDirectory.canonicalFile &&
            file.name.matches(Regex("(jarvis|beta)-[a-f0-9-]{36}\\.(apk|part)"))
    }.getOrDefault(false)

    private fun cleanExpiredFiles(directory: File) {
        val oldest = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles()?.filter { isOwnedFile(it) && it.lastModified() < oldest }?.forEach { it.delete() }
    }

    companion object {
        /** Bundle state only. The receipt never contains a token and does not prove current file integrity. */
        fun serializeReceipt(downloaded: UpdateDownloadResult.Downloaded): String = UpdateReceiptState.encode(downloaded)

        fun restoreReceipt(context: Context, raw: String): UpdateDownloadResult.Downloaded? = runCatching {
            UpdateReceiptState.decode(raw, File(context.applicationContext.cacheDir, "updates"),
                UpdateChannel.forApplicationId(context.packageName))
        }.getOrNull()

        @Suppress("DEPRECATION")
        fun installedIdentity(context: Context): InstalledUpdateIdentity {
            val info = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
            return InstalledUpdateIdentity(info.packageName, versionCode(info), signerDigests(info),
                Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
        }

        @Suppress("DEPRECATION")
        fun inspectArchive(context: Context, file: File): ApkUpdateIdentity {
            try {
                val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
                    ?: throw UpdateException(UpdateFailureCode.INVALID_APK, "Android could not verify the downloaded APK archive.")
                val applicationInfo = info.applicationInfo
                    ?: throw UpdateException(UpdateFailureCode.INVALID_APK, "The downloaded APK has no application identity.")
                val nativeAbis = mutableSetOf<String>()
                ZipFile(file).use { archive ->
                    val entries = archive.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val path = entry.name.split('/')
                        if (!entry.isDirectory && path.size >= 3 && path[0] == "lib") nativeAbis += path[1]
                    }
                }
                return ApkUpdateIdentity(info.packageName, versionCode(info), signerDigests(info), applicationInfo.minSdkVersion, nativeAbis)
            } catch (error: UpdateException) {
                throw error
            } catch (_: Exception) {
                throw UpdateException(UpdateFailureCode.INVALID_APK, "The downloaded file is not a valid signed APK archive.")
            }
        }

        @Suppress("DEPRECATION")
        private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        @Suppress("DEPRECATION")
        private fun signerDigests(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            if (signatures.isNullOrEmpty()) {
                throw UpdateException(UpdateFailureCode.DIFFERENT_SIGNER, "Android could not read the app signing certificate.")
            }
            return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        }

        private fun verifyFileIntegrity(file: File, artifact: UpdateArtifact) {
            if (file.length() != artifact.sizeBytes || file.length() !in 1..UpdatePolicy.MAX_APK_BYTES) {
                throw UpdateException(UpdateFailureCode.DOWNLOAD_INTEGRITY, "The downloaded APK size changed or the download is incomplete.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw UpdateException(UpdateFailureCode.CANCELLED, "Update verification was cancelled.")
                    val length = input.read(buffer)
                    if (length == -1) break
                    count += length
                    if (count > artifact.sizeBytes) throw UpdateException(UpdateFailureCode.DOWNLOAD_INTEGRITY, "The downloaded APK size changed.")
                    digest.update(buffer, 0, length)
                }
            }
            if (count != artifact.sizeBytes || digest.digest().hex() != artifact.sha256) {
                throw UpdateException(UpdateFailureCode.DOWNLOAD_INTEGRITY, "The downloaded APK failed its SHA-256 integrity check. Download it again.")
            }
        }

        private fun failure(error: Exception, fallback: UpdateFailureCode): UpdateFailure = when (error) {
            is UpdateException -> error.failure()
            is FileNotFoundException -> UpdateFailure(UpdateFailureCode.STORAGE, "The update file could not be saved or read. Check storage and try again.")
            is IOException -> UpdateFailure(fallback, "The update could not be read or downloaded. Check your connection and storage, then try again.")
            else -> UpdateFailure(fallback, "The update could not be verified. Your existing app has been kept; please try again.")
        }
    }
}
