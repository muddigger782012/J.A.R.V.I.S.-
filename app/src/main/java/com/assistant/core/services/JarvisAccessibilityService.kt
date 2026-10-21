package com.assistant.core.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-enabled accessibility bridge for J.A.R.V.I.S.
 * Provides bounded UI context/navigation primitives to the central assistant.
 * Sensitive actions remain subject to the assistant policy/confirmation layer.
 */
class JarvisAccessibilityService : AccessibilityService() {
    @Volatile var screenGeneration: String = java.util.UUID.randomUUID().toString(); private set
    override fun onServiceConnected() {
        super.onServiceConnected()
        screenGeneration = java.util.UUID.randomUUID().toString()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastPackage = event.packageName?.toString().orEmpty()
        lastEventType = event.eventType
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) screenGeneration = java.util.UUID.randomUUID().toString()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun performGlobal(action: Int): Boolean = performGlobalAction(action)

    fun currentWindowSummary(maxNodes: Int = 80): String {
        return runCatching {
            val snapshot = AndroidScreenPlatform(this).snapshot()
            if (snapshot.blocked) "Protected or password-entry screen; no content was read out."
            else snapshot.text.take(maxNodes.coerceIn(1, 80)).joinToString("\n").ifBlank { "Active window contains no readable accessibility text." }
        }.getOrElse { "Active accessibility window could not be read within its limits." }
    }

    companion object {
        @Volatile private var instance: JarvisAccessibilityService? = null
        @Volatile var lastPackage: String = ""; private set
        @Volatile var lastEventType: Int = 0; private set

        fun active(): JarvisAccessibilityService? = instance

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
