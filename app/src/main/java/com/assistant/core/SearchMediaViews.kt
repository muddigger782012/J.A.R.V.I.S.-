package com.assistant.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.assistant.core.services.SearchMedia
import com.assistant.core.services.SearchRouter

/** Media shares chat's theme, font preferences and source attribution. */
object SearchMediaViews {
    private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun images(activity: Activity, items: List<SearchRouter.Result>): HorizontalScrollView {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (item in items.take(6)) {
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ChatAppearancePreferences.background(activity, false)
                setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                layoutParams = LinearLayout.LayoutParams(dp(activity, 220), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(activity, 8)
                }
            }
            tile.addView(thumbnail(activity, item, 150))
            tile.addView(TextView(activity).apply {
                text = item.title
                maxLines = 3
                textSize = 14f
                setTextColor(ThemePreferences.color(activity, R.color.jarvis_on_dark))
            })
            tile.addView(TextView(activity).apply {
                text = Uri.parse(item.url).host + "  ↗"
                textSize = 12f
                setTextColor(ThemePreferences.color(activity, R.color.jarvis_on_dark_muted))
            })
            tile.isFocusable = true
            tile.contentDescription = "Preview image: ${item.title}"
            tile.setOnClickListener {
                val image = thumbnail(activity, item, 320)
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                image.minimumHeight = dp(activity, 320)
                ThemedDialogBuilder(activity).setTitle(item.title).setView(image)
                    .setPositiveButton("Open image") { _, _ -> open(activity, item.url) }
                    .setNegativeButton("Close", null).show()
            }
            FontPreferences.applyTo(tile, activity)
            row.addView(tile)
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            setPadding(0, dp(activity, 10), 0, dp(activity, 8))
            contentDescription = "Search image gallery. Swipe for more pictures."
        }
    }

    fun thumbnail(activity: Activity, item: SearchRouter.Result, height: Int): ImageView = ImageView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, height))
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = item.title
        setBackgroundColor(ThemePreferences.color(activity, R.color.jarvis_bg_deep))
        item.thumbnail?.let { SearchThumbnailLoader.load(this, it) }
    }

    fun play(activity: Activity, item: SearchRouter.Result) {
        val id = SearchMedia.youtubeId(item.url) ?: return open(activity, item.url)
        val player = WebView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 240))
            minimumHeight = dp(activity, 240)
            setBackgroundColor(ThemePreferences.color(activity, R.color.jarvis_bg_deep))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = android.webkit.WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return request.url.scheme != "https"
                    open(activity, item.url)
                    return true
                }
            }
        }
        val dialog = ThemedDialogBuilder(activity).setTitle(item.title).setView(player)
            .setPositiveButton("Open source") { _, _ -> open(activity, item.url) }
            .setNegativeButton("Close", null).create()
        dialog.setOnDismissListener { player.stopLoading(); player.loadUrl("about:blank"); player.destroy() }
        dialog.show()
        player.loadDataWithBaseURL("https://com.assistant.core/",
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                "<body style='margin:0'><iframe title='Video player' width='100%' height='100%' " +
                "src='https://www.youtube-nocookie.com/embed/$id' frameborder='0' allow='encrypted-media; fullscreen' allowfullscreen></iframe></body></html>",
            "text/html", "UTF-8", null)
    }

    private fun open(activity: Activity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { android.widget.Toast.makeText(activity, "No browser available", android.widget.Toast.LENGTH_SHORT).show() }
    }
}
