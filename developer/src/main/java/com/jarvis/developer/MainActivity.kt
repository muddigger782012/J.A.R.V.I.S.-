package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.graphics.BitmapFactory
import android.content.ActivityNotFoundException
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import kotlin.concurrent.thread
import com.jarvis.integration.DevelopmentBridgeContract as Wire

/**
 * Independent Gemini/OpenAI coding client.
 * Provider API key is encrypted using Android Keystore; it is never embedded in the APK.
 * This is API communication, NOT a connection to a ChatGPT conversation or Codex session.
 */
class MainActivity : Activity() {
    private var applicationWorkbench: ApplicationApprovalWorkbench.Handle? = null
    private var patchPreview: PatchWorkbench.Handle? = null
    private var activePage = 0
    private val pageHistory = mutableListOf<Int>()
    private var pageViews = emptyList<ScrollView>()
    private var uiRoot: View? = null
    private var navigateBack: (() -> Boolean)? = null
    private var openToolSection = -1
    private var guidedTarget: android.widget.Spinner? = null
    private var disposeGuidance: (() -> Unit)? = null
    private var activeProject: String? = null
    private var projectGeneration: ProjectGenerationWorkbench.Handle? = null
    private var homeWorkspace: DeveloperHomeWorkspace.Session? = null
    internal val connectionVerification = ConnectionVerification()
    private var selectPage: ((Int) -> Unit)? = null
    private var artifactReviewIntent: ((Intent) -> Unit)? = null
    private var artifactReviewJob: String? = null
    private var developmentHistory: DevelopmentInboxWorkbench.Handle? = null
    private var artifactReviewGeneration = 0L
    private var artifactInstallWhenFocused: (() -> Unit)? = null
    private var pendingArtifactInstall: Pair<Long, String>? = null
    private var selectedScreenshot: DeveloperScreenshot? = null
    private var screenshotLoading = false
    private var screenshotReadGeneration = 0L
    private var screenshotLabel: TextView? = null
    private var screenshotPreview: ImageView? = null
    private val screenshotPickerRequest = 7306

