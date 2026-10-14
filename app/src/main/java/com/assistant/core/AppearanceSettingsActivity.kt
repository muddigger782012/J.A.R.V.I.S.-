package com.assistant.core

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class AppearanceSettingsActivity : ThemedActivity() {
    private var selectedTab = -1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getInt("appearance_tab") ?: -1
        setContentView(R.layout.activity_appearances)
        findViewById<Button>(R.id.btnCloseAppearances).setOnClickListener { back() }
        findViewById<View>(R.id.appearanceTabs).visibility = View.GONE
        findViewById<View>(R.id.chatDarknessControls).visibility = View.GONE
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        showTab()
    }

    private fun back() { if (selectedTab == -1) finish() else { selectedTab = -1; showTab() } }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("appearance_tab", selectedTab)
        super.onSaveInstanceState(outState)
    }

    private fun chatDarkness(): View {
        val panel = SettingsMenuViews.panel(this)
        panel.addView(SettingsMenuViews.heading(this, "Chat contrast"))
        val label = TextView(this).apply {
            textSize = 16f
            setTextColor(ThemePreferences.color(this@AppearanceSettingsActivity, R.color.jarvis_on_dark))
        }
        panel.addView(label)
        panel.addView(TextView(this).apply {
            text = "0% uses the theme default; 100% is solid black. Saves automatically."
            textSize = 12f
            setTextColor(ThemePreferences.color(this@AppearanceSettingsActivity, R.color.jarvis_on_dark_muted))
        })
        val slider = SeekBar(this).apply { max = 100; progress = ChatAppearancePreferences.darkness(this@AppearanceSettingsActivity) }
        panel.addView(slider)
        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val assistant = TextView(this).apply { text = "J.A.R.V.I.S."; setPadding(16, 12, 16, 12) }
        val user = TextView(this).apply { text = "Your message"; setPadding(16, 12, 16, 12) }
        for (sample in listOf(assistant, user)) {
            sample.setTextColor(FontPreferences.parseColor(FontPreferences.colorHex(this)) ?: ThemePreferences.color(this, R.color.jarvis_on_dark))
            previewRow.addView(sample, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) })
        }
        panel.addView(previewRow)
        fun preview(value: Int) {
            label.text = "Chat box darkness: $value%"
            slider.contentDescription = "Chat box darkness, $value percent"
            assistant.background = ChatAppearancePreferences.background(this, false, value)
            user.background = ChatAppearancePreferences.background(this, true, value)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                preview(progress)
                if (fromUser) ChatAppearancePreferences.save(this@AppearanceSettingsActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        panel.addView((layoutInflater.inflate(R.layout.settings_action_button, null) as MaterialButton).apply {
            text = "Reset chat boxes"; isAllCaps = false
            setOnClickListener { slider.progress = 0; ChatAppearancePreferences.save(this@AppearanceSettingsActivity, 0) }
        })
        preview(slider.progress)
        return ScrollView(this).apply { addView(panel) }
    }

    private fun showTab() {
        val host = findViewById<FrameLayout>(R.id.appearanceContent)
        host.removeAllViews()
        when (selectedTab) {
            0 -> {
                val panel = SettingsMenuViews.panel(this)
                panel.addView(SettingsMenuViews.heading(this, "Themes"))
                ThemePreferences.ids.forEach { id ->
                    val selected = ThemePreferences.selected(this) == id
                    panel.addView(SettingsMenuViews.row(this, ThemePreferences.label(id), if (selected) "Active theme" else "Apply this theme", R.drawable.ic_tools_settings) {
                        if (!selected) { ThemePreferences.save(this, id); recreate() }
                    })
                }
                host.addView(ScrollView(this).apply { addView(panel) })
            }
            1 -> host.addView(fontControls())
            2 -> host.addView(chatDarkness())
            else -> {
                val panel = SettingsMenuViews.panel(this)
                panel.addView(SettingsMenuViews.heading(this, "Personalize"))
                fun choice(title: String, detail: String, page: Int) {
                    panel.addView(SettingsMenuViews.row(this, title, detail, R.drawable.ic_tools_settings) { selectedTab = page; showTab() })
                }
                choice("Themes", "Current: " + ThemePreferences.label(ThemePreferences.selected(this)), 0)
                choice("Fonts", "Text size, color and a live preview", 1)
                choice("Chat contrast", "Message box darkness and preview", 2)
                host.addView(ScrollView(this).apply { addView(panel) })
            }
        }
        FontPreferences.applyTo(host, this)
    }

    private fun fontControls(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.glass_panel_surface)
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size
            setTextColor(ThemePreferences.color(this@AppearanceSettingsActivity, R.color.jarvis_on_dark))
            panel.addView(this)
        }
        fun button(value: String, action: () -> Unit) = (layoutInflater.inflate(R.layout.settings_action_button, null) as MaterialButton).apply {
            text = value; isAllCaps = false
            panel.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { action() }
        }
        label("Fonts", 22f)
        label("Adjust chat and interface text. Size is relative to your phone’s font setting. Changes apply after saving.", 14f)
        val sizeLabel = label("Font size: ${FontPreferences.percent(this)}%")
        val slider = SeekBar(this).apply { max = 15; progress = (FontPreferences.percent(this@AppearanceSettingsActivity) - 75) / 5 }
        panel.addView(slider)
        label("Text color")
        label("Leave blank to use each theme’s original text colors, or enter #RRGGBB.", 14f)
        val colorInput = EditText(this).apply {
            setSingleLine(true)
            hint = "Theme default or #RRGGBB"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(FontPreferences.colorHex(this@AppearanceSettingsActivity))
        }
        panel.addView(colorInput)
        val names = arrayOf("Theme default", "White", "Cyan", "Green", "Amber", "Lavender")
        val colors = arrayOf("", "#FFFFFF", "#8DEFFF", "#5DFF99", "#FFD166", "#C4B5FD")
        button("Choose a color") {
            ThemedDialogBuilder(this).setTitle("Font color").setItems(names) { _, which -> colorInput.setText(colors[which]) }.show()
        }
        label("Preview", 14f)
        val preview = label("J.A.R.V.I.S. is ready.\nYour chat messages will look like this.", 16f).apply {
            setTag(R.id.font_preview_exempt, true)
            setPadding(16, 24, 16, 24)
            setBackgroundResource(R.drawable.chat_bubble_assistant)
        }
        val save = button("Save fonts") {
            FontPreferences.save(this, 75 + slider.progress * 5, colorInput.text.toString().trim())
            recreate()
        }
        fun updatePreview() {
            val percent = 75 + slider.progress * 5
            sizeLabel.text = "Font size: $percent%"
            slider.contentDescription = "Font size, $percent percent"
            preview.textSize = 16f * percent / 100f
            val hex = colorInput.text.toString().trim()
            val parsed = FontPreferences.parseColor(hex)
            val valid = hex.isEmpty() || parsed != null
            colorInput.error = if (valid) null else "Use six hex digits, for example #8DEFFF"
            save.isEnabled = valid
            preview.setTextColor(parsed ?: ThemePreferences.color(this, R.color.jarvis_on_dark))
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updatePreview()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        colorInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        button("Reset fonts to theme defaults") { FontPreferences.save(this, 100, ""); recreate() }
        updatePreview()
        return ScrollView(this).apply { addView(panel) }
    }
}
