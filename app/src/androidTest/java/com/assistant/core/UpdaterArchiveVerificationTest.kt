package com.assistant.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Examines real split APKs and certificates with Android's PackageManager, without accessing GitHub. */
@RunWith(AndroidJUnit4::class)
class UpdaterArchiveVerificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val installed get() = ApkUpdateService.installedIdentity(context)
    private val channel get() = UpdateChannel.forApplicationId(context.packageName)
    private val service get() = ApkUpdateService(context)

    private fun fixture(name: String): File {
        assertTrue("Use only the disposable updater regression variants",
            BuildConfig.BUILD_TYPE in setOf("updaterCheck", "updaterBetaCheck"))
        return File(context.filesDir, "updater-regression/$name.apk").also {
            assertTrue("The signed $name APK fixture is missing", it.isFile)
        }
    }

    private fun candidate(file: File): UpdateCandidate {
        val artifact = UpdateArtifact(
            abi = "x86_64", url = channel.artifactUrl("x86_64"), sizeBytes = file.length(),
            sha256 = sha256(file), signingCertificateSha256 = installed.signingCertificateSha256.single(), minSdk = 26
        )
        return UpdateCandidate(UpdateMetadata(channel, context.packageName, 109L, "fixture-109",
            "0".repeat(40), listOf(artifact)), artifact)
    }

    private fun receipt(name: String, advertised: UpdateCandidate? = null): UpdateDownloadResult.Downloaded {
        val source = fixture(name)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "${channel.id}-${UUID.randomUUID()}.apk")
        source.copyTo(target, overwrite = false)
        return UpdateDownloadResult.Downloaded(target, advertised ?: candidate(source))
    }

    private fun assertFailure(name: String, code: UpdateFailureCode) {
        assertEquals(108L, installed.versionCode)
        val downloaded = receipt(name)
        val result = service.verifyDownloaded(downloaded)
        assertTrue("The $name APK must fail verification", result is UpdateDownloadResult.Failure)
        assertEquals(code, (result as UpdateDownloadResult.Failure).failure.code)
        assertFalse("Rejected private-cache APKs must be removed so a retry starts cleanly", downloaded.file.exists())
        assertEquals(108L, installed.versionCode)
    }

    @Test fun matchingSignedPackageIsAccepted() {
        assertEquals(108L, installed.versionCode)
        val downloaded = receipt("matching")
        try {
            val archive = ApkUpdateService.inspectArchive(context, downloaded.file)
            assertEquals(context.packageName, archive.applicationId)
            assertEquals(109L, archive.versionCode)
            assertEquals(installed.signingCertificateSha256, archive.signingCertificateSha256)
            assertEquals(setOf("x86_64"), archive.nativeAbis)
            assertTrue(service.verifyDownloaded(downloaded) is UpdateDownloadResult.Downloaded)
        } finally { service.discard(downloaded.file) }
    }

    @Test fun oppositeVariantIsRejected() = assertFailure("opposite", UpdateFailureCode.WRONG_PACKAGE)
    @Test fun nonIncreasingArchiveIsRejected() = assertFailure("older", UpdateFailureCode.VERSION_MISMATCH)
    @Test fun wrongNativeAbiIsRejected() = assertFailure("wrong-abi", UpdateFailureCode.NO_COMPATIBLE_APK)
    @Test fun mismatchedSigningCertificateIsRejected() = assertFailure("wrong-signer", UpdateFailureCode.DIFFERENT_SIGNER)

    @Test fun modifiedDownloadIsDiscardedAndRetrySucceeds() {
        assertEquals(108L, installed.versionCode)
        val downloaded = receipt("matching")
        RandomAccessFile(downloaded.file, "rw").use { file ->
            file.seek(file.length() / 2)
            val original = file.readByte().toInt()
            file.seek(file.length() / 2)
            file.writeByte(original xor 1)
        }
        val result = service.verifyDownloaded(downloaded)
        assertTrue(result is UpdateDownloadResult.Failure)
        assertEquals(UpdateFailureCode.DOWNLOAD_INTEGRITY, (result as UpdateDownloadResult.Failure).failure.code)
        assertFalse(downloaded.file.exists())
        val retry = receipt("matching")
        try { assertTrue(service.verifyDownloaded(retry) is UpdateDownloadResult.Downloaded) }
        finally { service.discard(retry.file) }
    }

    @Test fun missingCachedDownloadIsRecoverable() {
        val downloaded = receipt("matching")
        assertTrue(downloaded.file.delete())
        val result = service.verifyDownloaded(downloaded)
        assertTrue(result is UpdateDownloadResult.Failure)
        assertEquals(UpdateFailureCode.INVALID_APK, (result as UpdateDownloadResult.Failure).failure.code)
    }

    @Test fun interruptedRecheckKeepsCacheAndRetrySucceeds() {
        assertEquals(108L, installed.versionCode)
        val downloaded = receipt("matching")
        try {
            try {
                Thread.currentThread().interrupt()
                val result = service.verifyDownloaded(downloaded)
                assertTrue(result is UpdateDownloadResult.Failure)
                assertEquals(UpdateFailureCode.CANCELLED, (result as UpdateDownloadResult.Failure).failure.code)
                assertTrue("An interrupted activity recheck must leave the receipt available for its replacement", downloaded.file.isFile)
            } finally {
                // JUnit uses the same worker for later tests; never leave it interrupted.
                Thread.interrupted()
            }
            assertTrue(service.verifyDownloaded(downloaded) is UpdateDownloadResult.Downloaded)
        } finally { service.discard(downloaded.file) }
    }

    @Test fun anotherDistributionSourceIsRejected() {
        val original = candidate(fixture("matching"))
        val artifact = original.artifact.copy(url = "https://example.invalid/Jarvis-x86_64.apk")
        val downloaded = receipt("matching", original.copy(
            artifact = artifact, metadata = original.metadata.copy(artifacts = listOf(artifact))))
        val result = service.verifyDownloaded(downloaded)
        assertTrue(result is UpdateDownloadResult.Failure)
        assertEquals(UpdateFailureCode.INVALID_APK, (result as UpdateDownloadResult.Failure).failure.code)
        assertFalse(downloaded.file.exists())
    }

    @Test fun alreadyInstalledCandidateIsRejectedAfterUpgrade() {
        assertEquals(109L, installed.versionCode)
        val downloaded = receipt("matching")
        val result = service.verifyDownloaded(downloaded)
        assertTrue(result is UpdateDownloadResult.Failure)
        assertEquals(UpdateFailureCode.VERSION_MISMATCH, (result as UpdateDownloadResult.Failure).failure.code)
        assertFalse(downloaded.file.exists())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
