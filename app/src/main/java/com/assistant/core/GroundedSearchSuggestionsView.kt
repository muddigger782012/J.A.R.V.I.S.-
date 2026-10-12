package com.assistant.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import com.assistant.core.services.ChatSearchResults

/** Isolated provider-owned HTML/CSS suggestions; no script, file access or device bridge. */
object GroundedSearchSuggestionsView {
    fun create(activity: Activity, html: String): WebView = WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        setBackgroundColor(ThemePreferences.color(activity, R.color.jarvis_bg_deep))
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        contentDescription = "Google Search suggestions for the grounded answer"
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (ChatSearchResults.safeUrl(url)) runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                view.post { view.layoutParams = view.layoutParams.apply { height = (view.contentHeight * view.scale).toInt().coerceAtLeast(view.minimumHeight) } }
            }
        }
        loadDataWithBaseURL("https://www.google.com", html, "text/html", "UTF-8", null)
    }
}
