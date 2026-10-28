package com.assistant.core

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.widget.RemoteViews

/** Lightweight launch surfaces; animation stays in the Activity to respect widget limits. */
open class JarvisHudWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val compact = this is JarvisReactorWidgetProvider
            val layout = if (compact) R.layout.widget_jarvis_reactor else R.layout.widget_jarvis_info
            val views = RemoteViews(context.packageName, layout)
            val launch = Intent(context, AssistantHudActivity::class.java)
            val pending = PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            if (!compact) {
                val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                views.setTextViewText(R.id.widget_battery, "BATTERY  ${level}%")
            }
            manager.updateAppWidget(id, views)
        }
    }
}
