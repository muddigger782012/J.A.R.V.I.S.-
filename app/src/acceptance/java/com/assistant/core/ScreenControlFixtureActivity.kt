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
    lateinit var output: TextView; private set
    lateinit var scroll: ScrollView; private set
    private lateinit var panel: LinearLayout
    fun changeContext() { output.text = "Changed fixture context" }
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
