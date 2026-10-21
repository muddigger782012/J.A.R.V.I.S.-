package com.jarvis.connectionmanager

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val cyan = Color.rgb(34, 210, 255)
    private val pale = Color.rgb(190, 239, 255)
    private val bg = Color.rgb(1, 12, 21)
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var events: LinearLayout
    private val cards = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val scroller = ScrollView(this).apply { setBackgroundColor(bg); fillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(18), dp(14), dp(22)) }
        scroller.addView(root)
        setContentView(scroller)
        label("◉  J.A.R.V.I.S.", 30, cyan, true)
        label("CONNECTION MANAGER", 18, cyan, true)
        label("MONITOR  •  DIAGNOSE  •  MANAGE  •  OPTIMIZE", 10, pale)
        section("NETWORK STATUS")
        status = label("NOT TESTED", 24, pale, true)
        label("Connection health and service diagnostics", 13, pale)
        section("SERVICE STATUS")
        listOf("INTERNET", "WEB SEARCH", "GEMINI AI", "AI FALLBACK", "GITHUB UPDATES", "LOCAL AI").chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { name ->
                val card = TextView(this).apply {
                    text = "$name\nNOT TESTED"
                    textSize = 14f
                    setTextColor(pale)
                    setPadding(dp(12), dp(17), dp(8), dp(17))
                    background = panel()
                    setOnClickListener { log("$name: dedicated test not configured") }
                }
                cards[name] = card
                row.addView(card, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
            }
            root.addView(row)
        }
        section("QUICK ACTIONS")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("RUN FULL\nDIAGNOSTICS", "TEST ALL\nCONNECTIONS", "REPAIR\nCONNECTIONS", "VIEW\nLOGS").forEachIndexed { index, title ->
            val b = TextView(this).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(cyan)
                background = panel()
                setOnClickListener {
                    when(index) {
                        0, 1 -> diagnose()
                        2 -> log("Android system network repair is not available; check Wi-Fi/mobile settings")
                        else -> log("Activity log shown below")
                    }
                }
            }
            actions.addView(b, LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        }
        root.addView(actions)
        section("RECENT ACTIVITY")
        events = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = panel(); setPadding(dp(10), dp(8), dp(10), dp(8)) }
        root.addView(events)
        log("Connection Manager initialized; no provider tests have run")
        section("OVERVIEW      DIAGNOSTICS      PROVIDERS      SETTINGS")
    }

    private fun diagnose() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        status.text = if (validated) "ANDROID NETWORK VALIDATED" else "NETWORK NOT VALIDATED"
        log("Android validated network: $validated")
        cards["INTERNET"]?.text = "INTERNET\nTESTING..."
        thread {
            val result = try {
                val connection = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                try { "HTTPS ${connection.responseCode}" } finally { connection.disconnect() }
            } catch (e: Exception) { "FAILED: ${e.javaClass.simpleName}" }
            runOnUiThread {
                cards["INTERNET"]?.text = "INTERNET\n$result"
                log("Internet probe: $result (single endpoint only)")
                listOf("WEB SEARCH", "GEMINI AI", "AI FALLBACK", "GITHUB UPDATES", "LOCAL AI").forEach {
                    cards[it]?.text = "$it\nNOT TESTED"
                }
            }
        }
    }
    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = TextView(this).apply { text = "$time   $message"; textSize = 12f; setTextColor(pale); setPadding(0, dp(6), 0, dp(6)) }
        events.addView(entry, 0)
    }
    private fun section(text: String) {
        val view = label(text, 17, cyan, true)
        view.setPadding(dp(6), dp(20), 0, dp(9))
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            root.addView(this)
        }
    }
    private fun panel() = GradientDrawable().apply {
        setColor(Color.rgb(3, 30, 45))
        setStroke(dp(1), cyan)
        cornerRadius = dp(9).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
