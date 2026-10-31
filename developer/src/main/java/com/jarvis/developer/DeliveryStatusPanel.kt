package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Project-scoped capability review with durable, evidence-gated verification. */
object DeliveryStatusPanel {
    private const val CYAN = 0xFF4EDDF3.toInt()
    private const val DARK = 0xFF0C202C.toInt()

    fun attach(activity: Activity, parent: LinearLayout) {
        val prefs = activity.getSharedPreferences("developer_delivery_v2", Context.MODE_PRIVATE)
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(DARK)
        }
        parent.addView(section)
        fun label(textValue: String): TextView = TextView(activity).apply {
            text = textValue
            setTextColor(Color.WHITE)
            textSize = 14f
            section.addView(this)
        }
        label("DEVELOPER DELIVERY GATES — 24 REQUIREMENTS").setTextColor(CYAN)
        label("Track real implementation. Verification requires a linked test or build result.")
        val project = EditText(activity).apply {
            hint = "Project ID (letters, numbers, . _ -)"
            setSingleLine(true)
            setText(prefs.getString("active_project", "jarvis-developer"))
        }
        section.addView(project)
        val summary = label("")
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        section.addView(rows)
        fun projectKey(): String? = project.text.toString().trim().takeIf {
            it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
        }
        fun render() {
            rows.removeAllViews()
            val key = projectKey()
            if (key == null) {
                summary.text = "Enter a valid project ID to view or edit progress."
                return
            }
            prefs.edit().putString("active_project", key).apply()
            val items = DevelopmentDeliveryGate.baseline.map { item ->
                val saved = prefs.getString("${key}.${item.id}.state", "NOT_STARTED")
                val state = DevelopmentDeliveryGate.State.values().firstOrNull { it.name == saved }
                    ?: DevelopmentDeliveryGate.State.NOT_STARTED
                val evidence = prefs.getString("${key}.${item.id}.evidence", null)
                if ((state == DevelopmentDeliveryGate.State.VERIFIED ||
                        state == DevelopmentDeliveryGate.State.RELEASED) &&
                    evidence?.startsWith("https://") != true) item
                else item.copy(state = state, evidenceUrl = evidence)
            }
            summary.text = "Project: $key | Verified: ${DevelopmentDeliveryGate.verifiedCount(items)}/24 | " +
                if (DevelopmentDeliveryGate.readyToPublish(items)) "Delivery gates passed" else "Not ready to publish"
            items.forEach { item ->
                val itemRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                rows.addView(itemRow)
                itemRow.addView(TextView(activity).apply {
                    text = "${item.id}. ${item.name} — ${item.state.name.replace('_', ' ')}"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                })
                val evidence = EditText(activity).apply {
                    hint = "Verification evidence URL (https://...)"
                    setSingleLine(true)
                    setText(item.evidenceUrl.orEmpty())
                    textSize = 12f
                }
                itemRow.addView(evidence)
                val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                itemRow.addView(buttons)
                fun action(caption: String, state: DevelopmentDeliveryGate.State) {
                    buttons.addView(Button(activity).apply {
                        text = caption
                        textSize = 11f
                        setOnClickListener {
                            val url = evidence.text.toString().trim()
                            val updated = try {
                                item.copy(state = state, evidenceUrl = url.takeIf { it.isNotEmpty() })
                            } catch (_: IllegalArgumentException) {
                                summary.text = "Valid HTTPS evidence is required before verifying ${item.name}."
                                return@setOnClickListener
                            }
                            prefs.edit()
                                .putString("${key}.${item.id}.state", updated.state.name)
                                .putString("${key}.${item.id}.evidence", updated.evidenceUrl)
                                .apply()
                            render()
                        }
                    })
                }
                action("Working", DevelopmentDeliveryGate.State.IMPLEMENTING)
                action("Integrated", DevelopmentDeliveryGate.State.INTEGRATED)
                action("Verified", DevelopmentDeliveryGate.State.VERIFIED)
            }
        }
        section.addView(Button(activity).apply {
            text = "Load project progress"
            setOnClickListener { render() }
        }, 2)
        render()
    }
}
