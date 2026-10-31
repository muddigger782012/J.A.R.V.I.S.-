package com.jarvis.developer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.*

/** Native interactive design review; never performs repository writes or builds. */
object BuildArchitectPanel {
    private const val PREFS = "developer_build_architect_v1"
    private const val CYAN = 0xFF4EDDF3.toInt()

    fun attach(activity: Activity, parent: LinearLayout) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = InteractiveBuildArchitect.defaults()
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF0C202C.toInt())
        }
        parent.addView(section)
        fun label(value: String): TextView = TextView(activity).apply {
            text = value
            setTextColor(CYAN)
            textSize = 16f
            contentDescription = value
            section.addView(this)
        }
        label("INTERACTIVE BUILD ARCHITECT")
        label("Review options before generating an application. Existing Jarvis styling is preserved.")
        var navigation = prefs.getString("navigation", defaults.navigation.name) ?: defaults.navigation.name
        var density = prefs.getString("density", defaults.density.name) ?: defaults.density.name
        var consultation = prefs.getString("consultation", defaults.consultation.name) ?: defaults.consultation.name
        var scale = prefs.getInt("scale", defaults.scalePercent).coerceIn(80, 130)
        var recommendations = prefs.getBoolean("recommendations", defaults.proactiveRecommendations)
        var screen = "PHONE"
        val preview = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xFF173A4A.toInt())
            contentDescription = "Live layout preview"
        }
        fun render() {
            val width = if (screen == "PHONE") 360 else 840
            preview.text = "J.A.R.V.I.S. | " + screen + " (" + width + " dp)\n" +
                "Menu: " + navigation + " | Density: " + density + " | Scale: " + scale + "%\n" +
                "Projects  |  Build  |  Tools  |  Settings\n" +
                "Recommendation: a drawer accommodates expanding tools."
        }
        fun selector(title: String, options: List<String>, saved: String, onSelect: (String) -> Unit) {
            label(title)
            section.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
                contentDescription = title
                setSelection(options.indexOf(saved).coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        onSelect(options[position])
                    }
                }
            })
        }
        selector("Consultation mode", listOf("COLLABORATIVE", "AUTOMATIC", "MANUAL"), consultation) { consultation = it }
        selector("Navigation", listOf("DRAWER", "TABS"), navigation) { navigation = it; render() }
        selector("Information density", listOf("BALANCED", "COMPACT", "SPACIOUS"), density) { density = it; render() }
        selector("Preview device", listOf("PHONE", "TABLET"), screen) { screen = it; render() }
        label("Interface scale: 80–130%")
        section.addView(SeekBar(activity).apply {
            max = 10
            progress = ((scale - 80) / 5).coerceIn(0, 10)
            contentDescription = "Interface scale"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    scale = 80 + value * 5
                    render()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        })
        section.addView(CheckBox(activity).apply {
            text = "Proactive design recommendations"
            isChecked = recommendations
            setOnCheckedChangeListener { _, checked -> recommendations = checked }
        })
        section.addView(preview)
        render()
        val feedback = label("Changes are local until approved.")
        section.addView(Button(activity).apply {
            text = "Approve design preferences"
            contentDescription = "Save approved Build Architect preferences"
            setOnClickListener {
                try {
                    val config = InteractiveBuildArchitect.Configuration(
                        consultation = InteractiveBuildArchitect.Consultation.valueOf(consultation),
                        navigation = InteractiveBuildArchitect.Navigation.valueOf(navigation),
                        density = InteractiveBuildArchitect.Density.valueOf(density),
                        scalePercent = scale,
                        proactiveRecommendations = recommendations
                    )
                    val saved = prefs.edit()
                        .putString("consultation", config.consultation.name)
                        .putString("navigation", config.navigation.name)
                        .putString("density", config.density.name)
                        .putInt("scale", config.scalePercent)
                        .putBoolean("recommendations", config.proactiveRecommendations)
                        .commit()
                    feedback.text = if (saved) "Approved preferences saved on this device." else
                        "Could not save preferences. Retry."
                } catch (_: IllegalArgumentException) {
                    feedback.text = "Invalid selection. No changes saved."
                }
            }
        })
    }
}
