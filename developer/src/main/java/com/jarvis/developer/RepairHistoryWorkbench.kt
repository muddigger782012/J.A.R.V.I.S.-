package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/** Explicit bookmark access. Loading history never contacts GitHub or rehydrates a writer. */
object RepairHistoryWorkbench {
    internal class Services(
        val directory: (Activity) -> File = { File(it.noBackupFilesDir.canonicalFile, "developer-repair-history") },
        val feedback: (String, RepairHistoryStore.Bookmark, PatchPreviewCancellation) -> BuildDiagnostics.Report = { token, entry, cancellation ->
            BuildFeedback.read(token, entry.branch, entry.commitSha, entry.target, cancellation)
        },
        val worker: (() -> Unit) -> Unit = { operation -> thread(name = "DeveloperRepairHistory") { operation() }; Unit }
    )

    internal fun attach(activity: Activity, root: LinearLayout, token: EditText,
        currentCommit: () -> PatchPublisher.CommitResult?, begin: () -> Boolean, end: () -> Unit,
        services: Services = Services()): () -> Unit {
        val context = root.context
        root.addView(TextView(context).apply { text = "Private repair history — read-only bookmarks" })
        root.addView(TextView(context).apply {
            text = "Explicitly save up to ten recent task commits on this device. Only product, task branch and commit identity are retained outside Android backup. Source, diffs, credentials and approvals are never saved. A bookmark is not proof of an approved or successful repair. After restarting, load, choose and recheck explicitly; any further repair needs fresh source analysis and exact diff approval."
        })
        fun button(label: String) = Button(context).apply { text = label; root.addView(this) }
        val save = button("Save current verified task commit bookmark")
        val load = button("Load private repair history (local only)")
        val choice = Spinner(context).apply { contentDescription = "Saved repair commit bookmark" }
        root.addView(choice)
        val read = button("Recheck selected saved build diagnostics (GET only)")
        val cancel = button("Cancel saved build read")
        val clear = button("Clear private repair history on this device")
        val result = TextView(context).apply {
            text = "History not loaded. No saved approval can be restored."
            setTextIsSelectable(true)
        }
        root.addView(result)
        var entries = emptyList<RepairHistoryStore.Bookmark>()
        var busy = false
        var disposed = false
        var activeRead: PatchPreviewCancellation? = null
        var lastSelected: RepairHistoryStore.Bookmark? = null
        val placeholder = "Select a saved task commit"
        fun selected() = entries.getOrNull(choice.selectedItemPosition - 1)
        fun alive() = !disposed && !activity.isFinishing && !activity.isDestroyed
        fun refresh() {
            save.isEnabled = !busy
            load.isEnabled = !busy
            clear.isEnabled = !busy
            choice.isEnabled = !busy && entries.isNotEmpty()
            read.isEnabled = !busy && selected() != null
            cancel.isEnabled = busy && activeRead?.isCancelled == false
        }
        fun show(values: List<RepairHistoryStore.Bookmark>) {
            entries = values
            lastSelected = null
            choice.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf(placeholder) + values.map { "${it.target.name}: ${it.branch} @ ${it.commitSha.take(12)}" })
            choice.setSelection(0)
            refresh()
        }
        fun cancelRead() {
            val pending = activeRead ?: return
            pending.cancel()
            if (alive()) {
                result.text = "Saved build read cancellation requested. Waiting for the bounded reader to close; its result will be discarded. No retry, source change or approval was created."
                refresh()
            }
        }
        cancel.setOnClickListener { cancelRead() }
        val tokenChanges = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { cancelRead() }
        }
        token.addTextChangedListener(tokenChanges)
        fun operate(isCurrent: () -> Boolean = { true }, cancellation: PatchPreviewCancellation? = null, action: () -> String) {
            if (busy || !alive()) return
            if (!begin()) {
                result.text = "Another coding operation is active. Finish it before using repair history; no history request was started."
                return
            }
            busy = true
            activeRead = cancellation
            result.text = "Reading or updating private metadata. No source write or approval is requested."
            refresh()
            services.worker {
                val message = try {
                    cancellation?.check()
                    action().also { cancellation?.check() }
                } catch (failure: Exception) {
                    BuildFeedback.explainFailure(failure) ?: "Repair history operation could not be verified. Check access or explicitly clear damaged local history. No approval, source change or retry was created."
                }
                activity.runOnUiThread {
                    if (alive()) {
                        result.text = if (cancellation?.isCancelled == true)
                            "Saved build read cancelled. Its evidence was discarded. Choose and recheck explicitly; no retry, source change or approval was created."
                        else if (isCurrent()) message else
                            "Saved selection changed. The old build response was discarded; choose and recheck explicitly."
                        busy = false
                        activeRead = null
                        end()
                        refresh()
                    }
                }
            }
        }
        show(emptyList())
        choice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val entry = selected()
                if (entry != lastSelected && !busy) {
                    result.text = "Saved references are untrusted bookmarks. Recheck explicitly; no write permission is restored."
                }
                lastSelected = entry
                refresh()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { refresh() }
        }
        save.setOnClickListener {
            if (busy) return@setOnClickListener
            val bookmark = try { currentCommit()?.let(RepairHistoryStore::bookmark) } catch (_: Exception) { null }
            if (bookmark == null) {
                result.text = "No verified task commit is available in this session. An interrupted or unapproved proposal cannot be saved as a verified commit."
                return@setOnClickListener
            }
            operate {
                RepairHistoryStore(services.directory(activity)).save(bookmark)
                "Commit bookmark saved privately. Use Load to review recent history. No source, diff or approval was saved."
            }
        }
        load.setOnClickListener {
            operate {
                val values = RepairHistoryStore(services.directory(activity)).load()
                activity.runOnUiThread { if (alive()) show(values) }
                "Loaded ${values.size} private commit bookmarks. Choose one and explicitly recheck its build; no network request was made."
            }
        }
        clear.setOnClickListener {
            operate {
                RepairHistoryStore(services.directory(activity)).clear()
                activity.runOnUiThread { if (alive()) show(emptyList()) }
                "Private repair history cleared. Repository branches and source are unchanged."
            }
        }
        read.setOnClickListener {
            val entry = selected() ?: return@setOnClickListener
            val authentication = token.text.toString().trim()
            val cancellation = PatchPreviewCancellation()
            operate(isCurrent = { selected() == entry }, cancellation = cancellation) {
                RepairHistoryStore.validate(entry)
                val report = services.feedback(authentication, entry, cancellation)
                BuildDiagnostics.validateReport(report)
                require(report.target == entry.target && report.branch == entry.branch && report.commitSha == entry.commitSha)
                BuildDiagnostics.render(report) +
                    "\n\nThis fresh build report describes only the saved commit. It does not verify a prior approval, restore source scope, start a follow-up or authorize changes."
            }
        }
        return {
            disposed = true
            activeRead?.cancel()
            token.removeTextChangedListener(tokenChanges)
        }
    }
}
