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
import android.view.View
import kotlin.concurrent.thread

/**
 * Independent Gemini/OpenAI coding client.
 * API key is kept only in memory and is never embedded in the APK or persisted.
 * This is API communication, NOT a connection to a ChatGPT conversation or Codex session.
 */
class MainActivity : Activity() {
    private var applicationWorkbench: ApplicationApprovalWorkbench.Handle? = null
    private var activePage = 0
    private var selectPage: ((Int) -> Unit)? = null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("developer_active_page", activePage)
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activePage = savedInstanceState?.getInt("developer_active_page", 0)?.coerceIn(0, 3) ?: 0
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
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
        val title = TextView(this).apply {
            text = "J.A.R.V.I.S.  DEVELOPER"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        // Reuse the original Jarvis header's vector lettering, not a substitute font.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 18)
        }
        val reactor = ImageView(this).apply {
            setImageResource(R.drawable.developer_arc_reactor_art)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Jarvis arc reactor"
        }
        header.addView(reactor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 124))
        val wordmark = ImageView(this).apply {
            setImageResource(R.drawable.developer_jarvis_wordmark)
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "J.A.R.V.I.S."
            adjustViewBounds = true
        }
        header.addView(wordmark, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 52))
        title.text = "DEVELOPER"
        title.textSize = 18f
        title.letterSpacing = 0.22f
        title.setTextColor(getColor(R.color.developer_primary))
        header.addView(title)
        root.addView(header)
        val status = TextView(this).apply {
            text = "Describe your idea, review changes, and build with confidence."
            setTextColor(getColor(R.color.developer_secondary))
        }
        root.addView(status)
        val setupHint = TextView(this).apply {
            text = "First time here? Connect an AI provider in Settings. Your credentials are used only for this session."
            setTextColor(getColor(R.color.developer_secondary))
            setPadding(0, 16, 0, 8)
        }
        root.addView(setupHint)
        val setupButton = Button(this).apply {
            text = "Set up AI connection"
            isAllCaps = false
            contentDescription = "Open Settings to enter your AI provider credentials"
        }
        root.addView(setupButton)
        val key = field("Selected provider API key (not saved)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val provider = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, listOf("Gemini", "OpenAI"))
        }
        root.addView(provider)
        val model = field("Model ID (blank = provider default)")
        val prompt = field("What would you like to build or change?", true)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            prompt.setText(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        }
        val output = TextView(this).apply {
            setTextColor(getColor(R.color.developer_text))
            setTextIsSelectable(true)
            text = "Coding responses appear here. Approved icon edits can be committed to the isolated Developer branch and checked against GitHub Actions."
        }
        val send = Button(this).apply { text = "Start with Jarvis" }
        root.addView(send)
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
        val githubToken = field("GitHub fine-grained token (not saved)").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val repositoryInfo = TextView(this).apply {
            text = "Codebase awareness: read-only inventory, source browsing and task plans. No AI key required; GitHub token is optional for public source."
        }
        root.addView(repositoryInfo)
        val sourcePath = field("Source path or directory prefix (blank lists all allowed sources)")
        val sourceRange = field("Source line range, up to 200 lines").apply { setText("1-120") }
        val inventoryButton = Button(this).apply { text = "Browse source inventory (read-only)" }
        val sourceButton = Button(this).apply { text = "Read source lines (read-only)" }
        val analyzeButton = Button(this).apply { text = "Analyze task and preview file plan (read-only)" }
        listOf(inventoryButton, sourceButton, analyzeButton).forEach { root.addView(it) }
        var repositorySnapshot: RepositoryAnalysis.Inventory? = null
        fun repositoryRead(label: String, operation: () -> Pair<RepositoryAnalysis.Inventory, String>) {
            listOf(inventoryButton, sourceButton, analyzeButton).forEach { it.isEnabled = false }
            status.text = label
            thread(name = "DeveloperRepositoryRead") {
                try {
                    val (snapshot, report) = operation()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            repositorySnapshot = snapshot
                            output.text = report
                            status.text = "Read-only preview complete at ${snapshot.commitSha.take(12)}"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Repository preview failed"
                            output.text = e.message?.take(500) ?: "Repository read unavailable."
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
            val task = prompt.text.toString().trim()
            if (task.isBlank()) {
                status.text = "Enter a task to analyze."
                return@setOnClickListener
            }
            repositoryRead("Inspecting bounded source context and dependencies...") {
                val (snapshot, plan) = RepositoryIntelligence.analyzeSnapshot(token, task)
                snapshot to RepositoryAnalysis.render(plan)
            }
        }
        val patchWorkbench = PatchWorkbench.attach(this, root, prompt, key, provider, model, githubToken, output, status) {
            health.getInt("confirmed_version", -1) == currentCode
        }
        TaskPlanningWorkbench.attach(this, root, prompt, githubToken, output, status, patchWorkbench)
        RepairWorkbench.attach(this, root, prompt, githubToken, output, status, patchWorkbench)
        SelfDevelopmentWorkbench.attach(this, root, prompt, githubToken, output, status, patchWorkbench)
        val projectStartIndex = root.childCount
        applicationWorkbench = ApplicationApprovalWorkbench.attach(this, root)
        val projectViews = (projectStartIndex until root.childCount).map { root.getChildAt(it) }
        root.addView(iconTest)
        if (recoveryWarning) {
            iconTest.isEnabled = false
            updates.isEnabled = false
            status.text = "Recovery mode: AI edits and updates paused until this version is confirmed working."
        }
        iconTest.setOnClickListener {
            iconTest.isEnabled = false
            CodingAgent.run(provider.selectedItem.toString(), key.text.toString().trim(),
                model.text.toString().trim(), githubToken.text.toString().trim(),
                prompt.text.toString().trim()) { message ->
                runOnUiThread {
                    status.text = message.take(240)
                    output.text = message
                    if (message.startsWith("Coding task failed") ||
                        message.contains("Review the commit and build")) iconTest.isEnabled = true
                }
            }
        }
        updates.setOnClickListener { DeveloperUpdater.check(this) { message -> status.text = message } }
        plans.setOnClickListener {
            status.text = "Reading GitHub plans..."
            thread {
                try {
                    val approved = GithubPlans.listPlans().map { it to GithubPlans.plan(it) }
                        .filter { (_, body) -> Regex("(?im)^Status:\\s*approved\\s*$").containsMatchIn(body) }
                    runOnUiThread {
                        output.text = if (approved.isEmpty()) "No approved plans found." else
                            approved.joinToString("\n\n") { (name, body) -> "$name\n\n$body" }
                        status.text = "Plans loaded"
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Plan lookup failed"; output.text = e.message }
                }
            }
        }
        builds.setOnClickListener {
            status.text = "Checking GitHub Actions..."
            thread {
                try {
                    val result = GithubPlans.latestBuild()
                    runOnUiThread { output.text = result; status.text = "Build status loaded" }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Build lookup failed"; output.text = e.message }
                }
            }
        }

        send.setOnClickListener {
            // Capture all widget state on the UI thread; a pending request owns this selection.
            val providerName = provider.selectedItem.toString()
            val apiKey = key.text.toString().trim()
            val modelName = model.text.toString().trim()
            val task = prompt.text.toString().trim()
            if (task.isEmpty()) {
                status.text = "Describe the change you want to make first."
                prompt.requestFocus()
                return@setOnClickListener
            }
            if (apiKey.isEmpty()) {
                status.text = "Add your AI provider credentials in Settings before continuing."
                selectPage?.invoke(3)
                key.requestFocus()
                return@setOnClickListener
            }
            val requestInputs = listOf(send, provider, key, model, prompt)
            requestInputs.forEach { it.isEnabled = false }
            status.text = "Connecting to $providerName..."
            thread(name = "DeveloperCodingAssistant") {
                try {
                    val result = CodingAssistantProvider.ask(providerName, apiKey, modelName, task)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            output.text = result
                            status.text = "Response received from $providerName"
                        }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Coding assistant request failed"
                            output.text = "Check the selected provider, API key, model and bounded request/output format."
                        }
                    }
                } finally {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) requestInputs.forEach { it.isEnabled = true }
                    }
                }
            }
        }
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
            text = "Create an Android app, backend service, or Windows starter. Review the generated files before exporting."
            setTextColor(0xFFC6DDE5.toInt())
        })
        projectViews.forEach { widget ->
            (widget.parent as? android.view.ViewGroup)?.removeView(widget)
            projects.addView(widget)
        }
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 24)
            setBackgroundColor(getColor(R.color.developer_panel))
        }
        settings.addView(TextView(this).apply {
            text = "Settings and connections"
            textSize = 22f
            setTextColor(getColor(R.color.developer_primary))
        })
        listOf(key, provider, model).forEach { widget ->
            (widget.parent as? android.view.ViewGroup)?.removeView(widget)
            settings.addView(widget)
        }
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.developer_background))
        }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        // Home can launch project creation without exposing advanced controls.
        val createProject = Button(this).apply {
            text = "Create a new project"
            isAllCaps = false
            contentDescription = "Open project creation tools"
        }
        root.addView(createProject)
        val pages = listOf(root, projects, advanced, settings).map { page ->
            ScrollView(this).apply {
                addView(page)
                isFillViewport = true
                visibility = View.GONE
            }
        }
        val tabs = mutableListOf<Button>()
        fun showPage(index: Int) {
            activePage = index.coerceIn(0, pages.lastIndex)
            pages.forEachIndexed { pageIndex, page ->
                page.visibility = if (pageIndex == activePage) View.VISIBLE else View.GONE
            }
            tabs.forEachIndexed { tabIndex, button ->
                button.isSelected = tabIndex == activePage
                button.alpha = if (tabIndex == activePage) 1f else 0.72f
                button.contentDescription = button.text.toString() +
                    if (tabIndex == activePage) ", selected" else ""
            }
        }
        selectPage = ::showPage
        fun tab(label: String, pageIndex: Int) = Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { showPage(pageIndex) }
            tabs.add(this)
            navigation.addView(this, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        setupButton.setOnClickListener {
            showPage(3)
            key.requestFocus()
        }
        createProject.setOnClickListener { showPage(1) }
        tab("Home", 0)
        tab("Projects", 1)
        tab("Tools", 2)
        tab("Settings", 3)
        showPage(activePage)
        shell.addView(navigation)
        pages.forEach { page ->
            shell.addView(page, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(shell)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (applicationWorkbench?.onActivityResult(requestCode, resultCode, data) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        selectPage = null
        applicationWorkbench?.dispose()
        applicationWorkbench = null
        super.onDestroy()
    }
}
