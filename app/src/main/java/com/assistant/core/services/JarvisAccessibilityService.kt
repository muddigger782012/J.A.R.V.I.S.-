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
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastPackage = event.packageName?.toString().orEmpty()
        lastEventType = event.eventType
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun performGlobal(action: Int): Boolean = performGlobalAction(action)

    fun currentWindowSummary(maxNodes: Int = 80): String {
        val root = rootInActiveWindow ?: return "No active accessibility window."
        val out = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank() || description.isNotBlank()) {
                out += listOf(text, description).filter { it.isNotBlank() }.distinct().joinToString(" | ")
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return out.joinToString("\n").ifBlank { "Active window contains no readable accessibility text." }
    }

    companion object {
        @Volatile private var instance: JarvisAccessibilityService? = null
        @Volatile var lastPackage: String = ""; private set
        @Volatile var lastEventType: Int = 0; private set

        fun active(): JarvisAccessibilityService? = instance

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
