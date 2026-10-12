package com.assistant.core

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import android.content.res.ColorStateList
import android.view.Gravity
import android.text.TextUtils
import android.widget.TextView

/** Embedded search browser; external sites still open in the user's browser. */
class SearchBrowserActivity : ThemedActivity() {
    private lateinit var browser: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val uri = Uri.parse(url)
        if (!com.assistant.core.services.SearchBrowserNavigation.canStart(url)) {
            finish()
            return
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemePreferences.color(this@SearchBrowserActivity, R.color.jarvis_bg_deep))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundResource(R.drawable.glass_panel_surface)
        }
        fun icon(label: String, reverse: Boolean = false, action: () -> Unit) =
            AppCompatImageButton(this).apply {
                contentDescription = label
                setImageResource(R.drawable.ic_composer_send)
                imageTintList = ColorStateList.valueOf(ThemePreferences.color(this@SearchBrowserActivity, R.color.jarvis_primary))
                setBackgroundResource(R.drawable.composer_icon_ring)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                if (reverse) rotation = 180f
                setOnClickListener { action() }
            }
        val back = icon("Back", reverse = true) {
            if (browser.canGoBack()) browser.goBack() else finish()
        }
        val title = TextView(this).apply {
            text = when (uri.getQueryParameter("tbm")) {
                "isch" -> "J.A.R.V.I.S. · Images"
                "vid" -> "J.A.R.V.I.S. · Videos"
                else -> "J.A.R.V.I.S. · Search"
            }
            setTextColor(ThemePreferences.color(this@SearchBrowserActivity, R.color.jarvis_on_dark))
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val external = icon("Open in browser") {
            openExternal(Uri.parse(browser.url ?: url))
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(external, LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(toolbar, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) android.widget.Toast.makeText(this@SearchBrowserActivity,
                        "Search page couldn't connect. Try again or use Open in browser.", android.widget.Toast.LENGTH_LONG).show()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    if (com.assistant.core.services.SearchBrowserNavigation.staysEmbedded(target.toString())) return false
                    if (target.scheme == "https") {
                        openExternal(target)
                    }
                    return true
                }
            }
            loadUrl(url)
        }
        container.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(container)
    }

    private fun openExternal(target: Uri) {
        if (target.scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, target))
        } catch (_: ActivityNotFoundException) {
            android.widget.Toast.makeText(this, "No browser available to open this link", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "search_url"
    }
}
