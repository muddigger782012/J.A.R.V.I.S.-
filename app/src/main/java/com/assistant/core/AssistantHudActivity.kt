package com.assistant.core

import android.content.Intent
import android.util.TypedValue
import android.os.BatteryManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Optional HUD surface. MainActivity remains the default Jarvis launcher. */
class AssistantHudActivity : ThemedActivity() {
    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }
    private val cyan get() = themeColor(android.R.attr.textColorPrimary)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = themeColor(android.R.attr.colorBackground)
        window.navigationBarColor = themeColor(android.R.attr.colorBackground)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 24)
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(label("J.A.R.V.I.S.  •  ASSISTANT HUD", 22))
        val reactor = ArcReactorView(this).apply {
            contentDescription = "Open Jarvis assistant"
            isClickable = true
            setOnClickListener { openJarvis() }
        }
        root.addView(reactor, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels * 0.82f).toInt()))
        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        root.addView(label("SYSTEM  •  BATTERY ${percent}%", 16))
        root.addView(label("Tap the reactor to open Jarvis", 14))
        row(root, "ASSISTANT", "SETTINGS") { index ->
            if (index == 0) openJarvis() else startActivity(Intent(this, SettingsActivity::class.java))
        }
        row(root, "TALK TO JARVIS", "CONNECTIONS") { index ->
            if (index == 0) openVoice()
            else startActivity(Intent(this, AssistantConnectionsActivity::class.java))
        }
        root.addView(label("The HUD is an optional extension. Existing Jarvis screens remain intact.", 13))
    }
    private fun label(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(cyan)
        gravity = Gravity.CENTER
        setPadding(8, 18, 8, 18)
    }
    private fun row(parent: LinearLayout, left: String, right: String, action: (Int) -> Unit) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(left, right).forEachIndexed { i, title ->
            line.addView(label(title, 15).apply {
                setBackgroundColor(themeColor(android.R.attr.colorBackground))
                setOnClickListener { action(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 8, 4, 8) })
        }
        parent.addView(line)
    }
    private fun openVoice() = startActivity(Intent(this, MainActivity::class.java).putExtra("jarvis_feature", "voice"))
    private fun openJarvis() = startActivity(Intent(this, MainActivity::class.java))
}
