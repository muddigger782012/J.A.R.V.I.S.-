package com.assistant.core.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.assistant.core.R

/** No screen content appears on the lock screen or notification. Cancellation needs no screen read. */
object ScreenTaskNotifications {
    const val CHANGED = "com.assistant.core.SCREEN_TASK_CHANGED"
    const val ID = 7634
    const val CANCEL = "com.assistant.core.CANCEL_SCREEN_TASK"
    private const val CHANNEL = "jarvis_screen_task"
    fun update(context: Context, active: Boolean, awaitingInput: Boolean, taskKey: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!active || taskKey.isNullOrBlank()) { manager.cancel(ID); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen task controls", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getBroadcast(context, ID,
            Intent(context, ScreenTaskCancelReceiver::class.java).setAction(CANCEL)
                .setData(android.net.Uri.Builder().scheme("jarvis-screen-task").authority("cancel").appendPath(taskKey).build())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_jarvis_core).setContentTitle("Jarvis screen task")
            .setContentText(if (awaitingInput) "Waiting for your review or confirmation" else "Inspect the result with continue screen task")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
            .setOngoing(true).addAction(0, "Cancel screen task", cancel).build())
    }
}

class ScreenTaskCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (context.packageName != "com.assistant.core" || intent?.action != ScreenTaskNotifications.CANCEL) return
        val taskKey = intent.data?.takeIf { it.scheme == "jarvis-screen-task" && it.authority == "cancel" }
            ?.pathSegments?.singleOrNull()?.takeIf { it.length in 1..100 } ?: return
        ScreenAccessClient(context).cancelTask(taskKey)
    }
}
