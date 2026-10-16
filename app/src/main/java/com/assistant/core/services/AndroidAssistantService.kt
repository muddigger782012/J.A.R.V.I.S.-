package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standard Android assistant capabilities that do not require privileged shell access.
 * These are invoked by the central assistant so voice/text/background entry points
 * share the same behavior.
 */
class AndroidAssistantService(private val context: Context) {
    private fun launch(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun setTimer(seconds: Int, message: String = "J.A.R.V.I.S. timer", showUi: Boolean = false): Boolean =
        launch(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, !showUi)
        })

    fun setAlarm(hour: Int, minute: Int, message: String = "J.A.R.V.I.S. alarm"): Boolean =
        launch(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })

    fun navigate(destination: String): Boolean =
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination))))

    fun resolveContactPhone(name: String): String? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val candidates = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: continue
                    if (number.isNotBlank()) add(displayName to number)
                }
            }
        }.orEmpty()
        val normalized = ContactNameMatcher.normalize(name)
        if (normalized.isBlank()) return null
        // Only unambiguous normalized names are dialed. Never guess among
        // multiple distinct contacts or use fuzzy phonetic matches for calls.
        val exactMatches = candidates.filter { ContactNameMatcher.normalize(it.first) == normalized }
        val matches = if (exactMatches.isNotEmpty()) exactMatches else candidates.filter {
            ContactNameMatcher.normalize(it.first).split(' ').contains(normalized)
        }
        return matches.map { it.first }.distinct().singleOrNull()?.let { selectedName ->
            matches.firstOrNull { it.first == selectedName }?.second
        }
    }

    fun lastMissedCall(): String {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            return "Call Log permission is required. Open Android Settings → Apps → J.A.R.V.I.S. → Permissions, allow Call logs, then ask again."
        return try {
            val projection = arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME,
                android.provider.CallLog.Calls.DATE, android.provider.CallLog.Calls.NUMBER_PRESENTATION)
            context.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, projection,
                "${android.provider.CallLog.Calls.TYPE} = ?", arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString()),
                "${android.provider.CallLog.Calls.DATE} DESC")?.use { cursor ->
                if (!cursor.moveToFirst()) return "There are no missed calls in your call log."
                val name = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.CACHED_NAME)).orEmpty()
                val number = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER)).orEmpty()
                val presentation = cursor.getInt(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.NUMBER_PRESENTATION))
                val caller = when {
                    presentation == android.provider.CallLog.Calls.PRESENTATION_RESTRICTED -> "a private number"
                    presentation != android.provider.CallLog.Calls.PRESENTATION_ALLOWED || number.isBlank() -> "an unknown number"
                    name.isNotBlank() -> "$name ($number)"
                    else -> number
                }
                val time = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.CallLog.Calls.DATE))
                val formatted = SimpleDateFormat("EEEE, MMMM d 'at' h:mm a", Locale.getDefault()).format(Date(time))
                "Your last missed call was from $caller on $formatted."
            } ?: "Android couldn't read the call log. Check Call Log permission and try again."
        } catch (_: Exception) { "Android couldn't read the call log. Check Call Log permission and try again." }
    }

    fun hasCallPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun call(number: String): Boolean =
        hasCallPermission() && launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))

    fun dial(number: String): Boolean =
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))

    fun composeSms(number: String, body: String): Boolean =
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
            putExtra("sms_body", body)
        })

    fun canLaunchApp(packageName: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }.getOrDefault(false)

    fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return launch(intent)
    }

    /** Resolve one visible launcher app; ambiguous names never select the first match. */
    fun resolveLearnableApp(label: String): Pair<String, String>? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, 0).map {
            it.activityInfo.packageName to it.loadLabel(pm).toString()
        }.distinctBy { it.first }.filter { it.second.equals(label.trim(), ignoreCase = true) }
        return candidates.singleOrNull()
    }

    fun launchAppByLabel(label: String): Boolean {
        val pm = context.packageManager
        val target = label.trim().lowercase()
        val apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        val match = apps.firstOrNull { info ->
            pm.getApplicationLabel(info).toString().trim().lowercase() == target
        } ?: apps.firstOrNull { info ->
            pm.getApplicationLabel(info).toString().trim().lowercase().contains(target)
        } ?: return false
        return launchApp(match.packageName)
    }

    fun amazonMusic(playMusic: Boolean): String {
        val pm = context.packageManager
        val app = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)).firstOrNull {
            pm.getApplicationLabel(it).toString().trim().lowercase(java.util.Locale.ROOT) in
                setOf("amazon music", "music amazon", "amazon prime music")
        } ?: return "I couldn't find Amazon Music installed. Install it and sign in, then try again."
        if (playMusic) {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(app.packageName)
                putExtra(android.app.SearchManager.QUERY, "")
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
            if (launch(intent)) return "Asked Amazon Music to start playing."
        }
        return if (launchApp(app.packageName)) {
            if (playMusic) "Opening Amazon Music. Tap Play in the app to start your music."
            else "Opening Amazon Music."
        } else "Android couldn't open Amazon Music. Check that the app is enabled."
    }

    fun playPauseMedia(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun nextMedia(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previousMedia(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun dispatchMediaKey(keyCode: Int): Boolean = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        true
    }.getOrDefault(false)

    fun createCalendarEvent(title: String, beginMillis: Long? = null): Boolean =
        launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            beginMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        })

    data class CalendarEntry(val title: String, val beginMillis: Long)

    fun hasCalendarReadPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    fun calendarEntries(startMillis: Long, endMillis: Long, limit: Int = 12): List<CalendarEntry> {
        if (!hasCalendarReadPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN
        )
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, startMillis)
        android.content.ContentUris.appendId(builder, endMillis)
        return context.contentResolver.query(
            builder.build(), projection, null, null,
            CalendarContract.Instances.BEGIN + " ASC"
        )?.use { cursor ->
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            buildList {
                while (cursor.moveToNext() && size < limit) {
                    add(CalendarEntry(cursor.getString(titleIndex) ?: "Untitled event", cursor.getLong(beginIndex)))
                }
            }
        } ?: emptyList()
    }

    fun createReminder(title: String, beginMillis: Long? = null): Boolean =
        createCalendarEvent(title, beginMillis)

    fun openNotificationSettings(): Boolean =
        launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        })

    fun openSettings(): Boolean = launch(Intent(Settings.ACTION_SETTINGS))

    fun adjustVolume(direction: Int): Boolean = synchronized(volumeLock) { runCatching {
        if (direction != AudioManager.ADJUST_RAISE && direction != AudioManager.ADJUST_LOWER) return@runCatching false
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        VolumeChangeVerification.changed(before, after, direction == AudioManager.ADJUST_RAISE)
    }.getOrDefault(false) }

    fun setMediaVolumePercent(percent: Int): Boolean = synchronized(volumeLock) { runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        VerifiedMediaVolumeLevel.execute(percent, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            read = { audio.getStreamVolume(AudioManager.STREAM_MUSIC) },
            set = { target -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI) })
    }.getOrDefault(false) }

    companion object {
        private val volumeLock = Any()
    }
}
