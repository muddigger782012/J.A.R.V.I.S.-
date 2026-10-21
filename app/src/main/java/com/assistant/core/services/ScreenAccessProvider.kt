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
    private val controls by lazy { ScreenSession(AndroidScreenPlatform(requireNotNull(context))) }
    private val epoch = ScreenRequestEpoch()
    @Volatile private var appliedCancellation = 0L // Only accessed on the main looper.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private fun applyCancellation() {
        val current = epoch.state()
        if (appliedCancellation != current.value) {
            if (current.preserveTask) controls.pause() else controls.discard()
            appliedCancellation = current.value
            publishTaskNotice()
        }
    }
    private fun publishTaskNotice() {
        main.post { runCatching { ScreenTaskNotifications.update(requireNotNull(context), controls.active, controls.awaitingInput) } }
    }
    private fun clearCancelledState() {
        if (Looper.myLooper() == Looper.getMainLooper()) applyCancellation()
        else main.post { applyCancellation() }
    }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Private screen bridge" }
        val context = requireNotNull(context)
        check(context.packageName == "com.assistant.core") { "Personal screen bridge only" }
        require(method in setOf("connected", "discard", "command", "task_state", "review_state"))
        require(extras == null || method == "command" &&
            (extras.keySet() == setOf("review_token") && extras.getString("review_token")?.length?.let { it <= 100 } == true ||
             extras.keySet() == setOf("task_key") && extras.getString("task_key")?.length?.let { it <= 100 } == true))
        if (method == "connected") return Bundle().apply { putBoolean("connected", JarvisAccessibilityService.active() != null) }
        if (method == "review_state") return Bundle().apply { putString("token", controls.pendingToken.orEmpty()) }
        if (method == "task_state") return Bundle().apply {
            val invalidation = epoch.state()
            val cancelled = appliedCancellation != invalidation.value && !invalidation.preserveTask
            putBoolean("active", !cancelled && controls.active)
            putBoolean("contextual", !cancelled && appliedCancellation == invalidation.value && controls.contextual)
            putBoolean("awaiting_input", !cancelled && appliedCancellation == invalidation.value && controls.awaitingInput)
            putString("task_key", if (cancelled) "" else controls.taskKey.orEmpty())
        }
        val command = if (method == "command") {
            require(arg != null && arg.length <= 400)
            ScreenControls.parse(arg)
        } else null
        val taskCommand = if (method == "command") ScreenTaskCommands.parse(requireNotNull(arg), controls.contextual) else null
        val confirmation = command?.kind == ScreenControls.Kind.CONFIRM || taskCommand?.kind == ScreenTaskCommands.Kind.CONFIRM_STEP
        require(extras == null || confirmation && extras.containsKey("review_token") ||
            taskCommand?.kind == ScreenTaskCommands.Kind.CONTINUE && extras.containsKey("task_key"))
        val cancelTask = taskCommand?.kind == ScreenTaskCommands.Kind.CANCEL
        if (method == "discard" || command?.kind == ScreenControls.Kind.CANCEL || cancelTask) {
            epoch.cancel(preserveTask = method == "discard") // Never queue cancellation behind a confirmed tap.
            clearCancelledState()
            return if (method == "discard") Bundle.EMPTY else Bundle().apply {
                putString("reply", if (cancelTask) "Cancelled the screen task. No later steps will run; already submitted Android actions cannot be undone." else "Cancelled the pending screen action and numbered controls. An Android action already submitted cannot be undone.")
            }
        }
        val token = epoch.token()
        val deadline = SystemClock.uptimeMillis() + 3000
        fun execute(): Bundle {
            applyCancellation()
            if (!epoch.valid(token)) return cancelledReply()
            val result = AndroidScreenPlatform.withDeadline(deadline, { !epoch.valid(token) }) {
                val beforeKey = controls.taskKey
                val wasConfirming = controls.confirming
                val step = controls.currentStep
                val reply = if (confirmation) controls.replyApproved(requireNotNull(arg), extras?.getString("review_token").orEmpty())
                    else if (extras?.containsKey("task_key") == true) controls.replyInTask(requireNotNull(arg), extras.getString("task_key").orEmpty())
                    else controls.reply(requireNotNull(arg))
                Bundle().apply {
                    putString("reply", reply.take(12_000))
                    putString("task_key", controls.taskKey.orEmpty())
                    putBoolean("observation_pending", controls.observing &&
                        (extras?.containsKey("task_key") != true || extras.getString("task_key") == controls.taskKey))
                    if (confirmation && wasConfirming && controls.observing && controls.taskKey == beforeKey) putInt("accepted_step", step)
                }
            }
            if (epoch.valid(token)) { publishTaskNotice(); return result }
            applyCancellation()
            return cancelledReply()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return execute()
        val task = FutureTask { execute() }
        main.post(task)
        return try { task.get(4, TimeUnit.SECONDS) } catch (_: Exception) {
            task.cancel(false) // A queued command cannot execute later after a timeout.
            epoch.timeout(token)
            clearCancelledState()
            Bundle().apply { putString("reply", "Screen request outcome is uncertain. Check the screen before trying again; no automatic retry was made.") }
        }
    }
    private fun cancelledReply() = Bundle().apply {
        putString("reply", "Screen request cancelled. Check the screen if an Android action was already submitted; no automatic retry was made.")
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
