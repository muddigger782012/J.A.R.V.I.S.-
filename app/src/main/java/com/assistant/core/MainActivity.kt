package com.assistant.core

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.GestureDetector
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.text.InputType
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.assistant.core.adapters.DhizukuAdapter
import com.assistant.core.adapters.ShizukuAdapter
import com.assistant.core.adapters.SpecialAccessAdapter
import com.assistant.core.adapters.StandardAdapter
import com.assistant.core.engine.ActionExecutor
import com.assistant.core.engine.ActionRegistry
import com.assistant.core.engine.AssistantEngine
import com.assistant.core.engine.CapabilityBroker
import com.assistant.core.engine.CapabilityDetector
import com.assistant.core.engine.IntentClassifier
import com.assistant.core.engine.PolicyGate
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.services.AuditService
import com.assistant.core.services.CodingService
import com.assistant.core.services.DhizukuService
import com.assistant.core.services.DhizukuStatus
import com.assistant.core.services.FileService
import com.assistant.core.services.HybridAssistantService
import com.assistant.core.services.LocalVoiceCommand
import com.assistant.core.services.PrivilegeCatalogService
import com.assistant.core.services.RunningShizukuCommand
import com.assistant.core.services.ShizukuShellService
import com.assistant.core.services.SystemService
import com.assistant.core.services.VoiceAssistantService
import com.assistant.core.services.VoiceCommandParser
import com.assistant.core.services.VoiceConfig
import com.assistant.core.services.VoiceForegroundService
import com.assistant.core.services.VoicePreferences
import com.assistant.core.services.VoiceRecognitionResult
import com.assistant.core.storage.ActionRepository
import com.assistant.core.storage.AuditRepository
import com.assistant.core.storage.Database
import com.assistant.core.storage.ProjectRepository
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rosan.dhizuku.api.Dhizuku
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var commandInput: EditText
    private lateinit var shizukuCommandInput: EditText
    private lateinit var terminalCommandInput: EditText
    private lateinit var shizukuRuntimeStatusView: TextView
    private lateinit var outputLog: TextView
    private lateinit var statusOutput: TextView
    private lateinit var updateStatusOutput: TextView
    private lateinit var specialPermissionsStatus: TextView
    private lateinit var terminalOutputView: TextView
    private lateinit var terminalHistoryView: TextView
    private lateinit var auditDebugOutput: TextView
    private lateinit var micPermissionStatusView: TextView
    private lateinit var grantMicPermissionButton: Button
    private lateinit var privilegeCenterView: TextView
    private lateinit var refreshPrivilegesButton: Button
    private lateinit var requestDhizukuPermissionButton: Button
    private lateinit var applyCoreDelegatedScopesButton: Button
    private lateinit var terminalRunButton: Button
    private lateinit var terminalStopButton: Button
    private lateinit var terminalClearButton: Button
    private lateinit var autoUpgradeButton: Button
    private lateinit var refreshShizukuStatusButton: Button
    private lateinit var openShizukuAppButton: Button
    private lateinit var requestShizukuPermissionButton: Button
    private lateinit var auditRefreshButton: Button
    private lateinit var auditClearViewButton: Button
    private lateinit var auditCopyButton: Button
    private lateinit var auditLimitInput: EditText
    private lateinit var auditStatusFilterInput: EditText
    private lateinit var auditAdapterFilterInput: EditText

    private lateinit var voiceButton: Button
    private lateinit var settingsButton: Button

    private lateinit var switchCameraDisabled: SwitchMaterial
    private lateinit var switchScreenCaptureDisabled: SwitchMaterial
    private lateinit var switchInstallAppsRestricted: SwitchMaterial
    private lateinit var switchUninstallAppsRestricted: SwitchMaterial
    private lateinit var switchStatusBarDisabled: SwitchMaterial
    private lateinit var switchPackageUninstallBlocked: SwitchMaterial
    private lateinit var lockScreenMessageInput: EditText
    private lateinit var policyPackageNameInput: EditText

    private lateinit var btnApplyCameraDisabled: Button
    private lateinit var btnApplyScreenCaptureDisabled: Button
    private lateinit var btnApplyInstallAppsRestriction: Button
    private lateinit var btnApplyUninstallAppsRestriction: Button
    private lateinit var btnApplyStatusBarDisabled: Button
    private lateinit var btnApplyLockScreenMessage: Button
    private lateinit var btnApplyPackagePolicy: Button
    private lateinit var btnLockNow: Button
    private lateinit var btnRebootFromDhizuku: Button

    private lateinit var headerTabButtons: List<Button>
    private lateinit var tabContainer: FrameLayout
    private lateinit var tabSections: List<View>
    private lateinit var tabBindings: List<TabBinding>
    private lateinit var hudPulseOverlay: View
    private lateinit var scanlineView: View

    private lateinit var actionRegistry: ActionRegistry
    private lateinit var assistantEngine: AssistantEngine
    private lateinit var auditService: AuditService
    private lateinit var systemService: SystemService
    private lateinit var capabilityState: CapabilityState
    private lateinit var voiceService: VoiceAssistantService
    private lateinit var voicePreferences: VoicePreferences
    private lateinit var voiceCommandParser: VoiceCommandParser
    private lateinit var hybridAssistantService: HybridAssistantService
    private lateinit var dhizukuService: DhizukuService
    private lateinit var privilegeCatalogService: PrivilegeCatalogService
    private lateinit var shizukuShellService: ShizukuShellService
    private lateinit var currentVoiceConfig: VoiceConfig
    private lateinit var currentDhizukuStatus: DhizukuStatus

    private val outputLines = mutableListOf<String>()
    private val terminalOutputLines = mutableListOf<String>()
    private val terminalHistory = mutableListOf<String>()
    private val uiPreferences by lazy { getSharedPreferences("main_ui", MODE_PRIVATE) }
    private val devicePolicyManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private var voiceEnabled = false
    private var shouldStartVoiceAfterPermission = false
    private var receiverRegistered = false
    private var shizukuListenersRegistered = false
    private var pendingClarification: String? = null
    private var runningTerminalCommand: RunningShizukuCommand? = null
    private var updateInProgress = false
    private var currentTabIndex = 0
    private var allowSwipeForCurrentTouch = true
    private var headerSelectionAnimator: ValueAnimator? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var scanlineAnimator: ObjectAnimator? = null
    private var swipeGestureDetector: GestureDetector? = null

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            appendOutput("Shizuku binder connected.")
            refreshShizukuRuntimeStatus()
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            appendOutput("Shizuku binder disconnected.")
            refreshShizukuRuntimeStatus()
        }
    }

    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    appendOutput("Shizuku permission granted.")
                } else {
                    appendOutput("Shizuku permission denied.")
                }
                refreshShizukuRuntimeStatus()
            }
        }
    }

    private val voiceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VoiceForegroundService.ACTION_EVENT) return
            intent.getStringExtra(VoiceForegroundService.EXTRA_EVENT_MESSAGE)?.let { message ->
                appendOutput("[BG] $message")
            }
            voiceEnabled = voicePreferences.isForegroundServiceRunning()
            refreshVoiceButtonLabel()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateMicrophonePermissionUi()
        if (granted) {
            if (shouldStartVoiceAfterPermission) startVoiceHotwordMode()
        } else {
            appendOutput(getString(R.string.voice_permission_required))
            shouldStartVoiceAfterPermission = false
        }
    }

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val granted = grantResults.filterValues { it }.keys
        val denied = grantResults.filterValues { !it }.keys
        appendOutput("Runtime granted: ${if (granted.isEmpty()) "none" else granted.joinToString()}")
        appendOutput("Runtime denied: ${if (denied.isEmpty()) "none" else denied.joinToString()}")
        refreshSpecialPermissionsStatus()
    }

    private val voiceSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        reloadVoiceConfiguration(showStatus = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupTabs()
        findViewById<android.widget.Button>(R.id.btnOpenJarvisChat).setOnClickListener {
            startActivity(Intent(this, JarvisChatActivity::class.java))
        }

        val database = Database(this)
        val projectRepository = ProjectRepository(database)
        val actionRepository = ActionRepository(database)
        val auditRepository = AuditRepository(database)

        val fileService = FileService(this)
        val codingService = CodingService(this, fileService)
        systemService = SystemService(this)
        auditService = AuditService(auditRepository)

        val standardAdapter = StandardAdapter(codingService, fileService, systemService, projectRepository)
        val shizukuAdapter = ShizukuAdapter(this)
        dhizukuService = DhizukuService()
        privilegeCatalogService = PrivilegeCatalogService()
        shizukuShellService = ShizukuShellService(this)
        val dhizukuAdapter = DhizukuAdapter(this, dhizukuService)
        val specialAccessAdapter = SpecialAccessAdapter()

        capabilityState = CapabilityDetector().detect(this)

        actionRegistry = ActionRegistry()
        voiceCommandParser = VoiceCommandParser(actionRegistry)
        voicePreferences = VoicePreferences(this)

        assistantEngine = AssistantEngine(
            intentClassifier = IntentClassifier(),
            actionRegistry = actionRegistry,
            policyGate = PolicyGate(),
            actionExecutor = ActionExecutor(
                CapabilityBroker(
                    standardAdapter = standardAdapter,
                    shizukuAdapter = shizukuAdapter,
                    dhizukuAdapter = dhizukuAdapter,
                    specialAccessAdapter = specialAccessAdapter
                )
            ),
            actionRepository = actionRepository,
            auditService = auditService,
            capabilityProvider = { capabilityState }
        )
        hybridAssistantService = HybridAssistantService(
            assistantEngine = assistantEngine,
            actionRegistry = actionRegistry,
            systemService = systemService,
            capabilityProvider = { capabilityState }
        )

        voiceService = VoiceAssistantService(
            context = this,
            onStatus = { status -> runOnUiThread { appendOutput(status) } },
            onHotwordDetected = { runOnUiThread { appendOutput("Hotword detected: jarvis") } },
            onCommandDetected = { recognition ->
                runOnUiThread { handleVoiceRecognition(recognition) }
            }
        )

        bindUiListeners()
        applyMicroInteractions(findViewById(android.R.id.content))
        reloadVoiceConfiguration(showStatus = true)
        refreshPrivilegeCenter()
        refreshSpecialPermissionsStatus()
        refreshShizukuRuntimeStatus()
        updateMicrophonePermissionUi()
        promptForMicrophonePermissionOnFirstLaunch()

        appendOutput("Startup capability status:\n${systemService.buildStatusSummary(capabilityState)}")
        appendOutput(getString(R.string.voice_hint))
        appendRecentAudit()
        refreshAuditDebugSection()
    }

    private fun initViews() {
        tabContainer = findViewById(R.id.tabContainer)
        tabBindings = listOf(
            TabBinding(
                findViewById(R.id.btnHeaderAssistant),
                findViewById(R.id.tabAssistantSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderProject),
                findViewById(R.id.tabProjectSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderShizuku),
                findViewById(R.id.tabShizukuSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderStatus),
                findViewById(R.id.tabStatusSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderTerminal),
                findViewById(R.id.tabTerminalSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderAudit),
                findViewById(R.id.tabAuditSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderPermissions),
                findViewById(R.id.tabPermissionsSection)
            ),
            TabBinding(
                findViewById(R.id.btnHeaderDhizuku),
                findViewById(R.id.tabDhizukuSection)
            )
        )
        headerTabButtons = tabBindings.map { it.button }
        tabSections = tabBindings.map { it.section }

        commandInput = findViewById(R.id.etCommandInput)
        shizukuCommandInput = findViewById(R.id.etShizukuCommand)
        terminalCommandInput = findViewById(R.id.etTerminalCommand)
        shizukuRuntimeStatusView = findViewById(R.id.tvShizukuRuntimeStatus)
        outputLog = findViewById(R.id.tvOutputLog)
        statusOutput = findViewById(R.id.tvStatusOutput)
        updateStatusOutput = findViewById(R.id.tvUpdateStatus)
        specialPermissionsStatus = findViewById(R.id.tvSpecialPermissionsStatus)
        terminalOutputView = findViewById(R.id.tvTerminalOutput)
        terminalHistoryView = findViewById(R.id.tvTerminalHistory)
        auditDebugOutput = findViewById(R.id.tvAuditDebugOutput)
        hudPulseOverlay = findViewById(R.id.vHudPulseOverlay)
        scanlineView = findViewById(R.id.vScanline)
        micPermissionStatusView = findViewById(R.id.tvMicPermissionStatus)
        grantMicPermissionButton = findViewById(R.id.btnGrantMicPermission)
        privilegeCenterView = findViewById(R.id.tvPrivilegeCenter)
        refreshPrivilegesButton = findViewById(R.id.btnRefreshPrivileges)
        requestDhizukuPermissionButton = findViewById(R.id.btnRequestDhizukuPermission)
        applyCoreDelegatedScopesButton = findViewById(R.id.btnApplyCoreDelegatedScopes)
        terminalRunButton = findViewById(R.id.btnTerminalRunCommand)
        terminalStopButton = findViewById(R.id.btnTerminalStopCommand)
        terminalClearButton = findViewById(R.id.btnTerminalClearOutput)
        autoUpgradeButton = findViewById(R.id.btnAutoUpgradeApp)
        refreshShizukuStatusButton = findViewById(R.id.btnRefreshShizukuStatus)
        openShizukuAppButton = findViewById(R.id.btnOpenShizukuApp)
        requestShizukuPermissionButton = findViewById(R.id.btnRequestShizukuPermission)
        auditRefreshButton = findViewById(R.id.btnAuditRefresh)
        auditClearViewButton = findViewById(R.id.btnAuditClearView)
        auditCopyButton = findViewById(R.id.btnAuditCopy)
        auditLimitInput = findViewById(R.id.etAuditLimit)
        auditStatusFilterInput = findViewById(R.id.etAuditStatusFilter)
        auditAdapterFilterInput = findViewById(R.id.etAuditAdapterFilter)

        voiceButton = findViewById(R.id.btnToggleVoice)
        settingsButton = findViewById(R.id.btnVoiceSettings)

        switchCameraDisabled = findViewById(R.id.switchCameraDisabled)
        switchScreenCaptureDisabled = findViewById(R.id.switchScreenCaptureDisabled)
        switchInstallAppsRestricted = findViewById(R.id.switchInstallAppsRestricted)
        switchUninstallAppsRestricted = findViewById(R.id.switchUninstallAppsRestricted)
        switchStatusBarDisabled = findViewById(R.id.switchStatusBarDisabled)
        switchPackageUninstallBlocked = findViewById(R.id.switchPackageUninstallBlocked)
        lockScreenMessageInput = findViewById(R.id.etLockScreenMessage)
        policyPackageNameInput = findViewById(R.id.etPolicyPackageName)

        btnApplyCameraDisabled = findViewById(R.id.btnApplyCameraDisabled)
        btnApplyScreenCaptureDisabled = findViewById(R.id.btnApplyScreenCaptureDisabled)
        btnApplyInstallAppsRestriction = findViewById(R.id.btnApplyInstallAppsRestriction)
        btnApplyUninstallAppsRestriction = findViewById(R.id.btnApplyUninstallAppsRestriction)
        btnApplyStatusBarDisabled = findViewById(R.id.btnApplyStatusBarDisabled)
        btnApplyLockScreenMessage = findViewById(R.id.btnApplyLockScreenMessage)
        btnApplyPackagePolicy = findViewById(R.id.btnApplyPackagePolicy)
        btnLockNow = findViewById(R.id.btnLockNow)
        btnRebootFromDhizuku = findViewById(R.id.btnRebootFromDhizuku)
    }

    private fun setupTabs() {
        tabBindings.forEachIndexed { index, binding ->
            binding.button.setOnClickListener {
                val direction = when {
                    index > currentTabIndex -> 1
                    index < currentTabIndex -> -1
                    else -> 0
                }
                showTab(index, animate = true, direction = direction)
            }
        }
        bindSwipeNavigation()
        showTab(0, animate = false, direction = 0)
    }

    private fun showTab(index: Int, animate: Boolean, direction: Int) {
        if (index !in tabSections.indices) return
        if (index == currentTabIndex && tabSections[index].visibility == View.VISIBLE) {
            updateHeaderSelection(index)
            return
        }
        val previousIndex = currentTabIndex
        currentTabIndex = index
        if (animate && previousIndex in tabSections.indices && previousIndex != index) {
            animateTabTransition(fromIndex = previousIndex, toIndex = index, direction = direction)
        } else {
            tabSections.forEachIndexed { i, view ->
                view.animate().cancel()
                view.translationX = 0f
                view.alpha = 1f
                view.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            updateHeaderSelection(index)
        }
    }

    private fun animateTabTransition(fromIndex: Int, toIndex: Int, direction: Int) {
        val fromView = tabSections[fromIndex]
        val toView = tabSections[toIndex]
        tabSections.forEachIndexed { i, view ->
            if (i != fromIndex && i != toIndex) {
                view.animate().cancel()
                view.visibility = View.GONE
                view.translationX = 0f
                view.alpha = 1f
            }
        }
        fromView.animate().cancel()
        toView.animate().cancel()

        val width = tabContainer.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val offset = when {
            direction > 0 -> width.toFloat()
            direction < 0 -> -width.toFloat()
            else -> width.toFloat()
        }

        toView.translationX = offset
        toView.alpha = 0.85f
        toView.visibility = View.VISIBLE

        fromView.animate()
            .translationX(-offset)
            .alpha(0f)
            .setDuration(TAB_TRANSITION_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                fromView.visibility = View.GONE
                fromView.translationX = 0f
                fromView.alpha = 1f
            }
            .start()

        toView.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(TAB_TRANSITION_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                updateHeaderSelection(toIndex)
            }
            .start()

        animateHeaderSelection(fromIndex, toIndex, TAB_TRANSITION_DURATION_MS)
    }

    private fun updateHeaderSelection(index: Int) {
        headerSelectionAnimator?.cancel()
        val activeColor = ContextCompat.getColor(this, R.color.jarvis_neon_green)
        val inactiveColor = ContextCompat.getColor(this, R.color.jarvis_on_dark)
        headerTabButtons.forEachIndexed { i, button ->
            val isActive = i == index
            button.isSelected = isActive
            button.setTextColor(if (isActive) activeColor else inactiveColor)
            button.alpha = if (isActive) 1f else 0.85f
            button.scaleX = if (isActive) 1.04f else 1f
            button.scaleY = if (isActive) 1.04f else 1f
        }
    }

    private fun animateHeaderSelection(fromIndex: Int, toIndex: Int, durationMs: Long) {
        if (fromIndex !in headerTabButtons.indices || toIndex !in headerTabButtons.indices || fromIndex == toIndex) {
            updateHeaderSelection(toIndex)
            return
        }
        headerSelectionAnimator?.cancel()
        val activeColor = ContextCompat.getColor(this, R.color.jarvis_neon_green)
        val inactiveColor = ContextCompat.getColor(this, R.color.jarvis_on_dark)
        headerSelectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                updateHeaderDragProgress(fromIndex, toIndex, progress, activeColor, inactiveColor)
            }
            start()
        }
    }

    private fun updateHeaderDragProgress(
        fromIndex: Int,
        toIndex: Int,
        progress: Float,
        activeColor: Int = ContextCompat.getColor(this, R.color.jarvis_neon_green),
        inactiveColor: Int = ContextCompat.getColor(this, R.color.jarvis_on_dark)
    ) {
        val p = progress.coerceIn(0f, 1f)
        headerTabButtons.forEachIndexed { index, button ->
            when (index) {
                fromIndex -> {
                    button.setTextColor(ColorUtils.blendARGB(activeColor, inactiveColor, p))
                    button.alpha = 1f - (0.15f * p)
                    val scale = 1.04f - (0.04f * p)
                    button.scaleX = scale
                    button.scaleY = scale
                }
                toIndex -> {
                    button.setTextColor(ColorUtils.blendARGB(inactiveColor, activeColor, p))
                    button.alpha = 0.85f + (0.15f * p)
                    val scale = 1f + (0.04f * p)
                    button.scaleX = scale
                    button.scaleY = scale
                }
                else -> {
                    button.setTextColor(inactiveColor)
                    button.alpha = 0.85f
                    button.scaleX = 1f
                    button.scaleY = 1f
                }
            }
        }
    }

    private fun bindSwipeNavigation() {
        val swipeDistanceThreshold = 64f * resources.displayMetrics.density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                allowSwipeForCurrentTouch = isSwipeAllowedForTouchStart(e)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (!allowSwipeForCurrentTouch || e1 == null) return false
                val dx = e2.rawX - e1.rawX
                val dy = e2.rawY - e1.rawY
                val horizontalIntent = kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                val qualifies = horizontalIntent &&
                    kotlin.math.abs(dx) >= swipeDistanceThreshold &&
                    kotlin.math.abs(velocityX) >= SWIPE_VELOCITY_THRESHOLD
                if (!qualifies) return false

                val delta = if (dx < 0f) 1 else -1
                val target = (currentTabIndex + delta).coerceIn(0, tabSections.lastIndex)
                if (target == currentTabIndex) return false
                showTab(target, animate = true, direction = delta)
                return true
            }
        })
        swipeGestureDetector = detector
        tabSections.forEach { section ->
            section.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                false
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Keep this stable: do not globally intercept touch dispatch.
        return super.dispatchTouchEvent(event)
    }

    private fun isSwipeAllowedForTouchStart(event: MotionEvent): Boolean {
        val activeSection = tabSections.getOrNull(currentTabIndex) ?: return true
        val targetView = findDeepestTouchedView(activeSection, event.rawX, event.rawY)
        if (targetView == null) return true
        return !isInteractiveControl(targetView)
    }

    private fun findDeepestTouchedView(root: View, rawX: Float, rawY: Float): View? {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val insideRoot = rawX >= location[0] &&
            rawX <= location[0] + root.width &&
            rawY >= location[1] &&
            rawY <= location[1] + root.height
        if (!insideRoot) return null
        if (root !is ViewGroup) return root

        for (index in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
            val found = findDeepestTouchedView(child, rawX, rawY)
            if (found != null) return found
        }
        return root
    }

    private fun isInteractiveControl(view: View): Boolean {
        return view is Button ||
            view is EditText ||
            view is SeekBar ||
            view is SwitchMaterial ||
            view is ScrollView ||
            view is HorizontalScrollView ||
            view.isClickable ||
            view.isLongClickable ||
            view.isFocusable
    }

    private fun updateSystemGestureExclusionRects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (tabContainer.width > 0 && tabContainer.height > 0) {
            tabContainer.systemGestureExclusionRects = listOf(
                Rect(0, 0, tabContainer.width, tabContainer.height)
            )
        }
        val scroller = findViewById<View>(R.id.headerTabScroller)
        if (scroller.width > 0 && scroller.height > 0) {
            scroller.systemGestureExclusionRects = listOf(
                Rect(0, 0, scroller.width, scroller.height)
            )
        }
    }

    private fun bindUiListeners() {
        findViewById<Button>(R.id.btnRunAssistantCommand).setOnClickListener {
            runAssistantCommandFromInput()
        }

        findViewById<Button>(R.id.btnAssistantQuickStatus).setOnClickListener {
            val reply = hybridAssistantService.handleUserInput("show status")
            appendOutput("J.A.R.V.I.S.: ${reply.text}")
            statusOutput.text = reply.actionResult?.output ?: reply.text
        }

        findViewById<Button>(R.id.btnCreateProject).setOnClickListener {
            val defaultName = if (::currentVoiceConfig.isInitialized) currentVoiceConfig.defaultProjectName else "assistant_demo"
            val result = assistantEngine.executeAction(actionRegistry.createProjectRequest(defaultName))
            appendActionResult(result)
            appendRecentAudit()
        }

        findViewById<Button>(R.id.btnRunShizuku).setOnClickListener {
            if (!ensureShizukuReadyForExecution(::appendOutput)) {
                return@setOnClickListener
            }
            val command = shizukuCommandInput.text?.toString()?.trim().orEmpty().ifBlank { "id" }
            val result = assistantEngine.executeAction(actionRegistry.runShellRequest(command = command, confirmed = true))
            appendActionResult(result)
            appendRecentAudit()
        }

        refreshShizukuStatusButton.setOnClickListener {
            refreshShizukuRuntimeStatus()
            appendOutput("Shizuku runtime status refreshed.")
        }
        autoUpgradeButton.setOnClickListener {
            startAppUpgradeFlow()
        }

        openShizukuAppButton.setOnClickListener {
            openShizukuApp()
        }

        requestShizukuPermissionButton.setOnClickListener {
            requestShizukuPermission()
        }

        terminalRunButton.setOnClickListener { runTerminalCommand() }
        terminalStopButton.setOnClickListener { stopTerminalCommand() }
        terminalClearButton.setOnClickListener {
            terminalOutputLines.clear()
            terminalOutputView.text = ""
            appendOutput("Terminal output cleared.")
        }
        auditRefreshButton.setOnClickListener {
            refreshAuditDebugSection()
            appendOutput("Audit debug logs refreshed.")
        }
        auditClearViewButton.setOnClickListener {
            auditDebugOutput.text = ""
            appendOutput("Audit debug view cleared.")
        }
        auditCopyButton.setOnClickListener {
            copyAuditLogsToClipboard()
        }

        findViewById<Button>(R.id.btnShowStatus).setOnClickListener {
            val commandText = commandInput.text?.toString()?.trim().orEmpty()
            if (commandText.isBlank()) {
                val reply = hybridAssistantService.handleUserInput("show status")
                appendOutput("J.A.R.V.I.S.: ${reply.text}")
                statusOutput.text = reply.actionResult?.output ?: reply.text
            } else {
                runAssistantCommandFromInput()
            }
        }

        voiceButton.setOnClickListener {
            if (voiceEnabled) stopVoiceHotwordMode() else ensureMicPermissionAndStartVoice()
        }

        settingsButton.setOnClickListener { openVoiceSettings() }

        grantMicPermissionButton.setOnClickListener {
            shouldStartVoiceAfterPermission = false
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        refreshPrivilegesButton.setOnClickListener {
            refreshPrivilegeCenter()
            appendOutput(getString(R.string.privilege_status_refreshed))
        }

        requestDhizukuPermissionButton.setOnClickListener { requestDhizukuPermission() }
        applyCoreDelegatedScopesButton.setOnClickListener { applyCoreDelegatedScopes() }

        findViewById<Button>(R.id.btnRequestAllRuntimePermissions).setOnClickListener {
            requestAllRuntimePermissions()
        }
        findViewById<Button>(R.id.btnRequestAllSpecialPermissions).setOnClickListener {
            requestAllSpecialPermissions()
        }
        findViewById<Button>(R.id.btnOpenOverlayPermission).setOnClickListener { openOverlayPermission() }
        findViewById<Button>(R.id.btnOpenWriteSettingsPermission).setOnClickListener { openWriteSettingsPermission() }
        findViewById<Button>(R.id.btnOpenAllFilesPermission).setOnClickListener { openAllFilesPermission() }
        findViewById<Button>(R.id.btnOpenUsageAccessPermission).setOnClickListener { openUsageAccessPermission() }
        findViewById<Button>(R.id.btnOpenBatteryOptimizationPermission).setOnClickListener { openBatteryOptimizationPermission() }
        findViewById<Button>(R.id.btnOpenNotificationPolicyPermission).setOnClickListener { openNotificationPolicyPermission() }
        findViewById<Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener { openAccessibilitySettings() }

        btnApplyCameraDisabled.setOnClickListener { applyCameraDisabled() }
        btnApplyScreenCaptureDisabled.setOnClickListener { applyScreenCaptureDisabled() }
        btnApplyInstallAppsRestriction.setOnClickListener { applyInstallAppsRestriction() }
        btnApplyUninstallAppsRestriction.setOnClickListener { applyUninstallAppsRestriction() }
        btnApplyStatusBarDisabled.setOnClickListener { applyStatusBarDisabled() }
        btnApplyLockScreenMessage.setOnClickListener { applyLockScreenMessage() }
        btnApplyPackagePolicy.setOnClickListener { applyPackagePolicy() }
        btnLockNow.setOnClickListener { lockNow() }
        btnRebootFromDhizuku.setOnClickListener { rebootDevice() }
    }

    private fun runAssistantCommandFromInput() {
        val commandText = commandInput.text?.toString()?.trim().orEmpty()
        val reply = hybridAssistantService.handleUserInput(commandText)
        appendOutput("You: ${if (commandText.isBlank()) "(status request)" else commandText}")
        appendOutput("J.A.R.V.I.S.: ${reply.text}")
        statusOutput.text = reply.actionResult?.output ?: reply.text
        appendRecentAudit()
    }

    private fun appendActionResult(result: ActionResult) {
        val status = if (result.success) "SUCCESS" else "FAIL"
        appendOutput("[$status][${result.adapterUsed}] ${result.message}")
        result.output?.let { appendOutput(it) }
    }

    private fun appendRecentAudit() {
        val audits = auditService.getRecentEntries(limit = 5)
        if (audits.isNotEmpty()) {
            val rendered = audits.joinToString(separator = "\n") {
                "- ${it.status ?: "UNKNOWN"} | ${it.adapterUsed ?: "N/A"} | ${it.message ?: ""}"
            }
            appendOutput("Recent audit logs:\n$rendered")
        }
    }

    private fun refreshAuditDebugSection() {
        val limit = auditLimitInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val statusFilter = auditStatusFilterInput.text?.toString()?.trim().orEmpty()
        val adapterFilter = auditAdapterFilterInput.text?.toString()?.trim().orEmpty()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        val entries = auditService.getRecentEntries(limit)
            .filter {
                statusFilter.isBlank() || (it.status ?: "").contains(statusFilter, ignoreCase = true)
            }
            .filter {
                adapterFilter.isBlank() || (it.adapterUsed ?: "").contains(adapterFilter, ignoreCase = true)
            }

        auditDebugOutput.text = if (entries.isEmpty()) {
            "No audit entries match current filters."
        } else {
            entries.joinToString(separator = "\n\n") { entry ->
                buildString {
                    appendLine("id: ${entry.id}")
                    appendLine("actionId: ${entry.actionId ?: "N/A"}")
                    appendLine("time: ${formatter.format(Date(entry.createdAt))}")
                    appendLine("status: ${entry.status ?: "UNKNOWN"}")
                    appendLine("adapter: ${entry.adapterUsed ?: "N/A"}")
                    append("message: ${entry.message ?: ""}")
                }
            }
        }
    }

    private fun copyAuditLogsToClipboard() {
        val text = auditDebugOutput.text?.toString().orEmpty()
        if (text.isBlank()) {
            appendOutput("Audit debug output is empty; nothing copied.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("audit_logs", text))
        appendOutput("Audit debug logs copied to clipboard.")
    }

    private fun appendOutput(text: String) {
        outputLines.add(text)
        outputLog.text = outputLines.joinToString(separator = "\n\n")
    }

    private fun appendTerminalOutput(line: String) {
        terminalOutputLines.add(line)
        terminalOutputView.text = terminalOutputLines.joinToString(separator = "\n")
    }

    private fun runTerminalCommand() {
        if (runningTerminalCommand != null) {
            appendTerminalOutput("[!] A command is already running. Stop it first.")
            return
        }
        if (!ensureShizukuReadyForExecution(::appendTerminalOutput)) {
            return
        }

        val rawCommand = terminalCommandInput.text?.toString()?.trim().orEmpty().ifBlank { "id" }
        val parsed = shizukuShellService.parseRishCommand(rawCommand)
        if (parsed.interactiveOnly) {
            val helper = shizukuShellService.ensureRishHelperFile().absolutePath
            appendTerminalOutput("[!] Interactive .rish shell is not supported in this UI.")
            appendTerminalOutput("[i] Use: .rish -c \"<command>\"")
            appendTerminalOutput("[i] Helper file: $helper")
            return
        }

        terminalHistory.add(0, rawCommand)
        if (terminalHistory.size > 50) {
            terminalHistory.removeAt(terminalHistory.lastIndex)
        }
        refreshTerminalHistory()
        appendTerminalOutput("$ $rawCommand")

        try {
            runningTerminalCommand = shizukuShellService.runStreaming(
                parsed = parsed,
                onStdout = { line -> runOnUiThread { appendTerminalOutput(line) } },
                onStderr = { line -> runOnUiThread { appendTerminalOutput("[err] $line") } },
                onCompleted = { exitCode, timedOut ->
                    runOnUiThread {
                        appendTerminalOutput("[exit=$exitCode timedOut=$timedOut]")
                        runningTerminalCommand = null
                    }
                },
                onError = { message ->
                    runOnUiThread {
                        appendTerminalOutput("[error] $message")
                        appendTerminalOutput("[i] ${buildShizukuStateSummary()}")
                        runningTerminalCommand = null
                    }
                }
            )
        } catch (error: Throwable) {
            appendTerminalOutput("[error] ${error.message ?: "Unable to start Shizuku command."}")
            appendTerminalOutput("[i] ${buildShizukuStateSummary()}")
        }
    }

    private fun stopTerminalCommand() {
        val running = runningTerminalCommand
        if (running == null) {
            appendTerminalOutput("[i] No running command.")
            return
        }
        running.stop()
        runningTerminalCommand = null
        appendTerminalOutput("[i] Command stopped.")
    }

    private fun refreshTerminalHistory() {
        terminalHistoryView.text = terminalHistory.joinToString(separator = "\n")
    }

    private fun startAppUpgradeFlow() {
        if (updateInProgress) {
            setUpdateStatus("Update is already in progress.")
            return
        }
        if (BuildConfig.GITHUB_UPDATE_TOKEN_REQUIRED && getGithubUpdateToken().isBlank()) {
            promptForGithubTokenAndRetryUpdate()
            return
        }
        updateInProgress = true
        autoUpgradeButton.isEnabled = false
        setUpdateStatus("Downloading latest build...")

        Thread {
            try {
                val updateDir = File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updateDir, "jarvis-latest.apk")
                val sourceUrl = downloadLatestApk(apkFile)
                runOnUiThread {
                    setUpdateStatus("Download complete from ${sourceUrl.substringAfter("//")}. Launching installer...")
                    launchInstallerForDownloadedApk(apkFile)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    val detail = error.message ?: "unknown download error"
                    setUpdateStatus("Update failed: $detail")
                }
            } finally {
                runOnUiThread {
                    updateInProgress = false
                    autoUpgradeButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun downloadLatestApk(targetFile: File): String {
        val failures = mutableListOf<String>()
        for (url in buildUpdateUrlCandidates()) {
            val result = runCatching { downloadLatestApkFromUrl(url, targetFile, getGithubUpdateToken()) }
            if (result.isSuccess) {
                return url
            }
            failures += "${url.substringAfter("//")} -> ${result.exceptionOrNull()?.message ?: "unknown error"}"
        }
        throw IllegalStateException(failures.firstOrNull() ?: "No updater URL candidates available.")
    }

    private fun downloadLatestApkFromUrl(url: String, targetFile: File, githubToken: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 22000
            readTimeout = 70000
            requestMethod = "GET"
            doInput = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JARVIS-Updater/2.3")
            setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
            if (githubToken.isNotBlank()) {
                setRequestProperty("Authorization", "token $githubToken")
            }
            connect()
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val contentType = connection.contentType.orEmpty().lowercase(Locale.getDefault())
            if (contentType.contains("text/html")) {
                throw IllegalStateException("received HTML instead of APK")
            }
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (!targetFile.exists() || targetFile.length() < MIN_VALID_APK_BYTES) {
                throw IllegalStateException("Downloaded APK appears invalid.")
            }
            if (!isLikelyApkZip(targetFile)) {
                throw IllegalStateException("Downloaded file is not an APK archive.")
            }
        } catch (error: Throwable) {
            targetFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUpdateUrlCandidates(): List<String> {
        val owner = BuildConfig.GITHUB_REPO_OWNER.trim().ifEmpty { "muddigger782012" }
        val repo = BuildConfig.GITHUB_REPO_NAME.trim().ifEmpty { "assistant-platform" }
        val branch = BuildConfig.GITHUB_UPDATE_BRANCH.trim().ifEmpty { "main" }
        val encodedBranch = Uri.encode(branch)
        val apiBranch = Uri.encode(branch, "@#&=*+-_.,:!?()/~'%")

        return listOf(
            "https://raw.githubusercontent.com/$owner/$repo/$branch/artifacts/app-debug.apk",
            "https://raw.githubusercontent.com/$owner/$repo/$encodedBranch/artifacts/app-debug.apk",
            "https://raw.githubusercontent.com/$owner/$repo/refs/heads/$branch/artifacts/app-debug.apk",
            "https://api.github.com/repos/$owner/$repo/contents/artifacts/app-debug.apk?ref=$apiBranch",
            "https://github.com/$owner/$repo/raw/refs/heads/$branch/artifacts/app-debug.apk",
            "https://github.com/$owner/$repo/raw/$branch/artifacts/app-debug.apk"
        ).distinct()
    }

    private fun promptForGithubTokenAndRetryUpdate() {
        val tokenInput = EditText(this).apply {
            hint = "GitHub Personal Access Token"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(getGithubUpdateToken())
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(this)
            .setTitle("GitHub Token Required")
            .setMessage(
                "This repository is private. Enter a GitHub token with repository read access to enable automatic in-app updates."
            )
            .setView(tokenInput)
            .setCancelable(true)
            .setNegativeButton("Cancel") { _, _ ->
                setUpdateStatus("Update cancelled. Token required for private-repo auto-update.")
            }
            .setPositiveButton("Save & Update") { _, _ ->
                val token = tokenInput.text?.toString()?.trim().orEmpty()
                if (token.isBlank()) {
                    setUpdateStatus("Token is empty. Update not started.")
                } else {
                    saveGithubUpdateToken(token)
                    setUpdateStatus("Token saved. Starting automatic update...")
                    startAppUpgradeFlow()
                }
            }
            .show()
    }

    private fun getGithubUpdateToken(): String {
        return uiPreferences.getString(KEY_GITHUB_UPDATE_TOKEN, "").orEmpty()
    }

    private fun saveGithubUpdateToken(token: String) {
        uiPreferences.edit().putString(KEY_GITHUB_UPDATE_TOKEN, token).apply()
    }

    private fun isLikelyApkZip(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                b0 == 0x50 && b1 == 0x4B // PK
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun launchInstallerForDownloadedApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            setUpdateStatus("Enable 'Install unknown apps' for J.A.R.V.I.S., then tap Update App again.")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        try {
            startActivity(installIntent)
            setUpdateStatus("Installer opened. Confirm installation to complete update.")
        } catch (_: Throwable) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(fallbackIntent)
            setUpdateStatus("Installer opened. Confirm installation to complete update.")
        }
    }

    private fun setUpdateStatus(text: String) {
        updateStatusOutput.text = text
        appendOutput("Updater: $text")
    }

    private fun applyMicroInteractions(root: View) {
        if (root is Button) {
            root.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    }
                }
                false
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyMicroInteractions(root.getChildAt(index))
            }
        }
    }

    private fun startHudAnimations() {
        pulseAnimator?.cancel()
        scanlineAnimator?.cancel()

        pulseAnimator = ObjectAnimator.ofFloat(hudPulseOverlay, View.ALPHA, 0.06f, 0.2f, 0.08f).apply {
            duration = 3200L
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        scanlineView.post {
            val travel = (tabContainer.height - scanlineView.height).coerceAtLeast(1)
            scanlineAnimator = ObjectAnimator.ofFloat(
                scanlineView,
                View.TRANSLATION_Y,
                -scanlineView.height.toFloat(),
                travel.toFloat()
            ).apply {
                duration = 3000L
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopHudAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        scanlineAnimator?.cancel()
        scanlineAnimator = null
    }

    private fun refreshShizukuRuntimeStatus() {
        val binderReady = isShizukuBinderReady()
        val permissionState = getShizukuPermissionState()
        val permissionLabel = when (permissionState) {
            PackageManager.PERMISSION_GRANTED -> "granted"
            PackageManager.PERMISSION_DENIED -> "denied"
            null -> "unknown (binder not received)"
            else -> "unknown ($permissionState)"
        }
        shizukuRuntimeStatusView.text = buildString {
            appendLine("binderReady: $binderReady")
            appendLine("permission: $permissionLabel")
            append("capabilityFlag: ${if (::capabilityState.isInitialized) capabilityState.shizuku else false}")
        }
        requestShizukuPermissionButton.isEnabled = binderReady && permissionState != PackageManager.PERMISSION_GRANTED
        if (::capabilityState.isInitialized) {
            capabilityState = capabilityState.copy(shizuku = binderReady)
        }
    }

    private fun ensureShizukuReadyForExecution(onMessage: (String) -> Unit): Boolean {
        if (!isShizukuBinderReady()) {
            onMessage("Shizuku binder not connected. Open Shizuku app and start the service first.")
            onMessage("Tap 'Open Shizuku App', start service, then tap 'Refresh Shizuku Status'.")
            refreshShizukuRuntimeStatus()
            return false
        }
        val permissionState = getShizukuPermissionState()
        if (permissionState != PackageManager.PERMISSION_GRANTED) {
            onMessage("Shizuku permission is not granted. Tap 'Request Shizuku Permission'.")
            refreshShizukuRuntimeStatus()
            return false
        }
        return true
    }

    private fun openShizukuApp() {
        val candidates = listOf("moe.shizuku.privileged.api", "rikka.shizuku")
        val intent = candidates
            .asSequence()
            .mapNotNull { packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()
        if (intent == null) {
            appendOutput("Shizuku app is not installed on this device.")
            return
        }
        startActivity(intent)
    }

    private fun requestShizukuPermission() {
        if (!isShizukuBinderReady()) {
            appendOutput("Cannot request permission: Shizuku binder not connected. Start Shizuku service first.")
            refreshShizukuRuntimeStatus()
            return
        }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            appendOutput("Requested Shizuku permission.")
        } catch (error: Throwable) {
            appendOutput("Failed to request Shizuku permission: ${error.message ?: "unknown error"}")
        }
        refreshShizukuRuntimeStatus()
    }

    private fun isShizukuBinderReady(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun getShizukuPermissionState(): Int? {
        if (!isShizukuBinderReady()) return null
        return try {
            Shizuku.checkSelfPermission()
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildShizukuStateSummary(): String {
        val binderReady = isShizukuBinderReady()
        val permissionState = when (getShizukuPermissionState()) {
            PackageManager.PERMISSION_GRANTED -> "granted"
            PackageManager.PERMISSION_DENIED -> "denied"
            null -> "unknown (binder has not been received)"
            else -> "unknown"
        }
        return "Shizuku state -> binderReady=$binderReady, permission=$permissionState."
    }

    private fun handleVoiceRecognition(recognition: VoiceRecognitionResult) {
        val command = recognition.transcript
        commandInput.setText(command)
        appendOutput("Voice command (${(recognition.confidence * 100f).toInt()}%): $command")

        pendingClarification?.let { awaiting ->
            when {
                looksLikeYes(command) -> {
                    pendingClarification = null
                    executeVoiceCommand(awaiting)
                }
                looksLikeNo(command) -> {
                    pendingClarification = null
                    appendOutput(getString(R.string.voice_clarification_cancelled))
                    voiceService.speak(getString(R.string.voice_repeat_prompt))
                }
                else -> {
                    appendOutput(getString(R.string.voice_clarification_yes_no))
                    voiceService.speak(getString(R.string.voice_clarification_yes_no))
                }
            }
            return
        }

        if (recognition.confidence < currentVoiceConfig.commandConfidenceThreshold) {
            pendingClarification = command
            val prompt = getString(R.string.voice_clarification_prompt, command)
            appendOutput(prompt)
            voiceService.speak(prompt)
            return
        }

        executeVoiceCommand(command)
    }

    private fun executeVoiceCommand(command: String) {
        val parsed = voiceCommandParser.parse(command, currentVoiceConfig)
        parsed.responseHint?.let { appendOutput(it) }

        when (parsed.localCommand) {
            LocalVoiceCommand.STOP_VOICE -> {
                stopVoiceHotwordMode()
                voiceService.speak("Voice mode disabled.")
            }
            LocalVoiceCommand.START_VOICE -> {
                ensureMicPermissionAndStartVoice()
                voiceService.speak("Voice mode enabled.")
            }
            LocalVoiceCommand.OPEN_SETTINGS -> {
                openVoiceSettings()
                voiceService.speak(getString(R.string.voice_settings_opened))
            }
            LocalVoiceCommand.NONE -> {
                val proposedAction = parsed.actionRequest
                if (proposedAction != null && !proposedAction.confirmed) {
                    val reply = hybridAssistantService.handleUserInput(command)
                    appendOutput("J.A.R.V.I.S.: ${reply.text}")
                    statusOutput.text = reply.actionResult?.output ?: reply.text
                    voiceService.speak(reply.text.lineSequence().firstOrNull()?.take(180) ?: "Confirmation required.")
                } else {
                    val directResult = proposedAction?.let { assistantEngine.executeAction(it) }
                    if (directResult != null) {
                        appendActionResult(directResult)
                        statusOutput.text = directResult.output ?: directResult.message
                        voiceService.speak(if (directResult.success) directResult.message else "I couldn't complete that command.")
                    } else {
                        val reply = hybridAssistantService.handleUserInput(parsed.fallbackTextCommand ?: command)
                        appendOutput("J.A.R.V.I.S.: ${reply.text}")
                        statusOutput.text = reply.actionResult?.output ?: reply.text
                        voiceService.speak(reply.text.lineSequence().firstOrNull()?.take(180) ?: "Done.")
                    }
                }
                appendRecentAudit()
            }
        }
    }

    private fun ensureMicPermissionAndStartVoice() {
        if (hasMicrophonePermission()) {
            startVoiceHotwordMode()
        } else {
            shouldStartVoiceAfterPermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceHotwordMode() {
        shouldStartVoiceAfterPermission = false
        pendingClarification = null
        if (currentVoiceConfig.enableDedicatedWakeWord && currentVoiceConfig.porcupineAccessKey.isBlank()) {
            appendOutput("Porcupine AccessKey is not configured; using speech fallback mode.")
        }
        if (currentVoiceConfig.useForegroundServiceMode) {
            VoiceForegroundService.start(this)
            voiceEnabled = true
            appendOutput(getString(R.string.voice_service_started))
        } else {
            voiceEnabled = true
            appendOutput(getString(R.string.voice_started))
            voiceService.startHotwordLoop()
            appendOutput(getString(R.string.voice_engine_label, voiceService.getCurrentHotwordEngine().name))
        }
        refreshVoiceButtonLabel()
    }

    private fun stopVoiceHotwordMode() {
        pendingClarification = null
        if (currentVoiceConfig.useForegroundServiceMode) {
            VoiceForegroundService.stop(this)
            voiceEnabled = false
            appendOutput(getString(R.string.voice_service_stopped))
        } else {
            voiceEnabled = false
            voiceService.stopListening()
        }
        refreshVoiceButtonLabel()
    }

    private fun openVoiceSettings() {
        voiceSettingsLauncher.launch(Intent(this, VoiceSettingsActivity::class.java))
    }

    private fun reloadVoiceConfiguration(showStatus: Boolean) {
        currentVoiceConfig = voicePreferences.load()
        voiceService.updateConfig(currentVoiceConfig)
        capabilityState = CapabilityDetector().detect(this)
        if (currentVoiceConfig.useForegroundServiceMode && voiceEnabled) {
            voiceService.stopListening()
        }
        voiceEnabled = if (currentVoiceConfig.useForegroundServiceMode) {
            voicePreferences.isForegroundServiceRunning()
        } else {
            voiceEnabled
        }
        if (showStatus) {
            appendOutput(getString(R.string.voice_engine_label, voiceService.getCurrentHotwordEngine().name))
            appendOutput(
                getString(
                    R.string.voice_mode_label,
                    if (currentVoiceConfig.useForegroundServiceMode) "FOREGROUND_SERVICE" else "IN_APP"
                )
            )
        }
        refreshVoiceButtonLabel()
        if (currentVoiceConfig.useForegroundServiceMode) {
            if (currentVoiceConfig.autoStartForegroundService && !voicePreferences.isForegroundServiceRunning()) {
                ensureMicPermissionAndStartVoice()
            }
        } else if (currentVoiceConfig.autoStartVoice && !voiceEnabled) {
            ensureMicPermissionAndStartVoice()
        }
        refreshPrivilegeCenter()
    }

    private fun refreshVoiceButtonLabel() {
        if (!::currentVoiceConfig.isInitialized) return
        voiceButton.text = if (currentVoiceConfig.useForegroundServiceMode) {
            if (voiceEnabled) getString(R.string.stop_voice_service) else getString(R.string.start_voice_service)
        } else {
            if (voiceEnabled) getString(R.string.stop_voice_hotword) else getString(R.string.start_voice_hotword)
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun updateMicrophonePermissionUi() {
        val granted = hasMicrophonePermission()
        micPermissionStatusView.text = if (granted) {
            getString(R.string.mic_permission_status_ok)
        } else {
            getString(R.string.mic_permission_status_missing)
        }
        grantMicPermissionButton.isEnabled = !granted
    }

    private fun promptForMicrophonePermissionOnFirstLaunch() {
        val alreadyPrompted = uiPreferences.getBoolean(KEY_PROMPTED_MIC_PERMISSION, false)
        if (!alreadyPrompted && !hasMicrophonePermission()) {
            uiPreferences.edit().putBoolean(KEY_PROMPTED_MIC_PERMISSION, true).apply()
            appendOutput(getString(R.string.mic_permission_prompt_startup))
            shouldStartVoiceAfterPermission = false
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestDhizukuPermission() {
        dhizukuService.requestPermission(this) { granted, message ->
            runOnUiThread {
                appendOutput(message)
                refreshPrivilegeCenter()
                if (granted) appendOutput(getString(R.string.dhizuku_permission_granted_hint))
            }
        }
    }

    private fun applyCoreDelegatedScopes() {
        dhizukuService.applyCoreDelegatedScopes(this) { success, message ->
            runOnUiThread {
                appendOutput(message)
                if (success) refreshPrivilegeCenter()
            }
        }
    }

    private fun refreshPrivilegeCenter() {
        currentDhizukuStatus = dhizukuService.getStatus(this)
        capabilityState = capabilityState.copy(dhizuku = currentDhizukuStatus.initialized)
        privilegeCenterView.text = privilegeCatalogService.buildPrivilegeOverview(
            capabilityState = capabilityState,
            dhizukuStatus = currentDhizukuStatus
        )
        requestDhizukuPermissionButton.isEnabled = currentDhizukuStatus.initialized && !currentDhizukuStatus.permissionGranted
        applyCoreDelegatedScopesButton.isEnabled = currentDhizukuStatus.initialized && currentDhizukuStatus.permissionGranted
        refreshDeviceOwnerControlsAvailability()
    }

    private fun refreshDeviceOwnerControlsAvailability() {
        val enabled = resolvePolicyComponent() != null
        val controls = listOf(
            switchCameraDisabled,
            switchScreenCaptureDisabled,
            switchInstallAppsRestricted,
            switchUninstallAppsRestricted,
            switchStatusBarDisabled,
            switchPackageUninstallBlocked,
            lockScreenMessageInput,
            policyPackageNameInput,
            btnApplyCameraDisabled,
            btnApplyScreenCaptureDisabled,
            btnApplyInstallAppsRestriction,
            btnApplyUninstallAppsRestriction,
            btnApplyStatusBarDisabled,
            btnApplyLockScreenMessage,
            btnApplyPackagePolicy,
            btnLockNow,
            btnRebootFromDhizuku
        )
        controls.forEach { it.isEnabled = enabled }
    }

    private fun resolvePolicyComponent(): ComponentName? {
        return try {
            when {
                currentDhizukuStatus.initialized && currentDhizukuStatus.permissionGranted -> {
                    Dhizuku.getOwnerComponent(this)
                }
                capabilityState.deviceOwner -> {
                    Dhizuku.getOwnerComponent(devicePolicyManager)
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyCameraDisabled() = withPolicyComponent { admin ->
        devicePolicyManager.setCameraDisabled(admin, switchCameraDisabled.isChecked)
        appendOutput("Camera disabled set to ${switchCameraDisabled.isChecked}")
    }

    private fun applyScreenCaptureDisabled() = withPolicyComponent { admin ->
        devicePolicyManager.setScreenCaptureDisabled(admin, switchScreenCaptureDisabled.isChecked)
        appendOutput("Screen capture disabled set to ${switchScreenCaptureDisabled.isChecked}")
    }

    private fun applyInstallAppsRestriction() = withPolicyComponent { admin ->
        applyUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS, switchInstallAppsRestricted.isChecked)
        appendOutput("Install apps restriction set to ${switchInstallAppsRestricted.isChecked}")
    }

    private fun applyUninstallAppsRestriction() = withPolicyComponent { admin ->
        applyUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS, switchUninstallAppsRestricted.isChecked)
        appendOutput("Uninstall apps restriction set to ${switchUninstallAppsRestricted.isChecked}")
    }

    private fun applyStatusBarDisabled() = withPolicyComponent { admin ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val applied = devicePolicyManager.setStatusBarDisabled(admin, switchStatusBarDisabled.isChecked)
            appendOutput("Status bar disabled set to ${switchStatusBarDisabled.isChecked} (applied=$applied)")
        } else {
            appendOutput("Status bar policy requires Android 6+.")
        }
    }

    private fun applyLockScreenMessage() = withPolicyComponent { admin ->
        val message = lockScreenMessageInput.text?.toString().orEmpty()
        devicePolicyManager.setDeviceOwnerLockScreenInfo(admin, message)
        appendOutput("Lock screen message updated.")
    }

    private fun applyPackagePolicy() = withPolicyComponent { admin ->
        val packageName = policyPackageNameInput.text?.toString()?.trim().orEmpty()
        if (packageName.isBlank()) {
            appendOutput("Package name is required.")
            return@withPolicyComponent
        }
        devicePolicyManager.setUninstallBlocked(admin, packageName, switchPackageUninstallBlocked.isChecked)
        appendOutput("Package uninstall policy set for $packageName = ${switchPackageUninstallBlocked.isChecked}")
    }

    private fun lockNow() = withPolicyComponent {
        devicePolicyManager.lockNow()
        appendOutput("Lock now command sent.")
    }

    private fun rebootDevice() = withPolicyComponent { admin ->
        devicePolicyManager.reboot(admin)
        appendOutput("Reboot command sent.")
    }

    private fun applyUserRestriction(admin: ComponentName, key: String, enabled: Boolean) {
        if (enabled) {
            devicePolicyManager.addUserRestriction(admin, key)
        } else {
            devicePolicyManager.clearUserRestriction(admin, key)
        }
    }

    private fun withPolicyComponent(action: (ComponentName) -> Unit) {
        val admin = resolvePolicyComponent()
        if (admin == null) {
            appendOutput("No active Dhizuku/device-owner component. Request Dhizuku permission first.")
            return
        }
        try {
            action(admin)
            refreshPrivilegeCenter()
        } catch (error: SecurityException) {
            appendOutput("Policy action blocked: ${error.message ?: "security exception"}")
        } catch (error: Throwable) {
            appendOutput("Policy action failed: ${error.message ?: "unknown error"}")
        }
    }

    private fun requestAllRuntimePermissions() {
        runtimePermissionsLauncher.launch(buildRuntimePermissionList().toTypedArray())
    }

    private fun buildRuntimePermissionList(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            permissions += Manifest.permission.READ_MEDIA_IMAGES
            permissions += Manifest.permission.READ_MEDIA_VIDEO
            permissions += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return permissions.distinct()
    }

    private fun requestAllSpecialPermissions() {
        val opened = openNextMissingSpecialPermission()
        if (!opened) {
            appendOutput("All tracked special permissions already appear granted.")
        }
        refreshSpecialPermissionsStatus()
    }

    private fun openNextMissingSpecialPermission(): Boolean {
        return when {
            !Settings.canDrawOverlays(this) -> {
                openOverlayPermission(); true
            }
            !Settings.System.canWrite(this) -> {
                openWriteSettingsPermission(); true
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager() -> {
                openAllFilesPermission(); true
            }
            !hasUsageAccess() -> {
                openUsageAccessPermission(); true
            }
            !isIgnoringBatteryOptimizations() -> {
                openBatteryOptimizationPermission(); true
            }
            !isNotificationPolicyAccessGranted() -> {
                openNotificationPolicyPermission(); true
            }
            !isAccessibilityEnabledForApp() -> {
                openAccessibilitySettings(); true
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms() -> {
                openExactAlarmPermission(); true
            }
            else -> false
        }
    }

    private fun refreshSpecialPermissionsStatus() {
        specialPermissionsStatus.text = buildString {
            appendLine("Runtime permissions:")
            buildRuntimePermissionList().forEach { permission ->
                val granted = ContextCompat.checkSelfPermission(this@MainActivity, permission) == PackageManager.PERMISSION_GRANTED
                appendLine("- $permission: $granted")
            }
            appendLine()
            appendLine("Special app-access permissions:")
            appendLine("- Overlay: ${Settings.canDrawOverlays(this@MainActivity)}")
            appendLine("- Write Settings: ${Settings.System.canWrite(this@MainActivity)}")
            appendLine("- All Files Access: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager() else "N/A"}")
            appendLine("- Usage Access: ${hasUsageAccess()}")
            appendLine("- Battery Optimization Exemption: ${isIgnoringBatteryOptimizations()}")
            appendLine("- Notification Policy Access: ${isNotificationPolicyAccessGranted()}")
            appendLine("- Accessibility Enabled For App: ${isAccessibilityEnabledForApp()}")
            appendLine("- Schedule Exact Alarms: ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) canScheduleExactAlarms() else "N/A"}")
        }
    }

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openWriteSettingsPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun openUsageAccessPermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openBatteryOptimizationPermission() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openNotificationPolicyPermission() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isNotificationPolicyAccessGranted(): Boolean {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    private fun isAccessibilityEnabledForApp(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(packageName, ignoreCase = true) == true
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    private fun registerVoiceEventReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(VoiceForegroundService.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceEventReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(voiceEventReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun registerShizukuListeners() {
        if (shizukuListenersRegistered) return
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
            shizukuListenersRegistered = true
        } catch (_: Throwable) {
            // ignore; status UI still works through manual refresh checks
        }
    }

    private fun unregisterVoiceEventReceiver() {
        if (!receiverRegistered) return
        unregisterReceiver(voiceEventReceiver)
        receiverRegistered = false
    }

    private fun unregisterShizukuListeners() {
        if (!shizukuListenersRegistered) return
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        } catch (_: Throwable) {
            // ignore
        }
        shizukuListenersRegistered = false
    }

    private fun looksLikeYes(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("yes") || normalized.contains("correct") || normalized.contains("confirm")
    }

    private fun looksLikeNo(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.contains("no") || normalized.contains("cancel") || normalized.contains("wrong")
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override fun onStart() {
        super.onStart()
        registerVoiceEventReceiver()
        registerShizukuListeners()
        startHudAnimations()
    }

    override fun onStop() {
        stopHudAnimations()
        unregisterShizukuListeners()
        unregisterVoiceEventReceiver()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        reloadVoiceConfiguration(showStatus = false)
        updateMicrophonePermissionUi()
        refreshSpecialPermissionsStatus()
        refreshShizukuRuntimeStatus()
        refreshAuditDebugSection()
        tabContainer.post { updateSystemGestureExclusionRects() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            tabContainer.post { updateSystemGestureExclusionRects() }
        }
    }

    override fun onDestroy() {
        runningTerminalCommand?.stop()
        runningTerminalCommand = null
        voiceService.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_PROMPTED_MIC_PERMISSION = "prompted_mic_permission"
        private const val KEY_GITHUB_UPDATE_TOKEN = "github_update_token"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 9567
        private const val TAB_TRANSITION_DURATION_MS = 220L
        private const val SWIPE_VELOCITY_THRESHOLD = 900f
        private const val MIN_VALID_APK_BYTES = 250_000L
    }

    private data class TabBinding(
        val button: Button,
        val section: View
    )
}
