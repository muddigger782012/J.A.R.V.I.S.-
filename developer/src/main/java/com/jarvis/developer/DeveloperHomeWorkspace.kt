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
import android.widget.ScrollView
import android.text.TextUtils
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * One chat-first Home surface over the EXISTING source, generation, review and
 * task engines. This class only arranges their original Android views. It is
 * deliberately not another patch engine, GitHub writer or source of approval.
 */
internal object DeveloperHomeWorkspace {
    private const val MAX_TURNS = 12
    private const val MAX_SAVED_CHARS = 1800
    private const val STATE_TURNS = "developer_home_turns_v1"
    private const val STATE_LAST_REPLY = "developer_home_last_reply_v1"

    /**
     * The existing authenticated inbox is the authority for job progress.
     * This projection is read-only and never upgrades a pending gate to passed.
     */
    internal fun latestJobEvidence(jobs: List<JSONObject>): String? {
        val job = jobs.lastOrNull() ?: return null
        val summary = job.optString("summary", "Development task")
            .replace(Regex("\\s+"), " ").trim().take(110)
        val state = job.optString("state", "UNKNOWN").take(40)
        val verification = job.optJSONObject("verification")
        val gates = listOf(
            "SOURCE_COMMITTED" to "Source",
            "BUILD_SUCCESSFUL" to "Build",
            "TESTS_PASSED" to "Tests",
            "SIGNED_APK" to "Signing",
            "DEVICE_INSTALLED" to "Installed"
        ).joinToString(" · ") { (key, label) ->
            "$label: " + (verification?.optString(key)?.takeIf {
                it in setOf("PASSED", "FAILED", "RUNNING", "PENDING", "BLOCKED", "UNKNOWN", "NOT_RUN")
            } ?: "Unverified")
        }
        return "$summary\n$state\n$gates"
    }

