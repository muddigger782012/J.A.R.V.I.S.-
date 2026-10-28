package com.jarvis.developer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import kotlin.concurrent.thread

/** Read-only discovery suggests a bounded scope; the existing diff engine alone owns writes. */
internal object GuidedDevelopmentWorkbench {
    internal class Services(
        val analyze: (String, PatchEngine.Target, PatchPreviewCancellation) -> RepositoryAnalysis.AnalysisPlan = { task, target, cancellation ->
            RepositoryIntelligence.analyzeAuthorizedSource(task, target, cancellation).second
        },
        val worker: (() -> Unit) -> Unit = { work -> thread(name = "DeveloperGuidedAnalysis") { work() }; Unit }
    )

    fun attach(activity: Activity, home: LinearLayout, task: EditText, patches: PatchWorkbench.Handle,
        restoredTarget: Int, openReview: () -> Unit, services: Services = Services()): Pair<Spinner, () -> Unit> {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        home.addView(panel)
        fun label(text: String) = TextView(activity).apply {
            this.text = text; setTextColor(activity.getColor(R.color.developer_secondary)); setPadding(0, 12, 0, 12)
        }
        fun button(text: String) = Button(activity).apply {
            this.text = text; isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        panel.addView(label("Describe → Analyze → Review → Approve → Verify"))
        panel.addView(label("Choose the app to improve. Analysis reads public source only; it does not send your task to AI or modify files."))
        val target = Spinner(activity).apply {
            contentDescription = "Application to improve"
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf("J.A.R.V.I.S. Developer", "Personal J.A.R.V.I.S."))
            setSelection(restoredTarget.coerceIn(0, 1))
        }
        val analyze = button("Analyze my request (read-only)")
        val cancel = button("Cancel analysis").apply { isEnabled = false }
        val feedback = label("Describe the problem or improvement above, then analyze. No task ID or source path is required.")
        feedback.contentDescription = "Guided analysis status"
        val scope = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val details = label("").apply { visibility = android.view.View.GONE; setTextIsSelectable(true) }
        val showDetails = button("Show analysis evidence").apply { isEnabled = false }
        val review = button("Review suggested changes in Activity").apply { isEnabled = false }
        val supported = button("Review protected-source options").apply { visibility = android.view.View.GONE }
        listOf(target, analyze, cancel, feedback, scope, showDetails, details, review, supported).forEach(panel::addView)
        var generation = 0L
        var request: PatchPreviewCancellation? = null
        var plan: RepositoryAnalysis.AnalysisPlan? = null
        val choices = mutableListOf<CheckBox>()
        fun invalidate() {
            generation++; request?.cancel(); request = null; plan = null
            analyze.isEnabled = true; cancel.isEnabled = false; review.isEnabled = false
            showDetails.isEnabled = false; details.text = ""; details.visibility = android.view.View.GONE
            scope.removeAllViews(); choices.clear(); supported.visibility = android.view.View.GONE
        }
        task.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { invalidate(); feedback.text = "Request changed. Analyze again before review." }
        })
        var selected = target.selectedItemPosition
        target.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position != selected) {
                    if (!patches.invalidate()) {
                        target.setSelection(selected)
                        feedback.text = "Finish or cancel the active code operation before changing the application."
                        return
                    }
                    selected = position; invalidate()
                    feedback.text = "Application changed. Analyze this app before review."
                }
            }
        }
        cancel.setOnClickListener { invalidate(); feedback.text = "Analysis cancelled. Nothing was approved or changed." }
        supported.setOnClickListener {
            android.app.AlertDialog.Builder(activity).setTitle("Protected-source review")
                .setMessage("Protected controllers, permissions and release controls cannot be changed by runtime repair. Keep this analysis for a supervised repository pull request with tests and owner review. You can also select an ordinary logic file from the suggested scope. No restriction or approval is bypassed.")
                .setNegativeButton("Back", null)
                .setPositiveButton("Open repository issues") { _, _ -> activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/muddigger782012/J.A.R.V.I.S.-/issues"))) }.show()
        }
        showDetails.setOnClickListener {
            details.visibility = if (details.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
        }
        analyze.setOnClickListener {
            val description = task.text.toString().trim()
            if (description.isBlank() || description.length > 4_000) {
                feedback.text = "Describe a request in 1–4,000 characters before analyzing."
                task.requestFocus(); return@setOnClickListener
            }
            if (!patches.invalidate()) { feedback.text = "Finish or cancel the active code operation before analyzing a new request."; return@setOnClickListener }
            invalidate()
            val ticket = generation
            val cancellation = PatchPreviewCancellation().also { request = it }
            val product = if (selected == 1) PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
            analyze.isEnabled = false; cancel.isEnabled = true
            feedback.text = "Analyzing pinned source and suggesting a review scope…"
            services.worker {
                val result = runCatching { services.analyze(description, product, cancellation) }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed || ticket != generation) return@runOnUiThread
                    request = null; analyze.isEnabled = true; cancel.isEnabled = false
                    result.onSuccess { found ->
                        plan = found
                        details.text = RepositoryAnalysis.render(found); showDetails.isEnabled = true
                        val eligible = found.files.filter { PatchEngine.allowedPath(it.path, product) &&
                            (product != PatchEngine.Target.PERSONAL || it.path.startsWith("app/src/main/")) }
                        found.files.forEach { file ->
                            val permitted = file in eligible
                            val choice = CheckBox(activity).apply {
                                text = file.path + "\n" + file.reasons.joinToString("; ") +
                                    if (permitted) "" else "\nProtected: supervised repository review required."
                                isEnabled = permitted; isChecked = permitted && (product == PatchEngine.Target.DEVELOPER || choices.none { it.isChecked })
                                minHeight = (48 * resources.displayMetrics.density).toInt()
                                tag = file.path
                            }
                            choices.add(choice); scope.addView(choice)
                            choice.setOnCheckedChangeListener { _, checked ->
                                if (checked && product == PatchEngine.Target.PERSONAL) choices.filter { it !== choice }.forEach { it.isChecked = false }
                                review.isEnabled = choices.any { it.isChecked }
                            }
                        }
                        supported.visibility = android.view.View.VISIBLE
                        feedback.text = "Analysis complete at ${found.commitSha.take(12)}. Select the suggested scope, then review a fresh exact diff. " +
                            if (eligible.isEmpty()) "No supported write scope was found. Use protected-source options for a supervised repair." else
                                "These are lexical suggestions, not a verified repair. Nothing has changed."
                        review.isEnabled = choices.any { it.isChecked }
                    }.onFailure {
                        feedback.text = "Source analysis failed. Check internet access and retry, or browse a saved project. No repair was performed."
                    }
                }
            }
        }
        review.setOnClickListener {
            val found = plan ?: return@setOnClickListener
            val product = if (selected == 1) PatchEngine.Target.PERSONAL else PatchEngine.Target.DEVELOPER
            val paths = choices.filter { it.isChecked && it.isEnabled }.map { it.tag as String }
            val accepted = patches.prepareGuided("guided-" + java.util.UUID.randomUUID().toString().take(16),
                found.task, paths, found.commitSha, product)
            feedback.text = if (accepted) "Scope staged. Generate and review the complete diff in Activity; approval is still required."
                else "Scope could not be staged. Finish active work and analyze again. No approval was created."
            if (accepted) openReview()
        }
        return target to { invalidate() }
    }
}
