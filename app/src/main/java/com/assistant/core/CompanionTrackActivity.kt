package com.assistant.core

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.assistant.core.services.DeveloperBridgeOperations
import com.assistant.core.services.GoalPlanStore
import com.assistant.core.services.PersonalStateFile
import com.assistant.core.services.ResearchBriefs
import com.google.android.material.button.MaterialButton

/** Read-only Companion dashboard. No implicit approval, job execution or network calls on the UI thread. */
class CompanionTrackActivity : MenuActivity("Companion Track", 0) {
    private lateinit var content: LinearLayout
    private var refreshGeneration = 0
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(ThemePreferences.color(this@CompanionTrackActivity, R.color.jarvis_bg_deep))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "J.A.R.V.I.S.  /  TRACK"
            textSize = 21f
            setTextColor(ThemePreferences.color(this@CompanionTrackActivity, R.color.jarvis_primary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(MaterialButton(this).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        root.addView(header)
        root.addView(MaterialButton(this).apply {
            text = "Refresh status"
            isAllCaps = false
            setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(MaterialButton(this).apply {
            text = "Open Jarvis Developer"
            isAllCaps = false
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage("com.jarvis.developer")
                if (launch != null) {
                    startActivity(launch)
                } else {
                    android.widget.Toast.makeText(this@CompanionTrackActivity,
                        "Jarvis Developer is not installed or cannot be opened.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized) refresh() }

    private fun section(title: String, details: String): TextView {
        content.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(ThemePreferences.color(this@CompanionTrackActivity, R.color.jarvis_primary))
            setPadding(0, dp(16), 0, dp(4))
        })
        val detailsView = TextView(this).apply {
            text = details
            textSize = 15f
            setTextColor(ThemePreferences.color(this@CompanionTrackActivity, R.color.jarvis_on_dark))
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(detailsView)
        return detailsView
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        content.removeAllViews()
        val goals = GoalPlanStore(this)
        val pending = runCatching { goals.pending() }.getOrNull()
        val active = runCatching { goals.current() }.getOrNull()
        section("GOALS", when {
            pending != null -> "Pending your approval: ${pending.kind.title} — ${pending.actions.size} steps. Review and approve in chat."
            active != null -> "Current: ${active.kind.title} — ${active.actions.size} steps. Ask for goal plan status in chat."
            else -> "No current goal plan. Create one in chat."
        })
        val research = runCatching {
            ResearchBriefs(PersonalStateFile(this, "research-brief.json")).status()
        }.getOrElse { "Research status unavailable." }
        section("RESEARCH", research)
        val developmentDetails = section("DEVELOPMENT", "Checking linked Developer status…")
        section("CONVERSATION", "Return to chat for instructions, approvals and voice interaction. Track never performs actions automatically.")
        // The bridge may require IPC; query it away from the Android main thread.
        Thread {
            val status = runCatching { DeveloperBridgeOperations(applicationContext).status() }
                .getOrElse { "Developer status unavailable: ${it.message ?: "connection not ready"}" }
            runOnUiThread {
                if (generation == refreshGeneration && !isFinishing && !isDestroyed) {
                    developmentDetails.text = status.take(3000)
                }
            }
        }.start()
    }
}
