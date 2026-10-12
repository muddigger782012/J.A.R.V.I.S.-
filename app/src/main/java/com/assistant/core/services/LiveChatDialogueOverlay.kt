package com.assistant.core.services

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.assistant.core.FontPreferences
import com.assistant.core.R
import com.assistant.core.ThemePreferences

/** One non-focusable, bounded response panel; microphone capture keeps running. */
class LiveChatDialogueOverlay(private val context: Context, private val onEnd: () -> Unit) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var panel: View? = null
    private var appearance = ""
    private var replyId = ""

    fun refresh(active: Boolean) {
        val reply = LiveChatStore.lastReply(context)
        val id = reply.optString("id")
        if (!active || !VoiceResponsePreferences.load(context).liveDialogue || !Settings.canDrawOverlays(context) ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked || id.isBlank() ||
            id == LiveChatStore.dismissedReply(context)) { hide(); return }
        val snapshot = VoiceResponsePreferences.appearance(context)
        val signature = snapshot.toString()
        if (panel == null || signature != appearance) {
            hide()
            val themed = ContextThemeWrapper(context, ThemePreferences.style(snapshot.optString("theme", "default")))
            val view = LayoutInflater.from(themed).inflate(R.layout.live_chat_dialogue, null)
            view.findViewById<Button>(R.id.btnCloseLiveDialogue).setOnClickListener {
                LiveChatStore.dismissReply(context, replyId)
                hide()
            }
            view.findViewById<Button>(R.id.btnEndLiveDialogue).setOnClickListener { onEnd() }
            FontPreferences.applyTo(view, themed, snapshot.optInt("fontPercent", 100), snapshot.optString("fontColor"))
            val metrics = context.resources.displayMetrics
            fun dp(value: Int) = (value * metrics.density).toInt()
            val params = WindowManager.LayoutParams(
                minOf(dp(380), (metrics.widthPixels - dp(24)).coerceAtLeast(1)),
                minOf(dp(360), (metrics.heightPixels * 0.55f).toInt()),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(48) }
            if (runCatching { manager.addView(view, params) }.isFailure) return
            panel = view
            appearance = signature
        }
        replyId = id
        panel?.findViewById<TextView>(R.id.tvLiveDialogueReply)?.text = reply.optString("text").removePrefix("J.A.R.V.I.S.: ")
    }

    fun hide() {
        panel?.let { runCatching { manager.removeView(it) } }
        panel = null
    }
}
