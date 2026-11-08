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
        private val localButton: Button,
        private val proposalButton: Button,
        private val classButton: Button,
        private val detailButton: Button,
        private val methodButton: Button,
        private val referencesButton: Button,
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
        private var classResult: ReaClassSearch.Result? = null
        private var classDetail: ReaClassDetail.Result? = null
        private var methodResult: ReaMethodInspection.Result? = null
        private var referencesResult: ReaMethodReferences.Result? = null

        private fun current(ticket: Long): Boolean =
            !disposed && !activity.isFinishing && !activity.isDestroyed &&
                ticket == generation && product() == 0

        private fun showControls() {
            analyzeButton.visibility = if (selected != null && !busy) View.VISIBLE else View.GONE
            analyzeButton.isEnabled = selected != null && !busy
            localButton.visibility = if (selected != null && !busy) View.VISIBLE else View.GONE
            localButton.isEnabled = selected != null && !busy
            proposalButton.visibility = if (report != null && !busy) View.VISIBLE else View.GONE
            proposalButton.isEnabled = report != null && !busy
            classButton.visibility = if (report != null && selected != null && !busy)
                View.VISIBLE else View.GONE
            classButton.isEnabled = report != null && selected != null && !busy
            detailButton.visibility = if (classResult != null && selected != null && !busy)
                View.VISIBLE else View.GONE
            detailButton.isEnabled = classResult != null && selected != null && !busy
            methodButton.visibility = if (classDetail != null && selected != null && !busy)
                View.VISIBLE else View.GONE
            methodButton.isEnabled = classDetail != null && selected != null && !busy
            referencesButton.visibility = if (methodResult != null && selected != null && !busy)
                View.VISIBLE else View.GONE
            referencesButton.isEnabled = methodResult != null && selected != null && !busy
        }

        /** A changed task draft invalidates the proposal offer, not saved evidence. */
        fun invalidateOffer() {
            generation++
            // A stale worker reply must never strand the composer in BUSY
            // after a newer chat turn takes ownership of the conversation.
            selected?.bytes?.fill(0)
            selected = null
            busy = false
            report = null
            classResult = null
            classDetail = null
            methodResult = null
            referencesResult = null
            showControls()
        }

        fun reset() {
            generation++
            selected?.bytes?.fill(0)
            selected = null
            report = null
            classResult = null
            classDetail = null
            methodResult = null
            referencesResult = null
            busy = false
            attachmentNote.visibility = View.GONE
            showControls()
        }

        fun onPaused() {
            // A dismissed Activity cannot accept a late analysis or source
            // selection. No network operation or approval is resumed later.
            generation++
            selected?.bytes?.fill(0)
            selected = null
            busy = false
            report = null
            classResult = null
            classDetail = null
            methodResult = null
            referencesResult = null
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
            classResult = null
            classDetail = null
            methodResult = null
            referencesResult = null
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

        /** No REA connection, network, model or code-edit capability is used. */
        fun inspectOffline() {
            val apk = selected ?: return
            if (product() != 0 || busy || !ready()) return
            val ticket = ++generation
            val projectAtStart = project()
            busy = true
            showControls()
            message("Inspect selected APK manifest locally")
            status.text = "Reading local APK manifest; no upload or app execution."
            worker {
                val result = runCatching {
                    OfflineApkInventory.inspect(activity, apk)
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    result.onSuccess {
                        output.text = it.display().take(12_000)
                        status.text = "Offline APK manifest inventory complete; no APK uploaded."
                        attachmentNote.text = "Local inventory completed. You can still tap " +
                            "Analyze APK for deeper REA/JADX analysis using the configured host."
                    }.onFailure {
                        output.text = "The selected archive's Android manifest could not be parsed. " +
                            "No package details were invented and no APK was uploaded."
                        status.text = "Offline APK inspection failed safely."
                    }
                    showControls()
                }
            }
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
                // Keep the selected APK only in transient in-memory chat state
                // for explicit follow-up class searches. Leaving/pausing wipes it.
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) {
                        apk.bytes.fill(0)
                        return@runOnUiThread
                    }
                    busy = false
                    result.onSuccess {
                        report = it
                        selected = apk
                        classResult = null
                        classDetail = null
                        methodResult = null
                        referencesResult = null
                        output.text = it.display().take(8500)
                        status.text = "REA evidence received for exact APK hash; no source changed."
                        attachmentNote.text = "Find classes by typing a class-name substring and " +
                            "tapping Search classes. The APK stays only in this active chat's " +
                            "memory and is cleared on background/exit."
                    }.onFailure { error ->
                        report = null
                        selected = null
                        classResult = null
                        classDetail = null
                        methodResult = null
                        referencesResult = null
                        apk.bytes.fill(0)
                        output.text = ReaApkAnalysis.describeFailure(error) +
                            "\nNo evidence, source change, or code approval was accepted."
                        status.text = "REA analysis did not complete."
                        attachmentNote.text = "Choose the APK again to retry a failed analysis."
                    }
                    showControls()
                }
            }
        }


        /** Explicitly upload the SAME selected APK for one fixed REA class-name search. */
        fun searchClasses() {
            val apk = selected ?: return
            val evidence = report ?: return
            val query = prompt.text.toString().trim()
            if (product() != 0 || busy || !ready() ||
                evidence.apkSha256 != apk.sha256 || !ReaClassSearch.validQuery(query)) {
                status.text = "Enter a class substring (2–80 letters, digits, dots, " +
                    "dollars or underscores), then tap Search classes."
                return
            }
            val configuration = ProviderCredentialStore.loadRea(activity)
            if (configuration == null) {
                status.text = "Save REA HTTPS origin and token in Settings first."
                return
            }
            val ticket = ++generation
            val projectAtStart = project()
            classResult = null
            classDetail = null
            methodResult = null
            referencesResult = null
            busy = true
            showControls()
            message("Search REA class names in the selected APK: $query")
            status.text = "Searching JADX classes in the selected APK; no source changes."
            worker {
                val result = runCatching {
                    ReaClassSearch.search(configuration.first, configuration.second, apk, query)
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    if (report !== evidence || prompt.text.toString().trim() != query) {
                        status.text = "Class query changed; stale REA result discarded."
                        showControls()
                        return@runOnUiThread
                    }
                    result.onSuccess {
                        classResult = it
                        classDetail = null
                        methodResult = null
                        referencesResult = null
                        output.text = (evidence.overview?.display().orEmpty() + "\n" +
                            it.display()).take(10000)
                        status.text = "REA class evidence received; source remains unchanged."
                        attachmentNote.text = "Class search ready. Describe the feature " +
                            "to implement, then tap Prepare REA proposal to review a source diff."
                    }.onFailure { error ->
                        classResult = null
                        classDetail = null
                        methodResult = null
                        referencesResult = null
                        output.text = ReaApkAnalysis.describeFailure(error)
                        status.text = "REA class search failed; no source change or approval."
                    }
                    showControls()
                }
            }
        }


        /** Inspect one exact name returned by the immediately preceding class search. */
        fun inspectClass() {
            val evidence = report ?: return
            val selection = selected ?: return
            val classNames = classResult ?: return
            val exact = prompt.text.toString().trim()
            if (product() != 0 || busy || !ready() ||
                evidence.apkSha256 != selection.sha256 ||
                classNames.apkSha256 != selection.sha256 ||
                !ReaClassDetail.validName(exact) || !classNames.matches.contains(exact)) {
                status.text = "Enter an exact class name shown in the Search classes result."
                return
            }
            val connection = ProviderCredentialStore.loadRea(activity)
            if (connection == null) {
                status.text = "Save the REA HTTPS origin and token in Settings first."
                return
            }
            val ticket = ++generation
            val projectAtStart = project()
            classDetail = null
            methodResult = null
            referencesResult = null
            busy = true
            showControls()
            message("Inspect REA class structure: $exact")
            status.text = "Inspecting class members using JADX. No source changes."
            worker {
                val result = runCatching {
                    ReaClassDetail.inspect(connection.first, connection.second, selection, exact)
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    if (report !== evidence || classResult !== classNames ||
                        prompt.text.toString().trim() != exact) {
                        status.text = "Class selection changed; stale details discarded."
                        showControls()
                        return@runOnUiThread
                    }
                    result.onSuccess {
                        classDetail = it
                        methodResult = null
                        referencesResult = null
                        output.text = (evidence.overview?.display().orEmpty() +
                            "\n" + it.display()).take(10000)
                        status.text = "REA class structure inspected; no code was changed."
                        attachmentNote.text = "Class members ready. Describe a feature and " +
                            "tap Prepare REA proposal for an explicit source diff review."
                    }.onFailure { error ->
                        classDetail = null
                        methodResult = null
                        referencesResult = null
                        output.text = ReaApkAnalysis.describeFailure(error)
                        status.text = "REA class inspection failed; no code approval or write."
                    }
                    showControls()
                }
            }
        }


        /** Analyze an exact method/index reported by the previously inspected class. */
        fun inspectMethod() {
            val apk = selected ?: return
            val packageEvidence = report ?: return
            val classEvidence = classDetail ?: return
            val chosen = prompt.text.toString().trim()
            val match = classEvidence.methods.firstOrNull { method ->
                method.name + "#" + method.overloadIndex == chosen
            }
            if (product() != 0 || busy || !ready() ||
                packageEvidence.apkSha256 != apk.sha256 ||
                classEvidence.apkSha256 != apk.sha256 || match == null ||
                !ReaMethodInspection.validMethod(match.name, match.overloadIndex)) {
                status.text = "Enter a reported method selector, such as onCreate#0, " +
                    "from the inspected class's method list."
                return
            }
            val host = ProviderCredentialStore.loadRea(activity)
            if (host == null) {
                status.text = "Save REA HTTPS host and token in Settings first."
                return
            }
            val ticket = ++generation
            val projectAtStart = project()
            methodResult = null
            referencesResult = null
            busy = true
            showControls()
            message("Inspect REA method: " + classEvidence.className + "." + chosen)
            status.text = "Inspecting method source on the authenticated REA host."
            worker {
                val result = runCatching {
                    ReaMethodInspection.inspect(host.first, host.second, apk,
                        classEvidence.className, match.name, match.overloadIndex)
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    if (classDetail !== classEvidence ||
                        prompt.text.toString().trim() != chosen) {
                        status.text = "Method selection changed; stale result discarded."
                        showControls()
                        return@runOnUiThread
                    }
                    result.onSuccess {
                        methodResult = it
                        referencesResult = null
                        output.text = it.display().take(10500)
                        status.text = "REA method inspection complete. No code was executed or changed."
                        attachmentNote.text = "Method evidence is available for a reviewed " +
                            "source proposal. Enter the feature to build and tap Prepare REA proposal."
                    }.onFailure { error ->
                        methodResult = null
                        referencesResult = null
                        output.text = ReaApkAnalysis.describeFailure(error)
                        status.text = "REA method inspection failed; source unchanged."
                    }
                    showControls()
                }
            }
        }


        /** Trace static DEX using nodes of the previously inspected exact method. */
        fun traceReferences() {
            val apk = selected ?: return
            val selectedMethod = methodResult ?: return
            val packageEvidence = report ?: return
            if (product() != 0 || busy || !ready() ||
                selectedMethod.apkSha256 != apk.sha256 ||
                packageEvidence.apkSha256 != apk.sha256 ||
                classDetail?.className != selectedMethod.className ||
                classDetail?.methods?.any {
                    it.name == selectedMethod.methodName &&
                        it.overloadIndex == selectedMethod.overloadIndex
                } != true) return
            val connection = ProviderCredentialStore.loadRea(activity)
            if (connection == null) {
                status.text = "Configure the REA HTTPS host and token in Settings."
                return
            }
            val ticket = ++generation
            val projectAtStart = project()
            referencesResult = null
            busy = true
            showControls()
            message("Trace static references to " + selectedMethod.className + "." +
                selectedMethod.methodName + "#" + selectedMethod.overloadIndex)
            status.text = "Tracing static DEX using nodes; no APK execution."
            worker {
                val result = runCatching {
                    ReaMethodReferences.trace(connection.first, connection.second, apk,
                        selectedMethod)
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    if (methodResult !== selectedMethod) {
                        status.text = "Method changed; stale reference report discarded."
                        showControls()
                        return@runOnUiThread
                    }
                    result.onSuccess {
                        referencesResult = it
                        output.text = it.display().take(12000)
                        status.text = "REA static reference trace ready. No source changed."
                        attachmentNote.text = "Reference evidence may be included in the next " +
                            "review-only Developer source proposal."
                    }.onFailure { error ->
                        referencesResult = null
                        output.text = ReaApkAnalysis.describeFailure(error)
                        status.text = "Reference tracing failed without code changes."
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
            val task = evidence.sourceProposalTask(instruction) +
                classResult?.takeIf { it.apkSha256 == evidence.apkSha256 }
                    ?.proposalEvidence().orEmpty() +
                classDetail?.takeIf { it.apkSha256 == evidence.apkSha256 &&
                    classResult?.matches?.contains(it.className) == true }
                    ?.proposalEvidence().orEmpty() +
                methodResult?.takeIf { method ->
                    method.apkSha256 == evidence.apkSha256 &&
                        classDetail?.className == method.className &&
                        classDetail?.methods?.any { it.name == method.methodName &&
                            it.overloadIndex == method.overloadIndex } == true
                }?.proposalEvidence().orEmpty() +
                referencesResult?.takeIf { refs ->
                    refs.apkSha256 == evidence.apkSha256 &&
                        refs.className == methodResult?.className &&
                        refs.methodName == methodResult?.methodName &&
                        refs.overloadIndex == methodResult?.overloadIndex
                }?.proposalEvidence().orEmpty()
            worker {
                val plan = runCatching {
                    val token = ProviderCredentialStore.loadGithub(activity).orEmpty()
                    RepositoryIntelligence.analyzeSnapshot(token, task).second
                }
                activity.runOnUiThread {
                    if (!current(ticket) || project() != projectAtStart) return@runOnUiThread
                    busy = false
                    if (prompt.text.toString().trim() != instruction || !ready() ||
                        report !== evidence) {
                        status.text = "Task changed; stale REA proposal discarded."
                        showControls()
                        return@runOnUiThread
                    }
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
        val inspectLocal = Button(activity).apply {
            text = "Inspect locally"
            isAllCaps = false
            tag = "developer-apk-inspect-offline"
            contentDescription = "Read selected APK manifest, permissions and components offline with no upload"
            visibility = View.GONE
            isEnabled = false
        }
        composer.addView(inspectLocal, composer.indexOfChild(actions))
        val propose = Button(activity).apply {
            text = "Prepare REA proposal"
            isAllCaps = false
            tag = "developer-rea-proposal"
            visibility = View.GONE
            isEnabled = false
        }
        composer.addView(propose, composer.indexOfChild(actions))
        val searchClasses = Button(activity).apply {
            text = "Search classes"
            isAllCaps = false
            tag = "developer-rea-class-search"
            contentDescription = "Search class names in the current APK using REA, without code edits"
            visibility = View.GONE
            isEnabled = false
        }
        val inspectClass = Button(activity).apply {
            text = "Inspect class"
            isAllCaps = false
            tag = "developer-rea-class-inspect"
            contentDescription = "Inspect the methods and fields of an exact class reported by REA"
            visibility = View.GONE
            isEnabled = false
        }
        val inspectMethod = Button(activity).apply {
            text = "Inspect method"
            isAllCaps = false
            tag = "developer-rea-method-inspect"
            contentDescription = "Read a selected APK method as static JADX evidence without executing it"
            visibility = View.GONE
            isEnabled = false
        }
        val traceReferences = Button(activity).apply {
            text = "Trace references"
            isAllCaps = false
            tag = "developer-rea-method-references"
            contentDescription = "Trace static DEX references to the selected method"
            visibility = View.GONE
            isEnabled = false
        }
        actions.addView(analyze, 0)
        val handle = Handle(activity, prompt, output, status, analyze, inspectLocal,
            propose, searchClasses, inspectClass, inspectMethod, traceReferences, note,
            product, project, ready, message, prepare, onReview)
        inspectLocal.setOnClickListener { handle.inspectOffline() }
        analyze.setOnClickListener { handle.analyze() }
        searchClasses.setOnClickListener { handle.searchClasses() }
        actions.addView(searchClasses, 1)
        inspectClass.setOnClickListener { handle.inspectClass() }
        actions.addView(inspectClass, 2)
        inspectMethod.setOnClickListener { handle.inspectMethod() }
        actions.addView(inspectMethod, 3)
        traceReferences.setOnClickListener { handle.traceReferences() }
        actions.addView(traceReferences, 4)
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
                        } else feedback.text = result.getOrElse { error ->
                            ReaApkAnalysis.describeFailure(error) +
                                " No APK or code was uploaded."
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
