package com.assistant.core

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.assistant.core.services.SelfDiagnosticsReport
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Local report review only. Importing or forwarding a draft never approves source edits. */
class SelfDiagnosticsActivity : MenuActivity("Self Diagnostics & Improvements") {
    private val prefs by lazy { getSharedPreferences("jarvis_self_diagnostics", MODE_PRIVATE) }
    private lateinit var panel: LinearLayout
    private lateinit var scroll: ScrollView
    private var report: SelfDiagnosticsReport.Report? = null
    private var generation = 0
    private class ReadOperation(val signal: CancellationSignal = CancellationSignal(),
        val handle: AtomicReference<android.content.res.AssetFileDescriptor?> = AtomicReference())
    private var operation: ReadOperation? = null
    private var loading = false
    private var restoredScroll: Int? = null
    private val document = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importReport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.BUILD_TYPE == "beta") { finish(); return }
        restoredScroll = savedInstanceState?.getInt("self_diagnostics_scroll")
        report = prefs.getString("report", null)?.let { runCatching { SelfDiagnosticsReport.parse(it) }.getOrNull() }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_panel_surface)
            setPadding(dp(12), 0, dp(12), dp(24))
        }
        UnifiedContentPanel.apply(panel)
        scroll = ScrollView(this).apply { setPadding(dp(16), dp(16), dp(16), dp(16)); addView(panel) }
        setContentView(scroll); render()
    }
    private fun dp(value: Int) = SettingsMenuViews.dp(this, value)
    private fun note(text: String) { panel.addView(SettingsMenuViews.heading(this, text)) }
    private fun row(title: String, detail: String, action: () -> Unit) {
        panel.addView(SettingsMenuViews.row(this, title, detail, R.drawable.ic_jarvis_core, action))
    }
    private fun message(title: String, text: String) {
        ThemedDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton("Close", null).show()
    }
    private fun open(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { message("Unable to open", "Install or enable a browser, then try again.") }
    }
    private fun render() {
        val position = restoredScroll ?: scroll.scrollY
        restoredScroll = null
        panel.removeAllViews()
        note("Self Diagnostics & Improvements")
        note("Read-only review. Imported findings and claimed revisions are unverified; they cannot authorize edits.")
        if (loading) row("Cancel report import", "Keep the previous report", ::cancelImport)
        row("Review voice, AI and capability diagnostics", "Inspect current permissions and retained callback/request observations without making network requests") {
            val snapshot = com.assistant.core.services.LiveCapabilityRegistry.snapshot(this)
            val review = com.assistant.core.services.OperationalDiagnostics.render(snapshot)
            ThemedDialogBuilder(this).setTitle("Runtime diagnostics").setMessage(review)
                .setNegativeButton("Close", null).setPositiveButton("Review in Developer") { _, _ ->
                    runCatching {
                        startActivity(Intent(Intent.ACTION_SEND).setComponent(ComponentName("com.jarvis.developer", "com.jarvis.developer.MainActivity"))
                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, review))
                        event(2)
                    }.onFailure { message("Jarvis Developer unavailable", "Install the independent Developer app to open this read-only review. Source analysis and explicit owner approval are required before a repair can be written.") }
                }.show()
        }
        row("Analyze Source Code", "Open the read-only GitHub workflow; run it on an authorized revision, then import its JSON report") {
            open("https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/workflows/self-diagnostics.yml")
        }
        row("Import diagnostic report", "Choose a local JSON report; no source or logs are uploaded") {
            if (!loading) document.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        row("Diagnose Build Failures", "Use the read-only workflow's optional failed-run selection; import the resulting report") {
            message("Diagnose Build Failures", "Run the read-only workflow with a failed Actions run from this repository. It reads failed-job logs and exports fixed diagnoses without raw log text. Import the JSON report here. It does not fix or rebuild source.")
        }
        row("Analyze Application Errors", "Import a report generated from an explicitly selected local crash log") {
            message("Analyze Application Errors", "The existing self-diagnostics tool accepts a local crash log and reports crash, memory-exhaustion and unresponsive-app markers without copying private values. Generate its JSON report on an authorized development machine, then import it here. Android logs are not collected automatically.")
        }
        note("Recommended Improvements")
        val current = report
        if (current == null) note("No report imported. Static review signals are not confirmed bugs.") else {
            note("${current.scanned} sources inspected; ${current.findings.size} review signals. " + if (current.complete) "Scanner reported complete coverage within its source rules." else "Partial coverage; inspect the original report's limits.")
            current.findings.take(30).forEach { finding ->
                val rule = SelfDiagnosticsReport.rules.getValue(finding.rule)
                row("P${rule.priority}: ${rule.title}", "${finding.path}${finding.line?.let { ":$it" } ?: ""} · ${finding.evidenceKind}") { review(current, finding) }
            }
            if (current.findings.size > 30) note("Showing the first 30 prioritized signals; the original report contains the remaining references.")
            row("Clear imported report", "Remove this local review; source and Developer tasks are unaffected") {
                cancelImport(); prefs.edit().remove("report").apply(); report = null; render()
            }
        }
        row("Pending Approvals", "No source-write, merge, publication or installation approvals are created here") {
            message("Pending Approvals", "Opening a draft grants no approval. In compatible Developer builds, use Personal Jarvis diagnostic repair review to verify source and stage a bounded proposal. Review the complete diff and explicitly approve its isolated branch there; changed source or scope needs fresh review. If Personal review is unavailable, retain the draft for manual review until a compatible Developer build is installed. Merge, publication and installation require separate approval.")
        }
        row("Development History", "Local imports and opened review drafts only; no repair completion is inferred") {
            val history = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrNull()
            val lines = (0 until (history?.length() ?: 0).coerceAtMost(20)).map { index ->
                when (history!!.optInt(index)) { 1 -> "Imported a diagnostic report"; 2 -> "Opened a Developer review draft"; else -> "Local review event" }
            }
            message("Development History", lines.joinToString("\n").ifEmpty { "No local review events recorded." })
        }
        row("Test Results", "Diagnostics do not run tests or prove a passing build") {
            open("https://github.com/muddigger782012/J.A.R.V.I.S.-/actions")
        }
        row("Rollback and Recovery", "Open existing recovery instructions; no installation or rollback runs") {
            open("https://github.com/muddigger782012/J.A.R.V.I.S.-/blob/main/docs/PERSONAL_RELEASE_PIPELINE.md")
        }
        FontPreferences.applyTo(panel, this)
        scroll.post { if (!isFinishing) scroll.scrollTo(0, position) }
    }
    private fun review(current: SelfDiagnosticsReport.Report, finding: SelfDiagnosticsReport.Finding) {
        val rule = SelfDiagnosticsReport.rules.getValue(finding.rule)
        ThemedDialogBuilder(this).setTitle(rule.title).setMessage(SelfDiagnosticsReport.details(finding) +
            "\n\nOpen a review draft in the separate Jarvis Developer app? No edit is approved. Verify the source and approve a scoped proposal there before any write.")
            .setNegativeButton("Cancel", null).setPositiveButton("Review in Developer") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_SEND).setComponent(ComponentName("com.jarvis.developer", "com.jarvis.developer.MainActivity"))
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, SelfDiagnosticsReport.developerDraft(current, finding)))
                    event(2)
                }.onFailure { message("Jarvis Developer unavailable", "Install the independent Jarvis Developer application to review this draft. Nothing was changed or authorized.") }
            }.show()
    }
    private fun event(code: Int) {
        val old = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray().put(code)
        for (i in 0 until old.length().coerceAtMost(19)) old.optInt(i).takeIf { it in 1..2 }?.let { next.put(it) }
        prefs.edit().putString("history", next.toString()).apply()
    }
    private fun stopRead() {
        generation++; val old = operation; operation = null
        runCatching { old?.signal?.cancel() }; runCatching { old?.handle?.getAndSet(null)?.close() }
        loading = false
    }
    private fun cancelImport() { stopRead(); if (::panel.isInitialized) render() }
    private fun importReport(uri: Uri) {
        cancelImport(); val ticket = generation; val read = ReadOperation(); val signal = read.signal
        operation = read; loading = true; render()
        scroll.postDelayed({
            if (ticket == generation && loading) {
                cancelImport()
                Toast.makeText(this, "Report import timed out. The previous report was kept.", Toast.LENGTH_LONG).show()
            }
        }, 10_000)
        thread(name = "jarvis-diagnostic-import", isDaemon = true) {
            val parsed = runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r", signal)!!.use { descriptor ->
                    read.handle.set(descriptor); signal.throwIfCanceled()
                    descriptor.createInputStream().use { input ->
                        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            signal.throwIfCanceled()
                            val size = input.read(buffer, 0, minOf(buffer.size, SelfDiagnosticsReport.MAX_BYTES + 1 - bytes.size()))
                            if (size == -1) break
                            bytes.write(buffer, 0, size); require(bytes.size() <= SelfDiagnosticsReport.MAX_BYTES)
                        }
                        SelfDiagnosticsReport.parse(bytes.toString("UTF-8"))
                    }
                }
            }.getOrNull()
            runOnUiThread {
                if (ticket == generation && !isFinishing && !isDestroyed) {
                    loading = false; operation = null
                    if (parsed == null) Toast.makeText(this, "Report rejected. Use a bounded, read-only report from the current diagnostics tool.", Toast.LENGTH_LONG).show()
                    else { report = parsed; prefs.edit().putString("report", SelfDiagnosticsReport.encode(parsed)).apply(); event(1) }
                    render()
                }
            }
        }
    }
    override fun onDestroy() { stopRead(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::scroll.isInitialized) outState.putInt("self_diagnostics_scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }
}
