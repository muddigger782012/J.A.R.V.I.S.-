package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import kotlin.concurrent.thread

/** Local starter previews. Export authority is limited to one explicitly selected document. */
object ApplicationApprovalWorkbench {
    private const val EXPORT_REQUEST = 7421
    private const val MAX_EXPORT_BYTES = 1_048_576

    interface Handle {
        /** Consume this workbench's result, including stale or cancelled picker results. */
        fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean
        fun dispose()
    }

    internal data class Services(
        val launch: (Intent, Int) -> Unit,
        val write: (Uri, ByteArray) -> Unit,
        val worker: (() -> Unit) -> Unit
    )

    fun attach(activity: Activity, root: LinearLayout): Handle = attach(activity, root,
        Services(
            launch = { intent, request -> activity.startActivityForResult(intent, request) },
            write = { uri, bytes ->
                require(bytes.size in 1..MAX_EXPORT_BYTES)
                val stream = requireNotNull(activity.contentResolver.openOutputStream(uri, "wt"))
                stream.use { it.write(bytes) }
            },
            worker = { work -> thread(name = "DeveloperApplicationExport") { work() } }
        ))

    internal fun attach(activity: Activity, root: LinearLayout, services: Services): Handle =
        Controller(activity, root, services).also { it.attach() }

    private class Controller(
        private val activity: Activity,
        private val root: LinearLayout,
        private val services: Services
    ) : Handle {
        private val context = root.context
        private val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isSaveEnabled = false
            isSaveFromParentEnabled = false
        }
        private val platforms = ApplicationPlatform.values().toList()
        private val platform = Spinner(context)
        private val behavior = Spinner(context)
        private val title = input("Application title (up to 80 characters)", 80)
        private val projectName = input("Project name (for example MyApplication)", 40)
        private val packageName = input("Independent package (for example org.example.myapp)", 190)
        private val specification = input("Written application specification (up to 4000 characters)", 4000, true)
        private val generate = button("Generate application preview")
        private val review = TextView(context).apply { setTextIsSelectable(true) }
        private val confirmation = input("Enter exact application preview SHA-256", 64)
        private val approved = CheckBox(context).apply {
            text = "I reviewed every file in this exact application preview"
            isSaveEnabled = false
        }
        private val export = button("Export reviewed application ZIP")
        private val status = TextView(context)
        private var plan: ApplicationScaffoldPlan? = null
        private var pending: PendingExport? = null
        private var pickerOpen = false
        private var writing = false
        private var rendering = false
        @Volatile private var disposed = false
        @Volatile private var inputRevision = 0L
        private var selectedPlatform = 0
        private var selectedBehavior = 0

        private data class PendingExport(val fingerprint: String, val bytes: ByteArray)

        private fun input(hintText: String, max: Int, multiline: Boolean = false) = EditText(context).apply {
            hint = hintText
            filters = arrayOf(InputFilter.LengthFilter(max))
            isSaveEnabled = false
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }

        private fun button(label: String) = Button(context).apply {
            text = label
            isSingleLine = false
        }

        private fun alive() = !disposed && !activity.isFinishing && !activity.isDestroyed

