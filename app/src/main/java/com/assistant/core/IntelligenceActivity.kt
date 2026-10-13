package com.assistant.core

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import com.assistant.core.learning.ApprovedRoutineRegistry
import com.assistant.core.learning.ValidatedCommandMemory
import com.assistant.core.services.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Date
import kotlin.concurrent.thread

/** Local intelligence management. Opening the dashboard never calls AI or runs a routine. */
class IntelligenceActivity : ThemedActivity() {
    private val commands by lazy { ValidatedCommandMemory(this) }
    private val examples by lazy { IntentLearningStore(this) }
    private val memory by lazy { AssistantMemory(this) }
    private val results by lazy { IntelligenceResultHistory(this) }
    private val routines by lazy { ApprovedRoutineRegistry(this) }
    private val prefs by lazy { getSharedPreferences("jarvis_cloud_ai", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var checking = false
    private var lastTest = "Connection has not been tested in this screen."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intelligence)
        content = findViewById(R.id.intelligenceContent)
        scroll = findViewById(R.id.intelligenceScroll)
        findViewById<Button>(R.id.btnCloseIntelligence).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(panel: LinearLayout, value: String, heading: Boolean = false) = TextView(this).apply {
        text = value
        textSize = if (heading) 19f else 14f
        setTextColor(ThemePreferences.color(this@IntelligenceActivity, if (heading) R.color.jarvis_primary else R.color.jarvis_on_dark))
        if (heading) { setTypeface(typeface, Typeface.BOLD); ViewCompat.setAccessibilityHeading(this, true) }
        else setTextIsSelectable(true)
        panel.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun section(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(12))
        setBackgroundResource(R.drawable.intelligence_panel)
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        text(this, title, true)
    }
    private fun button(panel: LinearLayout, label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        setSingleLine(false)
        maxLines = 3
        minHeight = dp(48)
        panel.addView(this, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { action() }
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    private fun render() {
        val oldScroll = scroll.scrollY
        content.removeAllViews()
        val direct = DirectOnlineClient(this)
        val gateway = FallbackAiClient(this)
        val savedCommands = commands.snapshot()
        val savedExamples = examples.snapshot()
        val savedRoutines = routines.snapshot()
        val overview = IntelligenceOverview.build(savedCommands, savedExamples, savedRoutines.size,
            direct.hasGeminiKey(), gateway.isConfigured(), prefs.getBoolean("automatic_command_learning", true))
        val local = section("Learning on this device")
        text(local, "Saved command entries: ${overview.commandEntries}\nPaused entries: ${overview.pausedEntries}\nIntent examples: ${overview.intentExamples}\nAvailable approved routines: ${overview.approvedRoutines}")
        text(local, "Counts describe saved learning, not every action J.A.R.V.I.S. can perform. Paused phrases need a new verified lesson or removal.")
        button(local, "Review learned commands") { reviewLearning(1) }
        button(local, "Forget a learned phrase") { choosePhrase() }
        val learningSwitch = SwitchMaterial(this).apply {
            text = "Automatic command learning"
            setTextColor(ThemePreferences.color(this@IntelligenceActivity, R.color.jarvis_on_dark))
            setSingleLine(false)
            maxLines = 3
            minHeight = dp(48)
            isChecked = overview.automaticLearning
        }
        local.addView(learningSwitch, LinearLayout.LayoutParams(-1, -2))
        text(local, "Save new AI interpretations after verified success. Explicit preferences, recent-action confirmations and routine approvals remain available.")
        var reverting = false
        learningSwitch.setOnCheckedChangeListener { view, enabled ->
            if (!reverting) {
                val saved = runCatching { prefs.edit().putBoolean("automatic_command_learning", enabled).commit() }.getOrDefault(false)
                if (saved) render() else {
                    runCatching { prefs.edit().putBoolean("automatic_command_learning", !enabled).commit() }
                    reverting = true; view.isChecked = !enabled; reverting = false
                    toast("Could not save the learning setting. Please retry.")
                }
            }
        }

        val resultPanel = section("Action and learning results")
        val entries = results.snapshot()
        text(resultPanel, "Recorded outcomes: ${entries.size} / ${IntelligenceResultHistory.LIMIT}")
        text(resultPanel, entries.firstOrNull()?.let { "Latest: ${it.outcome.label}\n${it.action}\n${it.detail}" }
            ?: "No outcomes recorded since this feature was installed.")
        text(resultPanel, "New tracked app/timer outcome feedback and confirmed AI volume lessons appear here. A routine's tracked final target is not proof of every step. This is a local journal, not chat history or a complete action log.")
        button(resultPanel, "Review result history") { reviewResults(0) }
        button(resultPanel, "Clear result history") {
            ThemedDialogBuilder(this).setTitle("Clear result history?")
                .setMessage("Remove this device's outcome journal? Saved commands, preferences, routines and pending confirmations remain available.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    toast(if (results.clear()) "Result history cleared." else "Could not clear result history.")
                    render()
                }.show()
        }

        val context = section("Approved memory")
        text(context, memory.summary())
        text(context, "App launches and timers enter recent memory after your outcome confirmation. These preferences belong to J.A.R.V.I.S.")
        button(context, "Forget an app preference") { choosePreference() }
        button(context, "Clear assistant memory") {
            ThemedDialogBuilder(this).setTitle("Clear assistant memory?")
                .setMessage("Remove saved app preferences and confirmed recent actions on this device? Routines that use preferences will need them restored.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    toast(if (memory.clear()) "Assistant memory cleared." else "Could not clear assistant memory.")
                    render()
                }.show()
        }

        val routinePanel = section("Approved routines")
        text(routinePanel, if (savedRoutines.isEmpty()) "No available routines are saved." else savedRoutines.take(5).joinToString("\n\n") { "${it.phrase}: ${it.actions.joinToString(" then ", transform = ::describeStep)}" })
        val unavailable = (routines.count() - savedRoutines.size).coerceAtLeast(0)
        if (unavailable > 0) text(routinePanel, "Unavailable stored routine entries: $unavailable. Conflicting or unsupported definitions cannot run.")
        text(routinePanel, "Reviewing a routine does not run it. Create and approve new routines in chat; invoke their saved names to run them offline.")
        button(routinePanel, "Review or forget routines") { chooseRoutine() }

        val discovery = section("Capability discovery")
        val requests = CapabilityRequestStore(this).snapshot()
        text(discovery, "Requested features for review: ${requests.size} / ${CapabilityRequestStore.LIMIT}")
        text(discovery, requests.take(3).joinToString("\n\n") { "${it.text} — requested; implementation not verified" }
            .ifEmpty { "No capability requests are saved." })
        text(discovery, "These are feature requests, not installed capabilities. In chat, say 'show offline capabilities', 'request a capability to' followed by its description, or 'show capability requests'. Recording and reviewing requests never runs them.")

        val connection = section("AI connection")
        text(connection, "Primary online AI: ${overview.primaryAi}" + if (overview.primaryAi != "Not configured") " — configured" else "")
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        text(connection, if (validated) "Device reports a validated internet connection." else "No validated internet connection reported. Saved local commands and routines remain available.")
        text(connection, lastTest)
        text(connection, "Configuration is not proof of a working connection. Testing sends a short test prompt to the primary AI; it sends no conversation, learned phrases, memory or location and performs no device action.")
        button(connection, if (checking) "Testing connection…" else "Test primary AI connection") { testConnection(overview.primaryAi) }
            .isEnabled = !checking && overview.primaryAi != "Not configured"
        button(connection, "Open AI and voice settings") { startActivity(Intent(this, VoiceSettingsActivity::class.java)) }
        button(connection, "Refresh dashboard") { render() }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "unknown"
        text(content, "App version: $version\nUpdated ${DateFormat.getTimeFormat(this).format(Date())}. Inventory stays on this device.")
        FontPreferences.applyTo(content, this)
        scroll.post { scroll.scrollTo(0, oldScroll) }
    }

    private fun reviewResults(page: Int) {
        val entries = results.snapshot()
        val pageSize = 10
        val pages = ((entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        val formatter = DateFormat.getMediumDateFormat(this)
        val time = DateFormat.getTimeFormat(this)
        val log = TextView(this).apply {
            text = entries.drop(current * pageSize).take(pageSize).joinToString("\n\n") {
                "${formatter.format(Date(it.time))} ${time.format(Date(it.time))} • ${it.outcome.label}\nPhrase: ${it.phrase}\nAction: ${it.action}\n${it.detail}"
            }.ifEmpty { "No recorded outcomes yet. Previous actions are not reconstructed from saved memory." }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(ThemePreferences.color(this@IntelligenceActivity, R.color.jarvis_on_dark))
            setTextIsSelectable(true)
        }
        val dialog = ThemedDialogBuilder(this).setTitle("Result history • Page ${current + 1} of $pages")
            .setView(ScrollView(this).apply { addView(log) }).setNegativeButton("Close", null)
        if (current + 1 < pages) dialog.setPositiveButton("Older") { _, _ -> reviewResults(current + 1) }
        if (current > 0) dialog.setNeutralButton("Newer") { _, _ -> reviewResults(current - 1) }
        dialog.show()
    }

    private fun describeStep(step: String): String = RoutineProposal.describe(step) +
        (RoutineSteps.preferredSlot(step)?.let { slot -> " (${memory.preferred(slot)?.parameters?.get("label") ?: "preference not saved"})" } ?: "")

    private fun reviewLearning(page: Int) {
        val total = commands.snapshot().size + examples.snapshot().size
        val pages = ((total + LearnedCommandReview.PAGE_SIZE - 1) / LearnedCommandReview.PAGE_SIZE).coerceAtLeast(1)
        val log = TextView(this).apply {
            text = LearnedCommandReview.render(page, commands, memory, examples.snapshot())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextColor(ThemePreferences.color(this@IntelligenceActivity, R.color.jarvis_on_dark))
            setTextIsSelectable(true)
        }
        val viewport = ScrollView(this).apply { addView(log) }
        val dialog = ThemedDialogBuilder(this).setTitle("Saved learning • Page $page")
            .setView(viewport).setNegativeButton("Close", null).setNeutralButton("Forget phrase") { _, _ -> choosePhrase() }
        if (page < pages) dialog.setPositiveButton("Next") { _, _ -> reviewLearning(page + 1) }
        else if (page > 1) dialog.setPositiveButton("First page") { _, _ -> reviewLearning(1) }
        dialog.show()
    }

    private fun choosePhrase() {
        val phrases = (commands.snapshot().map { it.phrase } + examples.snapshot().map { it.phrase }).distinct().sorted()
        if (phrases.isEmpty()) { toast("No saved phrases to forget."); return }
        ThemedDialogBuilder(this).setTitle("Choose a phrase to forget").setItems(phrases.toTypedArray()) { _, index ->
            val phrase = phrases[index]
            ThemedDialogBuilder(this).setTitle("Forget this phrase?")
                .setMessage("Remove matching local command and intent examples for '$phrase'? App preferences and routines remain saved.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ ->
                    val commandRemoved = commands.forget(phrase)
                    val exampleRemoved = examples.forget(phrase)
                    toast(if (commandRemoved || exampleRemoved) "Removed matching saved learning." else "Could not remove the phrase; it may have changed or saving may have failed.")
                    render()
                }.show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun choosePreference() {
        val slots = AssistantMemory.slots.filter { memory.preferred(it) != null }
        if (slots.isEmpty()) { toast("No app preferences are saved."); return }
        ThemedDialogBuilder(this).setTitle("Choose a preference").setItems(slots.toTypedArray()) { _, index ->
            val slot = slots[index]
            ThemedDialogBuilder(this).setTitle("Forget preferred $slot?").setMessage("Remove this saved J.A.R.V.I.S. preference?")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ ->
                    toast(if (memory.forget("preferred_$slot")) "Preference forgotten." else "Could not forget this preference.")
                    render()
                }.show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseRoutine() {
        val saved = routines.snapshot()
        if (saved.isEmpty()) { toast("No available routines to review."); return }
        ThemedDialogBuilder(this).setTitle("Choose an approved routine").setItems(saved.map { it.phrase }.toTypedArray()) { _, index ->
            val routine = saved[index]
            ThemedDialogBuilder(this).setTitle(routine.phrase)
                .setMessage(routine.actions.joinToString("\n", transform = ::describeStep) + "\n\nThis review does not run any step.")
                .setNegativeButton("Close", null).setPositiveButton("Forget") { _, _ ->
                    toast(if (routines.forget(routine.phrase)) "Routine forgotten." else "Could not forget this routine.")
                    render()
                }.show()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun testConnection(provider: String) {
        if (checking || provider == "Not configured") return
        checking = true
        render()
        val appContext = applicationContext
        thread(name = "jarvis-intelligence-check") {
            val reply = runCatching {
                if (provider == "Gemini") DirectOnlineClient(appContext).gemini(IntelligenceConnectionCheck.PROMPT)
                else FallbackAiClient(appContext).ask(IntelligenceConnectionCheck.PROMPT, learnIntent = false, includeLocation = false)?.reply
            }.getOrNull()
            val accepted = IntelligenceConnectionCheck.accepted(reply)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    checking = false
                    lastTest = "Last test: $provider " + (if (accepted) "returned the expected reply" else "did not return the expected reply; check AI settings and try again") +
                        " (${DateFormat.getTimeFormat(this).format(Date())}). This does not test device actions."
                    render()
                }
            }
        }
    }
}
