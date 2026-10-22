package com.assistant.core.services

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Normal Android API observations and user-operated system settings. No privileged toggles or IPC guesses. */
class AndroidConnectionAssistance(private val context: Context, private val discoverManager: () -> String) : ConnectionAssistance.Platform {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    override fun wifi(): ConnectionAssistance.State {
        val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return ConnectionAssistance.State.UNSUPPORTED
        return when (manager.wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> ConnectionAssistance.State.ON
            WifiManager.WIFI_STATE_DISABLED -> ConnectionAssistance.State.OFF
            WifiManager.WIFI_STATE_ENABLING, WifiManager.WIFI_STATE_DISABLING -> ConnectionAssistance.State.TRANSITIONING
            else -> ConnectionAssistance.State.UNAVAILABLE
        }
    }
    override fun bluetooth(): ConnectionAssistance.State {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return ConnectionAssistance.State.UNSUPPORTED
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) return ConnectionAssistance.State.PERMISSION
        return when (adapter.state) {
            BluetoothAdapter.STATE_ON -> ConnectionAssistance.State.ON
            BluetoothAdapter.STATE_OFF -> ConnectionAssistance.State.OFF
            else -> ConnectionAssistance.State.TRANSITIONING
        }
    }
    override fun cellular(): ConnectionAssistance.State {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return ConnectionAssistance.State.UNSUPPORTED
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return ConnectionAssistance.State.PERMISSION
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return ConnectionAssistance.State.UNAVAILABLE
        return when (manager.dataState) {
            TelephonyManager.DATA_CONNECTED -> ConnectionAssistance.State.ON
            TelephonyManager.DATA_DISCONNECTED -> ConnectionAssistance.State.OFF
            TelephonyManager.DATA_CONNECTING, TelephonyManager.DATA_SUSPENDED -> ConnectionAssistance.State.TRANSITIONING
            else -> ConnectionAssistance.State.UNAVAILABLE
        }
    }
    override fun vpn(): ConnectionAssistance.State {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return ConnectionAssistance.State.UNAVAILABLE
        var unknown = false
        val found = manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps == null) unknown = true
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        return if (found) ConnectionAssistance.State.ON else if (unknown) ConnectionAssistance.State.UNAVAILABLE else ConnectionAssistance.State.OFF
    }
    override fun network() = DeviceReport.render(DeviceReport.Command.NETWORK, AndroidDeviceReport(context))
    override fun settings(kind: ConnectionAssistance.Kind): Boolean {
        val action = settingsAction(kind) ?: return false
        return runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
    override fun manager() = "The Connection Manager remains a separate app. Choose its reviewed launcher identity below; opening it does not verify a connection or remote command interface.\n" + discoverManager()
    companion object {
        fun settingsAction(kind: ConnectionAssistance.Kind): String? = when (kind) {
            ConnectionAssistance.Kind.WIFI_SETTINGS -> Settings.ACTION_WIFI_SETTINGS
            ConnectionAssistance.Kind.BLUETOOTH_SETTINGS -> Settings.ACTION_BLUETOOTH_SETTINGS
            ConnectionAssistance.Kind.CELLULAR_SETTINGS -> Settings.ACTION_WIRELESS_SETTINGS
            ConnectionAssistance.Kind.VPN_SETTINGS -> Settings.ACTION_VPN_SETTINGS
            else -> null
        }
    }
}
