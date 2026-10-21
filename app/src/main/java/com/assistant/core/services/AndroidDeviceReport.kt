package com.assistant.core.services

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.assistant.core.engine.PrivilegedAccess

/** Standard APIs only. No permission request, shell command, screen read or endpoint probe. */
class AndroidDeviceReport(private val context: Context) : DeviceReport.Platform {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    override fun battery(): DeviceReport.Reading<DeviceReport.Battery> = DeviceReport.read {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return@read DeviceReport.Reading(problem = DeviceReport.Problem.UNAVAILABLE)
        if (intent.hasExtra(BatteryManager.EXTRA_PRESENT) && !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
            return@read DeviceReport.Reading(problem = DeviceReport.Problem.UNAVAILABLE)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (scale <= 0 || level !in 0..scale) return@read DeviceReport.Reading(problem = DeviceReport.Problem.INVALID)
        val charging = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> DeviceReport.Charging.CHARGING
            BatteryManager.BATTERY_STATUS_FULL -> DeviceReport.Charging.FULL
            BatteryManager.BATTERY_STATUS_DISCHARGING -> DeviceReport.Charging.DISCHARGING
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> DeviceReport.Charging.NOT_CHARGING
            else -> DeviceReport.Charging.UNKNOWN
        }
        DeviceReport.Reading(DeviceReport.Battery(level, scale, charging,
            if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it in -500..1000 } else null))
    }
    override fun storage(): DeviceReport.Reading<DeviceReport.Storage> = DeviceReport.read {
        val stat = StatFs(context.filesDir.absolutePath)
        DeviceReport.Reading(DeviceReport.Storage(stat.availableBytes, stat.totalBytes))
    }
    override fun memory(): DeviceReport.Reading<DeviceReport.Memory> = DeviceReport.read {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return@read DeviceReport.Reading(problem = DeviceReport.Problem.UNAVAILABLE)
        val info = ActivityManager.MemoryInfo(); manager.getMemoryInfo(info)
        DeviceReport.Reading(DeviceReport.Memory(info.availMem, info.totalMem, info.lowMemory))
    }
    override fun network(): DeviceReport.Reading<DeviceReport.Network> = DeviceReport.read {
        if (!granted(Manifest.permission.ACCESS_NETWORK_STATE)) return@read DeviceReport.Reading(problem = DeviceReport.Problem.PERMISSION)
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@read DeviceReport.Reading(problem = DeviceReport.Problem.UNAVAILABLE)
        val network = manager.activeNetwork ?: return@read DeviceReport.Reading(DeviceReport.Network(active = false))
        val caps = manager.getNetworkCapabilities(network)
            ?: return@read DeviceReport.Reading(problem = DeviceReport.Problem.UNAVAILABLE)
        val transports = buildSet {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(DeviceReport.Transport.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(DeviceReport.Transport.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(DeviceReport.Transport.ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(DeviceReport.Transport.VPN)
            if (isEmpty()) add(DeviceReport.Transport.OTHER)
        }
        DeviceReport.Reading(DeviceReport.Network(true, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), runCatching { manager.isActiveNetworkMetered }.getOrNull(), transports))
    }
    override fun thermal(): DeviceReport.Reading<DeviceReport.Thermal> = if (Build.VERSION.SDK_INT < 29)
        DeviceReport.Reading(problem = DeviceReport.Problem.UNSUPPORTED) else DeviceReport.thermal(Build.VERSION.SDK_INT) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: error("Power service unavailable")
        power.currentThermalStatus
    }
    override fun app(): DeviceReport.Reading<DeviceReport.App> = DeviceReport.read {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        DeviceReport.Reading(DeviceReport.App(info.versionName, code))
    }
    override fun contacts(): DeviceReport.Reading<Boolean> = DeviceReport.read { DeviceReport.Reading(granted(Manifest.permission.READ_CONTACTS)) }
    override fun calls(): DeviceReport.Reading<Boolean> = DeviceReport.read { DeviceReport.Reading(granted(Manifest.permission.CALL_PHONE)) }
    override fun notifications(): DeviceReport.Reading<Boolean> = DeviceReport.read {
        DeviceReport.Reading((Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)) && NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    override fun accessibility(): DeviceReport.Reading<DeviceReport.Accessibility> = DeviceReport.read {
        if (JarvisAccessibilityService.active() != null) return@read DeviceReport.Reading(DeviceReport.Accessibility.CONNECTED)
        if (context.packageName == "com.assistant.core" && ScreenAccessClient(context).connected())
            return@read DeviceReport.Reading(DeviceReport.Accessibility.REMOTE_CONNECTED)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return@read DeviceReport.Reading(problem = DeviceReport.Problem.UNAVAILABLE)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo?.serviceInfo
            service?.packageName == context.packageName && service?.name == JarvisAccessibilityService::class.java.name
        }
        DeviceReport.Reading(if (enabled) DeviceReport.Accessibility.CONFIGURED else DeviceReport.Accessibility.OFF)
    }
    override fun bridges() = PrivilegedAccess.read(context)
}