        fun attach() {
            root.addView(container)
            container.addView(TextView(context).apply { text = "Build a new application" })
            container.addView(TextView(context).apply {
                text = "Choose an Android, backend service or Windows starter and describe its purpose. " +
                    "Preview all generated files before choosing where to save a ZIP. " +
                    "A starter still needs platform builds, tests and device checks before it is ready to release."
            })
            platform.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("Android app", "Backend service", "Windows app"))
            platform.contentDescription = "Application platform"
            behavior.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                listOf("Counter", "Offline task list (saved on this device)"))
            behavior.contentDescription = "Android application behavior"
            listOf<View>(platform, behavior, title, projectName, packageName, specification, generate, review,
                confirmation, approved, export, status).forEach {
                container.addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            BuildArchitectPanel.attach(activity, container)
            val changes = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!rendering) invalidate("Application inputs changed. Generate and review a fresh preview.")
                }
            }
            listOf(title, projectName, packageName, specification).forEach { it.addTextChangedListener(changes) }
            platform.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != selectedPlatform) {
                        selectedPlatform = position
                        behavior.visibility = if (platforms[position] == ApplicationPlatform.ANDROID) View.VISIBLE else View.GONE
                        if (platforms[position] != ApplicationPlatform.ANDROID) {
                            selectedBehavior = 0
                            behavior.setSelection(0)
                        }
                        invalidate("Application platform changed. Generate and review a fresh preview.")
                    }
                }
            }
            behavior.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != selectedBehavior) {
                        selectedBehavior = position
                        invalidate("Application behavior changed. Generate and review a fresh preview.")
                    }
                }
            }
            confirmation.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!rendering) {
                        inputRevision++
                        pending = null
                        approved.isChecked = false
                        renderControls()
                    }
                }
            })
            approved.setOnCheckedChangeListener { _, checked ->
                if (!rendering && !checked) {
                    inputRevision++
                    pending = null
                }
                renderControls()
            }
            generate.setOnClickListener { generatePreview() }
            export.setOnClickListener { chooseExportDocument() }
            status.text = "Describe an application, then generate a local starter preview."
            renderControls()
        }

        private fun invalidate(message: String) {
            inputRevision++
            plan = null
            pending = null
            rendering = true
            confirmation.setText("")
            approved.isChecked = false
            review.text = ""
            rendering = false
            if (alive()) status.text = message
            renderControls()
        }

        private fun renderControls() {
            val active = alive()
            val reviewed = plan
            generate.isEnabled = active && !pickerOpen && !writing
            confirmation.isEnabled = active && reviewed != null && !pickerOpen && !writing
            approved.isEnabled = active && reviewed != null && !pickerOpen && !writing
            export.isEnabled = active && !pickerOpen && !writing && reviewed != null && approved.isChecked &&
                confirmation.text.toString() == reviewed.fingerprint
        }

        private fun generatePreview() {
            if (!alive() || pickerOpen || writing) return
            invalidate("Generating application preview…")
            try {
                val generated = ApplicationScaffolds.plan(ApplicationSpecification(
                    title.text.toString(), specification.text.toString(), platforms[platform.selectedItemPosition],
                    projectName.text.toString(), packageName.text.toString(),
                    if (platforms[platform.selectedItemPosition] == ApplicationPlatform.ANDROID)
                        AndroidApplicationBehavior.values()[behavior.selectedItemPosition] else AndroidApplicationBehavior.COUNTER))
                val preview = ApplicationScaffolds.review(generated)
                if (!alive()) return
                plan = generated
                review.text = preview
                status.text = "Review every generated file and its SHA-256 before approving export."
            } catch (_: Exception) {
                status.text = "Preview unavailable. Check the specification, title, project name and independent package."
            }
            renderControls()
        }

        private fun chooseExportDocument() {
            val reviewed = plan ?: return
            if (!alive() || pickerOpen || writing || !export.isEnabled) return
            try {
                val approval = ApplicationScaffolds.approve(reviewed, confirmation.text.toString(), approved.isChecked)
                val bytes = ApplicationScaffolds.exportZip(reviewed, approval)
                require(bytes.size in 1..MAX_EXPORT_BYTES)
                pending = PendingExport(reviewed.fingerprint, bytes.copyOf())
                pickerOpen = true
                renderControls()
                status.text = "Choose a ZIP destination. Cancelling saves no application files."
                services.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, reviewed.specification.projectName + ".zip")
                }, EXPORT_REQUEST)
            } catch (_: Exception) {
                consumeApproval()
                status.text = "Export could not start. Review and approve the exact preview again."
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            if (requestCode != EXPORT_REQUEST) return false
            if (!alive()) {
                pending = null
                pickerOpen = false
                return true
            }
            if (!pickerOpen) return true
            val selected = pending
            consumeApproval()
            if (selected == null || selected.fingerprint != plan?.fingerprint) {
                status.text = "Export discarded. Generate and approve a fresh preview if needed."
                return true
            }
            if (resultCode != Activity.RESULT_OK) {
                status.text = "Export cancelled. No application files were saved."
                return true
            }
            val uri = data?.data
            if (uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank()) {
                status.text = "Export refused: choose a document destination using Android's file picker."
                return true
            }
            val revision = inputRevision
            writing = true
            status.text = "Saving reviewed starter ZIP…"
            renderControls()
            try {
                services.worker {
                    var message: String? = null
                    if (alive() && inputRevision == revision) {
                        message = try {
                            require(selected.bytes.size in 1..MAX_EXPORT_BYTES)
                            services.write(uri, selected.bytes.copyOf())
                            "Reviewed starter ZIP saved. Build and test it before any release or installation."
                        } catch (_: Exception) {
                            "ZIP save failed. The selected document may be incomplete; review before retrying."
                        }
                    }
                    activity.runOnUiThread {
                        if (alive()) {
                            writing = false
                            if (inputRevision == revision && message != null) status.text = message
                            renderControls()
                        }
                    }
                }
            } catch (_: Exception) {
                writing = false
                status.text = "ZIP save could not start. Review and approve the exact preview again."
                renderControls()
            }
            return true
        }

        private fun consumeApproval() {
            pending = null
            pickerOpen = false
            rendering = true
            confirmation.setText("")
            approved.isChecked = false
            rendering = false
            renderControls()
        }

        override fun dispose() {
            disposed = true
            inputRevision++
            pending = null
            plan = null
            consumeApproval()
        }
    }
}
