package com.jarvis.developer

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/** Only ordinary drafts are restored. Credentials, proposals, approvals and review tokens aren't. */
internal object DeveloperUiState {
    private val hints = setOf(
        "Example: Build me an app to track equipment maintenance.",
        "Source path or directory prefix (blank lists all allowed sources)",
        "Source line range, up to 200 lines",
        "Exact allowed source paths, one per line (maximum eight)",
        "Own Developer improvement goal (maximum 4,000 characters)",
        "1–8 existing permitted Developer production paths, one per line",
        "Immutable source allowlist: 1–8 exact Developer paths, one per line",
        "Repair allowlist: 1–8 exact permitted Developer paths, one per line",
        "Repair goal and acceptance criteria (maximum 4,000 characters)",
        "Selected step title",
        "Selected step detail / acceptance criteria",
        "Personal source branch",
        "Optional new Personal test paths, one per line (maximum three)",
        "Application title (up to 80 characters)",
        "Project name (for example MyApplication)",
        "Independent package (for example org.example.myapp)",
        "Written application specification (up to 4000 characters)"
    )

    fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    fun save(root: View): Bundle = Bundle().apply {
        descendants(root).filterIsInstance<EditText>().forEach { field ->
            val hint = field.hint?.toString()
            if (hint in hints) putString(hint, field.text.toString().take(12_000))
        }
    }

    fun restore(root: View, state: Bundle?) {
        if (state == null) return
        descendants(root).filterIsInstance<EditText>().forEach { field ->
            val hint = field.hint?.toString()
            if (hint in hints && state.containsKey(hint)) field.setText(state.getString(hint).orEmpty().take(12_000))
        }
    }
}
