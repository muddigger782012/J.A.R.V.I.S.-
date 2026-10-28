package com.assistant.core

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionAssistanceAcceptanceTest {
    @Test fun actualAndroidDiagnosticsPreservePermissionsAndManagerRemainsASeparateReviewedApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_STATE)
        val before = permissions.map { context.checkSelfPermission(it) }
        val platform = AndroidConnectionAssistance(context) { "No manager chosen in this fixture" }
        val result = ConnectionAssistance.reply(ConnectionAssistance.Kind.ALL, platform)
        assertTrue(result, result.contains("Wi-Fi:")); assertTrue(result, result.contains("VPN:"))
        assertTrue(result, result.contains("without an internet probe"))
        assertEquals(before, permissions.map { context.checkSelfPermission(it) })
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.contains(Manifest.permission.ACCESS_WIFI_STATE))
        val manager = ConnectionAssistance.reply(ConnectionAssistance.Kind.MANAGER, platform)
        assertTrue(manager, manager.contains("separate app")); assertTrue(manager, manager.contains("does not verify a connection"))
        for (kind in listOf(ConnectionAssistance.Kind.WIFI_SETTINGS, ConnectionAssistance.Kind.BLUETOOTH_SETTINGS,
            ConnectionAssistance.Kind.CELLULAR_SETTINGS, ConnectionAssistance.Kind.VPN_SETTINGS)) {
            val action = AndroidConnectionAssistance.settingsAction(kind)!!
            assertNotNull(android.content.Intent(action).resolveActivity(context.packageManager))
        }
    }
}
