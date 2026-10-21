package com.assistant.core.services

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.assistant.core.MainActivity

object PersonalReminderRuntime {
    const val ACTION_FIRE = "com.assistant.core.PERSONAL_REMINDER"
    fun manager(context: Context): PersonalReminders {
        require(context.packageName == "com.assistant.core")
        return PersonalReminders(PersonalStateFile(context, "personal-reminders.json"), platform(context),
            observe = WorkflowDiagnostics(context)::observe)
    }
    fun platform(context: Context): PersonalReminders.Platform {
        require(context.packageName == "com.assistant.core")
        return AndroidPlatform(context.applicationContext)
    }
    fun restoreAsync(context: Context) {
        if (context.packageName != "com.assistant.core") return
        val app = context.applicationContext
        Thread { runCatching { manager(app).restore() } }.start()
    }

    private class AndroidPlatform(private val context: Context) : PersonalReminders.Platform {
        private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        private val alarms get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        override fun notificationsAvailable(): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = notifications
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Local reminders explicitly reviewed and approved in Jarvis"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        private fun alarmIntent(id: String, flags: Int) = PendingIntent.getBroadcast(context, 0,
            Intent(context, PersonalReminderReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.parse("jarvis-reminder://local/$id")), flags or PendingIntent.FLAG_IMMUTABLE)

        override fun schedule(id: String, dueAt: Long): Boolean {
            require(PersonalReminders.validId(id))
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, alarmIntent(id, PendingIntent.FLAG_UPDATE_CURRENT))
            return true
        }
        override fun cancel(id: String): Boolean {
            val intent = alarmIntent(id, PendingIntent.FLAG_NO_CREATE) ?: return true
            alarms.cancel(intent)
            intent.cancel()
            return true
        }
        override fun post(record: PersonalReminders.Record): PersonalReminders.Delivery {
            if (!notificationsAvailable()) return PersonalReminders.Delivery.BLOCKED
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .setData(Uri.parse("jarvis-reminder://open/${record.plan.id}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Jarvis reminder")
                .setContentText(record.plan.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(record.plan.title))
                .setWhen(record.plan.dueAt).setShowWhen(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setOnlyAlertOnce(true).setContentIntent(open).build()
            notifications.notify(record.plan.id, NOTIFICATION, notification)
            // NotificationManager enqueues asynchronously. A bounded readback avoids calling
            // enqueue success "posted"; absence (including fast dismissal) stays uncertain.
            repeat(10) {
                if (notifications.activeNotifications.any { it.tag == record.plan.id && it.id == NOTIFICATION })
                    return PersonalReminders.Delivery.POSTED
                android.os.SystemClock.sleep(100)
            }
            return PersonalReminders.Delivery.UNKNOWN
        }
        companion object { const val CHANNEL = "personal_reminders"; const val NOTIFICATION = 7141 }
    }
}

/** No external app can submit reminder titles or bypass the private approval journal. */
class PersonalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.packageName != "com.assistant.core") return
        val restore = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        val id = intent.data?.lastPathSegment
        if (!restore && (intent.action != PersonalReminderRuntime.ACTION_FIRE || id == null || !PersonalReminders.validId(id))) return
        val completion = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                runCatching {
                    val manager = PersonalReminderRuntime.manager(app)
                    if (restore) manager.restore() else manager.deliver(id!!)
                }
            } finally { completion.finish() }
        }.start()
    }
}
