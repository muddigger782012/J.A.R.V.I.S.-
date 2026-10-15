package com.jarvis.developer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** First standalone shell. No credentials or autonomous execution in the APK. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF101820.toInt())
        }
        root.addView(TextView(this).apply {
            text = "JARVIS DEVELOPER"
            textSize = 26f
            setTextColor(0xFFE9F3FA.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Hybrid autonomous mode\nDevelopment shell — secure agent connection pending"
            textSize = 16f
            setTextColor(0xFFB8C7D3.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })
        setContentView(root)
    }
}
