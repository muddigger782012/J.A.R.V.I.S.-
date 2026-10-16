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
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.assistant.core.services.ChatSearchResults
import com.assistant.core.services.SearchReadableText
import com.assistant.core.services.SearchRouter

/** Answer-first search presentation. Source numbering follows the stored evidence order. */
object ChatSearchResultsView {
    fun create(activity: Activity, message: ChatSearchResults.Message): View {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        fun label(value: CharSequence, size: Float = 14f, muted: Boolean = false) = TextView(activity).apply {
            text = value; textSize = size
            setTextColor(ThemePreferences.color(activity, if (muted) R.color.jarvis_on_dark_muted else R.color.jarvis_on_dark))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        fun action(title: String, description: String = title, block: () -> Unit) = TextView(activity).apply {
            text = title; textSize = 12f; minHeight = dp(48); maxLines = 2
            gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setTextColor(ThemePreferences.color(activity, R.color.jarvis_on_dark_muted))
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            isClickable = true; isFocusable = true; contentDescription = description
            foreground = android.util.TypedValue().let { value ->
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true))
                    androidx.core.content.ContextCompat.getDrawable(activity, value.resourceId) else null
            }
            setOnClickListener { block() }
        }
        fun icon(resource: Int, description: String, block: () -> Unit) = ImageButton(activity).apply {
            setImageResource(resource)
            imageTintList = android.content.res.ColorStateList.valueOf(ThemePreferences.color(activity, R.color.jarvis_on_dark_muted))
            background = null
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            contentDescription = description
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            foreground = android.util.TypedValue().let { value ->
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true))
                    androidx.core.content.ContextCompat.getDrawable(activity, value.resourceId) else null
            }
            setOnClickListener { block() }
        }
        fun open(item: SearchRouter.Result) {
            if (!ChatSearchResults.safeUrl(item.url)) return
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
                val source = match.groupValues[1].toIntOrNull()?.let { sources.getOrNull(it - 1) } ?: return@forEach
                if (!ChatSearchResults.safeUrl(source.url)) return@forEach
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
            actions.addView(icon(R.drawable.ic_search_copy, "Copy search answer") {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("J.A.R.V.I.S. search answer", formatted.text))
                Toast.makeText(activity, "Answer copied", Toast.LENGTH_SHORT).show()
            })
        }
        if (message.items.any { it.kind == "image" }) {
            val images = message.items.filter { it.kind == "image" }
            if (images.isNotEmpty()) root.addView(SearchMediaViews.images(activity, images))
        }
        if (sources.isNotEmpty()) {
            var expanded = message.kind == "video" || formatted == null
            val rows = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (expanded) View.VISIBLE else View.GONE
            }
            sources.forEachIndexed { index, item ->
                rows.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = androidx.appcompat.content.res.AppCompatResources.getDrawable(activity, R.drawable.intelligence_panel)
                    setPadding(dp(10), dp(8), dp(10), dp(8)); minimumHeight = dp(48)
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                    addView(label("${index + 1}  •  ${Uri.parse(item.url).host?.removePrefix("www.")}  ↗", 12f, true))
                    if (message.kind == "video" && item.thumbnail != null) addView(SearchMediaViews.thumbnail(activity, item, 140))
                    addView(label(SearchReadableText.format(item.title).text, 15f).apply {
                        setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, dp(4))
                    })
                    val excerpt = SearchReadableText.excerpt(item.description)
                    if (excerpt.isNotBlank()) addView(label(excerpt, 13f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                    val validSource = ChatSearchResults.safeUrl(item.url)
                    isFocusable = validSource; isClickable = validSource
                    contentDescription = if (validSource) "Open source ${index + 1}: ${item.title}" else "Unavailable source ${index + 1}: ${item.title}"
                    if (validSource) setOnClickListener { open(item) }
                })
            }
            val toggle = action("Sources · ${sources.size} ${if (expanded) "▴" else "▾"}", "Show or hide ${sources.size} search sources") { }
            toggle.setOnClickListener {
                expanded = !expanded
                rows.visibility = if (expanded) View.VISIBLE else View.GONE
                toggle.text = "Sources · ${sources.size} ${if (expanded) "▴" else "▾"}"
                toggle.contentDescription = (if (expanded) "Hide" else "Show") + " ${sources.size} search sources"
            }
            if (!expanded) {
                val preview = sources.take(2).joinToString("  •  ") {
                    Uri.parse(it.url).host?.removePrefix("www.").orEmpty()
                }
                root.addView(label(preview, 12f, true).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, dp(4))
                    setOnClickListener {
                        expanded = true
                        rows.visibility = View.VISIBLE
                        toggle.text = "Sources · ${sources.size} ▴"
                    }
                    isClickable = true
                    contentDescription = "Show sources from $preview"
                })
            }
            root.addView(toggle)
            root.addView(rows)
        }
        actions.addView(icon(R.drawable.ic_search_open, "Open search in the embedded browser") {
            val url = Uri.parse("https://www.google.com/search").buildUpon().appendQueryParameter("q", message.query)
            if (message.kind == "image") url.appendQueryParameter("tbm", "isch")
            if (message.kind == "video") url.appendQueryParameter("tbm", "vid")
            activity.startActivity(Intent(activity, SearchBrowserActivity::class.java).putExtra(SearchBrowserActivity.EXTRA_URL, url.build().toString()))
        })
        root.addView(actions)
        message.searchSuggestions?.let { root.addView(GroundedSearchSuggestionsView.create(activity, it)) }
        FontPreferences.applyTo(root, activity)
        return root
    }
}
