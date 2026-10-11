package com.assistant.core

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Shared dialog styling without taking over callers' show/dismiss listeners. */
class ThemedDialogBuilder(private val hostContext: Context) : AlertDialog.Builder(hostContext) {
    override fun create(): AlertDialog {
        val dialog = super.create()
        val decor = dialog.window?.decorView
        decor?.let { root ->
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                FontPreferences.applyTo(root, hostContext)
            }
        }
        return dialog
    }
}
