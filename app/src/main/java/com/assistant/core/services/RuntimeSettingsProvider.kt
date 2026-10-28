package com.assistant.core.services

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Main-process preferences remain the source of truth; :wake never trusts an old process cache. */
class RuntimeSettingsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Private settings bridge" }
        require(arg in setOf("voice_settings", "jarvis_cloud_ai", "jarvis_api_keys"))
        val preferences = requireNotNull(context).getSharedPreferences(requireNotNull(arg), Context.MODE_PRIVATE)
        if (method == "boolean") {
            val input = requireNotNull(extras)
            require(input.keySet() == setOf("key", "value"))
            val key = input.getString("key")
            require(arg == "voice_settings" && key == "foreground_service_running" ||
                arg == "jarvis_cloud_ai" && key == "weather_device_location")
            return Bundle().apply { putBoolean("saved", preferences.edit().putBoolean(key, input.getBoolean("value")).commit()) }
        }
        require(method == "snapshot" && extras == null)
        return RuntimeSettings.bundle(preferences.all)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

object RuntimeSettings {
    internal fun bundle(values: Map<String, *>): Bundle {
        require(values.size <= 64)
        return Bundle().apply {
            for ((key, value) in values) {
                require(key.length <= 128)
                when (value) {
                    is String -> { require(value.length <= 16_384); putString(key, value) }
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                }
            }
        }
    }
    class Snapshot internal constructor(private val values: Bundle) {
        fun getString(key: String, default: String?): String? = values.getString(key, default)
        fun getBoolean(key: String, default: Boolean) = values.getBoolean(key, default)
        fun getFloat(key: String, default: Float) = values.getFloat(key, default)
        fun getLong(key: String, default: Long) = values.getLong(key, default)
    }
    private fun uri(context: Context) = Uri.parse("content://${context.packageName}.runtime-settings")
    private fun remote(context: Context): Boolean = if (android.os.Build.VERSION.SDK_INT >= 28)
        android.app.Application.getProcessName().endsWith(":wake") else
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses.orEmpty()
            .any { it.pid == Process.myPid() && it.processName.endsWith(":wake") }
    fun read(context: Context, name: String): Snapshot = Snapshot(runCatching {
        if (remote(context)) context.contentResolver.call(uri(context), "snapshot", name, null)
        else bundle(context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
    }.getOrNull() ?: Bundle())
    fun boolean(context: Context, name: String, key: String, value: Boolean): Boolean = runCatching {
        if (!remote(context)) context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putBoolean(key, value).commit()
        else context.contentResolver.call(uri(context), "boolean", name, Bundle().apply { putString("key", key); putBoolean("value", value) })
                ?.getBoolean("saved") == true
    }.getOrDefault(false)
}
