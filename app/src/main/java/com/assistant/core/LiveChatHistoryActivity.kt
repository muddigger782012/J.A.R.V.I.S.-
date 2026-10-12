package com.assistant.core

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.assistant.core.services.LiveChatHistory
import com.assistant.core.services.LiveChatStore
import com.assistant.core.services.VoiceResponsePreferences
import com.google.android.material.button.MaterialButton

class LiveChatHistoryActivity : ThemedActivity() {
    private lateinit var content: LinearLayout
    private var selectedSession: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_chat_history)
        content = findViewById(R.id.liveChatHistoryContent)
        selectedSession = savedInstanceState?.getString("session")
        findViewById<Button>(R.id.btnCloseLiveHistory).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefreshLiveHistory).setOnClickListener { render() }
        findViewById<Button>(R.id.btnCopyAllLiveHistory).setOnClickListener { copyForDebug(null) }
    }

    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session", selectedSession)
        super.onSaveInstanceState(outState)
    }

    private fun text(value: String, heading: Boolean = false) {
        content.addView(TextView(this).apply {
            text = value
            textSize = if (heading) 18f else 15f
            setTextColor(ThemePreferences.color(this@LiveChatHistoryActivity, if (heading) R.color.jarvis_primary else R.color.jarvis_on_dark))
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 12)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(label: String, action: () -> Unit) {
        content.addView(MaterialButton(this).apply {
            text = label; isAllCaps = false; setSingleLine(false)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun render() {
        content.removeAllViews()
        val sessions = LiveChatHistory.sessions(LiveChatStore.messages(this))
        findViewById<Button>(R.id.btnCopyAllLiveHistory).isEnabled = sessions.isNotEmpty()
        val selected = sessions.firstOrNull { it.id == selectedSession }
        if (selected != null) {
            button("Back to sessions") { selectedSession = null; render() }
            text("Live chat • " + LiveChatHistory.time(selected.startedAt), true)
            button("Copy this session for debug") { copyForDebug(selected.id) }
            selected.entries.forEach { entry ->
                text("${LiveChatHistory.time(entry.createdAt)}\n${entry.text}")
            }
        } else {
            selectedSession = null
            if (sessions.isEmpty()) text("No live chat dialogue has been logged yet.")
            sessions.forEachIndexed { index, session ->
                val title = if (session.startedAt > 0) LiveChatHistory.time(session.startedAt) else "Earlier dialogue ${index + 1}"
                val preview = session.entries.firstOrNull { it.text.startsWith("You:") }?.text.orEmpty().take(100)
                button("$title • ${session.entries.size} entries\n$preview") { selectedSession = session.id; render() }
            }
        }
        FontPreferences.applyTo(content, this)
    }

    private fun copyForDebug(sessionId: String?) {
        val sessions = LiveChatHistory.sessions(LiveChatStore.messages(this)).filter { sessionId == null || it.id == sessionId }
        if (sessions.isEmpty()) { Toast.makeText(this, "No retained dialogue to copy", Toast.LENGTH_SHORT).show(); return }
        val text = LiveChatHistory.debugText(sessions, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", VoiceResponsePreferences.load(this))
        // Keep large retained logs within Android's clipboard IPC size limit.
        if (text.toByteArray(Charsets.UTF_8).size > 400000) {
            Toast.makeText(this, "This record is too large to copy at once. Copy individual sessions.", Toast.LENGTH_LONG).show(); return
        }
        runCatching { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("JARVIS live chat debug", text)) }
            .onSuccess { Toast.makeText(this, "Live chat debug record copied", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, "Couldn't copy this record. Try a smaller session.", Toast.LENGTH_LONG).show() }
    }
}
