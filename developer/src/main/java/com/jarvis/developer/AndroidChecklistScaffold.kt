package com.jarvis.developer

/**
 * Review-only independent Android template. It receives escaped Kotlin literals from the
 * existing scaffold engine; neither this generator nor ZIP approval executes generated code.
 */
internal object AndroidChecklistScaffold {
    fun generate(
        spec: ApplicationSpecification,
        counter: List<GeneratedApplicationFile>,
        title: String,
        description: String
    ): List<GeneratedApplicationFile> {
        val path = spec.packageName.replace('.', '/')
        val main = "app/src/main/java/$path"
        fun file(path: String, content: String) = GeneratedApplicationFile(path, content.trimIndent() + "\n")
        val common = counter.filterNot {
            it.path.endsWith("/CounterState.kt") || it.path.endsWith("/CounterStateTest.kt") ||
                it.path.endsWith("/StarterActivity.kt")
        }.map {
            when (it.path) {
                "app/build.gradle.kts" -> {
                    val runnerAnchor = "versionName = \"0.1.0\""
                    val dependencies = "dependencies { testImplementation(\"junit:junit:4.13.2\") }"
                    require(it.content.split(runnerAnchor).size == 2 && it.content.split(dependencies).size == 2)
                    it.copy(content = it.content.replace(runnerAnchor,
                        "$runnerAnchor\n        testInstrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\"")
                        .replace(dependencies, """
                            dependencies {
                                testImplementation("junit:junit:4.13.2")
                                androidTestImplementation("androidx.test:runner:1.6.2")
                                androidTestImplementation("androidx.test:rules:1.6.1")
                                androidTestImplementation("androidx.test.ext:junit:1.2.1")
                            }
                        """.trimIndent()))
                }
                "app/src/main/AndroidManifest.xml" -> it.copy(
                    content = it.content.replace("Generated starter", "Offline task list"))
                "gradle.properties" -> it.copy(content = it.content + "android.useAndroidX=true\n")
                else -> it
            }
        }
        return common + listOf(
            file("app/src/main/res/values/ids.xml", """
                <resources>
                    <item name="new_task" type="id" />
                    <item name="task_scroll" type="id" />
                </resources>
            """),
            file("$main/ChecklistState.kt", """
                package ${spec.packageName}

                import java.io.ByteArrayInputStream
                import java.io.ByteArrayOutputStream
                import java.io.DataInputStream
                import java.io.DataOutputStream
                import java.nio.charset.StandardCharsets
                import java.util.Base64
                import java.util.Collections

                data class ChecklistTask(val id: Long, val title: String, val completed: Boolean)

                class ChecklistState private constructor(tasks: List<ChecklistTask>, private val nextId: Long) {
                    val tasks: List<ChecklistTask> = Collections.unmodifiableList(tasks.toList())
                    init {
                        require(tasks.size <= MAX_TASKS && nextId > 0)
                        require(tasks.map { it.id }.distinct().size == tasks.size)
                        require(tasks.all { it.id > 0 && it.id < nextId && validTitle(it.title) })
                    }
                    fun add(title: String): ChecklistState {
                        val cleaned = title.trim()
                        require(validTitle(cleaned)) { "Enter 1–120 characters without control characters." }
                        require(tasks.size < MAX_TASKS) { "The list is full (100 tasks)." }
                        require(nextId < Long.MAX_VALUE) { "Task identifiers are exhausted." }
                        return ChecklistState(tasks + ChecklistTask(nextId, cleaned, false), nextId + 1)
                    }
                    fun toggle(id: Long): ChecklistState {
                        require(tasks.any { it.id == id }) { "The task is no longer available." }
                        return ChecklistState(tasks.map { if (it.id == id) it.copy(completed = !it.completed) else it }, nextId)
                    }
                    fun removeCompleted() = ChecklistState(tasks.filterNot { it.completed }, nextId)
                    fun encode(): String {
                        val bytes = ByteArrayOutputStream()
                        DataOutputStream(bytes).use { out ->
                            out.writeInt(MAGIC); out.writeInt(1); out.writeLong(nextId); out.writeInt(tasks.size)
                            for (task in tasks) {
                                out.writeLong(task.id); out.writeByte(if (task.completed) 1 else 0)
                                val title = task.title.toByteArray(StandardCharsets.UTF_8)
                                out.writeInt(title.size); out.write(title)
                            }
                        }
                        require(bytes.size() <= MAX_BYTES)
                        return Base64.getEncoder().encodeToString(bytes.toByteArray())
                    }
                    companion object {
                        const val MAX_TASKS = 100
                        private const val MAX_BYTES = 65536
                        private const val MAGIC = 0x4a434c31
                        fun empty() = ChecklistState(emptyList(), 1)
                        private fun validTitle(value: String) = value.isNotBlank() &&
                            value == value.trim() && value.length <= 120 &&
                            value.none { Character.isISOControl(it) } &&
                            StandardCharsets.UTF_8.newEncoder().canEncode(value)
                        fun restore(value: String): ChecklistState {
                            require(value.length <= ((MAX_BYTES + 2) / 3) * 4) { "Saved list is too large." }
                            val bytes = Base64.getDecoder().decode(value)
                            require(bytes.size <= MAX_BYTES && Base64.getEncoder().encodeToString(bytes) == value)
                            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                                require(input.readInt() == MAGIC && input.readInt() == 1)
                                val next = input.readLong()
                                val count = input.readInt()
                                require(count in 0..MAX_TASKS)
                                val tasks = (0 until count).map {
                                    val id = input.readLong()
                                    val completed = input.readUnsignedByte()
                                    require(completed in 0..1)
                                    val length = input.readInt()
                                    require(length in 1..480 && length <= input.available())
                                    val title = ByteArray(length).also { input.readFully(it) }
                                    val decoded = StandardCharsets.UTF_8.newDecoder()
                                        .decode(java.nio.ByteBuffer.wrap(title)).toString()
                                    ChecklistTask(id, decoded, completed == 1)
                                }
                                require(input.available() == 0)
                                return ChecklistState(tasks, next)
                            }
                        }
                    }
                }
            """),
            file("$main/StarterActivity.kt", """
                package ${spec.packageName}

                import android.app.AlertDialog
                import android.content.Context
                import android.os.Bundle
                import android.text.InputFilter
                import android.widget.Button
                import android.widget.CheckBox
                import android.widget.EditText
                import android.widget.LinearLayout
                import android.widget.ScrollView
                import android.widget.TextView

                class StarterActivity : ThemedActivity() {
                    private val preferences by lazy { getSharedPreferences("checklist", Context.MODE_PRIVATE) }
                    private var state = ChecklistState.empty()
                    private var loaded: Any? = null
                    private var damaged = false
                    private lateinit var draft: EditText
                    private lateinit var rows: LinearLayout
                    private lateinit var status: TextView
                    private lateinit var add: Button
                    private lateinit var remove: Button

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        title = $title
                        val content = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            val padding = (16 * resources.displayMetrics.density).toInt()
                            setPadding(padding, padding, padding, padding)
                        }
                        content.addView(TextView(this).apply { text = $title; textSize = 24f })
                        content.addView(TextView(this).apply { text = $description })
                        draft = EditText(this).apply {
                            id = R.id.new_task; hint = "New task"; contentDescription = "New task"
                            setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(120))
                        }
                        add = Button(this).apply {
                            text = "Add task"; contentDescription = "Add task"
                            setOnClickListener {
                                try {
                                    if (save(state.add(draft.text.toString()))) {
                                        draft.text.clear()
                                        status.text = "Task saved on this device."
                                    }
                                } catch (error: IllegalArgumentException) { status.text = error.message }
                            }
                        }
                        status = TextView(this).apply { contentDescription = "Task status" }
                        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                        remove = Button(this).apply {
                            text = "Remove completed tasks"; contentDescription = "Remove completed tasks"
                            setOnClickListener {
                                val reviewed = state
                                confirm("Remove completed tasks?", "Only completed tasks will be removed.") {
                                    if (state !== reviewed) status.text = "The list changed. Review it again."
                                    else if (save(state.removeCompleted())) status.text = "Completed tasks removed."
                                }
                            }
                        }
                        val reset = Button(this).apply {
                            text = "Reset saved list"; contentDescription = "Reset saved list"
                            setOnClickListener {
                                val reviewed = loaded
                                confirm("Reset saved list?", "This permanently removes this app's saved tasks.") {
                                    if (preferences.all["state"] != reviewed) {
                                        load(); render(); status.text = "Saved data changed. Review it again."
                                    } else if (preferences.edit().remove("state").commit()) {
                                        load(); render(); status.text = "Saved list reset."
                                    } else status.text = "Reset failed. Saved data was retained."
                                }
                            }
                        }
                        listOf(draft, add, status, rows, remove, reset).forEach { content.addView(it) }
                        setContentView(ScrollView(this).apply {
                            id = R.id.task_scroll; isFillViewport = true; addView(content)
                        })
                        load(); render()
                    }
                    private fun load() {
                        loaded = preferences.all["state"]
                        damaged = false
                        try {
                            val raw = loaded
                            state = if (raw == null) ChecklistState.empty() else {
                                require(raw is String); ChecklistState.restore(raw)
                            }
                        } catch (_: Exception) {
                            damaged = true
                            state = ChecklistState.empty()
                        }
                    }
                    private fun render() {
                        rows.removeAllViews()
                        draft.isEnabled = !damaged; add.isEnabled = !damaged
                        remove.isEnabled = !damaged && state.tasks.any { it.completed }
                        status.text = if (damaged)
                            "Saved data could not be read. It has been retained. Confirm reset to start again."
                            else "Tasks: " + state.tasks.size + " | Completed: " + state.tasks.count { it.completed }
                        if (!damaged) for (task in state.tasks) {
                            rows.addView(CheckBox(this).apply {
                                text = task.title; contentDescription = "Task " + task.id
                                isChecked = task.completed
                                setOnCheckedChangeListener { _, _ ->
                                    if (save(state.toggle(task.id))) status.text = "Task updated."
                                    else {
                                        val error = status.text
                                        render(); status.text = error
                                    }
                                }
                            })
                        }
                    }
                    private fun save(candidate: ChecklistState): Boolean {
                        if (damaged) return false
                        if (preferences.all["state"] != loaded) {
                            load(); render(); status.text = "Saved data changed. Review the list and try again."
                            return false
                        }
                        val encoded = candidate.encode()
                        if (!preferences.edit().putString("state", encoded).commit()) {
                            status.text = "Saving failed. Your entry has been retained."
                            return false
                        }
                        loaded = encoded; state = candidate; render()
                        return true
                    }
                    private fun confirm(title: String, message: String, action: () -> Unit) {
                        // This independent application uses its inherited Android system theme.
                        AlertDialog.Builder(this).setTitle(title).setMessage(message)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Confirm") { _, _ -> action() }.show()
                    }
                }
            """),
            file("app/src/test/java/$path/ChecklistStateTest.kt", """
                package ${spec.packageName}

                import java.util.Base64
                import org.junit.Assert.*
                import org.junit.Test

                class ChecklistStateTest {
                    private fun rejects(block: () -> Unit) {
                        try { block(); fail("Expected invalid list rejection") }
                        catch (_: Exception) { /* AssertionError deliberately is not caught. */ }
                    }
                    @Test fun addToggleRemoveAndRestorePreserveIdentityAndUnicode() {
                        val original = ChecklistState.empty().add("  Milk  ").add("café 🌍")
                        val changed = original.toggle(1).removeCompleted()
                        assertEquals(listOf("Milk", "café 🌍"), original.tasks.map { it.title })
                        assertEquals(2L, changed.tasks.single().id)
                        val restored = ChecklistState.restore(changed.encode()).add("Next")
                        assertEquals(listOf(2L, 3L), restored.tasks.map { it.id })
                        assertEquals("café 🌍", restored.tasks.first().title)
                        assertFalse(restored.tasks.first().completed)
                        assertTrue(ChecklistState.restore(original.toggle(1).encode()).tasks.first().completed)
                    }
                    @Test fun invalidTitlesAndMissingTasksAreRejectedWithoutMutation() {
                        val state = ChecklistState.empty().add("Keep")
                        for (title in listOf("", "  ", "x".repeat(121), "bad\nline", "\uD800"))
                            rejects { state.add(title) }
                        rejects { state.toggle(999) }
                        assertEquals("Keep", state.tasks.single().title)
                    }
                    @Test fun listLimitIsEnforcedAndCompletedRemovalReclaimsSpace() {
                        var state = ChecklistState.empty()
                        repeat(100) { state = state.add("Task " + it) }
                        rejects { state.add("Overflow") }
                        state = state.toggle(1).removeCompleted().add("Replacement")
                        assertEquals(100, state.tasks.size)
                        assertEquals(101L, state.tasks.last().id)
                    }
                    @Test fun damagedTruncatedOversizedOrTrailingDataIsNeverAccepted() {
                        val encoded = ChecklistState.empty().add("Keep").encode()
                        val raw = Base64.getDecoder().decode(encoded)
                        for (length in raw.indices) rejects {
                            ChecklistState.restore(Base64.getEncoder().encodeToString(raw.copyOf(length)))
                        }
                        rejects { ChecklistState.restore(encoded + "!") }
                        rejects { ChecklistState.restore("A".repeat(90000)) }
                        rejects { ChecklistState.restore(Base64.getEncoder().encodeToString(raw + byteArrayOf(0))) }
                        rejects { ChecklistState.restore(Base64.getEncoder().encodeToString(raw.clone().apply { this[7] = 2 })) }
                    }
                    @Test fun duplicateIdsAndInvalidFlagsAndUtf8AreRejected() {
                        val raw = Base64.getDecoder().decode(ChecklistState.empty().add("A").add("B").encode())
                        fun damaged(index: Int, value: Byte) = Base64.getEncoder().encodeToString(
                            raw.clone().apply { this[index] = value })
                        rejects { ChecklistState.restore(damaged(41, 1)) } // second id, low byte
                        rejects { ChecklistState.restore(damaged(28, 2)) } // completed flag
                        rejects { ChecklistState.restore(damaged(33, 0xff.toByte())) } // invalid UTF-8 title
                        assertEquals(2, ChecklistState.restore(Base64.getEncoder().encodeToString(raw)).tasks.size)
                    }
                }
            """),
            file("app/src/androidTest/java/$path/ChecklistDeviceTest.kt", """
                package ${spec.packageName}

                import android.content.Context
                import android.widget.CheckBox
                import android.widget.EditText
                import androidx.test.core.app.ActivityScenario
                import androidx.test.ext.junit.runners.AndroidJUnit4
                import androidx.test.platform.app.InstrumentationRegistry
                import org.junit.Assert.*
                import org.junit.Before
                import org.junit.Test
                import org.junit.runner.RunWith

                @RunWith(AndroidJUnit4::class)
                class ChecklistDeviceTest {
                    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
                    private val preferences get() = context.getSharedPreferences("checklist", Context.MODE_PRIVATE)
                    @Before fun disposableEmulatorOnly() {
                        assertEquals("true", InstrumentationRegistry.getArguments().getString("disposableTestDevice"))
                        assertEquals("${spec.packageName}", context.packageName)
                    }
                    private fun views(root: android.view.View): List<android.view.View> = listOf(root) +
                        if (root is android.view.ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) }
                        else emptyList()
                    private fun click(activity: StarterActivity, description: String) {
                        assertTrue(views(activity.window.decorView).single {
                            it.contentDescription?.toString() == description
                        }.performClick())
                    }
                    private fun enter(activity: StarterActivity, text: String) {
                        views(activity.window.decorView).filterIsInstance<EditText>().single().setText(text)
                        click(activity, "Add task")
                    }
                    private fun tasks(activity: StarterActivity) = views(activity.window.decorView).filterIsInstance<CheckBox>()
                    private fun dialogButton(positive: Boolean) {
                        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
                        val text = if (positive) "Confirm" else "Cancel"
                        val nodes = device.rootInActiveWindow.findAccessibilityNodeInfosByText(text)
                        try {
                            val exact = nodes.filter { it.text?.toString() == text && it.isClickable }
                            assertEquals(1, exact.size)
                            assertTrue(exact.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        } finally { nodes.forEach { it.recycle() } }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    }
                    @Test fun saveCompleteCancelRemovalAndRecreate() {
                        assertTrue(preferences.edit().clear().commit())
                        ActivityScenario.launch(StarterActivity::class.java).use { scenario ->
                            scenario.onActivity { activity ->
                                enter(activity, "Milk"); enter(activity, "Archive")
                                assertEquals(listOf("Milk", "Archive"), tasks(activity).map { it.text.toString() })
                                tasks(activity).last().performClick()
                                click(activity, "Remove completed tasks")
                            }
                            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            dialogButton(false)
                            scenario.onActivity { activity ->
                                assertEquals(2, tasks(activity).size)
                                click(activity, "Remove completed tasks")
                            }
                            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            dialogButton(true)
                            scenario.recreate()
                            scenario.onActivity { activity ->
                                assertEquals("Milk", tasks(activity).single().text.toString())
                                assertFalse(tasks(activity).single().isChecked)
                            }
                        }
                        assertEquals("Milk", ChecklistState.restore(requireNotNull(preferences.getString("state", null))).tasks.single().title)
                    }
                    @Test fun savedTaskSurvivesHostForceStop() {
                        // Host must run saveCompleteCancelRemovalAndRecreate, force-stop, then this test.
                        ActivityScenario.launch(StarterActivity::class.java).use { scenario ->
                            scenario.onActivity { activity ->
                                assertEquals("Milk", tasks(activity).single().text.toString())
                                assertFalse(tasks(activity).single().isChecked)
                            }
                        }
                    }
                    @Test fun corruptStorageRequiresConfirmedResetBeforeEditing() {
                        assertTrue(preferences.edit().putString("state", "not a valid list").commit())
                        ActivityScenario.launch(StarterActivity::class.java).use { scenario ->
                            scenario.onActivity { activity ->
                                assertFalse(views(activity.window.decorView).filterIsInstance<EditText>().single().isEnabled)
                                assertTrue(tasks(activity).isEmpty())
                                click(activity, "Reset saved list")
                            }
                            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            dialogButton(false)
                            assertEquals("not a valid list", preferences.getString("state", null))
                            scenario.onActivity { activity -> click(activity, "Reset saved list") }
                            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            dialogButton(true)
                            scenario.onActivity { activity ->
                                assertTrue(views(activity.window.decorView).filterIsInstance<EditText>().single().isEnabled)
                                enter(activity, "Recovered")
                                assertEquals("Recovered", tasks(activity).single().text.toString())
                            }
                        }
                    }
                }
            """),
            file("README.md", """
                # ${spec.projectName} — offline task list

                Implemented behavior: add up to 100 tasks, mark tasks complete, confirm removal
                of completed tasks, and save the list in this independent application's private
                storage. Restarting the application restores saved tasks. Corrupt stored data
                is retained and editing disabled until the user explicitly confirms a reset.
                The supplied title and written specification are displayed as data. This
                template does not implement arbitrary features from that specification.

                No network permissions, provider credentials, repository editor, signing key,
                update channel or publication integration is included. ZIP approval does not
                approve building, installing or publishing. No release signing or installer
                is generated. Debug APKs use a separate test signing identity.

                Build with Java 17, Gradle 8.7, Android SDK 34:
                gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
                No Gradle wrapper is bundled; declared public dependencies require network
                access at build time. The generated app works offline.

                Native tests require a disposable Android emulator and the explicit
                disposableTestDevice=true instrumentation argument. Run the save/recreate
                case first, force-stop this independent package from the host and verify
                that its process has exited, then run savedTaskSurvivesHostForceStop without
                clearing app data. Run corruptStorageRequiresConfirmedResetBeforeEditing last.
                These tests erase only this independent app's disposable test data.
            """)
        )
    }
}
