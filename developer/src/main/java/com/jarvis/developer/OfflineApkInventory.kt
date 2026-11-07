package com.jarvis.developer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Lightweight on-device APK manifest inventory, without a REA host or network.
 * Reads the archive's declared metadata only. It never installs, loads, or
 * executes its code, and never treats it as authenticated source.
 */
internal object OfflineApkInventory {
    private const val MAX_PERMISSIONS = 100
    private const val MAX_COMPONENTS = 12
    private const val FLAGS =
        PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

    data class Inventory(
        val apkSha256: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int?,
        val targetSdk: Int?,
        val permissions: List<String>,
        val permissionCount: Int,
        val activities: List<String>,
        val activityCount: Int,
        val services: List<String>,
        val serviceCount: Int,
        val receivers: List<String>,
        val receiverCount: Int,
        val providers: List<String>,
        val providerCount: Int
    ) {
        fun display(): String = buildString {
            append("Offline APK inventory — manifest declarations only\n")
            append("No upload, APK installation, code execution or signature verification occurred.\n")
            append("APK SHA-256: $apkSha256\n")
            append("Package: $packageName\nVersion: $versionName ($versionCode)\n")
            append("Android minimum SDK: ${minSdk ?: "unknown"}; target SDK: ${targetSdk ?: "unknown"}\n")
            append("\nRequested permissions ($permissionCount):\n")
            append(if (permissions.isEmpty()) "None declared or inventory empty\n"
                else permissions.joinToString("\n", postfix = "\n") { "• $it" })
            if (permissionCount > permissions.size) append("… additional permission names omitted\n")
            fun components(title: String, entries: List<String>, total: Int) {
                append("\n$title ($total):\n")
                if (entries.isEmpty()) append("None declared\n")
                else append(entries.joinToString("\n", postfix = "\n") { "• $it" })
                if (total > entries.size) append("… additional component names omitted\n")
            }
            components("Activities", activities, activityCount)
            components("Services", services, serviceCount)
            components("Receivers", receivers, receiverCount)
            components("Providers", providers, providerCount)
            append("\nThis is not decompilation. Use Analyze APK for deeper REA/JADX evidence.")
        }
    }

    private fun valid(value: String): Boolean =
        value.length in 1..240 && value.none { it.isISOControl() }

    private fun safeNames(values: Array<out String>? , max: Int): Pair<List<String>, Int> {
        val names = values?.toList().orEmpty().filter(::valid).distinct().sorted()
        return names.take(max) to names.size
    }

    fun fromPackageInfo(apkSha256: String, info: PackageInfo): Inventory {
        require(Regex("[a-f0-9]{64}").matches(apkSha256))
        val pkg = info.packageName
        require(valid(pkg)) { "APK package name is unavailable." }
        val permissionResult = safeNames(info.requestedPermissions, MAX_PERMISSIONS)
        val activities = safeNames(info.activities?.mapNotNull { it.name }?.toTypedArray(), MAX_COMPONENTS)
        val services = safeNames(info.services?.mapNotNull { it.name }?.toTypedArray(), MAX_COMPONENTS)
        val receivers = safeNames(info.receivers?.mapNotNull { it.name }?.toTypedArray(), MAX_COMPONENTS)
        val providers = safeNames(info.providers?.mapNotNull { it.name }?.toTypedArray(), MAX_COMPONENTS)
        val app = info.applicationInfo
        return Inventory(
            apkSha256, pkg, info.versionName?.takeIf(::valid) ?: "not declared",
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            app?.minSdkVersion?.takeIf { it > 0 }, app?.targetSdkVersion?.takeIf { it > 0 },
            permissionResult.first, permissionResult.second,
            activities.first, activities.second,
            services.first, services.second,
            receivers.first, receivers.second,
            providers.first, providers.second
        )
    }

    fun inspect(context: Context, selected: ReaApkAnalysis.Selected): Inventory {
        require(selected.size in 4..ReaApkAnalysis.MAX_APK &&
            ReaApkAnalysis.sha256(selected.bytes) == selected.sha256) {
            "Selected APK contents changed after import."
        }
        val archive = File.createTempFile("jarvis-inspect-", ".apk", context.cacheDir)
        return try {
            archive.outputStream().use { it.write(selected.bytes) }
            @Suppress("DEPRECATION")
            val parsed = context.packageManager.getPackageArchiveInfo(
                archive.absolutePath, FLAGS
            ) ?: error("Android could not parse a package manifest from this archive.")
            fromPackageInfo(selected.sha256, parsed)
        } finally {
            // The Android archive parser needs a file path; do not retain
            // the APK or its extracted contents after this local inspection.
            archive.delete()
        }
    }
}
