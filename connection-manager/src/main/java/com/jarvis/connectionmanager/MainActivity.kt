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
    private lateinit var scroller: ScrollView
    private lateinit var providerSection: LinearLayout
    private var runningDiagnostics = false
    private var sessionGeminiKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        scroller = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(18), dp(14), dp(22)) }
        scroller.addView(root)
        setContentView(scroller)
        label("◉  J.A.R.V.I.S.", 30, cyan, true)
        label("CONNECTION MANAGER", 18, cyan, true)
        label("MONITOR  •  DIAGNOSE  •  MANAGE  •  OPTIMIZE", 10, pale)
        section("NETWORK STATUS")
        status = label("NOT TESTED", 24, pale, true)
        label("Connection health and service diagnostics", 13, pale)
        root.addView(NetworkHudView(this), LinearLayout.LayoutParams(-1, -2))
        section("SERVICE STATUS")
        providerSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(providerSection)
        listOf("INTERNET", "WEB SEARCH", "GEMINI AI", "AI FALLBACK", "GITHUB UPDATES", "LOCAL AI").chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { name ->
                val card = TextView(this).apply {
                    text = "$name\nNOT TESTED"
                    textSize = 14f
                    setTextColor(pale)
                    setPadding(dp(12), dp(17), dp(8), dp(17))
                    background = panel()
                    setOnClickListener { log("$name: run diagnostics to refresh; authentication and local AI require integration") }
                }
                cards[name] = card
                row.addView(card, LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
            }
            providerSection.addView(row)
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
                        2 -> { log("Opening Android wireless settings; system repair requires user action"); startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
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
        section("NAVIGATION")
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("OVERVIEW", "DIAGNOSTICS", "PROVIDERS", "SETTINGS").forEachIndexed { index, title ->
            val tab = TextView(this).apply {
                text = title
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(cyan)
                contentDescription = "Open $title"
                background = panel()
                setOnClickListener {
                    when (index) {
                        0 -> scroller.smoothScrollTo(0, 0)
                        1 -> diagnose()
                        2 -> {
                            log("Provider status: public reachability only; authenticated tests not configured")
                            scroller.post { scroller.smoothScrollTo(0, providerSection.top) }
                        }
                        else -> showSettings()
                    }
                }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        root.addView(tabs)
    }

    private fun showSettings() {
        val options = arrayOf("Open Android wireless settings", "Configure Gemini key (session only)", "About diagnostics", "Clear activity log")
        android.app.AlertDialog.Builder(this)
            .setTitle("Connection Manager Settings")
            .setItems(options) { _, selected ->
                when (selected) {
                    0 -> startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                    1 -> configureGeminiKey()
                    2 -> android.app.AlertDialog.Builder(this)
                        .setTitle("Diagnostic scope")
                        .setMessage("Internet, search, Gemini, and GitHub checks test public endpoint reachability only. They do not confirm API credentials, model access, or update availability. Local AI and fallback are not connected yet.")
                        .setPositiveButton("OK", null).show()
                    3 -> {
                        events.removeAllViews()
                        log("Activity log cleared")
                    }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun configureGeminiKey() {
        val field = android.widget.EditText(this).apply {
            hint = "Gemini API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Gemini credentials")
            .setMessage("The key stays in app memory for this session only. It is never written to the activity log.")
            .setView(field)
            .setPositiveButton("Use for this session") { _, _ ->
                sessionGeminiKey = field.text.toString().trim().takeIf { it.isNotEmpty() }
                log(if (sessionGeminiKey == null) "Gemini credentials cleared" else "Gemini credentials configured for session")
            }
            .setNeutralButton("Clear key") { _, _ ->
                sessionGeminiKey = null
                log("Gemini credentials cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun diagnose() {
        if (runningDiagnostics) { log("Diagnostics already running"); return }
        runningDiagnostics = true
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Unknown or disconnected"
        }
        status.text = if (validated) "ANDROID NETWORK VALIDATED" else "NETWORK NOT VALIDATED"
        log("Android validated network: $validated; transport: $transport")
        listOf("INTERNET", "WEB SEARCH", "GEMINI AI", "GITHUB UPDATES").forEach { cards[it]?.text = "$it\nTESTING..." }
        thread {
            val started = android.os.SystemClock.elapsedRealtime()
            val result = try {
                val connection = URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                try { "HTTPS ${connection.responseCode} · ${android.os.SystemClock.elapsedRealtime() - started} ms" } finally { connection.disconnect() }
            } catch (e: Exception) { "FAILED: ${e.javaClass.simpleName}" }
            val github = try {
                val request = URL("https://api.github.com/repos/muddigger782012/J.A.R.V.I.S.-").openConnection() as HttpURLConnection
                request.connectTimeout = 5000
                request.readTimeout = 5000
                request.setRequestProperty("User-Agent", "JarvisConnectionManager/0.1")
                try { "GitHub HTTP ${request.responseCode}" } finally { request.disconnect() }
            } catch (e: Exception) { "GitHub FAILED: ${e.javaClass.simpleName}" }
            val search = ProviderDiagnostics.checkPublicEndpoint("WEB SEARCH", "https://www.google.com/search?q=connectivity")
            val key = sessionGeminiKey
            val gemini = if (key != null) GeminiDiagnostics.checkModels(key) else null
            val geminiPublic = if (gemini == null) ProviderDiagnostics.checkPublicEndpoint("GEMINI AI", "https://generativelanguage.googleapis.com/") else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runningDiagnostics = false
                cards["INTERNET"]?.text = "INTERNET\n$result"
                log("Internet probe: $result (single endpoint only)")
                cards["GITHUB UPDATES"]?.text = "GITHUB UPDATES\n$github"
                log("Repository API probe: $github (does not verify update manifest)")
                cards["WEB SEARCH"]?.text = "WEB SEARCH\n${search.detail} (${search.latencyMs} ms)"
                cards["GEMINI AI"]?.text = "GEMINI AI\n${gemini.detail} (${gemini.latencyMs} ms)"
                log(if (gemini != null) "Gemini: authenticated model listing checked; search remains public reachability only" else "Search and Gemini: public reachability only; provider API calls not authenticated")
                listOf("AI FALLBACK", "LOCAL AI").forEach {
                    cards[it]?.text = "$it\nNOT TESTED"
                }
            }
        }
    }
    private fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = TextView(this).apply { text = "$time   $message"; textSize = 12f; setTextColor(pale); setPadding(0, dp(6), 0, dp(6)) }
        events.addView(entry, 0)
        if (events.childCount > 50) events.removeViewAt(events.childCount - 1)
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
