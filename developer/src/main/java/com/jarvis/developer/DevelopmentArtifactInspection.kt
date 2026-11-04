package com.jarvis.developer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Verify cryptographic content before trusting Android's archive metadata. No install side effects. */
internal object DevelopmentArtifactInspection {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    internal fun certificates(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
    @Suppress("DEPRECATION")
    internal fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    fun inspect(context: Context, file: File, record: DevelopmentArtifact.Record) {
        DevelopmentArtifact.validate(record)
        inspectArchive(context, file, record.bytes, record.apkSha256, record.packageName, record.versionCode,
            record.versionName, record.signerSha256)
    }
    /** Updater provenance never fabricates a CI artifact/run record or an installation result. */
    fun inspectUpdate(context: Context, file: File, release: DeveloperUpdateRelease.Release) {
        DeveloperUpdateRelease.validate(release)
        require(context.packageName == DeveloperUpdateRelease.PACKAGE)
        inspectArchive(context, file, release.bytes, release.sha256, DeveloperUpdateRelease.PACKAGE,
            release.version, release.versionName, DeveloperUpdateRelease.SIGNER)
    }
    private fun inspectArchive(context: Context, file: File, bytes: Long, sha256: String, packageName: String,
        versionCode: Long, versionName: String, signerSha256: String) {
        if (file.length() != bytes || DevelopmentArtifactFiles.sha256(file) != sha256)
            throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.STORAGE)
        val signers = verifiedSigners(file, Build.VERSION.SDK_INT)
        val pm = context.packageManager
        val trusted = certificates(pm.getPackageInfo(context.packageName, flags()))
        if (signers != setOf(signerSha256) || signers != trusted)
            throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.SIGNATURE)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags())
            ?: throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.COMPATIBILITY)
        val installed = try { pm.getPackageInfo(packageName, flags()) } catch (_: PackageManager.NameNotFoundException) { null }
        if (archive.packageName != packageName || version(archive) != versionCode ||
            archive.versionName != versionName || certificates(archive) != signers ||
            archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT ||
            installed != null && (version(installed) > versionCode || certificates(installed) != signers))
            throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.COMPATIBILITY)
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().take(30_001).map { it.name }.toList()
            require(names.size <= 30_000 && names.distinct().size == names.size)
            val abis = names.filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/')[1] }.toSet()
            if (abis.isNotEmpty() && Build.SUPPORTED_ABIS.none { it in abis })
                throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.COMPATIBILITY)
        }
    }
    internal fun verifiedSigners(file: File, sdk: Int): Set<String> {
        val result = try { ApkVerifier.Builder(file).setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify() }
            catch (_: Exception) { throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.SIGNATURE) }
        if (!result.isVerified) throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.SIGNATURE)
        return result.signerCertificates.map { digest(it.encoded) }.toSet()
    }
    /** Read-only recovery, including updates that replaced the running Developer process. */
    fun packagePresent(context: Context, record: DevelopmentArtifact.Record): Boolean = runCatching {
        val installed = context.packageManager.getPackageInfo(record.packageName, flags())
        version(installed) == record.versionCode && installed.versionName == record.versionName &&
            certificates(installed) == setOf(record.signerSha256) &&
            DevelopmentArtifactFiles.sha256(File(installed.applicationInfo.sourceDir)) == record.apkSha256
    }.getOrDefault(false)
}