    internal class Session(
        val composer: LinearLayout,
        private val activity: Activity,
        private val feed: LinearLayout,
        private val response: TextView,
        private val phase: TextView,
        private val taskCard: LinearLayout,
        private val jobEvidence: TextView,
        private val jobDetails: Button,
        private val prepareNextButton: Button?,
        private val targetPicker: Spinner,
        private val branchLabel: TextView,
        saved: Bundle?
    ) {
        private val turns = ArrayList<String>()
        private var hasUserTurn = false
        private var readingJob = false
        private val liveReply = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "developer-home-live-response"
            addView(TextView(activity).apply {
                text = "J.A.R.V.I.S."
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activity.getColor(R.color.developer_primary))
                setPadding(dp(10), dp(8), 0, dp(3))
            })
            addView(response)
        }

        private fun scrollToLatest() {
            feed.post {
                var ancestor: android.view.ViewParent? = feed.parent
                while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
                (ancestor as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }

        /** Read existing private job receipts without contacting a provider or GitHub. */
        fun refreshJobEvidence() {
            if (readingJob || activity.isFinishing || activity.isDestroyed) return
            readingJob = true
            thread(name = "developer-home-job-read", isDaemon = true) {
                val summary = runCatching {
                    val store = DevelopmentJobInbox(File(activity.noBackupFilesDir, "development-inbox"))
                    latestJobEvidence(store.localSnapshot())
                }.getOrNull()
                activity.runOnUiThread {
                    readingJob = false
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    jobEvidence.text = summary.orEmpty()
                    jobEvidence.visibility = if (summary == null) View.GONE else View.VISIBLE
                    jobDetails.visibility = if (summary == null) View.GONE else View.VISIBLE
                    if (summary != null) taskCard.visibility = View.VISIBLE
                }
            }
        }

        init {
            val restored = saved?.getStringArrayList(STATE_TURNS).orEmpty()
            restored.takeLast(MAX_TURNS).forEach { encoded ->
                if (encoded.length > 2 && encoded[1] == ':' && encoded[0] in "UA") {
                    appendHistorical(encoded[0], encoded.substring(2))
                    turns.add(encoded.take(MAX_SAVED_CHARS + 2))
                    hasUserTurn = true
                }
            }
            // The workbench's live reply TextView is otherwise initialized with
            // its default placeholder again on Activity recreation. Restore only
            // bounded display content, never approval, a source diff or a job lease.
            saved?.getString(STATE_LAST_REPLY)?.take(MAX_SAVED_CHARS)?.let { last ->
                response.text = if (last.startsWith("Working on your request")) {
                    "Previous request was interrupted. Resubmit to continue; no code change was approved."
                } else last
            }
            feed.addView(liveReply)
            response.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (hasUserTurn) scrollToLatest()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        private fun appendHistorical(role: Char, message: String) {
            val user = role == 'U'
            // Compact thread cards: user tasks and Developer responses are visually
            // distinct, selectable, and remain in one continuous conversation.
            val bubble = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                tag = if (user) "developer-chat-user-turn" else "developer-chat-agent-turn"
                setPadding(dp(11), dp(8), dp(11), dp(10))
                background = surface(if (user) 0xFF214453.toInt() else 0xFF112B36.toInt())
            }
            bubble.addView(TextView(activity).apply {
                text = if (user) "YOU" else "J.A.R.V.I.S."
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activity.getColor(if (user)
                    R.color.developer_text else R.color.developer_primary))
            })
            bubble.addView(TextView(activity).apply {
                text = message.take(MAX_SAVED_CHARS)
                textSize = 14f
                setTextIsSelectable(true)
                setTextColor(activity.getColor(R.color.developer_text))
                setPadding(0, dp(3), 0, 0)
            })
            val margin = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
                if (user) leftMargin = dp(22) else rightMargin = dp(14)
            }
            feed.addView(bubble, margin)
        }

        /** Called only for a validated, explicit tap on the original chat send control. */
        fun sent(request: String) {
            setPrepareNextAvailable(false)
            val task = request.trim().take(MAX_SAVED_CHARS)
            if (task.isEmpty()) return
            // Snapshot the prior rendered answer before reusing the actual workbench
            // response TextView. Follow-ups never invalidate a pending exact diff.
            feed.removeView(liveReply)
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
            feed.addView(liveReply)
            scrollToLatest()
            phase.text = "UNDERSTANDING"
            taskCard.visibility = View.VISIBLE
        }

        /** Ready only after a fresh on-device diagnosis. Never restored after restart. */
        fun setPrepareNextAvailable(eligible: Boolean) {
            prepareNextButton?.isEnabled = eligible
            prepareNextButton?.visibility = if (eligible) View.VISIBLE else View.GONE
        }

        fun syncTarget(index: Int) {
            val next = index.coerceIn(0, 1)
            if (next != 0) setPrepareNextAvailable(false)
            if (targetPicker.selectedItemPosition != next) targetPicker.setSelection(next)
            branchLabel.text = if (next == 0) "feature/jarvis-developer · Developer"
                else "main · Personal J.A.R.V.I.S."
        }

        /** Persists presentation text only. No write authority, diff or API key. */
        fun save(outState: Bundle) {
            outState.putStringArrayList(STATE_TURNS, ArrayList(turns.takeLast(MAX_TURNS)))
            outState.putString(STATE_LAST_REPLY,
                response.text.toString().take(MAX_SAVED_CHARS))
        }

        fun reset() {
            setPrepareNextAvailable(false)
            turns.clear()
            hasUserTurn = false
            response.text = "Your assistant’s response will appear here."
            feed.removeAllViews()
            feed.addView(liveReply)
            phase.text = "READY"
            taskCard.visibility = if (jobEvidence.visibility == View.VISIBLE) View.VISIBLE else View.GONE
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
        codingStatus: TextView,
        review: Button,
        reviewToggle: Button,
        reviewPanel: LinearLayout,
        selectedTarget: Int,
        onTargetChanged: (Int) -> Unit,
        onAdvanced: () -> Unit,
        saved: Bundle?,
        onRuns: (() -> Unit)? = null,
        onNewTask: (() -> Unit)? = null,
        onTaskNext: (() -> Unit)? = null,
        onPrepareNext: (() -> Unit)? = null
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
        // Compact repository/status header in the style of an IDE task workspace.
        // The existing Stark header and persistent signed-build safeguards remain.
        val workspaceBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "developer-workspace-toolbar"
            setPadding(dp(2), dp(8), dp(2), dp(7))
        }
        val projectIdentity = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = "DEVELOPER WORKSPACE"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activity.getColor(R.color.developer_primary))
            })
            addView(TextView(activity).apply {
                text = "J.A.R.V.I.S. Developer"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activity.getColor(R.color.developer_text))
            })
        }
        val branchLabel = TextView(activity).apply {
            tag = "developer-workspace-branch"
            text = if (selectedTarget == 0) "feature/jarvis-developer · Developer"
                else "main · Personal J.A.R.V.I.S."
            textSize = 11f
            setTextColor(activity.getColor(R.color.developer_secondary))
        }
        projectIdentity.addView(branchLabel)
        workspaceBar.addView(projectIdentity, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onNewTask != null) workspaceBar.addView(Button(activity).apply {
            text = "New"
            isAllCaps = false
            contentDescription = "Start a new development task after safety checks"
            setTextColor(activity.getColor(R.color.developer_primary))
            background = surface(0xFF132F3B.toInt())
            setOnClickListener { onNewTask() }
        })
        if (onRuns != null) workspaceBar.addView(Button(activity).apply {
            text = "Runs"
            isAllCaps = false
            contentDescription = "Open development task history and build runs"
            setTextColor(activity.getColor(R.color.developer_primary))
            background = surface(0xFF132F3B.toInt())
            setOnClickListener { onRuns() }
        })
        home.addView(workspaceBar)
        val taskCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(8))
            background = surface(0xFF102B36.toInt())
            tag = "developer-home-task-summary"
        }
        val phase = TextView(activity).apply {
            tag = "developer-home-task-stage"
            text = stageLabel(status.text.toString())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activity.getColor(R.color.developer_primary))
        }
        val stageRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stageRow.addView(phase)
        status.apply {
            textSize = 12f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(activity.getColor(R.color.developer_secondary))
            setPadding(dp(10), 0, 0, 0)
        }
        stageRow.addView(status, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onTaskNext != null) stageRow.addView(Button(activity).apply {
            text = "Next"
            textSize = 12f
            isAllCaps = false
            minHeight = dp(40)
            tag = "developer-task-next"
            contentDescription = "Diagnose next Developer task action from saved local evidence"
            setTextColor(activity.getColor(R.color.developer_primary))
            background = surface(0xFF132F3B.toInt())
            setOnClickListener { onTaskNext() }
        })
        taskCard.addView(stageRow)
        val prepareNextButton = if (onPrepareNext == null) null else Button(activity).apply {
            text = "Prepare next diff"
            textSize = 12f
            isAllCaps = false
            isEnabled = false
            visibility = View.GONE
            tag = "developer-prepare-next-diff"
            contentDescription = "Generate a new source-scoped Developer diff preview for the eligible saved plan step"
            setTextColor(activity.getColor(R.color.developer_primary))
            background = surface(0xFF132F3B.toInt())
            setOnClickListener { if (isEnabled) onPrepareNext() }
        }
        if (prepareNextButton != null) taskCard.addView(prepareNextButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = dp(5)
            })
        taskCard.visibility = if (stageLabel(status.text.toString()) == "READY" && codingStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
        home.addView(taskCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4); bottomMargin = dp(10) })
        status.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val label = stageLabel(s?.toString().orEmpty())
                phase.text = label
                if (label != "READY") taskCard.visibility = View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        // Show the real patch engine and CI status without opening another page.
        // The detailed diff and controls remain the existing inline review.
        val codingEvidence = TextView(activity).apply {
            tag = "developer-home-build-evidence"
            textSize = 12f
            setTextColor(activity.getColor(R.color.developer_primary))
            visibility = if (codingStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
            text = codingStatus.text
        }
        taskCard.addView(codingEvidence)
        val persistedJob = TextView(activity).apply {
            tag = "developer-home-persisted-job-evidence"
            textSize = 12f
            setTextColor(activity.getColor(R.color.developer_secondary))
            visibility = View.GONE
        }
        taskCard.addView(persistedJob)
        persistedJob.apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val jobDetails = Button(activity).apply {
            text = "Show run details"
            isAllCaps = false
            visibility = View.GONE
            contentDescription = "Expand saved development task verification details"
            setOnClickListener {
                val expanded = persistedJob.maxLines == Int.MAX_VALUE
                persistedJob.maxLines = if (expanded) 3 else Int.MAX_VALUE
                persistedJob.ellipsize = if (expanded) TextUtils.TruncateAt.END else null
                text = if (expanded) "Show run details" else "Hide run details"
            }
        }
        taskCard.addView(jobDetails)
        codingStatus.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val detail = s?.toString().orEmpty().take(500)
                codingEvidence.text = detail
                codingEvidence.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
                if (detail.isNotBlank()) {
                    phase.text = stageLabel(detail)
                    taskCard.visibility = View.VISIBLE
                }
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
            maxLines = Int.MAX_VALUE
        }
        home.addView(feed)
        // Routine conversation has no stack of Analyze/Review/Advanced buttons.
        // The reviewed proposal actions appear only when there is a real diff.
        review.visibility = if (review.isEnabled) View.VISIBLE else View.GONE
        reviewToggle.visibility = View.GONE
        home.addView(review)
        home.addView(reviewToggle)
        home.addView(reviewPanel)

        val composer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "developer-home-sticky-composer"
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = surface(0xFF102B36.toInt())
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "developer-workspace-target-row"
        }
        row.addView(TextView(activity).apply {
            text = "TARGET"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(6), 0)
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
                ) {
                    branchLabel.text = if (position == 0) "feature/jarvis-developer · Developer"
                        else "main · Personal J.A.R.V.I.S."
                    onTargetChanged(position)
                }
            }
        }
        row.addView(choice, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(row)
        (prompt.parent as? ViewGroup)?.removeView(prompt)
        prompt.apply {
            // Retain the original accessibility/hint identity used by saved
            // drafts and screen readers; the workspace heading supplies context.
            minLines = 2
            maxLines = 5
            setTextSize(14f)
            setPadding(dp(12), dp(10), dp(12), dp(9))
            background = surface(0xFF173744.toInt())
        }
        composer.addView(prompt)

        // A single compact action strip replaces stacked full-width action
        // buttons. MainActivity adds its EXISTING picker/removal handlers here.
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "developer-home-composer-actions"
            setPadding(0, dp(3), 0, 0)
        }
        // Keep the original workbench click target as a direct child so its
        // exact pending/cancellation/approval logic remains untouched.
        send.visibility = View.GONE
        composer.addView(send)
        actions.addView(Button(activity).apply {
            text = "Send"
            isAllCaps = false
            contentDescription = "Send current task to J.A.R.V.I.S. Developer"
            setTextColor(activity.getColor(R.color.developer_text))
            background = surface(0xFF17637B.toInt())
            minHeight = dp(42)
            setOnClickListener { if (send.isEnabled) send.performClick() }
        }, LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT))
        composer.addView(actions)
        return Session(composer, activity, feed, output, phase, taskCard,
            persistedJob, jobDetails, prepareNextButton, choice, branchLabel, saved)
    }
}
