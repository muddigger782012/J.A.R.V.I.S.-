package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.content.pm.PackageManager

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

    fun setTimer(seconds: Int, message: String = "J.A.R.V.I.S. timer"): Boolean =
        launch(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
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

    fun dial(number: String): Boolean =
        launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))

    fun composeSms(number: String, body: String): Boolean =
        launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
            putExtra("sms_body", body)
        })

    fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return launch(intent)
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

    fun playPauseMedia(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun nextMedia(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previousMedia(): Boolean = dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun dispatchMediaKey(keyCode: Int): Boolean = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        true
    }.getOrDefault(false)

    fun openSettings(): Boolean = launch(Intent(Settings.ACTION_SETTINGS))

    fun adjustVolume(direction: Int): Boolean = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        true
    }.getOrDefault(false)
}
