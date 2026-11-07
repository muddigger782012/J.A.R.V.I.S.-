package com.jarvis.developer

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineApkInventoryTest {
    private val digest = "a".repeat(64)

    private fun packageInfo(): PackageInfo = PackageInfo().apply {
        packageName = "com.example.ownedtool"
        versionName = "4.2.1"
        longVersionCode = 42
        applicationInfo = ApplicationInfo().apply {
            minSdkVersion = 26
            targetSdkVersion = 34
        }
        requestedPermissions = arrayOf("android.permission.INTERNET",
            "android.permission.CAMERA", "android.permission.CAMERA")
        activities = arrayOf(ActivityInfo().apply { name = "com.example.ownedtool.MainActivity" })
        services = arrayOf(ServiceInfo().apply { name = "com.example.ownedtool.SyncService" })
        receivers = arrayOf(ActivityInfo().apply { name = "com.example.ownedtool.BootReceiver" })
        providers = arrayOf(ProviderInfo().apply { name = "com.example.ownedtool.AppProvider" })
    }

    @Test fun manifestInventoryIsOfflineDeterministicAndExplicitAboutLimitations() {
        val inventory = OfflineApkInventory.fromPackageInfo(digest, packageInfo())
        assertEquals("com.example.ownedtool", inventory.packageName)
        assertEquals("4.2.1", inventory.versionName)
        assertEquals(42L, inventory.versionCode)
        assertEquals(26, inventory.minSdk)
        assertEquals(34, inventory.targetSdk)
        assertEquals(2, inventory.permissionCount)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.INTERNET"),
            inventory.permissions)
        assertEquals(1, inventory.activityCount)
        assertEquals(1, inventory.serviceCount)
        assertEquals(1, inventory.receiverCount)
        assertEquals(1, inventory.providerCount)
        val output = inventory.display()
        assertTrue(output.contains("Offline APK inventory"))
        assertTrue(output.contains("APK SHA-256: $digest"))
        assertTrue(output.contains("MainActivity"))
        assertTrue(output.contains("android.permission.CAMERA"))
        assertTrue(output.contains("No upload"))
        assertTrue(output.contains("not decompilation", ignoreCase = true))
        assertFalse(output.contains("install successful"))
    }

    @Test fun aLargeManifestIsBoundedAndGenuinelyReportsOmittedEntries() {
        val info = packageInfo()
        info.requestedPermissions = Array(150) { "com.example.permission.READ_${it.toString().padStart(3, '0')}" }
        info.activities = Array(30) { ActivityInfo().apply { name = "com.example.Activity$it" } }
        val inventory = OfflineApkInventory.fromPackageInfo(digest, info)
        assertEquals(150, inventory.permissionCount)
        assertEquals(100, inventory.permissions.size)
        assertEquals(30, inventory.activityCount)
        assertEquals(12, inventory.activities.size)
        assertTrue(inventory.display().contains("additional permission names omitted"))
        assertTrue(inventory.display().contains("additional component names omitted"))
    }

    @Test fun emptyFieldsNeverInventPermissionsOrClassNames() {
        val info = PackageInfo().apply { packageName = "com.example.empty" }
        val inventory = OfflineApkInventory.fromPackageInfo(digest, info)
        assertEquals(0, inventory.permissionCount)
        assertEquals(0, inventory.activityCount)
        assertTrue(inventory.display().contains("None declared"))
        assertEquals("not declared", inventory.versionName)
        assertNull(inventory.minSdk)
    }

    @Test fun malformedPackageIdentifiersAndHashAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OfflineApkInventory.fromPackageInfo("b".repeat(20), packageInfo())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineApkInventory.fromPackageInfo(digest, packageInfo().apply {
                packageName = "unsafe\npackage"
            })
        }
    }

    @Test fun verifiedSignerIsAnExactFingerprintNotAClaimOfApplicationSafety() {
        val signed = OfflineApkInventory.SignatureCheck(
            OfflineApkInventory.SignatureStatus.VERIFIED,
            listOf("c".repeat(64)), 34
        )
        val inventory = OfflineApkInventory.fromPackageInfo(digest, packageInfo(), signed)
        assertEquals(OfflineApkInventory.SignatureStatus.VERIFIED, inventory.signature.status)
        assertTrue(inventory.display().contains("VERIFIED for Android SDK 34"))
        assertTrue(inventory.display().contains("c".repeat(64)))
        assertTrue(inventory.display().contains("does NOT mean this APK is safe"))
        assertTrue(inventory.display().contains("not independently trusted"))
        assertFalse(inventory.display().contains("trusted developer identity"))
    }

    @Test fun failedAndUncheckedSignaturesCannotMasqueradeAsVerified() {
        val failed = OfflineApkInventory.SignatureCheck(
            OfflineApkInventory.SignatureStatus.FAILED, checkedPlatformSdk = 34
        )
        val failedReport = OfflineApkInventory.fromPackageInfo(digest, packageInfo(), failed)
        assertTrue(failedReport.display().contains("FAILED or unsupported"))
        assertFalse(failedReport.display().contains("VERIFIED for Android"))
        val unchecked = OfflineApkInventory.fromPackageInfo(digest, packageInfo())
        assertTrue(unchecked.display().contains("NOT CHECKED"))
        assertThrows(IllegalArgumentException::class.java) {
            OfflineApkInventory.SignatureCheck(OfflineApkInventory.SignatureStatus.VERIFIED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OfflineApkInventory.SignatureCheck(OfflineApkInventory.SignatureStatus.VERIFIED,
                listOf("not-a-sha"), 34)
        }
    }

    @Test fun declaredPermissionsAreDataNotDevicePermissionGrants() {
        val inventory = OfflineApkInventory.fromPackageInfo(digest, packageInfo())
        assertTrue(inventory.permissions.contains("android.permission.CAMERA"))
        // This reads a manifest; it does not call requestPermissions(),
        // PackageInstaller or launch an imported Activity.
        assertFalse(inventory.display().contains("granted"))
    }
}
