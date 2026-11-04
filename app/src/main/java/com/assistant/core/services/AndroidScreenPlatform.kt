package com.assistant.core.services

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.security.MessageDigest

/** On-demand, bounded node access. No gestures, injected events, files or network traffic. */
class AndroidScreenPlatform(private val context: Context) : ScreenControls.Platform {
    override fun connected() = JarvisAccessibilityService.active() != null
    @Volatile private var lastRead: ScreenReadDiagnostics? = null
    override fun readDiagnostics() = lastRead
    private class ReadTrace {
        val started = SystemClock.uptimeMillis()
        private val queued = requestQueueWait.get() ?: 0L
        private val phaseTimes = LongArray(ScreenReadDiagnostics.Stage.values().size)
        private var phaseStarted = started
        var stage = ScreenReadDiagnostics.Stage.PREPARE
            set(value) {
                val now = SystemClock.uptimeMillis()
                phaseTimes[field.ordinal] += (now - phaseStarted).coerceAtLeast(0)
                field = value; phaseStarted = now
            }
        var nodes = 0
        var cache = ScreenReadDiagnostics.Cache.UNKNOWN
        var protectedScreen = false
        var freshness: ScreenCaptureRead.Freshness? = null
        fun result(reason: ScreenReadFailure.Reason? = null, blocked: Boolean = protectedScreen): ScreenReadDiagnostics {
            val now = SystemClock.uptimeMillis()
            val times = phaseTimes.copyOf().also { it[stage.ordinal] += (now - phaseStarted).coerceAtLeast(0) }
            return ScreenReadDiagnostics(reason, blocked, stage, if (blocked) 0 else nodes.coerceIn(0, 300),
                (now - started).coerceIn(0, 60_000), cache, queued,
                ScreenReadDiagnostics.Phases(times[0], times[1], times[2], times[3], times[4]),
                if (blocked) 0 else freshness?.refreshes ?: 0,
                when (freshness?.platformFresh) {
                    true -> ScreenReadDiagnostics.RefreshPolicy.PLATFORM_FRESH
                    false -> ScreenReadDiagnostics.RefreshPolicy.EXPLICIT_REQUIRED
                    null -> ScreenReadDiagnostics.RefreshPolicy.UNKNOWN
                })
        }
    }
    private fun readFailure(failure: Exception): ScreenReadFailure = when {
        requestCancelled.get()?.invoke() == true -> ScreenReadFailure(ScreenReadFailure.Reason.CANCELLED)
        SystemClock.uptimeMillis() >= (requestDeadline.get() ?: Long.MAX_VALUE) -> ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
        failure is ScreenReadFailure -> failure
        !connected() -> ScreenReadFailure(ScreenReadFailure.Reason.DISCONNECTED)
        else -> ScreenReadFailure(ScreenReadFailure.Reason.UNAVAILABLE)
    }
    private fun within(deadline: Long = Long.MAX_VALUE) {
        if (requestExpired()) throw readFailure(IllegalStateException())
        if (SystemClock.uptimeMillis() > deadline) throw ScreenReadFailure(ScreenReadFailure.Reason.BUSY)
    }
    private fun locked(): Boolean = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.let {
        it.isDeviceLocked || it.isKeyguardLocked
    } ?: true
    private class Capture(val snapshot: ScreenControls.Snapshot, val nodes: Map<String, AccessibilityNodeInfo>,
                          val owned: List<AccessibilityNodeInfo>) : AutoCloseable {
        @Suppress("DEPRECATION") override fun close() { owned.forEach { it.recycle() } }
    }
    private fun capture(): Capture {
        val trace = ReadTrace()
        val owned = mutableListOf<AccessibilityNodeInfo>()
        try {
            within()
            val service = JarvisAccessibilityService.active() ?: throw ScreenReadFailure(ScreenReadFailure.Reason.DISCONNECTED)
            if (locked()) {
                lastRead = trace.result(blocked = true)
                return Capture(ScreenControls.Snapshot("locked", "Android", emptyList(), emptyList(), true), emptyMap(), emptyList())
            }
            // With caching disabled, getChild already queries fresh nodes. clearCache
            // can return false in that state; it must not select the cached fallback.
            val caching = if (Build.VERSION.SDK_INT >= 33) service.isCacheEnabled else null
            trace.cache = when (caching) { true -> ScreenReadDiagnostics.Cache.ENABLED; false -> ScreenReadDiagnostics.Cache.DISABLED; null -> ScreenReadDiagnostics.Cache.UNKNOWN }
            val freshHierarchy = Build.VERSION.SDK_INT >= 33 && (caching == false || service.clearCache())
            val freshness = ScreenCaptureRead.Freshness(freshHierarchy).also { trace.freshness = it }
            val generation = service.screenGeneration
            trace.stage = ScreenReadDiagnostics.Stage.ROOT
            val root = service.rootInActiveWindow ?: throw ScreenCaptureRead.Changed()
            owned.add(root)
            freshness.validate { root.refresh() }
            val pkg = root.packageName?.toString()?.takeIf { it.length in 1..200 } ?: error("No active package")
            trace.stage = ScreenReadDiagnostics.Stage.WINDOW
            val window = root.window ?: throw ScreenCaptureRead.Changed()
            @Suppress("DEPRECATION") val windowType = try { window.type } finally { window.recycle() }
            val appLabel = appLabel(pkg)
            val protected = windowType != AccessibilityWindowInfo.TYPE_APPLICATION || pkg in setOf("android", "com.android.settings", "com.android.systemui", "com.android.packageinstaller",
                "com.google.android.packageinstaller", "com.samsung.android.settings") ||
                listOf("permissioncontroller", "packageinstaller", "securitycenter").any { pkg.contains(it, true) }
            if (protected) {
                lastRead = trace.result(blocked = true)
                return Capture(ScreenControls.Snapshot("protected", pkg, emptyList(), emptyList(), true, appLabel), emptyMap(), owned)
            }
            val deadline = minOf(SystemClock.uptimeMillis() + 1500, requestDeadline.get() ?: Long.MAX_VALUE)
            val queue = ArrayDeque<Pair<String, AccessibilityNodeInfo>>()
            queue.add("0" to root)
            val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
            val text = mutableListOf<String>()
            val controls = mutableListOf<ScreenControls.Control>()
            val nodeLabels = linkedMapOf<String, String>()
            val boundsByPath = linkedMapOf<String, Rect>()
            val headings = linkedMapOf<String, String>()
            val digest = MessageDigest.getInstance("SHA-256")
            var characters = 0
            var sensitive = false
            fun hash(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
            fun limit(allowed: Boolean) { if (!allowed) throw ScreenReadFailure(ScreenReadFailure.Reason.LIMIT) }
            hash(generation); hash(root.windowId.toString()); hash(pkg)
            trace.stage = ScreenReadDiagnostics.Stage.CONTROLS
            while (queue.isNotEmpty()) {
                limit(nodes.size < 300)
                within(deadline)
                val (path, node) = queue.removeFirst()
                nodes[path] = node
                trace.nodes = nodes.size
                // API 33+ either cleared the cache before this root or has caching
                // disabled. Both paths supply fresh getChild results. Refreshing
                // them again duplicates remote IPC on cold Compose screens.
                // The fallback root was already refreshed before package/window validation.
                if (path != "0") freshness.validate { node.refresh() }
                if (!node.isVisibleToUser) continue
                val variation = node.inputType and InputType.TYPE_MASK_VARIATION
                val password = node.isPassword || ((node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
                    variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                    ((node.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                if (password || Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive) {
                    sensitive = true; trace.protectedScreen = true; continue // Never read this node or its descendants.
                }
                val label = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val hint = node.hintText?.toString()?.trim().orEmpty()
                limit(label.length <= 2000 && description.length <= 2000 && hint.length <= 2000)
                characters += label.length + description.length + hint.length; limit(characters <= 50_000)
                nodeLabels[path] = label.ifBlank { description }
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                boundsByPath[path] = bounds
                val click = node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                val scroll = node.isScrollable || node.actionList.any { it.id in setOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                val role = ScreenSemantics.role(node.className?.toString().orEmpty(), node.isEditable, scroll)
                val heading = Build.VERSION.SDK_INT >= 28 && node.isHeading
                if (heading && nodeLabels[path].orEmpty().isNotBlank()) headings[path] = display(nodeLabels.getValue(path), 100)
                hash(path); hash(node.className?.toString().orEmpty()); hash(node.viewIdResourceName.orEmpty())
                hash(label); hash(description); hash(hint); hash(bounds.flattenToString())
                hash("${node.isEnabled}:$click:$scroll:$heading:${node.isCheckable}:${node.isChecked}:${node.isSelected}")
                listOf(label, description, hint).filter { it.isNotBlank() }.forEach { text.add(display(it, 200)) }
                if (click || scroll || node.isEditable || node.isCheckable || role in setOf(ScreenSemantics.Role.BUTTON,
                        ScreenSemantics.Role.SWITCH, ScreenSemantics.Role.CHECKBOX, ScreenSemantics.Role.RADIO_BUTTON, ScreenSemantics.Role.MENU_ITEM)) {
                    val controlLabel = if (role == ScreenSemantics.Role.TEXT_FIELD) description.ifBlank { hint }.ifBlank { "Text field" }
                        else label.ifBlank { description }.ifBlank { if (scroll) "Scrollable area" else "Unlabelled control" }
                    controls.add(ScreenControls.Control(path, display(controlLabel, 100), click, scroll, role,
                        enabled = node.isEnabled, checked = if (node.isCheckable) node.isChecked else null, selected = node.isSelected))
                }
                limit(path.count { it == '/' } < 30 && node.childCount <= 300)
                for (index in 0 until node.childCount) {
                    within(deadline) // Stop a wide parent before issuing another remote child query.
                    val child = node.getChild(index) ?: throw ScreenCaptureRead.Changed()
                    owned.add(child); limit(owned.size <= 300); queue.add("$path/$index" to child)
                }
            }
            // Password-entry windows expose neither surrounding text nor executable targets.
            val labelled = controls.map { original ->
                val control = if (original.label != "Unlabelled control") original else {
                    val nested = nodeLabels.filter { (path, label) -> path.startsWith(original.path + "/") && label.isNotBlank() }.values.take(3).joinToString(" ")
                    if (nested.isBlank()) original else original.copy(label = display(nested, 100))
                }
                val parentLabel = generateSequence(control.path.substringBeforeLast('/', "")) {
                    it.substringBeforeLast('/', "").takeIf(String::isNotBlank)
                }.mapNotNull { nodeLabels[it]?.takeIf { value -> value.isNotBlank() && value != control.label } }.firstOrNull()
                val bounds = boundsByPath.getValue(control.path)
                val preceding = headings.keys.filter { path ->
                    val header = boundsByPath.getValue(path)
                    header.top <= bounds.top && header.left < bounds.right && header.right > bounds.left && path != control.path
                }
                val sameSection = preceding.filter { it.substringBeforeLast('/') == control.path.substringBeforeLast('/') }
                val headingPath = (sameSection.ifEmpty { preceding }).maxByOrNull { boundsByPath.getValue(it).top }
                control.copy(contextLabel = display(parentLabel ?: headingPath?.let { headings[it] }.orEmpty(), 100))
            }
            trace.stage = ScreenReadDiagnostics.Stage.VERIFY
            if (service.screenGeneration != generation) throw ScreenCaptureRead.Changed()
            within(deadline)
            val snapshot = ScreenControls.Snapshot(digest.digest().joinToString("") { "%02x".format(it) }, pkg,
                if (sensitive) emptyList() else labelled, if (sensitive) emptyList() else text.distinct(), sensitive,
                appLabel, if (sensitive) emptyList() else headings.values.distinct().take(20))
            lastRead = trace.result(blocked = sensitive)
            return Capture(snapshot, nodes, owned)
        } catch (failure: Exception) {
            @Suppress("DEPRECATION") owned.forEach { it.recycle() }
            val safeFailure = readFailure(failure)
            lastRead = trace.result(safeFailure.reason)
            throw safeFailure
        }
    }
    override fun snapshot(): ScreenControls.Snapshot {
        var attempts = 0
        var firstFailure: ScreenReadFailure.Reason? = null
        val started = SystemClock.uptimeMillis()
        return try { ScreenCaptureRead.read({ !requestExpired() && connected() },
            retryTimeout = { canRecoverReadTimeout() }, beforeRetry = { firstFailure = it }) {
            attempts++
            capture().use { it.snapshot }
        } } catch (failure: Exception) {
            val safeFailure = readFailure(failure)
            lastRead = if (attempts == 0) ScreenReadDiagnostics(reason = safeFailure.reason, queueMillis = requestQueueWait.get() ?: 0L)
                else lastRead?.copy(reason = safeFailure.reason) ?: ScreenReadDiagnostics(reason = safeFailure.reason)
            throw safeFailure
        } finally {
            if (attempts == 2 && firstFailure != null) lastRead = lastRead?.copy(recapture =
                ScreenReadDiagnostics.Recapture(requireNotNull(firstFailure), SystemClock.uptimeMillis() - started))
        }
    }
    override fun perform(snapshot: ScreenControls.Snapshot, control: ScreenControls.Control, scroll: ScreenControls.Kind?): ScreenControls.Result = try {
        capture().use { current ->
            if (current.snapshot.blocked || current.snapshot.key != snapshot.key || control !in current.snapshot.controls || !control.enabled)
                return@use ScreenControls.Result.CHANGED
            val node = current.nodes[control.path] ?: return@use ScreenControls.Result.CHANGED
            val action = when (scroll) {
                null -> AccessibilityNodeInfo.ACTION_CLICK
                ScreenControls.Kind.SCROLL_DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScreenControls.Kind.SCROLL_UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> return@use ScreenControls.Result.REJECTED
            }
            if (node.actionList.none { it.id == action }) return@use ScreenControls.Result.REJECTED
            if (requestExpired()) return@use ScreenControls.Result.REJECTED
            if (node.performAction(action)) ScreenControls.Result.ACCEPTED else ScreenControls.Result.REJECTED
        }
    } catch (_: Exception) { ScreenControls.Result.UNKNOWN }
    private fun appLabel(pkg: String): String = if (pkg == "android") "Android system" else runCatching {
        display(context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString(), 80)
            .ifBlank { "the active app" }
    }.getOrDefault("the active app")
    override fun global(kind: ScreenControls.Kind): ScreenControls.Result = try {
        if (locked()) ScreenControls.Result.REJECTED else {
            val action = when (kind) {
                ScreenControls.Kind.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                ScreenControls.Kind.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                ScreenControls.Kind.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                ScreenControls.Kind.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                ScreenControls.Kind.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                else -> -1
            }
            if (action < 0 || requestExpired()) ScreenControls.Result.REJECTED
            else if (JarvisAccessibilityService.active()?.performGlobal(action) == true) ScreenControls.Result.ACCEPTED else ScreenControls.Result.REJECTED
        }
    } catch (_: Exception) { ScreenControls.Result.UNKNOWN }
    companion object {
        private val requestDeadline = ThreadLocal<Long>()
        private val requestCancelled = ThreadLocal<() -> Boolean>()
        private val requestQueueWait = ThreadLocal<Long>()
        private val requestReadKind = ThreadLocal<ScreenControls.Kind>()
        private fun requestExpired() = SystemClock.uptimeMillis() >= (requestDeadline.get() ?: Long.MAX_VALUE) ||
            requestCancelled.get()?.invoke() == true
        internal fun canRecoverReadTimeout(): Boolean {
            val deadline = requestDeadline.get() ?: return false
            return !requestExpired() && ScreenCaptureRead.mayRetryTimeout(requestReadKind.get(), deadline - SystemClock.uptimeMillis())
        }
        fun <T> withDeadline(deadline: Long, cancelled: () -> Boolean = { false }, queueWaitMillis: Long = 0,
                             readKind: ScreenControls.Kind? = null, block: () -> T): T {
            requestDeadline.set(deadline)
            requestCancelled.set(cancelled)
            requestQueueWait.set(queueWaitMillis.coerceIn(0, 60_000))
            if (readKind == null) requestReadKind.remove() else requestReadKind.set(readKind)
            return try { block() } finally {
                requestDeadline.remove(); requestCancelled.remove(); requestQueueWait.remove(); requestReadKind.remove()
            }
        }
        private fun display(value: String, limit: Int) = value.replace(Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+"), " ")
            .replace(Regex("\\s+"), " ").trim().take(limit)
    }
}
