package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * One-screen APK picker -> isolated REA analysis -> evidence-bound Developer
 * source proposal. No REA executable/remote command runs inside the APK.
 * User instructions and REA output are never GitHub write authority.
 */
internal object ReaApkWorkbench {
    const val PICK_REQUEST = 7354

    internal class Handle(
        private val activity: Activity,
        private val prompt: EditText,
        private val output: TextView,
        private val status: TextView,
        private val analyzeButton: Button,
        private val proposalButton: Button,
        private val attachmentNote: TextView,
        private val product: () -> Int,
        private val project: () -> String?,
        private val ready: () -> Boolean,
        private val message: (String) -> Unit,
        private val prepare: (String, String, List<String>, String) -> Boolean,
        private val onReview: () -> Unit,
        private val worker: ((() -> Unit) -> Unit) = { task ->
            thread(name = "developer-rea-worker", isDaemon = true) { task() }
        }
    ) {
        private var generation = 0L
        private var disposed = false
        private var busy = false
        private var selected: ReaApkAnalysis.Selected? = null
        private var report: ReaApkAnalysis.Report? = null

        private fun current(ticket: Long): Boolean =
            !disposed && !activity.isFinishing && !activity.isDestroyed &&
                ticket == generation && product() == 0

        private fun showControls() {
            analyzeButton.visibility = if (selected != null && !busy) View.VISIBLE else View.GONE
            analyzeButton.isEnabled = selected != null && !busy
            proposalButton.visibility = if (report != null && !busy) View.VISIBLE else View.GONE
            proposalButton.isEnabled = report != null && !busy
        }

        /** A changed task draft invalidates the proposal offer, not saved evidence. */
        fun invalidateOffer() {
            generation++
            // A stale worker reply must never strand the composer in BUSY
            // after a newer chat turn takes ownership of the conversation.
            if (busy) selected = null
            busy = false
            report = null
            showControls()
        }

        fun reset() {
            generation++
            selected?.bytes?.fill(0)
            selected = null
            report = null
            busy = false
            attachmentNote.visibility = View.GONE
            showControls()
        }

        fun onPaused() {
            // A dismissed Activity cannot accept a late analysis or source
            // selection. No network operation or approval is resumed later.
            generation++
            if (busy) selected = null
            busy = false
            report = null
            showControls()
        }

        fun close() {
            disposed = true
            reset()
        }

        fun onPickerResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode != PICK_REQUEST) return false
            if (resultCode != Activity.RESULT_OK || data?.data == null) return true
            if (product() != 0 || busy || !ready()) {
                attachmentNote.text = "APK import requires an idle Developer chat."
                attachmentNote.visibility = View.VISIBLE
                return true
            }
            val uri: Uri = data.data ?: return true
            val ticket = ++generation
            report = null
            selected?.bytes?.fill(0)
            selected = null
            busy = true
            showControls()
            attachmentNote.text = "Reading selected APK on-device. Nothing has been uploaded."
            attachmentNote.visibility = View.VISIBLE
            worker {
                val attempt = runCatching { ReaApkAnalysis.fromUri(activity.contentResolver, uri) }
                activity.runOnUiThread {
                    if (!current(ticket)) {
                        attempt.getOrNull()?.bytes?.fill(0)
                        return@runOnUiThread
                    }
                    busy = false
                    attempt.onSuccess {
                        selected = it
                        attachmentNote.text = "APK selected (${it.size / 1024} KiB, SHA-256 " +
                            "${it.sha256.take(12)}). Tap Analyze APK to upload it to your " +
                            "configured REA host over HTTPS."
                    }.onFailure {
                        attachmentNote.text = "APK import rejected. Choose an APK under 24 MB."
                    }
                    showControls()
                }
            }
            return true
        }

        fun analyze() {
            val apk = selected ?: return
            if (product() != 0 || !ready() || busy) return
            val configuration = ProviderCredentialStore.loadRea(activity)
            if (configuration == null) {
                attachmentNote.text = "Configure REA HTTPS origin and token in Settings first."
                attachmentNote.visibility = View.VISIBLE
                return
            }
            val ticket = ++generation
            val projectAtStart = project()
            busy = true
            showControls()
            message("Analyze selected APK with REA")
            status.text = "Uploading selected APK to authenticated REA static analysis host."
            worker {
                val result = runCatching {
                    ReaApkAnalysis.analyze(configuration.first, configuration.second, apk)
                }
                // APK bytes are no longer needed once the HTTPS exchange
                // terminates, even if the Activity has switched tasks.
                apk.bytes.fill(0)
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    selected = null
                    apk.bytes.fill(0)
                    result.onSuccess {
                        report = it
                        output.text = it.display().take(8500)
                        status.text = "REA evidence received for exact APK hash; no source changed."
                        attachmentNote.text = "Static REA evidence is ready. Describe the specific " +
                            "Developer feature to implement, then tap Prepare proposal."
                    }.onFailure {
                        report = null
                        output.text = "REA analysis did not complete. Check HTTPS certificate, " +
                            "bridge token, Node/JADX setup and connection. No evidence or code approval accepted."
                        status.text = "REA unavailable or returned unverified evidence."
                        attachmentNote.text = "Choose the APK again to retry a failed analysis."
                    }
                    showControls()
                }
            }
        }

        fun propose() {
            val evidence = report ?: return
            val instruction = prompt.text.toString().trim()
            if (product() != 0 || busy || !ready() || instruction.length !in 12..1200) {
                status.text = "Describe a specific Developer feature (12–1200 characters) " +
                    "before starting a reviewable REA-backed proposal."
                return
            }
            val ticket = ++generation
            val projectAtStart = project()
            busy = true
            showControls()
            message("Prepare source-scoped Developer proposal using REA APK evidence")
            status.text = "Reading Developer source at an immutable commit; no code write."
            val task = evidence.sourceProposalTask(instruction)
            worker {
                val plan = runCatching {
                    val token = ProviderCredentialStore.loadGithub(activity).orEmpty()
                    RepositoryIntelligence.analyzeSnapshot(token, task).second
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart ||
                        prompt.text.toString().trim() != instruction || !ready() ||
                        report !== evidence) return@runOnUiThread
                    busy = false
                    plan.onSuccess { scoped ->
                        val paths = scoped.files.map { it.path }.distinct()
                            .filter { PatchEngine.allowedPath(it, PatchEngine.Target.DEVELOPER) }
                            .take(PatchEngine.MAX_FILES)
                        val id = "rea-" + evidence.apkSha256.take(16)
                        val started = paths.isNotEmpty() && runCatching {
                            prepare(id, task, paths, scoped.commitSha)
                        }.getOrDefault(false)
                        if (started) onReview()
                        output.text = if (started)
                            "REA evidence ${evidence.evidenceSha256.take(16)} is bound to " +
                                "APK ${evidence.apkSha256.take(16)}. A NEW source-pinned " +
                                "Developer diff preview is running for ${paths.size} eligible " +
                                "file(s) at ${scoped.commitSha.take(12)}. Review its complete " +
                                "diff and explicitly approve the exact fingerprint before any commit."
                        else "No eligible source scope or current coding slot was available. " +
                            "REA evidence was not converted into an edit or approval."
                        status.text = if (started) "REA-backed Developer diff preview requested."
                            else "No REA-backed diff started; source and approval unchanged."
                    }.onFailure {
                        output.text = "Developer source scan failed; REA evidence alone cannot " +
                            "authorize source changes. No diff or write was created."
                        status.text = "REA-backed source review unavailable."
                    }
                    showControls()
                }
            }
        }
    }

    fun install(activity: Activity, composer: LinearLayout, settings: LinearLayout,
        prompt: EditText, output: TextView, status: TextView,
        product: () -> Int, project: () -> String?, ready: () -> Boolean,
        message: (String) -> Unit,
        prepare: (String, String, List<String>, String) -> Boolean,
        onReview: () -> Unit): Handle {
        val actions = composer.findViewWithTag<LinearLayout>("developer-home-composer-actions")
            ?: error("Developer composer actions are required")
        val note = TextView(activity).apply {
            tag = "developer-rea-attachment-status"
            visibility = View.GONE
            setTextColor(activity.getColor(R.color.developer_secondary))
            textSize = 11f
        }
        composer.addView(note, composer.indexOfChild(actions))
        val analyze = Button(activity).apply {
            text = "Analyze APK"
            isAllCaps = false
            tag = "developer-rea-analyze"
            visibility = View.GONE
            isEnabled = false
        }
        val propose = Button(activity).apply {
            text = "Prepare REA proposal"
            isAllCaps = false
            tag = "developer-rea-proposal"
            visibility = View.GONE
            isEnabled = false
        }
        composer.addView(propose, composer.indexOfChild(actions))
        actions.addView(analyze, 0)
        val handle = Handle(activity, prompt, output, status, analyze, propose, note,
            product, project, ready, message, prepare, onReview)
        analyze.setOnClickListener { handle.analyze() }
        propose.setOnClickListener { handle.propose() }
        actions.addView(Button(activity).apply {
            text = "+ APK"
            isAllCaps = false
            tag = "developer-rea-picker"
            contentDescription = "Select an APK for static REA analysis, without automatic upload"
            setOnClickListener {
                if (product() != 0 || !ready()) {
                    status.text = "APK analysis is available only in idle Developer chat."
                    return@setOnClickListener
                }
                try {
                    activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, PICK_REQUEST)
                } catch (_: ActivityNotFoundException) {
                    status.text = "Android document picker unavailable."
                }
            }
        }, 0)
        val old = ProviderCredentialStore.loadRea(activity)
        val origin = EditText(activity).apply {
            hint = "REA HTTPS origin (https://host.example:8765)"
            setSingleLine(true)
            setText(old?.first.orEmpty())
            isSaveEnabled = false
            tag = "developer-rea-origin"
        }
        val bearer = EditText(activity).apply {
            hint = if (old == null) "REA bridge token (24+ characters)" else
                "REA token stored securely (leave blank to keep)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            tag = "developer-rea-bearer"
        }
        val feedback = TextView(activity).apply {
            setTextColor(activity.getColor(R.color.developer_secondary))
            text = "REA is an external static analysis engine. Only your explicit Analyze APK tap uploads an APK."
        }
        settings.addView(TextView(activity).apply {
            text = "REA APK analysis bridge"
            setTextColor(activity.getColor(R.color.developer_primary))
            textSize = 18f
        })
        settings.addView(origin)
        settings.addView(bearer)
        settings.addView(Button(activity).apply {
            text = "Save REA bridge connection"
            isAllCaps = false
            setOnClickListener {
                val server = origin.text.toString().trim()
                val previous = ProviderCredentialStore.loadRea(activity)
                val key = bearer.text.toString().ifBlank {
                    previous?.takeIf { it.first == server }?.second.orEmpty()
                }
                feedback.text = if (runCatching {
                    ProviderCredentialStore.saveRea(activity, server, key)
                }.isSuccess) {
                    bearer.text.clear()
                    "REA HTTPS connection saved in Android Keystore. Analysis remains opt-in."
                } else "Invalid HTTPS origin or token. No REA connection was saved."
            }
        })
        settings.addView(Button(activity).apply {
            text = "Test REA connection"
            isAllCaps = false
            tag = "developer-rea-test-connection"
            contentDescription = "Check authenticated REA/JADX host readiness without uploading an APK"
            setOnClickListener {
                val config = ProviderCredentialStore.loadRea(activity)
                if (config == null) {
                    feedback.text = "Save a valid REA HTTPS origin and token first."
                    return@setOnClickListener
                }
                isEnabled = false
                feedback.text = "Checking REA bridge and JADX provider on your host; no APK uploaded."
                val button = this
                thread(name = "developer-rea-health", isDaemon = true) {
                    val result = runCatching {
                        ReaApkAnalysis.checkHealth(config.first, config.second)
                    }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        button.isEnabled = true
                        if (ProviderCredentialStore.loadRea(activity) != config) {
                            feedback.text = "REA settings changed. Previous health response discarded."
                        } else feedback.text = result.getOrElse {
                            "REA host unreachable or unauthorized. Verify TLS, token, Node and JADX. " +
                                "No APK or code was uploaded."
                        }
                    }
                }
            }
        })
        settings.addView(Button(activity).apply {
            text = "Disconnect REA bridge"
            isAllCaps = false
            setOnClickListener {
                ProviderCredentialStore.clearRea(activity)
                bearer.text.clear()
                feedback.text = "REA connection removed. No APK or token was uploaded."
            }
        })
        settings.addView(feedback)
        return handle
    }
}
