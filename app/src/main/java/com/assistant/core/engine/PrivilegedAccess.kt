package com.assistant.core.engine

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import com.assistant.core.models.CapabilityState
import com.rosan.dhizuku.api.Dhizuku
import rikka.shizuku.Shizuku

/** Readiness is a fresh observation, never a permission grant or action authorization. */
object PrivilegedAccess {
    enum class State { NOT_CONNECTED, NEEDS_PERMISSION, AUTHORIZED, UNKNOWN }
    data class Snapshot(val shizuku: State, val dhizuku: State, val deviceOwner: Boolean?) {
        fun capabilities() = CapabilityState(standard = true, specialAccess = false,
            shizuku = shizuku == State.AUTHORIZED, dhizuku = dhizuku == State.AUTHORIZED,
            deviceOwner = deviceOwner == true, oemPrivileged = false, platformSigned = false)
    }

    fun probe(connected: () -> Boolean, granted: () -> Boolean): State = try {
        if (!connected()) State.NOT_CONNECTED else if (granted()) State.AUTHORIZED else State.NEEDS_PERMISSION
    } catch (_: Throwable) { State.UNKNOWN }

    fun read(context: Context) = Snapshot(
        probe({ Shizuku.pingBinder() }, { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }),
        probe({ Dhizuku.init(context) }, { Dhizuku.isPermissionGranted() }),
        runCatching { (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
            ?.isDeviceOwnerApp(context.packageName) }.getOrNull()
    )
}
