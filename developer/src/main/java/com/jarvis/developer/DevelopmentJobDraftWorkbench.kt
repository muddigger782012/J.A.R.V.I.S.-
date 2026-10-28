package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Local editing controls only; never stages a proposal or restores an approval. */
internal object DevelopmentJobDraftWorkbench {
    fun attach(activity: Activity, parent: LinearLayout, store: DevelopmentJobDrafts,
        key: DevelopmentJobDrafts.Key, initial: Result<DevelopmentJobDrafts.Snapshot>,
        goal: EditText, paths: EditText, preferCurrent: Boolean, current: () -> Boolean,
        worker: (() -> Unit) -> Unit, button: (String, () -> Unit) -> Button, label: (String) -> TextView) {
        var snapshot = initial.getOrNull()
        var busy = false
        val recovered = snapshot?.takeIf { !preferCurrent && it.matches }?.text
        val restored = recovered != null
        recovered?.let { goal.setText(it.goal); paths.setText(it.paths) }
        val status = label(activity.getString(when {
            initial.isFailure -> R.string.development_draft_unavailable
            snapshot?.matches == false -> R.string.development_draft_stale
            restored -> R.string.development_draft_restored
            snapshot?.text == DevelopmentJobDrafts.Text(goal.text.toString(), paths.text.toString()) -> R.string.development_draft_saved
            else -> R.string.development_draft_unsaved
        })).apply { contentDescription = "Task draft status" }
        lateinit var save: Button
        lateinit var forget: Button
        fun alive() = !activity.isFinishing && !activity.isDestroyed && current()
        fun controls() {
            save.isEnabled = !busy && snapshot != null
            forget.isEnabled = !busy && snapshot?.present == true
        }
        fun submit(remove: Boolean) {
            val before = snapshot ?: return
            if (busy || !alive() || (remove && !before.present)) return
            val text = DevelopmentJobDrafts.Text(goal.text.toString(), paths.text.toString())
            busy = true; controls(); status.setText(R.string.development_draft_saving)
            val work: () -> Unit = {
                val result = runCatching { if (remove) store.forget(key, before.sequence) else store.save(key, before.sequence, text) }
                activity.runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    busy = false
                    result.onSuccess { next ->
                        snapshot = next
                        status.setText(when {
                            remove -> R.string.development_draft_forgotten
                            goal.text.toString() != text.goal || paths.text.toString() != text.paths -> R.string.development_draft_changed_while_saving
                            next.text != text -> R.string.development_draft_redacted
                            else -> R.string.development_draft_saved
                        })
                    }.onFailure {
                        // A conflict needs a fresh snapshot, never an automatic last-writer overwrite.
                        snapshot = null; status.setText(R.string.development_draft_save_failed)
                    }
                    controls()
                }
            }
            runCatching { worker(work) }.onFailure {
                busy = false; snapshot = null; status.setText(R.string.development_draft_save_failed); controls()
            }
        }
        save = button(activity.getString(R.string.development_draft_save)) { submit(false) }
        forget = button(activity.getString(R.string.development_draft_forget)) { submit(true) }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!busy && snapshot != null) status.setText(if (snapshot?.text ==
                    DevelopmentJobDrafts.Text(goal.text.toString(), paths.text.toString())) R.string.development_draft_saved
                    else R.string.development_draft_unsaved)
            }
        }
        goal.addTextChangedListener(watcher); paths.addTextChangedListener(watcher)
        parent.addView(save); parent.addView(forget); parent.addView(status); controls()
    }
}