    // A job may complete while Developer is in the background. Reconcile Track
    // with the persisted inbox whenever the activity becomes visible again.
    override fun onResume() {
        super.onResume()
        if (activePage == 2) developmentHistory?.refreshNow()
        if (activePage == 0) homeWorkspace?.refreshJobEvidence()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("developer_active_page", activePage)
        outState.putIntArray("developer_page_history", pageHistory.takeLast(32).toIntArray())
        outState.putIntArray("developer_scroll", pageViews.map { it.scrollY }.toIntArray())
        outState.putInt("developer_tool_section", openToolSection)
        outState.putInt("developer_guided_target", guidedTarget?.selectedItemPosition ?: 0)
        outState.putString("developer_active_project", activeProject)
        uiRoot?.let { outState.putBundle("developer_drafts", DeveloperUiState.save(it)) }
        homeWorkspace?.save(outState)
        artifactReviewJob?.let { outState.putString("developer_artifact_review_job", it) }
        developmentHistory?.let { outState.putBundle("developer_job_history", it.saveState()) }
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activePage = savedInstanceState?.getInt("developer_active_page", 0)?.coerceIn(0, 4) ?: 0
        pageHistory.addAll(savedInstanceState?.getIntArray("developer_page_history")?.filter { it in 0..4 }.orEmpty())
        activeProject = savedInstanceState?.getString("developer_active_project")
        val health = getSharedPreferences("developer_health", MODE_PRIVATE)
        val currentCode = BuildConfig.VERSION_CODE
        val previousCode = health.getInt("last_started_version", -1)
        val previousConfirmed = health.getInt("confirmed_version", -1)
        val pendingReview = previousCode != currentCode || previousConfirmed != currentCode
        val now = System.currentTimeMillis()
        val lastLaunch = health.getLong("last_launch_ms", 0L)
        val lastLaunchVersion = health.getInt("last_launch_version", -1)
        val quickRestart = lastLaunchVersion == currentCode && lastLaunch > 0 &&
            now >= lastLaunch && now - lastLaunch < 120_000L
        // A short relaunch is only a warning signal; it cannot establish that Android crashed.
        val restarts = if (previousConfirmed != currentCode && quickRestart)
            (health.getInt("quick_restarts", 0) + 1).coerceAtMost(10) else 0
        val recoveryWarning = restarts >= 3 && previousConfirmed != currentCode
        health.edit().putInt("last_started_version", currentCode)
            .putInt("last_launch_version", currentCode)
            .putLong("last_launch_ms", now)
            .putInt("quick_restarts", restarts).apply()
        fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(getColor(R.color.developer_background))
        }
        fun field(hint: String, multiline: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setHintTextColor(getColor(R.color.developer_hint))
            setTextColor(0xFFFFFFFF.toInt())
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            root.addView(this)
        }
        // The approved composite remains the only artwork source. Its lettering and border
        // stay still; only the existing reactor annuli are animated.
        root.addView(ApprovedReactorHeader(this), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        val status = TextView(this).apply {
            tag = "project-generation-status"
            text = "Your AI development assistant"
            textSize = 13f
            setPadding(dp(4), dp(8), 0, dp(6))
            setTextColor(getColor(R.color.developer_secondary))
        }
        root.addView(status)
        val setupHint = TextView(this).apply {
            text = if (ProviderCredentialStore.load(this@MainActivity) != null)
                "AI connection saved. Connection checks run automatically; manage credentials in Settings."
                else "New here? Connect an AI provider in Settings to get started."
            setTextColor(getColor(R.color.developer_secondary))
            setPadding(0, 16, 0, 8)
        }
        if (ProviderCredentialStore.load(this@MainActivity) == null) root.addView(setupHint)
        lateinit var updateConnectionSetup: () -> Unit
        val key = field("Paste your API key here (not a model name)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val provider = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, listOf("Gemini", "OpenAI"))
        }
        root.addView(provider)
        val model = field("Model ID (blank = provider default)")
        ProviderCredentialStore.load(this)?.let { (savedProvider, savedKey, savedModel) ->
            provider.setSelection(if (savedProvider == "OpenAI") 1 else 0)
            key.setText(savedKey)
            model.setText(savedModel)
        }
        root.addView(TextView(this).apply {
            text = "What would you like to create?"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.developer_text))
            setPadding(0, dp(24), 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = "Describe an app, a feature, or a problem to fix in your own words."
            textSize = 14f
            setTextColor(getColor(R.color.developer_secondary))
            setPadding(0, 0, 0, dp(12))
        })
        val prompt = field("Example: Build me an app to track equipment maintenance.", true)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            prompt.setText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        }
        val output = TextView(this).apply {
            tag = "project-generation-reply"
            setTextColor(getColor(R.color.developer_text))
            setTextIsSelectable(true)
            text = "Your assistant’s response will appear here."
        }
        val proposeCodePatch = android.widget.CheckBox(this).apply {
            text = "Prepare reviewable code changes"
            isChecked = true
            setTextColor(getColor(R.color.developer_text))
            contentDescription = "For implementation requests, prepare a source-verified code proposal for the selected app; questions remain advisory and no write is automatic"
        }
        root.addView(proposeCodePatch)
        val send = Button(this).apply { text = "Ask Jarvis / save instructions"; isAllCaps = false }
        root.addView(send)
        // Keep read-only repository analysis on Home beside the conversational task.
        // This does not grant patch, branch, commit, or installation authority.
        val analyzeFromHome = Button(this).apply {
            text = "Analyze repository request (read-only)"
            isAllCaps = false
            contentDescription = "Inspect repository source for the Home task without modifying files"
        }
        (send.parent as? android.view.ViewGroup)?.removeView(send)
        val composerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(analyzeFromHome, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(composerActions)
        val reviewHomeAnalysis = Button(this).apply {
            text = "Review changes on Home"
            isAllCaps = false
            isEnabled = false
            contentDescription = "Open the inline patch review on Home; no changes are approved automatically"
        }
        // The separate review launcher is redundant; the successful analysis stages review.
        reviewHomeAnalysis.visibility = View.GONE
        root.addView(reviewHomeAnalysis)
        var fullHomeAnalysis: String? = null
        var homeAnalysisPlan: RepositoryAnalysis.AnalysisPlan? = null
        var stageHomeAnalysis: (() -> Unit)? = null
        val stageHomeAnalysisButton = Button(this).apply {
            text = "Load analyzed files into code review"
            isAllCaps = false
            isEnabled = false
            visibility = View.GONE
            setOnClickListener { stageHomeAnalysis?.invoke() }
        }
        root.addView(stageHomeAnalysisButton)
        val homeHandoffStatus = TextView(this).apply {
            tag = "home-analysis-handoff-status"
            setTextColor(getColor(R.color.developer_secondary))
            visibility = View.GONE
        }
        root.addView(homeHandoffStatus)
        var homeAnalysisRevision = 0L
        // One explicit implementation instruction may advance from source
        // analysis to a read-only AI diff preview. Never persist or replay it.
        var pendingAutoPreviewTask: String? = null
        val expandHomeAnalysis = Button(this).apply {
            text = "Show full source findings"
            isAllCaps = false
            visibility = View.GONE
        }
        root.addView(expandHomeAnalysis)
        expandHomeAnalysis.setOnClickListener {
            val full = fullHomeAnalysis ?: return@setOnClickListener
            val expanded = expandHomeAnalysis.text.toString() == "Show full source findings"
            output.text = if (expanded) full else full.lineSequence().take(12).joinToString("\n") +
                "\n\nMore findings are available below."
            expandHomeAnalysis.text = if (expanded) "Collapse source findings" else "Show full source findings"
        }
        var openInlineReview: (() -> Unit)? = null
        reviewHomeAnalysis.setOnClickListener { openInlineReview?.invoke() }
        prompt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                homeAnalysisRevision++
                pendingAutoPreviewTask = null
                reviewHomeAnalysis.isEnabled = false
                fullHomeAnalysis = null
                homeAnalysisPlan = null
                stageHomeAnalysisButton.isEnabled = false
                stageHomeAnalysisButton.visibility = View.GONE
                homeHandoffStatus.visibility = View.GONE
                expandHomeAnalysis.visibility = View.GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        analyzeFromHome.setOnClickListener {
            val task = prompt.text.toString().trim()
            if (task.isBlank()) {
                output.text = "Describe what to inspect in the task box above."
                return@setOnClickListener
            }
            val requestRevision = ++homeAnalysisRevision
            analyzeFromHome.isEnabled = false
            reviewHomeAnalysis.isEnabled = false
            fullHomeAnalysis = null
            homeAnalysisPlan = null
            stageHomeAnalysisButton.isEnabled = false
            stageHomeAnalysisButton.visibility = View.GONE
            homeHandoffStatus.visibility = View.GONE
            expandHomeAnalysis.visibility = View.GONE
            output.text = "Inspecting repository sources for your request..."
            thread(name = "DeveloperHomeRepositoryAnalysis") {
                val result = runCatching {
                    val token = ProviderCredentialStore.loadGithub(this@MainActivity).orEmpty()
                    val (_, plan) = RepositoryIntelligence.analyzeSnapshot(token, task)
                    plan to RepositoryAnalysis.render(plan)
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        // A completed background request must never replace the response
                        // for a newer task entered while the analysis was running.
                        if (homeAnalysisRevision != requestRevision || prompt.text.toString().trim() != task) {
                            if (pendingAutoPreviewTask == task) pendingAutoPreviewTask = null
                            analyzeFromHome.isEnabled = true
                            return@runOnUiThread
                        }
                        output.text = result.fold(onSuccess = { it.second }, onFailure = { error ->
                            val message = error.message.orEmpty()
                            when {
                                "401" in message -> "GitHub authentication failed. Check your saved connection."
                                "403" in message -> "GitHub access denied or rate limited."
                                "404" in message -> "Repository or branch unavailable."
                                else -> "Repository analysis could not complete. Check your connection and retry."
                            } + " No files were changed."
                        })
                        val isCurrent = prompt.text.toString().trim() == task
                        if (result.isSuccess && isCurrent) {
                            homeAnalysisPlan = result.getOrNull()?.first
                            stageHomeAnalysisButton.isEnabled = homeAnalysisPlan?.files?.isNotEmpty() == true
                            // Successful read-only analysis automatically stages eligible source paths.
                            // This only prepares a review; it never generates or approves a write.
                            stageHomeAnalysisButton.visibility = View.GONE
                            if (stageHomeAnalysisButton.isEnabled) stageHomeAnalysis?.invoke()
                            else if (pendingAutoPreviewTask == task) pendingAutoPreviewTask = null
                            fullHomeAnalysis = result.getOrNull()?.second
                            val full = fullHomeAnalysis.orEmpty()
                            val lines = full.lineSequence().toList()
                            if (lines.size > 12) {
                                output.text = lines.take(12).joinToString("\n") +
                                    "\n\nMore findings are available below."
                                expandHomeAnalysis.text = "Show full source findings"
                                expandHomeAnalysis.visibility = View.VISIBLE
                            }
                        }
                        if (result.isFailure && pendingAutoPreviewTask == task)
                            pendingAutoPreviewTask = null
                        reviewHomeAnalysis.isEnabled = result.isSuccess && isCurrent
                        analyzeFromHome.isEnabled = true
                    }
                }
            }
        }
        val cancelGeneration = Button(this).apply {
            setText(R.string.project_generation_cancel); isAllCaps = false; isEnabled = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.developer_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.developer_panel))
        }
        root.addView(cancelGeneration)
        val forgetDiscussion = Button(this).apply {
            setText(R.string.project_generation_forget_discussion); isAllCaps = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.developer_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.developer_panel))
        }
        root.addView(forgetDiscussion)
        root.removeView(status)
        root.addView(status)
        root.addView(output)

        val plans = Button(this).apply { text = "Read approved development plans" }
        root.addView(plans)
        val builds = Button(this).apply { text = "Check Developer build status" }
        root.addView(builds)
        val updates = Button(this).apply { text = "Check for Developer updates" }
        root.addView(updates)
        val recoveryStatus = TextView(this).apply {
            setTextColor(getColor(R.color.developer_text))
            text = if (recoveryWarning) "WARNING: $restarts rapid launches on unconfirmed version $currentCode. This may be normal relaunching, not necessarily a crash. Review recovery before coding or updating." else if (pendingReview) "Version $currentCode is awaiting your health confirmation." else
                "Version $currentCode was confirmed working on this device."
        }
        root.addView(recoveryStatus)
        val iconTest = Button(this).apply { text = "Generate icon code, commit and check build" }
        val confirmHealthy = Button(this).apply { text = "Confirm this version works" }
        root.addView(confirmHealthy)
        confirmHealthy.setOnClickListener {
            health.edit().putInt("confirmed_version", currentCode).putInt("quick_restarts", 0).apply()
            recoveryStatus.text = "Version $currentCode confirmed working locally."
            iconTest.isEnabled = true
            updates.isEnabled = true
        }
        val recovery = Button(this).apply { text = "Recovery / rebuild previous version" }
        root.addView(recovery)
        recovery.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Jarvis Developer recovery")
                .setMessage("Installed version: $currentCode\\nLast locally confirmed version: ${if (previousConfirmed >= 0) previousConfirmed.toString() else "none"}\\n\\nTo recover from a bad update, open the GitHub workflow, choose Run workflow, and enter a verified working commit SHA in recovery_sha. The signed rebuild gets a new version code so Android can install it. Previous signed APK: https://github.com/muddigger782012/J.A.R.V.I.S.-/releases/tag/developer-previous. This does not automatically roll back or bypass Android installation approval.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open recovery workflow") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://github.com/muddigger782012/J.A.R.V.I.S.-/actions/workflows/release-jarvis-developer.yml")))
                }.show()
        }
        val githubToken = field("GitHub fine-grained token (encrypted on device)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            ProviderCredentialStore.loadGithub(this@MainActivity)?.let { setText(it) }
        }
        // Keep the credential input and feedback beside its actions: the global status
        // lives on Home and is not visible from the Advanced tools page.
        val githubFeedback = TextView(this).apply {
            text = "Paste your GitHub token, then save the encrypted connection."
            setTextColor(getColor(R.color.developer_secondary))
            textSize = 14f
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setTextIsSelectable(false)
        }
        val pasteGithub = Button(this).apply {
            text = "Paste GitHub token from clipboard"
            isAllCaps = false
            setOnClickListener {
                val result = runCatching {
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()?.trim().orEmpty()
                }
                val pasted = result.getOrNull().orEmpty()
                when {
                    result.isFailure -> githubFeedback.text = "Unable to access clipboard. Copy the token again and retry."
                    pasted.isBlank() -> githubFeedback.text = "Clipboard is empty. Copy your complete GitHub token first."
                    pasted.length > 4096 || pasted.any { it.isISOControl() } ->
                        githubFeedback.text = "Clipboard contains invalid token characters."
                    else -> {
                        githubToken.setText(pasted)
                        githubToken.setSelection(githubToken.length())
                        githubFeedback.text = "Pasted " + pasted.length + " characters. Tap Save GitHub connection securely."
                    }
                }
            }
        }
        root.addView(pasteGithub)
        root.addView(githubFeedback)
        val saveGithub = Button(this).apply {
            text = "Save GitHub connection securely"
            isAllCaps = false
            setOnClickListener {
                val token = githubToken.text.toString().trim()
                if (token.isBlank()) {
                    ProviderCredentialStore.clearGithub(this@MainActivity)
                    githubFeedback.text = "Saved GitHub connection cleared."
                } else {
                    val saved = runCatching { ProviderCredentialStore.saveGithub(this@MainActivity, token) }
                    githubFeedback.text = if (saved.isSuccess) "GitHub token encrypted and saved on this device."
                        else "Unable to save GitHub connection securely."
                }
            }
        }
        root.addView(saveGithub)
        // Move the field immediately above the paste button in the same tools section.
        root.removeView(githubToken)
        root.addView(githubToken, root.indexOfChild(pasteGithub))
        val repositoryInfo = TextView(this).apply {
            text = "Codebase awareness: read-only inventory, source browsing and task plans. No AI key required; GitHub token is optional for public source."
        }
        root.addView(repositoryInfo)
        val sourcePath = field("Source path or directory prefix (blank lists all allowed sources)")
        val sourceRange = field("Source line range, up to 200 lines").apply { setText("1-120") }
        val inventoryButton = Button(this).apply { text = "Browse source inventory (read-only)" }
        val sourceButton = Button(this).apply { text = "Read source lines (read-only)" }
        val repositoryTask = field("Describe the source analysis task here").apply {
            setText(prompt.text.toString())
            minLines = 3
            maxLines = 6
        }
        val analyzeButton = Button(this).apply { text = "Analyze this task (read-only)" }
        listOf(inventoryButton, sourceButton, analyzeButton).forEach { root.addView(it) }
        root.removeView(repositoryTask)
        root.addView(repositoryTask, root.indexOfChild(analyzeButton))
        val repositoryFeedback = TextView(this).apply {
            text = "Choose Browse source inventory to list available repository files."
            textSize = 14f
            setTextColor(getColor(R.color.developer_text))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(repositoryFeedback, root.indexOfChild(analyzeButton) + 1)
        var repositorySnapshot: RepositoryAnalysis.Inventory? = null
        fun repositoryRead(label: String, operation: () -> Pair<RepositoryAnalysis.Inventory, String>) {
            listOf(inventoryButton, sourceButton, analyzeButton).forEach { it.isEnabled = false }
            repositoryFeedback.text = label
            repositoryFeedback.text = label
            thread(name = "DeveloperRepositoryRead") {
                try {
                    val (snapshot, report) = operation()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            repositorySnapshot = snapshot
                            repositoryFeedback.text = report
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            repositoryFeedback.text = when { e.message.orEmpty().contains("HTTP 401") -> "GitHub HTTP 401: authentication failed. Check the saved token."; e.message.orEmpty().contains("HTTP 403") -> "GitHub HTTP 403: permission denied or rate limited."; e.message.orEmpty().contains("HTTP 404") -> "GitHub HTTP 404: repository or branch not accessible."; e.message.orEmpty().contains("response exceeds read limit") -> "GitHub repository response exceeds the configured limit."; e.message.orEmpty().contains("Repository GET failed") -> "GitHub network request failed. Check connectivity."; else -> "Repository preview failed. Check access or source limits." } + " No source was changed."
                        }
                    }
                } finally {
                    runOnUiThread {
                        listOf(inventoryButton, sourceButton, analyzeButton).forEach { it.isEnabled = true }
                    }
                }
            }
        }
        inventoryButton.setOnClickListener {
            val token = githubToken.text.toString().trim()
            val prefix = sourcePath.text.toString().trim()
            repositoryRead("Reading pinned source inventory...") {
                val snapshot = RepositoryIntelligence.inventory(token)
                snapshot to RepositoryBrowser.inventory(snapshot, prefix)
            }
        }
        sourceButton.setOnClickListener {
            val token = githubToken.text.toString().trim()
            val path = sourcePath.text.toString().trim()
            val range = sourceRange.text.toString().trim()
            val selectedSnapshot = repositorySnapshot
            repositoryRead("Reading source at the selected commit...") {
                val snapshot = selectedSnapshot ?: RepositoryIntelligence.inventory(token)
                val source = RepositoryIntelligence.readSource(token, snapshot, path)
                snapshot to RepositoryBrowser.source(snapshot, path, source, range)
            }
        }
        analyzeButton.setOnClickListener {
            val token = githubToken.text.toString().trim()
            val task = repositoryTask.text.toString().trim().ifBlank { prompt.text.toString().trim() }
            if (task.isBlank()) {
                repositoryFeedback.text = "Describe your task in the box above, then tap Analyze this task."
                return@setOnClickListener
            }
            repositoryRead("Inspecting bounded source context and dependencies...") {
                val (snapshot, plan) = RepositoryIntelligence.analyzeSnapshot(token, task)
                snapshot to RepositoryAnalysis.render(plan)
            }
        }
        fun feedback(): Pair<TextView, TextView> = Pair(
            TextView(this).apply { setTextColor(getColor(R.color.developer_text)); setTextIsSelectable(true) },
            TextView(this).apply { setTextColor(getColor(R.color.developer_secondary)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        fun addFeedback(pair: Pair<TextView, TextView>) { root.addView(pair.second); root.addView(pair.first) }
        val codingStart = root.childCount
        // The chat composer is NOT an approved code-task field: typing a follow-up
        // must never invalidate a pinned, already-reviewed diff or change its scope.
        val scopedCodingTask = EditText(this).apply {
            hint = "Code change under review (independent of the main chat)"
            setText(prompt.text.toString().trim())
            minLines = 2
            setSingleLine(false)
        }
        root.addView(scopedCodingTask)
        val codingFeedback = feedback()
        val patchWorkbench = PatchWorkbench.attach(this, root, scopedCodingTask, key, provider, model, githubToken,
            codingFeedback.first, codingFeedback.second,
            { health.getInt("confirmed_version", -1) == currentCode },
            { update -> output.text = update })
        addFeedback(codingFeedback)
        patchPreview = patchWorkbench
        val planningStart = root.childCount
        // Task-plan drafts are distinct from conversational follow-ups. The
        // planning workbench watches its goal field and invalidates coding reviews
        // on plan edits; it must never watch every message typed into chat.
        val scopedPlanGoal = EditText(this).apply {
            hint = "Development-plan goal (independent of the main chat)"
            minLines = 2
            setSingleLine(false)
        }
        root.addView(scopedPlanGoal)
        val planningFeedback = feedback()
        TaskPlanningWorkbench.attach(this, root, scopedPlanGoal, githubToken,
            planningFeedback.first, planningFeedback.second, patchWorkbench)
        addFeedback(planningFeedback)
        val repairStart = root.childCount
        val repairFeedback = feedback()
        RepairWorkbench.attach(this, root, prompt, githubToken, repairFeedback.first, repairFeedback.second, patchWorkbench)
        addFeedback(repairFeedback)
        val selfDevelopmentStart = root.childCount
        val selfFeedback = feedback()
        SelfDevelopmentWorkbench.attach(this, root, prompt, githubToken, selfFeedback.first, selfFeedback.second, patchWorkbench)
        addFeedback(selfFeedback)
        val personalDiagnosticsStart = root.childCount
        val personalFeedback = feedback()
        PersonalRepairWorkbench.attach(this, root, prompt, githubToken, personalFeedback.first, personalFeedback.second, patchWorkbench)
        addFeedback(personalFeedback)
        val codingWidgets = (codingStart until planningStart).map { root.getChildAt(it) }.toSet()
        val planningWidgets = (planningStart until repairStart).map { root.getChildAt(it) }.toSet()
        val repairWidgets = (repairStart until selfDevelopmentStart).map { root.getChildAt(it) }.toSet()
        val selfWidgets = (selfDevelopmentStart until personalDiagnosticsStart).map { root.getChildAt(it) }.toSet()
        val personalWidgets = (personalDiagnosticsStart until root.childCount).map { root.getChildAt(it) }.toSet()
        val projectStartIndex = root.childCount
        applicationWorkbench = ApplicationApprovalWorkbench.attach(this, root)
        val projectViews = (projectStartIndex until root.childCount).map { root.getChildAt(it) }
        root.addView(iconTest)
        if (recoveryWarning) {
            iconTest.isEnabled = false
            updates.isEnabled = false
            status.text = "Recovery mode: AI edits and updates paused until this version is confirmed working."
        }
        val releaseFeedback = feedback()
        iconTest.setOnClickListener {
            iconTest.isEnabled = false
            CodingAgent.run(provider.selectedItem.toString(), key.text.toString().trim(),
                model.text.toString().trim(), githubToken.text.toString().trim(),
                prompt.text.toString().trim()) { message ->
                runOnUiThread {
                    releaseFeedback.second.text = message.take(240)
                    releaseFeedback.first.text = message
                    if (message.startsWith("Coding task failed") ||
                        message.contains("Review the commit and build")) iconTest.isEnabled = true
                }
            }
        }
        updates.setOnClickListener { DeveloperUpdater.check(this) { message -> releaseFeedback.second.text = message } }
        plans.setOnClickListener {
            releaseFeedback.second.text = "Reading GitHub plans..."
            thread {
                try {
                    val approved = GithubPlans.listPlans().map { it to GithubPlans.plan(it) }
                        .filter { (_, body) -> Regex("(?im)^Status:\\s*approved\\s*$").containsMatchIn(body) }
                    runOnUiThread {
                        releaseFeedback.first.text = if (approved.isEmpty()) "No approved plans found." else
                            approved.joinToString("\n\n") { (name, body) -> "$name\n\n$body" }
                        releaseFeedback.second.text = "Plans loaded"
                    }
                } catch (e: Exception) {
                    runOnUiThread { releaseFeedback.second.text = "Plan lookup failed"; releaseFeedback.first.text = e.message }
                }
            }
        }
        builds.setOnClickListener {
            releaseFeedback.second.text = "Checking GitHub Actions..."
            thread {
                try {
                    val result = GithubPlans.latestBuild()
                    runOnUiThread { releaseFeedback.first.text = result; releaseFeedback.second.text = "Build status loaded" }
                } catch (e: Exception) {
                    runOnUiThread { releaseFeedback.second.text = "Build lookup failed"; releaseFeedback.first.text = e.message }
                }
            }
        }

        // Persist the project before contacting an AI provider. A network failure must
        // never discard the user's description or the generated response.
        val projectPrefs = getSharedPreferences("developer_local_projects_v1", MODE_PRIVATE)
        var refreshSavedProjectsAfterChange: (() -> Unit)? = null
        var revealImportedReview: (() -> Unit)? = null
        var chatReviewStaged = false
        var updateChatReviewButton: ((Boolean) -> Unit)? = null
        projectGeneration = ProjectGenerationWorkbench.attach(this, send, cancelGeneration, provider, key, model,
            prompt, output, status, { guidedTarget?.selectedItemPosition ?: 0 }, { guidedTarget },
            { activeProject }, { activeProject = it }, ProjectGenerationStore(projectPrefs),
            { refreshSavedProjectsAfterChange?.invoke() }, githubToken = { githubToken.text.toString() }, forget = forgetDiscussion,
            requestPatch = { proposeCodePatch.isChecked },
            stageReview = { taskText, summary, edits, commitSha, patchTarget ->
                val staged = patchWorkbench.importChatProposal(taskText, summary, edits, commitSha, patchTarget)
                chatReviewStaged = staged
                updateChatReviewButton?.invoke(staged)
                if (staged) revealImportedReview?.invoke()
                staged
            }, requestDeveloperRepair = {
                val started = patchWorkbench.prepareDeveloperRepair()
                if (started) revealImportedReview?.invoke()
                started
            }, requestDeveloperBuild = { patchWorkbench.refreshDeveloperCi() },
            saveDeveloperTask = { patchWorkbench.saveDeveloperBookmark() },
            readSavedDeveloperBuild = { patchWorkbench.readSavedDeveloperBuild() },
            approveDeveloperDiff = { fingerprint -> patchWorkbench.approveDeveloperDiff(fingerprint) },
            showDeveloperDiff = { patchWorkbench.showDeveloperDiff() },
            continueDeveloperPlan = { id, description, paths, base ->
                val started = patchWorkbench.continueDeveloperPlan(id, description, paths, base)
                if (started) revealImportedReview?.invoke()
                started
            }, currentDeveloperCommit = { patchWorkbench.currentDeveloperCommit() },
            mayReadSavedCompletion = { patchWorkbench.mayReadSavedCompletion() },
            onUserMessage = { request -> homeWorkspace?.sent(request) },
            screenshot = { selectedScreenshot },
            screenshotLoading = { screenshotLoading },
            onScreenshotSent = { sent ->
                if (selectedScreenshot === sent) {
                    selectedScreenshot = null
                    screenshotPreview?.setImageDrawable(null)
                    screenshotPreview?.visibility = View.GONE
                    screenshotLabel?.text = "Screenshot sent for analysis; attach another if needed."
                }
            },
            requestDeveloperSourceAnalysis = {
                // Explicit inspection never triggers AI or a source write.
                if (!analyzeFromHome.isEnabled) false
                else {
                    pendingAutoPreviewTask = null
                    analyzeFromHome.performClick()
                    true
                }
            },
            requestDeveloperAutonomousPreview = {
                // User requested an actual Developer implementation. The source
                // scanner chooses a bounded allowlist; PatchWorkbench creates
                // only a fresh, exact-source diff PREVIEW (no approval or write).
                if (!analyzeFromHome.isEnabled || prompt.text.isNullOrBlank()) false
                else {
                    pendingAutoPreviewTask = prompt.text.toString().trim()
                    analyzeFromHome.performClick()
                    true
                }
            })
        // Preserve all existing controls while separating the beginner home from tools.
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 20)
            setBackgroundColor(getColor(R.color.developer_panel))
        }
        val advancedStart = root.indexOfChild(plans)
        while (root.childCount > advancedStart) {
            val child = root.getChildAt(advancedStart)
            root.removeViewAt(advancedStart)
            advanced.addView(child)
        }
        val projects = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundColor(getColor(R.color.developer_background))
        }
        projects.addView(TextView(this).apply {
            text = "My projects"
            textSize = 23f
            setTextColor(getColor(R.color.developer_primary))
        })
        projects.addView(TextView(this).apply {
            text = "Start a new project or continue working on one. Jarvis will guide you through each step."
            setTextColor(0xFFC6DDE5.toInt())
        })
        projectViews.forEach { widget ->
            (widget.parent as? android.view.ViewGroup)?.removeView(widget)
            projects.addView(widget)
        }
        // Existing projects are readable and recoverable after a failed AI request.
        val savedProjects = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val refreshProjects = Button(this).apply { text = "Refresh saved projects"; isAllCaps = false }
        fun refreshSavedProjects() {
            savedProjects.removeAllViews()
            val records = projectPrefs.all.values.mapNotNull { raw ->
                try { org.json.JSONObject(raw as String) } catch (_: Exception) { null }
            }.sortedByDescending { it.optLong("updated_at") }
            if (records.isEmpty()) {
                savedProjects.addView(TextView(this).apply { text = "No saved projects yet." })
            }
            records.forEach { record ->
                val description = record.optString("description")
                val state = ProjectGenerationStore.stateLabel(record)
                val response = record.optString("response")
                savedProjects.addView(Button(this).apply {
                    text = description.take(80) + " (" + state + ")"
                    isAllCaps = false
                    setOnClickListener {
                        if (!patchWorkbench.invalidate()) {
                            status.setText(R.string.project_generation_finish_code_operation)
                            return@setOnClickListener
                        }
                        projectGeneration?.cancel()
                        activeProject = record.optString("id").takeIf { it.isNotBlank() }
                        prompt.setText(description)
                        guidedTarget?.setSelection(record.optInt("target", 0).coerceIn(0, 1))
                        output.text = if (response.isBlank()) getString(R.string.project_generation_restored_without_reply)
                            else if (ProjectGenerationStore.replyMatches(record) == true) response
                            else getString(R.string.project_generation_previous_reply, response)
                        status.text = "Loaded saved project. Description restored."
                        selectPage?.invoke(0)
                    }
                })
            }
        }
        refreshSavedProjectsAfterChange = ::refreshSavedProjects
        refreshProjects.setOnClickListener { refreshSavedProjects() }
        // The single Home review panel owns preview, approval and GitHub writes.
        // Remove the legacy modal review path, which bypassed pinned existing-file reads.
        val reviewFromChat = Button(this).apply {
            text = "Review proposed changes"
            isAllCaps = false
            // An AI explanation is not a patch; never show a misleading
            // review action until a source-pinned preview has been started.
            isEnabled = false
            visibility = View.GONE
            setOnClickListener {
                if (chatReviewStaged) revealImportedReview?.invoke()
                else status.text = "No code proposal is staged. Choose one feature and ask Jarvis to implement it."
            }
        }
        updateChatReviewButton = { ready ->
            reviewFromChat.isEnabled = ready
            reviewFromChat.visibility = if (ready) View.VISIBLE else View.GONE
        }
        val reviewPosition = root.indexOfChild(output)
        root.addView(reviewFromChat, reviewPosition + 1)
        projects.addView(refreshProjects)
        projects.addView(savedProjects)
        refreshSavedProjects()
        val activityPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.developer_background))
        }
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundColor(getColor(R.color.developer_panel))
        }
        settings.addView(TextView(this).apply {
            text = "Settings"
            textSize = 22f
            setTextColor(getColor(R.color.developer_primary))
        })
        settings.addView(TextView(this).apply { text = "API key — not a model name"; textSize = 16f; setTextColor(getColor(R.color.developer_primary)) })
        listOf(key, provider, model).forEach { widget ->
            (widget.parent as? android.view.ViewGroup)?.removeView(widget)
            settings.addView(widget)
        }
        val credentialStatus = TextView(this).apply {
            setTextColor(getColor(R.color.developer_secondary))
            text = if (ProviderCredentialStore.load(this@MainActivity) != null)
                "AI connection saved securely on this device." else "No AI connection saved yet."
        }
        val saveConnection = Button(this).apply {
            text = "Save AI connection"
            isAllCaps = false
        }
        val forgetConnection = Button(this).apply {
            text = "Remove saved connection"
            isAllCaps = false
        }
        fun renderConnection() {
            setupHint.text = connectionVerification.detail
        }
        connectionVerification.onChanged = {
            renderConnection()
            credentialStatus.text = connectionVerification.detail
        }
        updateConnectionSetup = {
            connectionVerification.select(provider.selectedItem.toString(), key.text.toString().trim(), model.text.toString().trim())
            renderConnection()
        }
        val connectionChanges = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                updateConnectionSetup()
                credentialStatus.text = connectionVerification.detail
            }
        }
        listOf(key, model).forEach { it.addTextChangedListener(connectionChanges) }
        updateConnectionSetup()
        // Verify the saved provider automatically at startup. Connection status belongs in
        // the existing Home status line, not behind a manual test button.
        fun verifySavedConnection() {
            val saved = ProviderCredentialStore.load(this@MainActivity) ?: return
            val (savedProvider, savedKey, savedModel) = saved
            if (savedKey.isBlank()) return
            connectionVerification.select(savedProvider, savedKey, savedModel)
            val ticket = connectionVerification.begin()
            renderConnection()
            thread(name = "DeveloperAutomaticConnectionCheck") {
                var successful = false
                val message = try {
                    CodingAssistantProvider.testConnection(savedProvider, savedKey, savedModel)
                    successful = true
                    "$savedProvider connection successful."
                } catch (e: CodingAssistantProvider.ProviderFailure) {
                    e.message ?: "AI connection failed."
                } catch (_: Exception) {
                    "AI connection failed. Check provider and model in Settings."
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed &&
                        connectionVerification.finish(ticket, successful, message)) {
                        credentialStatus.text = message
                        renderConnection()
                    }
                }
            }
        }
        verifySavedConnection()
        val discoverModels = Button(this).apply {
            text = "Find models available to my API key"
            isAllCaps = false
        }
        settings.addView(discoverModels)
        discoverModels.setOnClickListener {
            if (provider.selectedItem.toString() != "Gemini") {
                credentialStatus.text = "Model discovery currently supports Gemini."
                return@setOnClickListener
            }
            val apiKey = key.text.toString().trim()
            if (apiKey.isBlank()) {
                credentialStatus.text = "Enter your Gemini API key first."
                return@setOnClickListener
            }
            val discoveryModel = model.text.toString().trim()
            discoverModels.isEnabled = false
            credentialStatus.text = "Checking Gemini models available to this key..."
            thread(name = "GeminiModelDiscovery") {
                var safeFailure: String? = null
                val result = try {
                    CodingAssistantProvider.availableGeminiModels(apiKey)
                } catch (e: CodingAssistantProvider.ProviderFailure) {
                    safeFailure = e.message ?: "Model discovery failed."
                    emptyList()
                } catch (_: Exception) {
                    safeFailure = "Model discovery failed due to a network or response error."
                    emptyList()
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        discoverModels.isEnabled = true
                        if (provider.selectedItem.toString() != "Gemini" || key.text.toString().trim() != apiKey ||
                            model.text.toString().trim() != discoveryModel) return@runOnUiThread
                        if (result.isEmpty()) {
                            credentialStatus.text = safeFailure ?: "No compatible models returned for this API key."
                        } else {
                            credentialStatus.text = "${result.size} compatible Gemini models found. Choose one."
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Available Gemini models")
                                .setItems(result.toTypedArray()) { _, index ->
                                    if (provider.selectedItem.toString() == "Gemini" && key.text.toString().trim() == apiKey &&
                                        model.text.toString().trim() == discoveryModel) model.setText(result[index])
                                    credentialStatus.text = "Selected ${result[index]}. Save and test connection."
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            }
        }
        settings.addView(saveConnection)
        settings.addView(forgetConnection)
        settings.addView(credentialStatus)
        saveConnection.setOnClickListener {
            val selectedKey = key.text.toString().trim()
            if (selectedKey.isBlank()) {
                credentialStatus.text = "Enter an actual API key, not a model name."
            } else {
                try {
                    ProviderCredentialStore.save(this, provider.selectedItem.toString(),
                        selectedKey, model.text.toString().trim())
                    credentialStatus.text = "Connection saved securely. Checking connection..."
                    updateConnectionSetup()
                    verifySavedConnection()
                } catch (_: Exception) {
                    credentialStatus.text = "Unable to save securely. Check device security settings."
                }
            }
        }
        forgetConnection.setOnClickListener {
            projectGeneration?.cancel()
            ProviderCredentialStore.clear(this)
            key.text.clear()
            model.text.clear()
            credentialStatus.text = "Saved connection removed from this device."
            updateConnectionSetup()
        }
        // Model presets are suggestions, not a fixed list of provider entitlements.
        val modelChoices = listOf(
            "Automatic (recommended)",
            "Gemini Flash — fast everyday coding",
            "Gemini 2.5 Pro — complex reasoning and architecture",
            "Gemini 2.5 Flash-Lite — lightweight requests",
            "Custom model ID"
        )
        val openAiChoices = listOf(
            "Automatic (recommended)",
            "GPT-4.1 mini — fast coding and explanations",
            "GPT-4.1 — larger development tasks",
            "GPT-4.1 nano — lightweight requests",
            "Custom model ID"
        )
        val modelPickerTitle = TextView(this).apply {
            text = "Choose an AI model"
            textSize = 16f
            setTextColor(getColor(R.color.developer_primary))
        }
        val modelDescription = TextView(this).apply {
            setTextColor(getColor(R.color.developer_secondary))
            textSize = 13f
        }
        val modelPicker = android.widget.Spinner(this)
        val modelPickerIndex = settings.indexOfChild(model)
        settings.addView(modelPickerTitle, modelPickerIndex)
        settings.addView(modelPicker, modelPickerIndex + 1)
        settings.addView(modelDescription, modelPickerIndex + 2)
        var updatingModelPicker = false
        fun refreshModelPicker() {
            val gemini = provider.selectedItem.toString() == "Gemini"
            val choices = if (gemini) modelChoices else openAiChoices
            val selectedId = model.text.toString().trim()
            val ids = if (gemini)
                listOf("", CodingAssistantProvider.DEFAULT_GEMINI_MODEL, "gemini-2.5-pro", "gemini-2.5-flash-lite")
            else listOf("", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano")
            updatingModelPicker = true
            modelPicker.adapter = android.widget.ArrayAdapter(this,
                android.R.layout.simple_spinner_dropdown_item, choices)
            val match = ids.indexOf(selectedId)
            modelPicker.setSelection(if (match >= 0) match else 4)
            modelDescription.text = if (match == 0) "Jarvis selects the provider's default model." else
                if (match == -1) "Custom model: enter the exact model ID below." else
                    "Suggested use: ${choices[match].substringAfter(" — ", "automatic selection")}."
            updatingModelPicker = false
        }
        var firstModelSelection = true
        modelPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                position: Int, id: Long) {
                if (updatingModelPicker) return
                if (firstModelSelection) { firstModelSelection = false; refreshModelPicker(); return }
                val gemini = provider.selectedItem.toString() == "Gemini"
                val ids = if (gemini)
                    listOf("", CodingAssistantProvider.DEFAULT_GEMINI_MODEL, "gemini-2.5-pro", "gemini-2.5-flash-lite")
                else listOf("", "gpt-4.1-mini", "gpt-4.1", "gpt-4.1-nano")
                if (position < ids.size) model.setText(ids[position])
                modelDescription.text = when (position) {
                    0 -> "Recommended: automatically use the provider default."
                    1 -> if (gemini) "Fast everyday coding, chat, and project planning."
                         else "Fast coding assistance and beginner-friendly explanations."
                    2 -> "More demanding coding, architecture, and reasoning tasks; may cost more."
                    3 -> "Lightweight tasks, simple edits, and lower-cost requests."
                    else -> "Enter a supported model ID in the field below."
                }
            }
        }
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?,
                position: Int, id: Long) {
                // Do not carry a Gemini model ID into an OpenAI request or vice versa.
                val current = model.text.toString().trim()
                if ((position == 0 && current.startsWith("gpt-")) ||
                    (position == 1 && current.startsWith("gemini-"))) model.text.clear()
                refreshModelPicker()
                updateConnectionSetup()
                credentialStatus.text = connectionVerification.detail
            }
        }
        refreshModelPicker()
        // Plain-language introductions for every destination; advanced controls remain available.
        fun sectionIntro(container: LinearLayout, heading: String, description: String) {
            container.addView(TextView(this).apply {
                text = heading
                textSize = 23f
                setTextColor(getColor(R.color.developer_primary))
                setPadding(4, 12, 4, 6)
            }, 0)
            container.addView(TextView(this).apply {
                text = description
                textSize = 15f
                setTextColor(getColor(R.color.developer_secondary))
                setPadding(4, 0, 4, 18)
            }, 1)
        }
        sectionIntro(advanced, "Assistant and development tools",
            "Describe a change on Home to ask Jarvis for help. Here you can review code, check builds, and use advanced development tools.")
        sectionIntro(settings, "Connect your AI",
            "Choose Gemini or OpenAI, enter your API key, then tap Save connection. The key is encrypted on this device.")
        val developmentInbox = DevelopmentInboxWorkbench.attach(this, activityPage, settings, patchWorkbench,
            { githubToken.text.toString().trim() }, savedInstanceState?.getBundle("developer_job_history"),
            { artifactReviewJob = null }) { review ->
            // Preserve the validated legacy diagnostic format for PersonalRepair.parse.
            // The text watcher invalidates any previously staged proposal before loading it.
            prompt.setText(review)
            output.text = "Queued review loaded. Verify the source and exact proposed scope using the existing repair workbench. Nothing was executed."
            status.text = "Unapproved diagnostic review loaded."
            selectPage?.invoke(0)
        }
        developmentHistory = developmentInbox
        // Keep updates discoverable in Settings instead of hiding them under developer tools.
        (updates.parent as? android.view.ViewGroup)?.removeView(updates)
        settings.addView(TextView(this).apply {
            text = "App updates"
            textSize = 19f
            setTextColor(getColor(R.color.developer_primary))
            setPadding(0, dp(18), 0, dp(6))
        })
        settings.addView(TextView(this).apply {
            text = "Check for and install signed J.A.R.V.I.S. Developer updates."
            setTextColor(getColor(R.color.developer_secondary))
        })
        settings.addView(updates)
        val updateStatusInSettings = TextView(this).apply {
            setTextColor(getColor(R.color.developer_secondary))
            text = "Updates are checked when you tap the button."
        }
        settings.addView(updateStatusInSettings)
        updates.setOnClickListener {
            DeveloperUpdater.check(this) { message ->
                updateStatusInSettings.text = message
            }
        }
        // Give every secondary screen the same rounded, readable Jarvis surfaces.
        fun roundedSurface(color: Int = getColor(R.color.developer_panel)) =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = 24f
                setStroke(2, 0xFF285563.toInt())
            }
        listOf(projects, settings).forEach { page ->
            page.setPadding(24, 24, 24, 32)
        }
        listOf(key, model, githubToken, sourcePath, sourceRange).forEach { input ->
            input.setPadding(20, 18, 20, 18)
            input.background = roundedSurface(0xFF112A35.toInt())
            input.setSingleLine(true)
        }
        provider.setPadding(16, 12, 16, 12)
        provider.background = roundedSurface(0xFF112A35.toInt())
        // Match the same visual language in the guided Projects workbench.
        projectViews.filterIsInstance<Button>().forEach { button ->
            button.isAllCaps = false
            button.setTextColor(getColor(R.color.developer_primary))
            button.background = roundedSurface(0xFF112A35.toInt())
        }
        listOf(plans, builds, updates, recovery, inventoryButton, sourceButton,
            analyzeButton, iconTest, confirmHealthy).forEach { button ->
            button.isAllCaps = false
            button.setTextColor(getColor(R.color.developer_primary))
            button.background = roundedSurface(0xFF112A35.toInt())
        }
        // Organize the existing engineering controls into small, task-oriented sections.
        // The original views and listeners are retained; only their presentation changes.
        val advancedChildren = (0 until advanced.childCount).map { advanced.getChildAt(it) }
        advancedChildren.forEach { advanced.removeView(it) }
        val toolsTitle = TextView(this).apply {
            text = "What would you like to do?"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.developer_primary))
            setPadding(0, dp(16), 0, dp(12))
        }
        advanced.addView(toolsTitle)
        val toolsHint = TextView(this).apply {
            text = "Choose a task below. Advanced engineering controls are kept available without crowding the screen."
            setTextColor(getColor(R.color.developer_secondary))
            setPadding(0, 0, 0, dp(16))
        }
        advanced.addView(toolsHint)
        val sectionNames = listOf(
            "Connections and source browsing",
            "Code changes and review",
            "Planning",
            "Testing and repair",
            "Self-development",
            "Release verification",
            "Assistant diagnostics"
        )
        // Partition by actual view identity, so intro text and moved controls cannot shift groups.
        val sectionGroups = sectionNames.map {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        }
        // Reuse the actual patch engine controls on Home. The panel starts
        // collapsed, retaining its existing preview, diff, approval and commit gates.
        val homeCodeReview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val homeCodeReviewToggle = Button(this).apply {
            text = "Open inline code review"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                visibility = View.VISIBLE
                val opening = homeCodeReview.visibility != View.VISIBLE
                homeCodeReview.visibility = if (opening) View.VISIBLE else View.GONE
                text = if (opening) "Close inline code review" else "Open inline code review"
            }
        }
        root.addView(homeCodeReviewToggle)
        root.addView(homeCodeReview)
        revealImportedReview = {
            homeCodeReviewToggle.visibility = View.VISIBLE
            homeCodeReview.visibility = View.VISIBLE
            homeCodeReviewToggle.text = "Close inline code review"
            homeCodeReviewToggle.post { homeCodeReviewToggle.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, homeCodeReviewToggle.width, homeCodeReviewToggle.height), false) }
        }
        stageHomeAnalysis = {
            val plan = homeAnalysisPlan
            if (plan != null && plan.task == prompt.text.toString().trim() &&
                plan.files.isNotEmpty() && plan.files.size <= RepositoryAnalysis.MAX_PLANNED_FILES) {
                // Lexical analysis can select documentation, protected files, or other paths
                // outside the explicitly scoped patch engine. Never send those paths to
                // the coding provider or weaken PatchEngine.allowedPath to accommodate them.
                val eligiblePaths = plan.files.map { it.path }.distinct().filter {
                    PatchEngine.allowedPath(it, PatchEngine.Target.DEVELOPER)
                }.take(PatchEngine.MAX_FILES)
                val excludedCount = plan.files.size - eligiblePaths.size
                val automatic = pendingAutoPreviewTask == plan.task
                pendingAutoPreviewTask = null  // one use, even if staging fails
                val taskId = "analysis-" + plan.analysisId.take(16)
                val loaded = eligiblePaths.isNotEmpty() && if (automatic)
                    patchWorkbench.continueDeveloperPlan(
                        taskId, plan.task, eligiblePaths, plan.commitSha)
                else patchWorkbench.prepareGuided(
                    taskId, plan.task, eligiblePaths, plan.commitSha,
                    PatchEngine.Target.DEVELOPER)
                if (loaded) {
                    homeCodeReviewToggle.visibility = View.VISIBLE
                    homeCodeReview.visibility = View.VISIBLE
                    homeCodeReviewToggle.text = "Close inline code review"
                    stageHomeAnalysisButton.isEnabled = false
                    homeHandoffStatus.text = if (automatic)
                        "Source-scoped Developer diff preview running for ${eligiblePaths.size} files at ${plan.commitSha.take(12)}; ${excludedCount} analysis-only files excluded. Review and approve exact changes before any write."
                    else
                        "Loaded ${eligiblePaths.size} editable source files at ${plan.commitSha.take(12)}; ${excludedCount} analysis-only paths excluded. Generate a fresh diff and explicitly approve it before any write."
                    homeHandoffStatus.visibility = View.VISIBLE
                } else {
                    homeHandoffStatus.text = if (eligiblePaths.isEmpty()) "Analysis found no files eligible for the protected coding scope. Refine the task to Developer Kotlin, Java, or supported XML source. No files were modified." else "Code review could not stage ${eligiblePaths.size} eligible files (workbench busy or source validation rejected). No files were modified."
                    homeHandoffStatus.visibility = View.VISIBLE
                }
            }
        }
        openInlineReview = {
            homeCodeReviewToggle.visibility = View.VISIBLE
            homeCodeReview.visibility = View.VISIBLE
            homeCodeReviewToggle.text = "Close inline code review"
            homeCodeReviewToggle.post {
                homeCodeReviewToggle.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, homeCodeReviewToggle.width, homeCodeReviewToggle.height), false)
            }
        }
        advancedChildren.forEach { child ->
            if (child in codingWidgets) {
                homeCodeReview.addView(child)
                return@forEach
            }
            val section = when (child) {
                in codingWidgets -> 1
                in planningWidgets -> 2
                in repairWidgets -> 3
                in selfWidgets -> 4
                in personalWidgets -> 6
                else -> 0
            }
            sectionGroups[section].addView(child)
        }
        // Repair bookmarks are recovery evidence, not a code-approval control.
        // Move the ORIGINAL bookmark panel to the visible Track page. Previously
        // it was trapped inside the collapsed Home diff-review panel, preventing
        // the owner from loading saved history after an Activity recreation.
        // This changes view placement only: no bookmark, source scope, approval
        // or commit capability is recreated or restored.
        homeCodeReview.findViewWithTag<LinearLayout>("developer-private-repair-history")?.let { history ->
            (history.parent as? android.view.ViewGroup)?.removeView(history)
            activityPage.addView(history)
        }
        // Promote the everyday build and recovery actions into their own section.
        // Keep the original button objects and click listeners.
        listOf(plans, builds, recoveryStatus, confirmHealthy, recovery, iconTest, releaseFeedback.second, releaseFeedback.first).forEach { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            sectionGroups[5].addView(view)
        }
        // Source feedback must be adjacent to its source buttons.
        // Display a concise initial message instead of an empty results pane.
        repositoryFeedback.text = "Source browsing results and errors will appear here."
        // Keep frequently used actions discoverable in their task section.
        // Moving a view preserves its original listener, enabled state and approval gate.
        listOf(inventoryButton, sourceButton, analyzeButton, repositoryFeedback).forEach { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            sectionGroups[0].addView(view)
        }
        // The exact-diff, approvals and build/test results are also reachable from Activity.
        val codingViews = (0 until sectionGroups[1].childCount).map { sectionGroups[1].getChildAt(it) }
        codingViews.forEach { sectionGroups[1].removeView(it); activityPage.addView(it) }
        sectionGroups[1].addView(Button(this).apply {
            text = "Open code review on Home"; isAllCaps = false
            setOnClickListener {
                selectPage?.invoke(0)
                openInlineReview?.invoke()
            }
        })
        // Always give a clear route back to the category overview.
        val sectionIntro = TextView(this).apply {
            text = "Tap a category to open its tools. Tap it again to close."
            setTextColor(getColor(R.color.developer_secondary))
            setPadding(0, dp(4), 0, dp(10))
        }
        advanced.addView(sectionIntro)
        var openSection = -1
        val sectionButtons = mutableListOf<Button>()
        sectionNames.forEachIndexed { index, name ->
            val button = Button(this).apply {
                isAllCaps = false
                text = name
                textSize = 16f
                setTextColor(getColor(R.color.developer_primary))
                background = roundedSurface(0xFF142F3B.toInt())
                contentDescription = "Open $name tools"
            }
            advanced.addView(button)
            advanced.addView(sectionGroups[index])
            sectionButtons.add(button)
            button.setOnClickListener {
                dismissKeyboard()
                openSection = if (openSection == index) -1 else index
                openToolSection = openSection
                sectionGroups.forEachIndexed { i, group ->
                    group.visibility = if (i == openSection) View.VISIBLE else View.GONE
                    sectionButtons[i].text = sectionNames[i] +
                        if (i == openSection) "  ▲" else "  ▼"
                    sectionButtons[i].contentDescription =
                        (if (i == openSection) "Close " else "Open ") + sectionNames[i] + " tools"
                }
            }
        }
        sectionButtons.forEachIndexed { i, button -> button.text = sectionNames[i] + "  ▼" }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundColor(getColor(R.color.developer_background))
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        // Home can launch project creation without exposing advanced controls.
        val createProject = Button(this).apply {
            text = "My projects"
            isAllCaps = false
            contentDescription = "Open project creation tools"
        }
        listOf(send, createProject).forEach { button ->
            button.isAllCaps = false
            button.setTextColor(0xFF071B25.toInt())
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.developer_primary))
        }
        fun openCodeReview() {
            // The exact review engine is already hosted on Home, not Track.
            selectPage?.invoke(0)
            openInlineReview?.invoke()
            codingWidgets.firstOrNull()?.let { first -> first.post {
                first.requestRectangleOnScreen(android.graphics.Rect(0, 0, first.width, 1), false)
            } }
        }
        val guidance = GuidedDevelopmentWorkbench.attach(this, root, prompt, patchWorkbench,
            savedInstanceState?.getInt("developer_guided_target", 0) ?: 0, ::openCodeReview)
        guidedTarget = guidance.first
        disposeGuidance = guidance.second
        sectionIntro(activityPage, "Track", "Live development job history, reviews, failures, and verified results. Pending work is not marked complete.")
        settings.addView(Button(this).apply {
            text = "Advanced tools"; isAllCaps = false
            setOnClickListener { selectPage?.invoke(4) }
        }, 2)
        settings.addView(TextView(this).apply {
            text = "Appearance: current cyan HUD and reactor branding. Android display size and font settings are respected."
            setTextColor(getColor(R.color.developer_secondary))
        })
        settings.addView(Button(this).apply {
            text = "Permissions and installation access"; isAllCaps = false
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"))) }
        })
        // GitHub credentials belong to Settings, and remain memory-only.
        (githubToken.parent as? android.view.ViewGroup)?.removeView(githubToken)
        settings.addView(githubToken, 3)
        settings.addView(TextView(this).apply {
            text = "GitHub access (optional for public reads; token is not saved)"
            setTextColor(getColor(R.color.developer_primary))
        }, 3)
        projects.addView(Button(this).apply {
            text = "Browse repository sources"; isAllCaps = false
            setOnClickListener {
                selectPage?.invoke(4)
                if (openSection != 0) sectionButtons[0].performClick()
            }
        }, 2)
        projects.addView(Button(this).apply {
            text = "Resume saved development plans"; isAllCaps = false
            setOnClickListener {
                selectPage?.invoke(4)
                if (openSection != 2) sectionButtons[2].performClick()
            }
        }, 3)
        DeveloperUiState.descendants(shell).forEach { it.isSaveEnabled = false }
        // Move status feedback to the assistant screen when the user selects Tools.
        output.setPadding(dp(4), dp(12), dp(4), dp(12))
        prompt.apply {
            minLines = 4
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = roundedSurface(0xFF142F3B.toInt())
        }
        homeWorkspace = DeveloperHomeWorkspace.install(this, root, advanced,
            prompt, send, output, status, codingFeedback.second,
            reviewFromChat, homeCodeReviewToggle, homeCodeReview,
            guidedTarget?.selectedItemPosition ?: 0,
            { selected ->
                if (guidedTarget?.isEnabled != false) guidedTarget?.setSelection(selected)
                else homeWorkspace?.syncTarget(guidedTarget?.selectedItemPosition ?: 0)
            },
            { selectPage?.invoke(4) }, savedInstanceState)
        // One direct attachment control inside the existing chat composer.
        // ACTION_OPEN_DOCUMENT needs no media/storage permission and never uploads
        // without a subsequent explicit Send press.
        val screenshotControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val attachScreenshot = Button(this).apply {
            text = "Attach screenshot"
            isAllCaps = false
            contentDescription = "Choose an error screenshot from your files"
            setOnClickListener {
                if (!send.isEnabled || screenshotLoading) return@setOnClickListener
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                }
                try { startActivityForResult(picker, screenshotPickerRequest) }
                catch (_: ActivityNotFoundException) {
                    screenshotLabel?.text = "No Android image picker is available."
                }
            }
        }
        screenshotControls.addView(attachScreenshot,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        screenshotControls.addView(Button(this).apply {
            text = "Remove"
            isAllCaps = false
            contentDescription = "Remove attached screenshot"
            setOnClickListener {
                if (!send.isEnabled) return@setOnClickListener
                screenshotReadGeneration++
                screenshotLoading = false
                selectedScreenshot = null
                screenshotPreview?.setImageDrawable(null)
                screenshotPreview?.visibility = View.GONE
                screenshotLabel?.text = "No screenshot attached."
            }
        })
        screenshotLabel = TextView(this).apply {
            text = "Optional: attach one screenshot. It stays on your phone until you press Send; " +
                "check it for passwords, tokens and other private information."
            textSize = 12f
            setTextColor(getColor(R.color.developer_secondary))
            tag = "developer-screenshot-status"
        }
        screenshotPreview = ImageView(this).apply {
            tag = "developer-screenshot-preview"
            visibility = View.GONE
            adjustViewBounds = true
            maxHeight = dp(136)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Selected debugging screenshot preview"
        }
        homeWorkspace?.composer?.apply {
            addView(screenshotControls, 1)
            addView(screenshotLabel, 2)
            addView(screenshotPreview, 3)
        }
        val pages = listOf(root, projects, activityPage, settings, advanced).mapIndexed { index, page ->
            ScrollView(this).apply {
                tag = "developer-page-$index"
                addView(page)
                isFillViewport = true
                visibility = View.GONE
            }
        }
        val tabs = mutableListOf<Button>()
        val positions = savedInstanceState?.getIntArray("developer_scroll")?.takeIf { it.size == pages.size } ?: IntArray(pages.size)
        pageViews = pages
        fun showPage(index: Int, remember: Boolean = true) {
            val next = index.coerceIn(0, pages.lastIndex)
            if (next != activePage) {
                DeveloperUpdater.cancel(this, report = true)
                dismissKeyboard()
                positions[activePage] = pages[activePage].scrollY
                if (remember) pageHistory.add(activePage)
            }
            val enteringTrack = next == 2 && activePage != 2
            val previousPage = activePage
            activePage = next
            if (next == 0) {
                homeWorkspace?.syncTarget(guidedTarget?.selectedItemPosition ?: 0)
                if (activePage != previousPage) homeWorkspace?.refreshJobEvidence()
            }
            homeWorkspace?.composer?.visibility = if (next == 0) View.VISIBLE else View.GONE
            if (enteringTrack) developmentHistory?.refreshNow()
            pages.forEachIndexed { pageIndex, page ->
                page.visibility = if (pageIndex == activePage) View.VISIBLE else View.GONE
            }
            tabs.forEachIndexed { tabIndex, button ->
                val selected = tabIndex == activePage || (activePage == 4 && tabIndex == 3)
                button.isSelected = selected
                button.alpha = if (selected) 1f else 0.78f
                button.background = GradientDrawable().apply {
                    setColor(if (selected) 0xFF173F4B.toInt()
                        else getColor(R.color.developer_panel))
                    cornerRadius = 18f
                }
                button.contentDescription = button.text.toString() +
                    if (selected) ", selected" else ""
            }
            pages[activePage].post { pages[activePage].scrollTo(0, positions[activePage]) }
        }
        selectPage = { showPage(it) }
        navigateBack = {
            if (activePage == 4 && openSection >= 0) {
                sectionButtons[openSection].performClick(); true
            } else if (pageHistory.isNotEmpty()) {
                showPage(pageHistory.removeAt(pageHistory.lastIndex), false); true
            } else if (activePage != 0) { showPage(0, false); true } else false
        }
        listOf(projects, activityPage, settings, advanced).forEach { page ->
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(Button(this).apply {
                text = "Back"; isAllCaps = false; setOnClickListener { onBackPressed() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            controls.addView(Button(this).apply {
                text = "Close"; contentDescription = "Close page and return Home"; isAllCaps = false
                setOnClickListener { pageHistory.clear(); showPage(0, false) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            page.addView(controls, 0)
        }
        fun tab(label: String, pageIndex: Int, iconResource: Int) = Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            gravity = Gravity.CENTER
            setPadding(2, 8, 2, 8)
            setTextColor(getColor(R.color.developer_primary))
            setCompoundDrawablesWithIntrinsicBounds(0, iconResource, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.developer_primary))
            compoundDrawablePadding = 4
            setOnClickListener { showPage(pageIndex) }
            tabs.add(this)
            navigation.addView(this, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        createProject.setOnClickListener {
            if (!patchWorkbench.invalidate()) {
                status.setText(R.string.project_generation_finish_code_operation)
                return@setOnClickListener
            }
            projectGeneration?.cancel()
            activeProject = null
            prompt.text.clear()
            output.text = ""
            homeWorkspace?.reset()
            status.text = "Describe your new project."
            showPage(1)
        }
        tab("Home", 0, android.R.drawable.ic_menu_view)
        tab("Projects", 1, android.R.drawable.ic_menu_agenda)
        tab("Track", 2, android.R.drawable.ic_menu_recent_history)
        tab("Settings", 3, android.R.drawable.ic_menu_preferences)
        showPage(activePage, false)
        pages.forEach { page ->
            shell.addView(page, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Composer stays visible below the scrolling conversation on Home.
        homeWorkspace?.composer?.let { shell.addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )) }
        navigation.setBackgroundColor(getColor(R.color.developer_panel))
        shell.addView(navigation)
        uiRoot = shell
        // Keep the visible/accessibility labels identical to the reviewed action
        // names; legacy workbenches otherwise inherit Android's all-caps transform.
        DeveloperUiState.descendants(shell).filterIsInstance<Button>().forEach { it.isAllCaps = false }
        DeveloperUiState.descendants(shell).forEach { it.isSaveEnabled = false }
        DeveloperUiState.restore(shell, savedInstanceState?.getBundle("developer_drafts"))
        savedInstanceState?.getInt("developer_tool_section", -1)?.takeIf { it in sectionButtons.indices }
            ?.let { sectionButtons[it].performClick() }
        setContentView(shell)
        fun openArtifactJob(job: String) {
            showPage(2)
            artifactReviewJob = job
            developmentInbox.focus(job)
            developmentInbox.view.post {
                developmentInbox.view.requestRectangleOnScreen(android.graphics.Rect(0, 0, developmentInbox.view.width, 1), false)
            }
        }
        artifactInstallWhenFocused = {
            val pending = pendingArtifactInstall
            if (pending != null && hasWindowFocus() && !isFinishing && !isDestroyed) {
                pendingArtifactInstall = null
                val (generation, ticket) = pending
                thread(name = "developer-install-consent", isDaemon = true) {
                    val result = runCatching {
                        DevelopmentJobInbox(java.io.File(noBackupFilesDir.canonicalFile, "development-inbox"))
                            .consumeArtifactReview(ticket, Wire.personalPackage(packageName), installation = true)
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && generation == artifactReviewGeneration && hasWindowFocus()) {
                            result.onSuccess { job -> runCatching {
                                openArtifactJob(job.getString("id"))
                                val record = requireNotNull(JarvisPackageInstaller.inbox(this).artifactRecord(job.getString("id")))
                                JarvisPackageInstaller.installVerified(this, job.getString("id"), job.getLong("revision"), record) { message ->
                                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }.onFailure {
                                android.widget.Toast.makeText(this, "Review the current APK and request installation again. No successful installation was verified.", android.widget.Toast.LENGTH_LONG).show()
                            } }.onFailure {
                                android.util.Log.i("DeveloperReview", "artifact.install.handoff.rejected")
                                android.widget.Toast.makeText(this, "The installation approval changed, expired or was already used. Request a fresh review in Assistant.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        artifactReviewIntent = { incoming ->
            if (incoming.action == Wire.ARTIFACT_INSTALL_ACTION) {
                val generation = ++artifactReviewGeneration
                val ticket = runCatching { incoming.getStringExtra(Wire.ARTIFACT_REVIEW_EXTRA) }.getOrNull().orEmpty()
                pendingArtifactInstall = generation to ticket
                artifactInstallWhenFocused?.invoke()
            } else if (incoming.action == Wire.ARTIFACT_REVIEW_ACTION) {
                pendingArtifactInstall = null
                val generation = ++artifactReviewGeneration
                val ticket = runCatching { incoming.getStringExtra(Wire.ARTIFACT_REVIEW_EXTRA) }.getOrNull().orEmpty()
                thread(name = "developer-artifact-review", isDaemon = true) {
                    val result = runCatching {
                        DevelopmentJobInbox(java.io.File(noBackupFilesDir.canonicalFile, "development-inbox"))
                            .consumeArtifactReview(ticket, Wire.personalPackage(packageName)).getString("id")
                    }
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed && generation == artifactReviewGeneration) {
                            result.onSuccess { job -> runCatching { openArtifactJob(job) }.onFailure {
                                android.widget.Toast.makeText(this, "Open the development inbox to review this job. No installation was requested.", android.widget.Toast.LENGTH_LONG).show()
                            } }.onFailure {
                                android.util.Log.i("DeveloperReview", "artifact.handoff.rejected")
                                android.widget.Toast.makeText(this, "The APK review changed or expired. Ask Assistant for a fresh review. No installation was requested.", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
        val restoredReview = savedInstanceState?.getString("developer_artifact_review_job")
        if (restoredReview != null) runCatching { openArtifactJob(restoredReview) }
        else if (savedInstanceState == null) intent?.let { artifactReviewIntent?.invoke(it) }
    }

    @Deprecated("Uses the framework Back callback for the standalone Activity")
    override fun onBackPressed() {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
        if (currentFocus is EditText && insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
            dismissKeyboard()
            return
        }
        if (navigateBack?.invoke() != true) super.onBackPressed()
    }

    private fun dismissKeyboard() {
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        if (token != null) {
            // Ask while the editor still owns the input connection.
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
        }
        uiRoot?.requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        artifactReviewIntent?.invoke(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) artifactInstallWhenFocused?.invoke()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == screenshotPickerRequest) {
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) {
                val generation = ++screenshotReadGeneration
                screenshotLoading = true
                selectedScreenshot = null
                screenshotPreview?.setImageDrawable(null)
                screenshotPreview?.visibility = View.GONE
                screenshotLabel?.text = "Preparing selected screenshot on-device…"
                thread(name = "developer-screenshot-import", isDaemon = true) {
                    val result = runCatching { DeveloperScreenshot.fromUri(contentResolver, uri) }
                    runOnUiThread {
                        if (generation != screenshotReadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                        screenshotLoading = false
                        result.onSuccess { image ->
                            selectedScreenshot = image
                            screenshotPreview?.setImageBitmap(BitmapFactory.decodeByteArray(
                                image.jpegBytes, 0, image.jpegBytes.size))
                            screenshotPreview?.visibility = View.VISIBLE
                            screenshotLabel?.text = "Screenshot attached (one image). " +
                                "Press Send to analyze it with the selected AI provider. No code will be changed."
                        }.onFailure {
                            screenshotLabel?.text = "Image could not be attached. " +
                                "Choose a valid PNG, JPEG or WebP screenshot under 12 MB."
                        }
                    }
                }
            }
            return
        }
        if (applicationWorkbench?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        DeveloperUpdater.onBackground(this)
        super.onPause()
    }

    override fun onDestroy() {
        DeveloperUpdater.cancel(this)
        projectGeneration?.dispose()
        projectGeneration = null
        homeWorkspace = null
        screenshotReadGeneration++
        screenshotLoading = false
        selectedScreenshot = null
        screenshotPreview?.setImageDrawable(null)
        screenshotPreview = null
        screenshotLabel = null
        artifactReviewGeneration++
        artifactReviewIntent = null
        artifactInstallWhenFocused = null
        pendingArtifactInstall = null
        selectPage = null
        navigateBack = null
        disposeGuidance?.invoke()
        disposeGuidance = null
        uiRoot = null
        pageViews = emptyList()
        connectionVerification.onChanged = null
        patchPreview?.dispose()
        patchPreview = null
        applicationWorkbench?.dispose()
        applicationWorkbench = null
        super.onDestroy()
    }
}
