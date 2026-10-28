package com.jarvis.developer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Explicit non-exported callback. Extras alone never establish a successful installation. */
class DevelopmentInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "developer-install-result", isDaemon = true) {
            try { JarvisPackageInstaller.receive(context.applicationContext, intent) }
            catch (_: Exception) { android.util.Log.w("DeveloperInstallation", "result.unverified") }
            finally { pending.finish() }
        }
    }
}
