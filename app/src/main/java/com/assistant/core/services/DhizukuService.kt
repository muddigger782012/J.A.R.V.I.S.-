package com.assistant.core.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.app.admin.DevicePolicyManager
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener

data class DhizukuStatus(
    val appInstalled: Boolean,
    val initialized: Boolean,
    val permissionGranted: Boolean,
    val versionCode: Int?,
    val versionName: String?,
    val ownerPackageName: String?,
    val ownerComponentName: String?,
    val delegatedScopes: List<String>,
    val errorMessage: String? = null
) {
    companion object {
        fun unavailable(reason: String): DhizukuStatus {
            return DhizukuStatus(
                appInstalled = false,
                initialized = false,
                permissionGranted = false,
                versionCode = null,
                versionName = null,
                ownerPackageName = null,
                ownerComponentName = null,
                delegatedScopes = emptyList(),
                errorMessage = reason
            )
        }
    }
}

class DhizukuService {

    fun getStatus(context: Context): DhizukuStatus {
        val installed = isDhizukuInstalled(context)
        if (!installed) {
            return DhizukuStatus.unavailable("Dhizuku app is not installed.")
        }

        val initialized = try {
            Dhizuku.init(context)
        } catch (error: Throwable) {
            return DhizukuStatus(
                appInstalled = true,
                initialized = false,
                permissionGranted = false,
                versionCode = null,
                versionName = null,
                ownerPackageName = null,
                ownerComponentName = null,
                delegatedScopes = emptyList(),
                errorMessage = error.message ?: "Dhizuku initialization failed."
            )
        }

        if (!initialized) {
            return DhizukuStatus(
                appInstalled = true,
                initialized = false,
                permissionGranted = false,
                versionCode = null,
                versionName = null,
                ownerPackageName = null,
                ownerComponentName = null,
                delegatedScopes = emptyList(),
                errorMessage = "Dhizuku is installed but not running or not ready."
            )
        }

        val permissionGranted = try {
            Dhizuku.isPermissionGranted()
        } catch (_: Throwable) {
            false
        }

        val ownerComponent = runCatching {
            Dhizuku.getOwnerComponent().flattenToShortString()
        }.getOrNull()

        val delegatedScopes = try {
            Dhizuku.getDelegatedScopes()?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        return DhizukuStatus(
            appInstalled = true,
            initialized = true,
            permissionGranted = permissionGranted,
            versionCode = runCatching { Dhizuku.getVersionCode() }.getOrNull(),
            versionName = runCatching { Dhizuku.getVersionName() }.getOrNull(),
            ownerPackageName = runCatching { Dhizuku.getOwnerPackageName() }.getOrNull(),
            ownerComponentName = ownerComponent,
            delegatedScopes = delegatedScopes,
            errorMessage = null
        )
    }

    fun requestPermission(context: Context, callback: (granted: Boolean, message: String) -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        val status = getStatus(context)
        if (!status.initialized) {
            callback(false, status.errorMessage ?: "Dhizuku is not ready.")
            return
        }
        if (status.permissionGranted) {
            callback(true, "Dhizuku permission already granted.")
            return
        }

        try {
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    mainHandler.post {
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            callback(true, "Dhizuku permission granted.")
                        } else {
                            callback(false, "Dhizuku permission denied.")
                        }
                    }
                }
            })
        } catch (error: Throwable) {
            callback(false, "Dhizuku permission request failed: ${error.message ?: "unknown error"}")
        }
    }

    fun applyCoreDelegatedScopes(context: Context, callback: (success: Boolean, message: String) -> Unit) {
        val status = getStatus(context)
        if (!status.initialized || !status.permissionGranted) {
            callback(false, "Dhizuku permission is required before delegated scopes can be changed.")
            return
        }
        val scopes = arrayOf(
            DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL,
            DevicePolicyManager.DELEGATION_PACKAGE_ACCESS,
            DevicePolicyManager.DELEGATION_PERMISSION_GRANT,
            DevicePolicyManager.DELEGATION_APP_RESTRICTIONS
        )
        try {
            Dhizuku.setDelegatedScopes(scopes)
            callback(true, "Core delegated scopes applied successfully.")
        } catch (error: Throwable) {
            callback(false, "Failed to apply delegated scopes: ${error.message ?: "unknown error"}")
        }
    }

    private fun isDhizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.rosan.dhizuku", 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
