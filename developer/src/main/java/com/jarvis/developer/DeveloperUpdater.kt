package com.jarvis.developer

import android.app.Activity
import android.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.util.WeakHashMap
import kotlin.concurrent.thread

/** Explicit public-channel check, cancellable download, verified review, then Android confirmation. */
object DeveloperUpdater {
    private val active = WeakHashMap<Activity, Controller>()
    internal class Services(
        val source: (Activity, PatchPreviewCancellation) -> DeveloperUpdateSource = { activity, cancel ->
            DeveloperUpdateDownload(activity.cacheDir,
                { file, release -> DevelopmentArtifactInspection.inspectUpdate(activity.applicationContext, file, release) }, cancel)
        },
        val installed: (Activity) -> Long = { activity ->
            DevelopmentArtifactInspection.version(activity.packageManager.getPackageInfo(activity.packageName, 0))
        },
        val permission: (Activity) -> Boolean = JarvisPackageInstaller::canRequestInstall,
        val foreground: (Activity, AlertDialog?) -> Boolean = { activity, dialog ->
            dialog?.isShowing == true && (activity.hasWindowFocus() || dialog.window?.decorView?.hasWindowFocus() == true)
        },
        val install: (Activity, File) -> Boolean = { activity, file ->
            JarvisPackageInstaller.requestInstall(activity, FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file))
        },
        val worker: (String, () -> Unit) -> Unit = { name, work -> thread(name = name, isDaemon = true) { work() }; Unit }
    )
    fun check(activity: Activity, status: (String) -> Unit) = check(activity, status, Services())
    internal fun check(activity: Activity, status: (String) -> Unit, services: Services) {
        activity.runOnUiThread {
            cancel(activity)
            if (!activity.isFinishing && !activity.isDestroyed) try {
                Controller(activity, status, services).also { active[activity] = it; it.start() }
            } catch (e: Exception) { cancel(activity); status(DeveloperUpdateRelease.explain(e)) }
        }
    }
    /** Called by Activity destruction and by the explicit Cancel/Back controls. No approval is persisted. */
    fun cancel(activity: Activity, report: Boolean = false) {
        active.remove(activity)?.let { controller ->
            if (report && !activity.isDestroyed) controller.status("Update review closed. No installation was requested.")
            controller.cancel()
        }
    }
    /** Pausing never transfers installation consent across a foreground transition. */
    fun onBackground(activity: Activity) { active[activity]?.invalidateInstallReview() }

    private class Controller(val activity: Activity, val status: (String) -> Unit, val services: Services) {
        private val cancellation = PatchPreviewCancellation()
        private val source = services.source(activity, cancellation)
        private var dialog: AlertDialog? = null
        private var staged: File? = null
        private var transferred = false
        private var busy = false
        private var reviewGeneration = 0L
        fun invalidateInstallReview() { reviewGeneration++ }
        private fun alive() = active[activity] === this && !cancellation.isCancelled && !activity.isFinishing && !activity.isDestroyed
        fun cancel() {
            cancellation.cancel(); dialog?.dismiss(); dialog = null
            if (!transferred) staged?.delete()
            staged = null
        }
        private fun finish() { if (active[activity] === this) active.remove(activity); cancel() }
        private fun fail(error: Throwable) { if (alive()) { status(DeveloperUpdateRelease.explain(error)); finish() } }
        fun start() {
            status("Checking the published Developer update...")
            services.worker("DeveloperUpdateCheck") {
                try {
                    val found = source.latest(); DeveloperUpdateRelease.validate(found)
                    val installed = services.installed(activity)
                    activity.runOnUiThread {
                        if (!alive()) return@runOnUiThread
                        if (found.version <= installed) {
                            status("No newer Developer update: installed $installed, published ${found.version}."); finish()
                        } else showReview(found, installed)
                    }
                } catch (e: Exception) { activity.runOnUiThread { fail(e) } }
            }
        }
        private fun showReview(found: DeveloperUpdateRelease.Release, installed: Long) {
            // Retain the existing Developer confirmation dialog and its Activity theme.
            dialog = android.app.AlertDialog.Builder(activity)
                .setTitle("Jarvis Developer update")
                .setMessage("Published release: ${found.versionName}\nInstalled version: $installed\nDownload and verify the signed update before reviewing installation?")
                .setNegativeButton("Cancel") { _, _ -> cancelledByOwner() }
                .setPositiveButton("Download", null)
                .setOnCancelListener { cancelledByOwner() }
                .create().also { shown ->
                    shown.setOnShowListener { shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (alive() && !busy) if (staged == null) download(found) else requestInstall(found)
                    } }
                    shown.show()
                }
            status("Developer ${found.versionName} is available. Download and installation each require your review.")
        }
        private fun cancelledByOwner() {
            if (alive()) { status("Update cancelled. No installation was requested."); finish() }
        }
        private fun download(found: DeveloperUpdateRelease.Release) {
            busy = true; dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            dialog?.setMessage("Downloading and verifying ${found.versionName}...\nCancel stops this update request.")
            status("Downloading and verifying the Developer update...")
            services.worker("DeveloperUpdateDownload") {
                var file: File? = null
                try {
                    file = source.download(found)
                    val ready = file
                    activity.runOnUiThread {
                        if (!alive()) { ready.delete(); return@runOnUiThread }
                        staged = ready; busy = false
                        dialog?.setMessage("Signed Developer ${found.versionName} verified.\nAndroid will ask you to confirm installation. Installation has not occurred.")
                        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { text = "Install"; isEnabled = true }
                        status("Signed update verified. Review Install to request Android's confirmation.")
                    }
                } catch (e: Exception) { file?.delete(); activity.runOnUiThread { fail(e) } }
            }
        }
        private fun requestInstall(found: DeveloperUpdateRelease.Release) {
            val file = staged ?: return
            val requestedGeneration = reviewGeneration
            busy = true; dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            status("Rechecking the current release and downloaded signature before Android confirmation...")
            services.worker("DeveloperUpdateInstallReview") {
                try {
                    source.verify(file, found)
                    if (services.installed(activity) >= found.version)
                        throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.CURRENT)
                    activity.runOnUiThread {
                        if (!alive()) return@runOnUiThread
                        if (requestedGeneration != reviewGeneration || !services.foreground(activity, dialog)) {
                            busy = false; dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                            status("Return to this update review and tap Install again. No installation was requested.")
                            return@runOnUiThread
                        }
                        try {
                            if (services.installed(activity) >= found.version)
                                throw DeveloperUpdateRelease.Failure(DeveloperUpdateRelease.Problem.CURRENT)
                            val permitted = services.permission(activity)
                            val opened = services.install(activity, file)
                            if (opened) {
                                // Android reads the content URI asynchronously; retain this verified cache file.
                                transferred = true
                                status("Android installer opened. Confirm there; installation has not been verified.")
                                finish()
                            } else {
                                busy = false; dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                                status(if (!permitted) "Allow installs from Jarvis Developer in Android settings, then return and tap Install again."
                                    else "Android could not open its installer. Keep the installed app and try again explicitly.")
                            }
                        } catch (e: Exception) { fail(e) }
                    }
                } catch (e: Exception) { activity.runOnUiThread { fail(e) } }
            }
        }
    }
}
