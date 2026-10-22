package com.assistant.core

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Disposable, themed UI for real Accessibility actions; excluded from release and Beta. */
class ScreenControlFixtureActivity : ThemedActivity() {
    var tapCount = 0; private set
    var taskTapCount = 0; private set
    lateinit var output: TextView; private set
    lateinit var scroll: ScrollView; private set
    private lateinit var panel: LinearLayout
    fun changeContext() { output.text = "Changed fixture context" }
    private fun flowLabel(value: String) = TextView(this).apply {
        text = value
        setTextColor(SettingsMenuViews.color(this@ScreenControlFixtureActivity, R.attr.jarvis_on_dark))
        minimumHeight = SettingsMenuViews.dp(this@ScreenControlFixtureActivity, 48)
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }
    private fun page(title: String) {
        panel.removeAllViews(); panel.addView(flowLabel(title)); scroll.scrollTo(0, 0)
    }
    fun showTaskStart() {
        taskTapCount = 0; page("Start page")
        panel.addView(Button(this).apply {
            text = "Menu"
            setOnClickListener {
                taskTapCount++; page("Menu page")
                panel.addView(Button(this@ScreenControlFixtureActivity).apply {
                    text = "Downloads"
                    setOnClickListener { taskTapCount++; page("Downloads page") }
                })
            }
        })
    }
    fun showTaskAccounts() {
        taskTapCount = 0; page("Accounts page")
        for (section in listOf("Work", "Personal")) panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(flowLabel(section))
            addView(Button(this@ScreenControlFixtureActivity).apply {
                text = "Account"
                setOnClickListener { taskTapCount++; page("$section account page") }
            })
        })
        panel.addView(Button(this).apply { text = "Unavailable"; isEnabled = false })
    }
    fun showSensitiveContent() {
        page("Sensitive screen")
        panel.addView(TextView(this).apply {
            text = "private-fixture-sensitive-content"
            if (android.os.Build.VERSION.SDK_INT >= 34) setAccessibilityDataSensitive(android.view.View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        })
    }
    fun showPassword() {
        panel.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText("private-fixture-password")
            isFocusable = false
            setTextColor(SettingsMenuViews.color(this@ScreenControlFixtureActivity, R.attr.jarvis_on_dark))
        }, 0)
        scroll.scrollTo(0, 0)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SettingsMenuViews.color(this@ScreenControlFixtureActivity, R.attr.jarvis_bg_deep))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(SettingsMenuViews.color(this@ScreenControlFixtureActivity, R.attr.jarvis_on_dark))
            minimumHeight = SettingsMenuViews.dp(this@ScreenControlFixtureActivity, 48)
        }
        panel.addView(label("Screen control fixture"))
        output = label("No sample tap yet"); panel.addView(output)
        panel.addView(Button(this).apply {
            text = "Practice button"
            setOnClickListener { tapCount++; output.text = "Sample tap count $tapCount" }
        })
        panel.addView(LinearLayout(this).apply {
            addView(label("Nested sample"))
            isClickable = true
            setOnClickListener { tapCount++; output.text = "Sample tap count $tapCount" }
        })
        repeat(2) { panel.addView(Button(this).apply { text = "Duplicate sample"; setOnClickListener { tapCount++ } }) }
        repeat(30) { panel.addView(label("Sample row ${it + 1}")) }
        scroll = ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
    }
}
