package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.os.Build

/** Uses the existing launcher visibility query; no broad package permission or inventory upload. */
class AndroidInstalledApps(private val context: Context) : InstalledApps.Platform {
    override fun apps(): List<InstalledApps.App> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).also { check(it.size <= 2048) }.mapNotNull {
            val info = it.activityInfo ?: return@mapNotNull null
            if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return@mapNotNull null
            runCatching { InstalledApps.App(info.packageName, it.loadLabel(pm).toString().trim()).takeIf { app -> app.valid() } }.getOrNull()
        }.distinct()
    }
    override fun canLaunch(packageName: String) = context.packageManager.getLaunchIntentForPackage(packageName) != null
    override fun version(packageName: String): InstalledApps.Version {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return InstalledApps.Version(info.versionName, code)
    }
}
