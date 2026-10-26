package com.jarvis.developer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * User-consented APK installation from a content:// URI.
 * The caller must grant read access to the URI. No silent installation is attempted.
 */
object JarvisPackageInstaller {
    fun canRequestInstall(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${activity.packageName}")
        })
    }

    fun requestInstall(activity: Activity, apkUri: Uri): Boolean {
        require(apkUri.scheme == "content") { "APK must be supplied through a content URI" }
        if (!canRequestInstall(activity)) {
            openInstallPermissionSettings(activity)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            return false
        }
        return true
    }
}
