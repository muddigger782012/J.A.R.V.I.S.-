package com.jarvis.developer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.ApplicationInfo
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * User-consented APK installation from a content:// URI.
 * The caller must grant read access to the URI. No silent installation is attempted.
 */
object JarvisPackageInstaller {
    private val confirmations = ConcurrentHashMap<String, Intent>()
    private val owners = ConcurrentHashMap<String, WeakReference<Activity>>()
    internal fun inbox(context: Context) = DevelopmentJobInbox(java.io.File(context.noBackupFilesDir.canonicalFile, "development-inbox"))
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

    /** An owner click or purpose-bound conversational consent creates one fresh session. No installer RPC exists. */
    internal fun installVerified(activity: Activity, job: String, revision: Long, record: DevelopmentArtifact.Record,
        report: (String) -> Unit) {
        if (!canRequestInstall(activity)) {
            openInstallPermissionSettings(activity)
            report("Allow installs in Android settings, then return and review this APK again. No installation was requested.")
            return
        }
        val context = activity.applicationContext
        thread(name = "developer-approved-install", isDaemon = true) {
            val queue = inbox(context)
            var request: DevelopmentArtifact.Installation? = null
            var sessionId = -1
            try {
                val file = DevelopmentArtifactFiles(context.cacheDir).checked(record)
                DevelopmentArtifactInspection.inspect(context, file, record)
                val installer = context.packageManager.packageInstaller
                require(installer.mySessions.none { it.appPackageName == record.packageName })
                val initial = queue.beginInstallation(job, revision, record.apkSha256,
                    com.jarvis.integration.DevelopmentBridgeContract.personalPackage(context.packageName))
                request = initial
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(record.packageName); setSize(record.bytes)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                sessionId = installer.createSession(params)
                val bound = queue.bindInstallation(initial, sessionId)
                request = bound
                installer.openSession(sessionId).use { session ->
                    val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
                    session.openWrite("base.apk", 0, record.bytes).use { output ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                count += n; require(count <= record.bytes)
                                hash.update(buffer, 0, n); output.write(buffer, 0, n)
                            }
                        }
                        require(count == record.bytes && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == record.apkSha256)
                        session.fsync(output)
                    }
                    require(queue.submitInstallation(bound))
                    val callback = Intent(context, DevelopmentInstallReceiver::class.java)
                        .setData(Uri.parse("jarvis-job-install://callback/${bound.nonce}"))
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val sender = PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender
                    owners[bound.nonce] = WeakReference(activity)
                    session.commit(sender)
                }
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed)
                    report("APK submitted to Android. Confirm in Android's installer; refresh this job for the actual recorded result.") }
            } catch (failure: Exception) {
                if (sessionId >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                request?.let { runCatching { queue.installationStatus(it, "OUTCOME_UNKNOWN") }; owners.remove(it.nonce) }
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed)
                    report(DevelopmentArtifact.explain(failure)) }
            }
        }
    }

    internal fun receive(context: Context, intent: Intent) {
        val nonce = intent.data?.takeIf { it.scheme == "jarvis-job-install" && it.host == "callback" }?.lastPathSegment ?: return
        val queue = inbox(context)
        val request = queue.pendingInstallations().singleOrNull { it.nonce == nonce } ?: return
        if (request.session != intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)) return
        when (val androidStatus = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                val handler = context.packageManager.resolveActivity(confirmation, 0) ?: return
                if (handler.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return
                if (confirmation.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != request.session) return
                if (!queue.installationStatus(request, "PENDING_USER_ACTION", androidStatus)) return
                confirmations[nonce] = Intent(confirmation).setComponent(ComponentName(handler.activityInfo.packageName, handler.activityInfo.name))
                owners[nonce]?.get()?.let { owner -> owner.runOnUiThread {
                    if (!owner.isFinishing && !owner.isDestroyed && owner.hasWindowFocus()) runCatching { continueConfirmation(owner, request) }
                } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                queue.installationStatus(request, if (DevelopmentArtifactInspection.packagePresent(context, request.record)) "PACKAGE_PRESENT" else "OUTCOME_UNKNOWN", androidStatus)
                confirmations.remove(nonce); owners.remove(nonce)
            }
            PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED, PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_STORAGE -> {
                queue.installationStatus(request, "FAILED", androidStatus); confirmations.remove(nonce); owners.remove(nonce)
            }
        }
    }
    internal fun continueConfirmation(activity: Activity, request: DevelopmentArtifact.Installation) {
        val current = inbox(activity).pendingInstallations().single { it.nonce == request.nonce && it.session == request.session }
        val intent = confirmations.remove(current.nonce)
            ?: throw DevelopmentArtifact.Failure(DevelopmentArtifact.Problem.INSTALLATION)
        activity.startActivity(intent)
    }
    internal fun cancel(context: Context, request: DevelopmentArtifact.Installation) {
        require(inbox(context).installationRecord(request.job) == request)
        inbox(context).cancelInstallationTracking(request)
        abandon(context, request)
    }
    internal fun abandon(context: Context, request: DevelopmentArtifact.Installation) {
        confirmations.remove(request.nonce); owners.remove(request.nonce)
        if (request.session >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(request.session) }
    }
    internal fun recover(context: Context) {
        val queue = inbox(context)
        for (request in queue.pendingInstallations()) {
            val session = if (request.session >= 0) context.packageManager.packageInstaller.getSessionInfo(request.session) else null
            // An existing matching APK may predate a requested reinstallation. A still-owned
            // session is not completed merely because those bytes are already installed.
            if (request.session >= 0 && session == null && DevelopmentArtifactInspection.packagePresent(context, request.record)) queue.installationStatus(request, "PACKAGE_PRESENT")
            else if (request.session < 0 || session == null ||
                !confirmations.containsKey(request.nonce)) queue.installationStatus(request, "OUTCOME_UNKNOWN")
        }
    }
}
