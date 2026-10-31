package com.jarvis.developer

import android.app.Activity
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView

/** Read-only, deliberately conservative overview of the complete delivery contract. */
object DeliveryStatusPanel {
    fun attach(activity: Activity, parent: LinearLayout) {
        val section = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF0C202C.toInt())
        }
        parent.addView(section)
        section.addView(TextView(activity).apply {
            text = "DEVELOPER DELIVERY GATES — 24 REQUIREMENTS"
            textSize = 16f
            setTextColor(0xFF4EDDF3.toInt())
        })
        section.addView(TextView(activity).apply {
            text = "Verification is evidence-based. A source commit is not a released feature."
            setTextColor(Color.WHITE)
        })
        DevelopmentDeliveryGate.baseline.forEach { capability ->
            section.addView(TextView(activity).apply {
                text = "${capability.id}. ${capability.name} — NOT VERIFIED"
                setTextColor(Color.WHITE)
                textSize = 13f
                contentDescription = "Capability ${capability.id}: ${capability.name}; not verified"
            })
        }
    }
}
