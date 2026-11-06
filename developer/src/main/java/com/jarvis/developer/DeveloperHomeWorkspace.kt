package com.jarvis.developer

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * One chat-first Home surface over the EXISTING source, generation, review and
 * task engines. This class only arranges their original Android views. It is
 * deliberately not another patch engine, GitHub writer or source of approval.
 */
internal object DeveloperHomeWorkspace {
    private const val MAX_TURNS = 12
    private const val MAX_SAVED_CHARS = 1800
    private const val STATE_TURNS = "developer_home_turns_v1"

    internal class Session(
        val composer: LinearLayout,
        private val activity: Activity,
        private val feed: LinearLayout,
        private val response: TextView,
        private val phase: TextView,
        saved: Bundle?
    ) {
        private val turns = ArrayList<String>()
        private var hasUserTurn = false

        init {
            val restored = saved?.getStringArrayList(STATE_TURNS).orEmpty()
            restored.takeLast(MAX_TURNS).forEach { encoded ->
                if (encoded.length > 2 && encoded[1] == ':' && encoded[0] in "UA") {
                    appendHistorical(encoded[0], encoded.substring(2))
                    turns.add(encoded.take(MAX_SAVED_CHARS + 2))
                    hasUserTurn = true
                }
            }
            feed.addView(response)
        }

        private fun appendHistorical(role: Char, message: String) {
            val user = role == 'U'
            val label = TextView(activity).apply {
                text = (if (user) "You\n" else "Jarvis\n") + message.take(MAX_SAVED_CHARS)
                textSize = 14f
                setTextColor(activity.getColor(R.color.developer_text))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = surface(if (user) 0xFF224453.toInt() else 0xFF112B36.toInt())
            }
            val margin = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10); if (user) leftMargin = dp(26) else rightMargin = dp(14) }
            feed.addView(label, margin)
        }

        /** Called only for a validated, explicit tap on the original chat send control. */
        fun sent(request: String) {
            val task = request.trim().take(MAX_SAVED_CHARS)
            if (task.isEmpty()) return
            // Snapshot the prior rendered answer before reusing the actual workbench
            // response TextView. Follow-ups never invalidate a pending exact diff.
            feed.removeView(response)
            if (hasUserTurn) {
                val prior = response.text.toString().take(MAX_SAVED_CHARS)
                if (prior.isNotBlank()) {
                    appendHistorical('A', prior)
                    turns.add("A:$prior")
                }
            }
            appendHistorical('U', task)
            turns.add("U:$task")
            while (turns.size > MAX_TURNS) turns.removeAt(0)
            hasUserTurn = true
            response.text = "Working on your request…"
            feed.addView(response)
            phase.text = "UNDERSTANDING"
        }

        /** Persists presentation text only. No write authority, diff or API key. */
        fun save(outState: Bundle) {
            outState.putStringArrayList(STATE_TURNS, ArrayList(turns.takeLast(MAX_TURNS)))
        }

        fun reset() {
            turns.clear()
            hasUserTurn = false
            feed.removeAllViews()
            feed.addView(response)
            phase.text = "READY"
        }
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()

    private fun surface(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), 0xFF285666.toInt())
    }

    internal fun stageLabel(message: String): String {
        val s = message.lowercase()
        return when {
            listOf("fail", "reject", "denied", "unavailable", "error", "cancel").any { it in s } ->
                "ATTENTION REQUIRED"
            listOf("passed", "build success", "ci green", "build verified").any { it in s } ->
                "VERIFIED"
            listOf("building", "checking ci", "compil", "running test").any { it in s } ->
                "BUILD / TEST"
            listOf("approval", "review", "diff", "proposal", "preview").any { it in s } ->
                "REVIEW"
            listOf("source", "repository", "reading", "inventory", "inspect").any { it in s } ->
                "READING SOURCE"
            listOf("planning", "analyz", "context").any { it in s } ->
                "ANALYZING"
            else -> "READY"
        }
    }

    /**
     * Preserve every legacy workbench and its onClick listener, but take them
     * out of the primary conversation. They remain in Settings → Advanced.
     */
    fun install(
        activity: Activity,
        home: LinearLayout,
        advanced: LinearLayout,
        prompt: EditText,
        send: Button,
        output: TextView,
        status: TextView,
        review: Button,
        reviewToggle: Button,
        reviewPanel: LinearLayout,
        selectedTarget: Int,
        onTargetChanged: (Int) -> Unit,
        onAdvanced: () -> Unit,
        saved: Bundle?
    ): Session {
        val original = (0 until home.childCount).map { home.getChildAt(it) }
        val header = original.firstOrNull()
            ?: error("The existing Jarvis header must be initialized before Home")
        (send.parent as? ViewGroup)?.removeView(send)
        home.removeAllViews()
        val essentials = setOf(header, prompt, send, output, status, review, reviewToggle, reviewPanel)
        val legacy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            tag = "developer-legacy-home-tools"
        }
        original.filterNot { it in essentials }.forEach { legacy.addView(it) }
        val legacyToggle = Button(activity).apply {
            text = "Additional Home tools"
            isAllCaps = false
            setOnClickListener {
                legacy.visibility = if (legacy.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        advanced.addView(legacyToggle)
        advanced.addView(legacy)

        home.addView(header)
        home.addView(TextView(activity).apply {
            text = "Development workspace"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.developer_text))
            setPadding(0, dp(8), 0, dp(6))
        })
        home.addView(TextView(activity).apply {
            text = "Describe a task, follow its progress and review code here."
            textSize = 13f
            setTextColor(activity.getColor(R.color.developer_secondary))
        })
        val taskCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = surface(0xFF102B36.toInt())
        }
        val phase = TextView(activity).apply {
            tag = "developer-home-task-stage"
            text = stageLabel(status.text.toString())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.developer_primary))
        }
        taskCard.addView(phase)
        taskCard.addView(status)
        home.addView(taskCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14); bottomMargin = dp(14) })
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                phase.text = stageLabel(s.orEmpty().toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val feed = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "developer-home-conversation"
        }
        output.apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = surface(0xFF112B36.toInt())
            setTextIsSelectable(true)
            maxLines = 20
        }
        home.addView(feed)
        val expand = Button(activity).apply {
            text = "Expand response"
            isAllCaps = false
            setOnClickListener {
                val expanded = output.maxLines != Int.MAX_VALUE
                output.maxLines = if (expanded) Int.MAX_VALUE else 20
                text = if (expanded) "Collapse response" else "Expand response"
            }
        }
        home.addView(expand)
        home.addView(review)
        home.addView(reviewToggle)
        home.addView(reviewPanel)
        home.addView(Button(activity).apply {
            text = "Advanced development tools"
            isAllCaps = false
            setOnClickListener { onAdvanced() }
        })

        val composer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "developer-home-sticky-composer"
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = surface(0xFF102B36.toInt())
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(activity).apply {
            text = "Project"
            setTextColor(activity.getColor(R.color.developer_secondary))
            gravity = Gravity.CENTER_VERTICAL
        })
        val choice = Spinner(activity).apply {
            tag = "developer-home-target"
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("J.A.R.V.I.S. Developer", "Personal J.A.R.V.I.S."))
            setSelection(selectedTarget.coerceIn(0, 1))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) { onTargetChanged(position) }
            }
        }
        row.addView(choice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(row)
        (prompt.parent as? ViewGroup)?.removeView(prompt)
        prompt.apply {
            hint = "Ask Jarvis to build, fix, or explain…"
            minLines = 2
            maxLines = 5
        }
        composer.addView(prompt)
        composer.addView(send)
        return Session(composer, activity, feed, output, phase, saved)
    }
}
