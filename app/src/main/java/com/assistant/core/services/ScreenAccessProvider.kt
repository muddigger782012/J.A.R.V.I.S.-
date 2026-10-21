package com.assistant.core.services

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Private same-UID IPC keeps :wake and the Accessibility service in one review/dispatch domain. */
class ScreenAccessProvider : ContentProvider() {
    private val controls by lazy { ScreenControls(AndroidScreenPlatform(requireNotNull(context))) }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Private screen bridge" }
        val context = requireNotNull(context)
        check(context.packageName == "com.assistant.core") { "Personal screen bridge only" }
        require(extras == null && method in setOf("connected", "discard", "command"))
        val deadline = SystemClock.uptimeMillis() + 3000
        fun execute(): Bundle {
            if (method == "connected") return Bundle().apply { putBoolean("connected", JarvisAccessibilityService.active() != null) }
            if (method == "discard") { controls.discard(); return Bundle.EMPTY }
            require(arg != null && arg.length <= 400)
            val command = ScreenControls.parse(arg) ?: ScreenControls.Command(ScreenControls.Kind.INVALID)
            return AndroidScreenPlatform.withDeadline(deadline) {
                Bundle().apply { putString("reply", controls.reply(command).take(12_000)) }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return execute()
        val task = FutureTask { execute() }
        Handler(Looper.getMainLooper()).post(task)
        return try { task.get(4, TimeUnit.SECONDS) } catch (_: Exception) {
            task.cancel(false) // A queued command cannot execute later after a timeout.
            Bundle().apply { putString("reply", "Screen request outcome is uncertain. Check the screen before trying again; no automatic retry was made.") }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
