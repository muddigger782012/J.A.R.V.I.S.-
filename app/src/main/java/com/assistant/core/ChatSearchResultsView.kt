package com.assistant.core

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.assistant.core.services.ChatSearchResults
import com.assistant.core.services.SearchReadableText
import com.assistant.core.services.SearchRouter
import com.google.android.material.button.MaterialButton

/** Answer-first search presentation. Source numbering follows the stored evidence order. */
object ChatSearchResultsView {
    fun create(activity: Activity, message: ChatSearchResults.Message): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun label(value: CharSequence, size: Float = 14f, muted: Boolean = false) = TextView(activity).apply {
            text = value; textSize = size
            setTextColor(ThemePreferences.color(activity, if (muted) R.color.jarvis_on_dark_muted else R.color.jarvis_on_dark))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        fun action(title: String, block: () -> Unit) = MaterialButton(activity).apply {
            text = title; isAllCaps = false; minHeight = dp(48); setSingleLine(false); maxLines = 3
            setOnClickListener { block() }
        }
        fun open(item: SearchRouter.Result) {
            if (item.kind == "video") SearchMediaViews.play(activity, item)
            else if (ChatSearchResults.safeUrl(item.url)) activity.startActivity(Intent(activity, SearchBrowserActivity::class.java)
                .putExtra(SearchBrowserActivity.EXTRA_URL, item.url))
        }
        val sources = message.items.filter { it.kind != "image" }
        root.addView(label(message.query, 12f, true).apply { setPadding(0, 0, 0, dp(10)) })
        val formatted = message.answer?.let(SearchReadableText::format)
        if (formatted != null) {
            val text = SpannableString(formatted.text)
            formatted.emphasis.filter { it.end <= text.length }.forEach {
                text.setSpan(StyleSpan(Typeface.BOLD), it.start, it.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            Regex("\\[(\\d+)]").findAll(formatted.text).forEach { match ->
                val source = match.groupValues[1].toIntOrNull()?.let { sources.take(5).getOrNull(it - 1) } ?: return@forEach
                text.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) { open(source) }
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = ThemePreferences.color(activity, R.color.jarvis_primary)
                        ds.isUnderlineText = false; ds.isFakeBoldText = true
                    }
                }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            root.addView(label(text, 16f).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(0, 0, 0, dp(10))
            })
            root.addView(action("Copy answer") {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("J.A.R.V.I.S. search answer", formatted.text))
                Toast.makeText(activity, "Answer copied", Toast.LENGTH_SHORT).show()
            })
        }
        if (message.kind == "image") {
            val images = message.items.filter { it.kind == "image" }
            if (images.isNotEmpty()) root.addView(SearchMediaViews.images(activity, images))
        }
        if (sources.isNotEmpty()) {
            root.addView(label("Sources • ${sources.size}", 13f, true).apply { setPadding(0, dp(12), 0, dp(6)) })
            val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            sources.forEachIndexed { index, item ->
                rows.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.intelligence_panel)
                    setPadding(dp(12), dp(10), dp(12), dp(10)); minimumHeight = dp(48)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                    addView(label("${index + 1}  •  ${Uri.parse(item.url).host?.removePrefix("www.")}  ↗", 12f, true))
                    if (message.kind == "video" && item.thumbnail != null) addView(SearchMediaViews.thumbnail(activity, item, 140))
                    addView(label(SearchReadableText.format(item.title).text, 15f).apply {
                        setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, dp(4))
                    })
                    val excerpt = SearchReadableText.excerpt(item.description)
                    if (excerpt.isNotBlank()) addView(label(excerpt, 13f, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
                    isFocusable = true; isClickable = true
                    contentDescription = "Open source ${index + 1}: ${item.title}"
                    setOnClickListener { open(item) }
                    visibility = if (index < 3) View.VISIBLE else View.GONE
                })
            }
            root.addView(rows)
            if (sources.size > 3) {
                var expanded = false
                val toggle = action("Show all ${sources.size} sources") { }
                toggle.setOnClickListener {
                    expanded = !expanded
                    for (index in 3 until rows.childCount) rows.getChildAt(index).visibility = if (expanded) View.VISIBLE else View.GONE
                    toggle.text = if (expanded) "Show fewer sources" else "Show all ${sources.size} sources"
                }
                root.addView(toggle)
            }
        }
        root.addView(action("Search in browser") {
            val url = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", message.query)
            if (message.kind == "image") url.appendQueryParameter("tbm", "isch")
            if (message.kind == "video") url.appendQueryParameter("tbm", "vid")
            activity.startActivity(Intent(activity, SearchBrowserActivity::class.java).putExtra(SearchBrowserActivity.EXTRA_URL, url.build().toString()))
        })
        message.searchSuggestions?.let { root.addView(GroundedSearchSuggestionsView.create(activity, it)) }
        FontPreferences.applyTo(root, activity)
        return root
    }
}
