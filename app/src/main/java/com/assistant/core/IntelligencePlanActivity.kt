package com.assistant.core

import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import com.assistant.core.services.IntelligencePlanRepository
import kotlin.concurrent.thread

class IntelligencePlanActivity : ThemedActivity() {
    private val repository by lazy { IntelligencePlanRepository(this) }
    private var checking = false
    private var status = "Saved plan available offline."
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        val plan = repository.snapshot()
        val panel = SettingsMenuViews.panel(this)
        panel.addView(SettingsMenuViews.row(this, "Back", icon = R.drawable.ic_search_open) { finish() })
        panel.addView(SettingsMenuViews.heading(this, "Intelligence Plan").apply { textSize = 24f })
        fun text(value: String) { panel.addView(TextView(this).apply {
            text = value; textSize = 14f; setPadding(0, SettingsMenuViews.dp(this@IntelligencePlanActivity, 8), 0, SettingsMenuViews.dp(this@IntelligencePlanActivity, 8))
            setTextColor(SettingsMenuViews.color(this@IntelligencePlanActivity, R.attr.jarvis_on_dark))
            setTextIsSelectable(true)
        }) }
        text("≈${plan.overall}% estimated scope completion\nPlan revision ${plan.revision} · ${plan.updated}\nPublished evidence through ${plan.throughVersion}")
        text("Installed app: " + packageManager.getPackageInfo(packageName, 0).versionName)
        text(plan.note)
        text(status)
        panel.addView(SettingsMenuViews.row(this, if (checking) "Checking plan updates…" else "Check plan updates",
            "Fetch the public progress report; sends no chat or device data", R.drawable.ic_composer_tools) {
            if (!checking) {
                checking = true; render()
                thread {
                    val result = runCatching { repository.refresh() }.getOrDefault("Couldn't check plan updates. Your saved plan remains available offline.")
                    runOnUiThread { if (!isFinishing && !isDestroyed) { status = result; checking = false; render() } }
                }
            }
        }.apply { isEnabled = !checking })
        panel.addView(SettingsMenuViews.heading(this, "Workstreams"))
        plan.workstreams.forEach { row ->
            panel.addView(SettingsMenuViews.row(this, "${row.title} · ${row.percent}%", row.status, R.drawable.ic_jarvis_core) {
                ThemedDialogBuilder(this).setTitle(row.title)
                    .setMessage("${row.percent}% engineering scope estimate\n${row.status}\n\nImplemented\n${row.implemented}\n\nRemaining\n${row.remaining}")
                    .setPositiveButton("Close", null).show()
            })
            panel.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = row.percent
                progressTintList = ColorStateList.valueOf(SettingsMenuViews.color(this@IntelligencePlanActivity, R.attr.jarvis_primary))
                progressBackgroundTintList = ColorStateList.valueOf(SettingsMenuViews.color(this@IntelligencePlanActivity, R.attr.jarvis_on_dark_muted))
                contentDescription = "${row.title}, ${row.percent} percent estimated completion"
            })
        }
        panel.addView(SettingsMenuViews.heading(this, "Verification notes")); text(plan.milestone)
        text("In chat, ask: 'Show intelligence plan progress', then 'What's left?' or 'What about the capability builder?' Updating this report does not install or approve features.")
        setContentView(SettingsMenuViews.screen(this, panel)); FontPreferences.applyTo(panel, this)
    }
}
