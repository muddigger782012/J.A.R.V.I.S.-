package com.assistant.core.services

/**
 * Read-only checks before handing an already downloaded update to Android.
 * Android remains the final authority for APK signatures and installation.
 */
object PersonalUpdatePackagePolicy {
    fun rejection(
        installedPackage: String,
        installedVersion: Long,
        installedSigners: Set<String>,
        candidatePackage: String,
        candidateVersion: Long,
        candidateSigners: Set<String>
    ): String? {
        if (candidatePackage != installedPackage) {
            return "Downloaded APK belongs to $candidatePackage, not $installedPackage. Installation was stopped."
        }
        if (candidateVersion <= installedVersion) {
            return "No newer update: downloaded build $candidateVersion, installed build $installedVersion."
        }
        // If Android can read both sets, reject a provable certificate mismatch
        // before showing an installer that cannot replace the installed package.
        if (installedSigners.isNotEmpty() && candidateSigners.isNotEmpty() &&
            installedSigners.intersect(candidateSigners).isEmpty()) {
            return "APK signing certificate differs from your installed Jarvis. " +
                "An in-place update would fail; keep the installed app and its data."
        }
        return null
    }
}
