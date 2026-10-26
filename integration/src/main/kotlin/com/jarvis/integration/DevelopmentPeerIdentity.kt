package com.jarvis.integration

import android.content.Context
import android.content.pm.PackageManager

/** Identity comes from the Binder kernel UID and Android signing records, never a payload claim. */
object DevelopmentPeerIdentity {
    fun select(actualPackages: Set<String>, allowed: Set<String>, sameSigner: Boolean): String? =
        actualPackages.singleOrNull()?.takeIf { sameSigner && it in allowed }

    fun packageForUid(context: Context, uid: Int, allowed: Set<String>): String? = runCatching {
        val manager = context.packageManager
        val packages = manager.getPackagesForUid(uid)?.toSet().orEmpty()
        select(packages, allowed, manager.checkSignatures(context.applicationInfo.uid, uid) == PackageManager.SIGNATURE_MATCH)
            ?.takeIf { manager.getApplicationInfo(it, 0).uid == uid }
    }.getOrNull()
}
