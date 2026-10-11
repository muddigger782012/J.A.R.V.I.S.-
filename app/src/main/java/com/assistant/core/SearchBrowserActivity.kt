package com.assistant.core

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView

/** Embedded search browser; external sites still open in the user's browser. */
class SearchBrowserActivity : ThemedActivity() {
    private lateinit var browser: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.host != "www.google.com" || uri.path != "/search") {
            finish()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemePreferences.color(this@SearchBrowserActivity, R.color.jarvis_on_dark))
        }
        val toolbar = LinearLayout(this)
        val title = TextView(this).apply {
            text = if (uri.getQueryParameter("tbm") == "isch") "J.A.R.V.I.S.  •  Images" else "J.A.R.V.I.S.  •  Search"
            setTextColor(ThemePreferences.color(this@SearchBrowserActivity, R.color.jarvis_primary))
            textSize = 17f
            setPadding(16, 12, 16, 12)
        }
        container.addView(title)
        val back = Button(this).apply {
            text = "Back"
            setOnClickListener { if (browser.canGoBack()) browser.goBack() else finish() }
        }
        val external = Button(this).apply {
            text = "Open in browser"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browser.url ?: url)))
            }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(external, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listOf(back, external).forEach { it.setTextColor(ThemePreferences.color(this, R.color.jarvis_primary)) }
        container.addView(toolbar)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (target.scheme == "https" && target.host in setOf("www.google.com", "google.com")) return false
                    if (target.scheme == "https") {
                        startActivity(Intent(Intent.ACTION_VIEW, target))
                    }
                    return true
                }
            }
            loadUrl(url)
        }
        container.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(container)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "search_url"
    }
}
