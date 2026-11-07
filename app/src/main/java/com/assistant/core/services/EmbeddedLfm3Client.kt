package com.assistant.core.services

import android.app.ActivityManager
import android.content.Context
import com.assistant.core.BuildConfig
import java.io.File

/**
 * The JNI library is packaged only in opt-in ARM64 builds. Default Personal/Beta APKs cannot
 * claim this capability merely because a model was downloaded.
 */
internal object EmbeddedLfm3Native {
    val available: Boolean by lazy {
        BuildConfig.EMBEDDED_LFM3 && runCatching {
            System.loadLibrary("jarvis_lfm3")
            true
        }.getOrDefault(false)
    }
    external fun nativeInit(nativeLibraryDir: String): Boolean
    external fun nativeLoad(modelPath: String): Boolean
    external fun nativeGenerate(systemText: String, userText: String, tokens: Int): String?
    external fun nativeCancel()
    external fun nativeUnload()
}

/** Trim history first, preserving the newest user question in every on-device prompt. */
internal object EmbeddedLfm3Prompt {
    const val MAX_PROMPT_CHARS = 11_000
    const val MAX_QUESTION_CHARS = 6_000
    fun compose(question: String, conversation: List<Pair<String, String>>): String {
        val latest = "\nUser: " + question.take(MAX_QUESTION_CHARS)
        val historyBudget = (MAX_PROMPT_CHARS - latest.length).coerceAtLeast(0)
        var remaining = historyBudget
        val selected = mutableListOf<String>()
        for ((user, response) in conversation.asReversed().take(5)) {
            val turn = "\nUser: " + user.take(800) + "\nJ.A.R.V.I.S.: " + response.take(800)
            if (turn.length > remaining) break
            selected.add(turn)
            remaining -= turn.length
        }
        return selected.asReversed().joinToString("") + latest
    }
}

/** Device-local direct model execution. There is no HTTP client or model-controlled tool access here. */
class EmbeddedLfm3Client(private val context: Context) {
    companion object {
        const val MIN_AVAILABLE_MEMORY = 2_000_000_000L
        const val MAX_RESPONSE_TOKENS = 256
        // A downloaded 1.1 GB model must not be rehashed on every spoken turn.
        // Recheck when the file, size, SHA-256 receipt, or timestamp changes.
        private var verifiedIdentity: String? = null
    }
    private val models by lazy { LocalLfm3ModelStore(context.applicationContext) }
    private val personal get() = context.packageName == "com.assistant.core"
    private val prefs get() = RuntimeSettings.read(context, "jarvis_cloud_ai")
    private val enabled get() = personal && prefs.getBoolean("lfm3_enabled", false)
    fun binaryIncluded(): Boolean = personal && EmbeddedLfm3Native.available

    private fun enoughRam(file: File): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return !info.lowMemory && info.availMem >= maxOf(MIN_AVAILABLE_MEMORY, file.length() + 700_000_000L)
    }
    private fun historyPrompt(userText: String, conversation: List<Pair<String, String>>): String =
        EmbeddedLfm3Prompt.compose(userText, conversation)

    /**
     * null means no bundled library or no downloaded weights (try the existing loopback bridge).
     * An actual native failure is returned as a provider failure so routing can use cloud fallback.
     */
    fun ask(
        userText: String,
        conversation: List<Pair<String, String>> = emptyList(),
        current: () -> Boolean = { true }
    ): FallbackAiResult? {
        if (!enabled || !binaryIncluded()) return null
        if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
        val variant = LocalLfm3Models.selected(prefs.getString("lfm3_variant", "atomic"))
        val snapshot = models.status(variant)
        if (!snapshot.exists) return null
        val path = models.modelFile(variant)
        if (!enoughRam(path)) return FallbackAiResult(
            "Embedded LFM3 needs more available memory. Close other apps, or switch to a configured fallback.", null, true)
        return try {
            synchronized(EmbeddedLfm3Native) {
                val identity = path.absolutePath + ":" + snapshot.bytes + ":" +
                    snapshot.sha256 + ":" + path.lastModified()
                if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
                if (verifiedIdentity != identity) {
                    // The path may have been replaced by a new download; never reuse stale
                    // mmap/model state for a changed file at the same pathname.
                    EmbeddedLfm3Native.nativeUnload()
                    verifiedIdentity = null
                    if (!models.verify(variant, cancelled = { !current() })) {
                        return FallbackAiResult("Saved LFM3 GGUF failed its SHA-256 integrity check. Re-download the model.", null, true)
                    }
                    verifiedIdentity = identity
                }
                if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
                if (!EmbeddedLfm3Native.nativeInit(context.applicationInfo.nativeLibraryDir) ||
                    !EmbeddedLfm3Native.nativeLoad(path.absolutePath))
                    return FallbackAiResult("Embedded LFM3 could not initialize its model/context. Check storage and available memory.", null, true)
                val system = "You are J.A.R.V.I.S., a personal offline companion. Be direct and technically useful. " +
                    "Do not falsely claim certainty or phone actions. You have no ability to execute commands; " +
                    "the host app separately handles approved device operations."
                if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
                val result = EmbeddedLfm3Native.nativeGenerate(system, historyPrompt(userText, conversation), MAX_RESPONSE_TOKENS)
                    ?.trim()?.takeIf(String::isNotBlank)
                if (!current()) return FallbackAiResult("Earlier LFM3 response cancelled.", null, true)
                if (result == null) FallbackAiResult("The embedded LFM3 model returned no usable text or was cancelled.", null, true)
                else FallbackAiResult(result, null)
            }
        } catch (_: Exception) {
            FallbackAiResult("Embedded LFM3 failed before completing inference; see Android diagnostics.", null, true)
        } catch (_: LinkageError) {
            FallbackAiResult("Embedded LFM3 native library could not be loaded for this device.", null, true)
        }
    }

    fun cancel() {
        if (binaryIncluded()) runCatching { EmbeddedLfm3Native.nativeCancel() }
    }
    fun unload() {
        if (binaryIncluded()) synchronized(EmbeddedLfm3Native) {
            runCatching { EmbeddedLfm3Native.nativeUnload() }
            verifiedIdentity = null
        }
    }
}
