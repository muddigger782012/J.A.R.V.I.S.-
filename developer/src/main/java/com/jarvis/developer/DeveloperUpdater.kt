package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Explicit user-initiated updater. Only the separately signed Developer release channel is used. */
object DeveloperUpdater {
    private const val RELEASE = "https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-/releases/tags/developer-latest"
    fun check(activity: Activity, status: (String) -> Unit) {
        thread {
            try {
                val connection = URL(RELEASE).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "JarvisDeveloper")
                val response = try {
                    if (connection.responseCode != 200) throw IllegalStateException("Developer release not published yet (HTTP ${connection.responseCode})")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
                val assets = response.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "Jarvis-Developer.apk" }
                    ?: throw IllegalStateException("No Developer APK in the release")
                val url = apk.getString("browser_download_url")
                val expectedDigest = apk.optString("digest").removePrefix("sha256:")
                require(apk.optString("digest").startsWith("sha256:") && expectedDigest.matches(Regex("[0-9a-fA-F]{64}"))) {
                    "Published Developer APK is missing a valid SHA-256 digest"
                }
                val releaseVersion = response.optString("name")
                val releaseTag = response.optString("tag_name")
                if (releaseTag != "developer-latest") {
                    throw IllegalStateException("Unexpected Developer release tag")
                }
                activity.runOnUiThread {
                    android.app.AlertDialog.Builder(activity)
                        .setTitle("Jarvis Developer update")
                        .setMessage("Published release: $releaseVersion\nInstalled version: ${BuildConfig.VERSION_NAME}\nDownload and request installation?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download") { _, _ -> download(activity, url, expectedDigest, status) }
                        .show()
                }
            } catch (e: Exception) { activity.runOnUiThread { status(e.message ?: "Update check failed") } }
        }
    }
    private fun download(activity: Activity, url: String, expectedDigest: String, status: (String) -> Unit) {
        thread {
            try {
                require(url.startsWith("https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/download/developer-latest/")) {
                    "Unexpected update source"
                }
                activity.runOnUiThread { status("Downloading Developer update...") }
                val folder = File(activity.cacheDir, "updates").apply { mkdirs() }
                val file = File(folder, "Jarvis-Developer.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("Download HTTP ${conn.responseCode}")
                    conn.inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                } finally { conn.disconnect() }
                val actualDigest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                if (!actualDigest.equals(expectedDigest, ignoreCase = true)) {
                    file.delete()
                    throw IllegalStateException("Downloaded APK failed SHA-256 integrity verification")
                }
                val archive = activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    ?: throw IllegalStateException("Downloaded file is not an APK")
                if (archive.packageName != activity.packageName) throw IllegalStateException("APK package mismatch")
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                val incomingVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
                val currentVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
                if (incomingVersion <= currentVersion) {
                    activity.runOnUiThread { status("Already current (installed $currentVersion, available $incomingVersion)") }
                    return@thread
                }
                activity.runOnUiThread {
                    if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                        status("Allow installs from Jarvis Developer, then check for updates again.")
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } else {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                        status("Android installer opened. Confirm the update to proceed.")
                    }
                }
            } catch (e: Exception) { activity.runOnUiThread { status(e.message ?: "Update failed") } }
        }
    }
}
