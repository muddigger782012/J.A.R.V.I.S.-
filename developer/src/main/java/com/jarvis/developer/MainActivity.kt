package com.jarvis.developer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
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
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.concurrent.thread

/**
 * Independent Gemini/OpenAI coding client.
 * Provider API key is encrypted using Android Keystore; it is never embedded in the APK.
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
        // The release asset has already been alpha-matted by transparent_reactor.py.
        // Do not apply the threshold a second time: that erased fine reactor details.
        val original = BitmapFactory.decodeResource(resources, R.drawable.developer_arc_reactor_art)
        val reactor = original.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(reactor.width * reactor.height)
        reactor.getPixels(pixels, 0, reactor.width, 0, 0, reactor.width, reactor.height)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.developer_panel))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF265361.toInt())
            }
        }
        // Animate independent concentric artwork layers: stationary core,
        // clockwise reactor arc, and counter-clockwise outer ring.
        // Each layer retains its original transparent pixels and alignment.
        val reactorFrame = android.widget.FrameLayout(this)
        val cx = (reactor.width - 1) / 2.0
        val cy = (reactor.height - 1) / 2.0
        val maxRadius = minOf(reactor.width, reactor.height) / 2.0
        fun reactorLayer(innerFraction: Double, outerFraction: Double): Bitmap {
            val layerPixels = pixels.copyOf()
            for (y in 0 until reactor.height) {
                for (x in 0 until reactor.width) {
                    val radius = kotlin.math.hypot(x - cx, y - cy) / maxRadius
                    if (radius < innerFraction || radius >= outerFraction) {
                        layerPixels[y * reactor.width + x] = Color.TRANSPARENT
                    }
                }
            }
            return Bitmap.createBitmap(layerPixels, reactor.width, reactor.height, Bitmap.Config.ARGB_8888)
        }
        fun addReactorLayer(bitmap: Bitmap, description: String): ImageView =
            ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = description
                reactorFrame.addView(this, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
        val outerRing = addReactorLayer(reactorLayer(0.78, Double.POSITIVE_INFINITY), "Counter-rotating outer ring")
        val reactorArc = addReactorLayer(reactorLayer(0.34, 0.78), "Rotating reactor arc")
        addReactorLayer(reactorLayer(0.0, 0.34), "Stationary reactor core")
        // The reactor sits outside the bordered title panel, which extends from its edge.
        // This avoids framing or clipping the animated concentric rings.
        val headerAssembly = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerAssembly.addView(reactorFrame, LinearLayout.LayoutParams(dp(84), dp(84)))
        fun spinReactorLayer(view: View, degrees: Float, durationMs: Long) {
            ObjectAnimator.ofFloat(view, View.ROTATION, 0f, degrees).apply {
                duration = durationMs
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        spinReactorLayer(reactorArc, 360f, 12000L)
        spinReactorLayer(outerRing, -360f, 16000L)
        val headerWords = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerWords.addView(ImageView(this).apply {
            setImageResource(R.drawable.developer_jarvis_wordmark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "J.A.R.V.I.S."
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        headerWords.addView(TextView(this).apply {
            text = "DEVELOPER"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.developer_primary))
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply {
            marginStart = dp(8)
        })
        header.addView(headerWords, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        // A restrained breathing glow follows the panel's cyan stroke.
        // Keep the glow on the background drawable: no image scaling or text movement.
        val borderDrawable = header.background as GradientDrawable
        val borderPulse = ValueAnimator.ofObject(android.animation.ArgbEvaluator(),
            0xFF265361.toInt(), 0xFF5AF4FF.toInt(), 0xFF265361.toInt()).apply {
            duration = 2800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                borderDrawable.setStroke(dp(1), animator.animatedValue as Int)
                header.invalidate()
            }
        }
        borderPulse.start()
        headerAssembly.addView(header, LinearLayout.LayoutParams(0, dp(78), 1f).apply {
            marginStart = dp(4)
        })
        root.addView(headerAssembly)
        val status = TextView(this).apply {
            text = "Your AI development assistant"
            textSize = 13f
            setPadding(dp(4), dp(8), 0, dp(6))
            setTextColor(getColor(R.color.developer_secondary))
        }
        root.addView(status)
        val setupHint = TextView(this).apply {
            text = "New here? Connect an AI provider in Settings to get started."
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
            setTextColor(getColor(R.color.developer_text))
            setTextIsSelectable(true)
            text = "Your assistant’s response will appear here."
        }
        val send = Button(this).apply { text = "Start project"; isAllCaps = false }
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
                status.text = "Save your AI provider credentials in Settings before continuing."
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
                } catch (e: Exception) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            status.text = "Coding assistant request failed"
                            output.text = if (e is CodingAssistantProvider.ProviderFailure) e.message ?: "AI connection failed." else "Check the selected provider, API key and model."
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
            text = "Start a new project or continue working on one. Jarvis will guide you through each step."
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
        val testConnection = Button(this).apply {
            text = "Test AI connection"
            isAllCaps = false
        }
        settings.addView(testConnection)
        testConnection.setOnClickListener {
            val selectedKey = key.text.toString().trim()
            if (selectedKey.isBlank() || selectedKey.startsWith("gemini-") || selectedKey.startsWith("gpt-")) {
                credentialStatus.text = "Enter an actual API key, not a model name."
                return@setOnClickListener
            }
            val selectedProvider = provider.selectedItem.toString()
            val selectedModel = model.text.toString().trim()
            testConnection.isEnabled = false
            credentialStatus.text = "Testing $selectedProvider connection..."
            thread(name = "DeveloperConnectionTest") {
                val message = try {
                    CodingAssistantProvider.ask(selectedProvider, selectedKey, selectedModel,
                        "Reply with the single word connected.") 
                    "$selectedProvider connection successful."
                } catch (e: CodingAssistantProvider.ProviderFailure) {
                    e.message ?: "AI connection failed."
                } catch (_: Exception) {
                    "AI connection failed. Check provider and model."
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        credentialStatus.text = message
                        testConnection.isEnabled = true
                    }
                }
            }
        }
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
                        if (result.isEmpty()) {
                            credentialStatus.text = safeFailure ?: "No compatible models returned for this API key."
                        } else {
                            credentialStatus.text = "${result.size} compatible Gemini models found. Choose one."
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Available Gemini models")
                                .setItems(result.toTypedArray()) { _, index ->
                                    model.setText(result[index])
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
                    credentialStatus.text = "Connection saved securely. You can now start a project."
                    status.text = "AI connection saved"
                } catch (_: Exception) {
                    credentialStatus.text = "Unable to save securely. Check device security settings."
                }
            }
        }
        forgetConnection.setOnClickListener {
            ProviderCredentialStore.clear(this)
            key.text.clear()
            model.text.clear()
            credentialStatus.text = "Saved connection removed from this device."
        }
        // Model presets are suggestions, not a fixed list of provider entitlements.
        val modelChoices = listOf(
            "Automatic (recommended)",
            "Gemini 2.5 Flash — fast everyday coding",
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
                listOf("", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")
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
                    listOf("", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")
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
                status.text = message
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
        // Keep complex repository, recovery, and approval controls accessible but collapsed by default.
        val advancedChildren = (2 until advanced.childCount).map { advanced.getChildAt(it) }
        advancedChildren.forEach { advanced.removeView(it) }
        val advancedToggle = Button(this).apply {
            text = "Show developer controls"
            isAllCaps = false
            setTextColor(getColor(R.color.developer_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.developer_panel))
        }
        advanced.addView(advancedToggle)
        val advancedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        advancedChildren.forEach { advancedContent.addView(it) }
        advanced.addView(advancedContent)
        advancedToggle.setOnClickListener {
            val expanding = advancedContent.visibility != View.VISIBLE
            advancedContent.visibility = if (expanding) View.VISIBLE else View.GONE
            advancedToggle.text = if (expanding) "Hide developer controls" else "Show developer controls"
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
            text = "My projects"
            isAllCaps = false
            contentDescription = "Open project creation tools"
        }
        listOf(setupButton, send, createProject).forEach { button ->
            button.isAllCaps = false
            button.setTextColor(0xFF071B25.toInt())
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.developer_primary))
        }
        // Reference-style quick actions, backed by existing functional screens.
        val quickHeading = TextView(this).apply {
            text = "Quick actions"
            textSize = 19f
            setTextColor(getColor(R.color.developer_text))
            setPadding(0, dp(20), 0, dp(12))
        }
        root.addView(quickHeading)
        fun quickAction(label: String, destination: Int, action: (() -> Unit)? = null): Button =
            Button(this).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), dp(12), dp(8), dp(12))
                isAllCaps = false
                setTextColor(getColor(R.color.developer_text))
                background = GradientDrawable().apply {
                    setColor(0xFF142F3B.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(2, 0xFF285563.toInt())
                }
                setOnClickListener {
                    if (action != null) action() else selectPage?.invoke(destination)
                }
            }
        fun quickRow(first: Button, second: Button): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
                addView(first, LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                    marginEnd = dp(5)
                })
                addView(second, LinearLayout.LayoutParams(0, dp(92), 1f).apply {
                    marginStart = dp(5)
                })
            }
        root.addView(quickRow(
            quickAction("▣  Create an app", 1),
            quickAction("▱  My projects", 1)
        ))
        root.addView(quickRow(
            quickAction("⌁  Improve Jarvis", 2) {
                selectPage?.invoke(2)
                advancedContent.visibility = View.VISIBLE
                advancedToggle.text = "Hide developer controls"
            },
            quickAction("▤  Build status", 2) {
                selectPage?.invoke(2)
                advancedContent.visibility = View.VISIBLE
                advancedToggle.text = "Hide developer controls"
                builds.performClick()
            }
        ))
        // Move status feedback to the assistant screen when the user selects Tools.
        output.setPadding(dp(4), dp(12), dp(4), dp(12))
        prompt.apply {
            minLines = 4
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = roundedSurface(0xFF142F3B.toInt())
        }
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
                button.alpha = if (tabIndex == activePage) 1f else 0.78f
                button.background = GradientDrawable().apply {
                    setColor(if (tabIndex == activePage) 0xFF173F4B.toInt()
                        else getColor(R.color.developer_panel))
                    cornerRadius = 18f
                }
                button.contentDescription = button.text.toString() +
                    if (tabIndex == activePage) ", selected" else ""
            }
        }
        selectPage = ::showPage
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
        setupButton.setOnClickListener {
            showPage(3)
            key.requestFocus()
        }
        createProject.setOnClickListener { showPage(1) }
        tab("Home", 0, android.R.drawable.ic_menu_view)
        tab("Projects", 1, android.R.drawable.ic_menu_agenda)
        tab("Tools", 2, android.R.drawable.ic_menu_manage)
        tab("Settings", 3, android.R.drawable.ic_menu_preferences)
        showPage(activePage)
        pages.forEach { page ->
            shell.addView(page, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        navigation.setBackgroundColor(getColor(R.color.developer_panel))
        shell.addView(navigation)
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
