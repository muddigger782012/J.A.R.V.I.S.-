package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/** Local task checkpoints and read-only decomposition; implementation still requires a new diff. */
object TaskPlanningWorkbench {
    fun attach(
        activity: Activity,
        root: LinearLayout,
        task: EditText,
        githubToken: EditText,
        output: TextView,
        status: TextView,
        patches: PatchWorkbench.Handle
    ) {
        Controller(activity, root, task, githubToken, output, status, patches).attach()
    }

    private class Controller(
        private val activity: Activity,
        private val root: LinearLayout,
        private val task: EditText,
        private val githubToken: EditText,
        private val output: TextView,
        private val status: TextView,
        private val patches: PatchWorkbench.Handle
    ) {
        private val context = root.context
        private val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val planId = input("New plan ID (1–48 letters, digits, hyphens or underscores)", true)
        private val scope = input("Immutable source allowlist: 1–8 exact Developer paths, one per line")
        private val create = button("Analyze repository and save a new local task plan (read-only repository)")
        private val reload = button("Reload / recover interrupted local plans")
        private val planChoice = Spinner(context)
        private val stepChoice = Spinner(context)
        private val dashboard = TextView(context).apply { setTextIsSelectable(true) }
        private val title = input("Selected step title", true)
        private val detail = input("Selected step detail / acceptance criteria")
        private val dependencies = input("Dependency step IDs, separated by commas or new lines")
        private val checkpoint = input("User-reported checkpoint evidence / blocker (not verified CI)")
        private val note = input("Decision note to preserve with this plan")
        private val saveEdit = button("Save queue edit (resets affected progress)")
        private val earlier = button("Move selected step earlier")
        private val later = button("Move selected step later")
        private val saveNote = button("Save decision note locally")
        private val pause = button("Pause selected plan")
        private val stage = button("Prepare selected implementation step for a NEW Phase 2 diff")
        private val statusButtons = TaskPlanner.Status.values().associateWith {
            button("Mark selected step ${it.name} (user-reported)")
        }
        private var plans: List<TaskPlanner.Plan> = emptyList()
        private var needsRecovery: Set<String> = emptySet()
        private var selectedPlanId: String? = null
        private var selectedStepId: String? = null
        private var busy = false
        private var failedStore = false
        private var rendering = false
        private var inputRevision = 0L
        private val actionButtons = listOf(saveEdit, earlier, later, saveNote, pause, stage) + statusButtons.values

        private fun input(hintText: String, single: Boolean = false) = EditText(root.context).apply {
            hint = hintText
            if (single) setSingleLine(true) else {
                minLines = 2
                gravity = Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }

        private fun button(label: String) = Button(root.context).apply { text = label }
        private fun label(text: String) = TextView(context).apply { this.text = text }
        private fun alive() = !activity.isFinishing && !activity.isDestroyed
        private fun store() = TaskPlanStore(File(context.noBackupFilesDir.canonicalFile, "developer-task-plans"))
        private fun selectedPlan() = plans.singleOrNull { it.id == selectedPlanId }
        private fun selectedStep() = selectedPlan()?.steps?.singleOrNull { it.id == selectedStepId }
        private fun interrupted(plan: TaskPlanner.Plan) = plan.steps.any { it.status == TaskPlanner.Status.RUNNING }

        fun attach() {
            root.addView(container)
            container.addView(label("Task planning — repository reads and local checkpoints"))
            container.addView(label("Plans decompose work at an immutable source SHA. Saving progress changes only private local plan files. Checkpoints are user-reported notes, not verified builds. Preparing a step only fills the separate coding controls; generate, review and explicitly approve a new diff before any repository write."))
            planId.setText("plan-" + UUID.randomUUID().toString().replace("-", "").take(16))
            listOf<View>(planId, scope, create, reload, planChoice, dashboard, stepChoice,
                title, detail, dependencies, saveEdit, earlier, later, checkpoint).forEach(container::addView)
            statusButtons.values.forEach(container::addView)
            listOf<View>(note, saveNote, pause, stage).forEach(container::addView)
            val changes = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!rendering) {
                        inputRevision++
                        // Draft text does not mutate a saved plan; any prior patch review is still invalidated.
                        patches.invalidate()
                    }
                }
            }
            listOf(planId, scope, task, title, detail, dependencies, checkpoint, note)
                .forEach { it.addTextChangedListener(changes) }
            planChoice.onItemSelectedListener = selection {
                val id = plans.getOrNull(planChoice.selectedItemPosition)?.id
                if (id != selectedPlanId && allowChange()) {
                    selectedPlanId = id
                    selectedStepId = null
                    render()
                } else if (id != selectedPlanId) render()
            }
            stepChoice.onItemSelectedListener = selection {
                val id = selectedPlan()?.steps?.getOrNull(stepChoice.selectedItemPosition)?.id
                if (id != selectedStepId && allowChange()) {
                    selectedStepId = id
                    renderStep()
                } else if (id != selectedStepId) render()
            }
            create.setOnClickListener { createPlan() }
            reload.setOnClickListener { load(recover = true) }
            saveEdit.setOnClickListener {
                mutate { plan ->
                    val step = selectedStep() ?: error("No selected step")
                    TaskPlanner.editStep(plan, step.id, title.text.toString().trim(), detail.text.toString().trim(),
                        dependencies.text.toString().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }, now())
                }
            }
            earlier.setOnClickListener { move(-1) }
            later.setOnClickListener { move(1) }
            statusButtons.forEach { (state, control) ->
                control.setOnClickListener {
                    mutate { plan -> TaskPlanner.transition(plan, selectedStep()?.id ?: error("No step"),
                        state, checkpoint.text.toString().trim(), now()) }
                }
            }
            saveNote.setOnClickListener {
                if (mutate { TaskPlanner.note(it, note.text.toString().trim(), now()) }) {
                    rendering = true
                    note.setText("")
                    rendering = false
                }
            }
            pause.setOnClickListener { mutate { TaskPlanner.pause(it, !it.paused, now()) } }
            stage.setOnClickListener { stageStep() }
            // Initial attachment reads local state only. Recovery writes require the explicit reload action.
            load(recover = false)
        }

        private fun selection(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!rendering && !busy) action()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        private fun allowChange(): Boolean {
            if (busy) { status.text = "Wait for repository planning to finish."; return false }
            if (!patches.invalidate()) {
                status.text = "Wait for the coding preview, commit or CI check to finish before changing task planning."
                return false
            }
            return true
        }

        private fun load(recover: Boolean) {
            if (!allowChange()) return
            try {
                val storage = store()
                val loaded = storage.loadAll()
                val restored = loaded.map { plan ->
                    if (recover && interrupted(plan)) {
                        val candidate = TaskPlanner.recover(plan, maxOf(System.currentTimeMillis(), plan.updatedAtMillis))
                        storage.save(candidate, plan.revision)
                        candidate
                    } else plan
                }
                plans = restored
                needsRecovery = if (recover) emptySet() else loaded.filter(::interrupted).map { it.id }.toSet()
                failedStore = false
                if (plans.none { it.id == selectedPlanId }) selectedPlanId = plans.firstOrNull()?.id
                render()
                status.text = if (recover) "Local plans reloaded; interrupted work is blocked and paused for review."
                    else "Local task plans loaded; no repository request was made."
            } catch (_: Exception) {
                storeFailed()
            }
        }

        private fun storeFailed() {
            failedStore = true
            dashboard.text = "Local task-plan storage could not be verified. Task-plan writes and staging are disabled. Existing plan files were not reset. Use Reload after resolving storage access or integrity; no approval was restored."
            status.text = "Task-plan storage unavailable; changes are disabled until a successful reload."
            refresh()
        }

        private fun createPlan() {
            if (failedStore || !allowChange()) return
            val frozen = Draft(planId.text.toString(), task.text.toString(), scope.text.toString())
            val id = frozen.id.trim()
            val requestedTask = frozen.task.trim()
            val paths = frozen.scope.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val invalid = when {
                !id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,47}")) -> "Use a plan ID of 1–48 letters, digits, hyphens or underscores."
                requestedTask.isBlank() || requestedTask.length > 4_000 -> "Enter a task of 1–4,000 characters."
                paths.size !in 1..8 || paths.distinct().size != paths.size -> "Enter 1–8 distinct exact source paths."
                !paths.all(PatchEngine::allowedPath) -> "The task scope includes a protected or unsupported Developer source path."
                !safeInput(id) || !safeInput(requestedTask) || !paths.all(::safeInput) -> "Remove credential literals or invalid control text from the plan inputs."
                plans.any { it.id == id } -> "That local plan ID already exists. Select it or use a new ID."
                plans.size >= 10 -> "The local task-plan limit of ten has been reached."
                else -> null
            }
            if (invalid != null) { status.text = invalid; return }
            val revision = inputRevision
            val token = githubToken.text.toString().trim()
            busy = true
            refresh()
            status.text = "Reading repository source for task decomposition; no provider or repository write."
            thread(name = "DeveloperTaskPlan") {
                try {
                    val analysis = RepositoryIntelligence.analyzeSnapshot(token, requestedTask).second
                    val candidate = TaskPlanner.create(id, analysis, paths, System.currentTimeMillis())
                    activity.runOnUiThread {
                        if (alive()) {
                            busy = false
                            if (revision != inputRevision || frozen != draft()) {
                                status.text = "Planning inputs changed; the stale analysis was discarded without saving."
                            } else if (!patches.invalidate()) {
                                status.text = "Coding controls are busy; the analysis was not saved. Start planning again when they finish."
                            } else {
                                try {
                                    store().save(candidate, null)
                                    plans = plans + candidate
                                    selectedPlanId = candidate.id
                                    selectedStepId = null
                                    render()
                                    output.text = "Local task plan saved: ${candidate.id}\nBase SHA: ${candidate.baseCommitSha}\nAnalysis: ${candidate.analysisId}\n${candidate.steps.size} queued steps. No repository change or AI provider call."
                                    status.text = "Task decomposition saved locally; review scope, dependencies and limitations."
                                } catch (_: Exception) { storeFailed() }
                            }
                            refresh()
                        }
                    }
                } catch (_: Exception) {
                    activity.runOnUiThread {
                        if (alive()) {
                            busy = false
                            status.text = if (revision != inputRevision || frozen != draft())
                                "Planning inputs changed; the stale result was discarded."
                            else "Read-only task planning stopped. Check repository read access, scope and analysis limits. No local plan was saved."
                            refresh()
                        }
                    }
                }
            }
        }

        private fun draft() = Draft(planId.text.toString(), task.text.toString(), scope.text.toString())
        private data class Draft(val id: String, val task: String, val scope: String)

        private fun mutate(transform: (TaskPlanner.Plan) -> TaskPlanner.Plan): Boolean {
            if (failedStore || !allowChange()) return false
            val original = selectedPlan() ?: return false
            if (original.id in needsRecovery) {
                status.text = "Reload / recover the saved RUNNING step before changing this interrupted plan."
                return false
            }
            val changed = try { transform(original).also(TaskPlanner::validate) } catch (_: Exception) {
                status.text = "Plan change rejected. Check text lengths, dependencies, step state and checkpoint evidence."
                return false
            }
            try { store().save(changed, original.revision) } catch (_: Exception) {
                storeFailed()
                return false
            }
            plans = plans.map { if (it.id == original.id) changed else it }
            render()
            status.text = "Task plan saved locally at revision ${changed.revision}. Progress remains user-reported."
            return true
        }

        private fun move(delta: Int) {
            mutate { plan -> TaskPlanner.move(plan, selectedStep()?.id ?: error("No step"), delta, now()) }
        }

        private fun stageStep() {
            if (failedStore || !allowChange()) return
            val plan = selectedPlan() ?: return
            val step = selectedStep() ?: return
            if (plan.paused || plan.id in needsRecovery) {
                status.text = "Review recovery and resume the local plan before preparing implementation."
                return
            }
            val ready = try { TaskPlanner.next(plan) } catch (_: Exception) { null }
            if (step.paths.isEmpty() || ready?.id != step.id ||
                step.status !in setOf(TaskPlanner.Status.PENDING, TaskPlanner.Status.RUNNING)) {
                status.text = "Select the next ready PENDING or current-session RUNNING implementation step with explicit source paths. Complete its dependencies first."
                return
            }
            val packet = "Local plan: ${plan.id}; step: ${step.id}; revision: ${plan.revision}\n" +
                "Immutable base: ${plan.baseCommitSha}\nAnalysis: ${plan.analysisId}\n" +
                "Task: ${plan.task}\nStep: ${step.title}\n${step.detail}\n" +
                "Prepare only this implementation step within the separately supplied source allowlist. " +
                "Local checkpoints are user-reported. Generate a new reviewable diff; approval, CI and release are separate."
            if (packet.length > 4_000 || !safeInput(packet) || !step.paths.all { it in plan.scope }) {
                status.text = "The step packet must fit 4,000 characters and the immutable scope. Shorten its editable title/detail before preparing a diff."
                return
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${plan.id}\n${step.id}\n${plan.revision}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val id = plan.id.take(25) + "-" + digest.take(16)
            // prepare intentionally edits the shared task field. Its own atomic handle sets the pin;
            // our draft watcher must not clear that pin in response to the staged packet.
            val wasRendering = rendering
            rendering = true
            val prepared = try { patches.prepare(id, packet, step.paths.toList(), plan.baseCommitSha) }
                finally { rendering = wasRendering }
            if (!prepared) {
                status.text = "Coding controls are busy; this step was not prepared."
                return
            }
            output.text = "Prepared local plan ${plan.id}, step ${step.id}, for a new Phase 2 diff.\nPinned base: ${plan.baseCommitSha}\nScope:\n${step.paths.joinToString("\n")}\nNo provider request, repository write or completion checkpoint was made."
            status.text = "Step prepared. If the development branch moved, create a new plan from fresh analysis; old checkpoints remain preserved."
        }

        private fun render() {
            if (failedStore) { refresh(); return }
            rendering = true
            val planLabels = plans.map { "${it.id} — ${it.steps.count { step -> step.status == TaskPlanner.Status.DONE }}/${it.steps.size} done${if (it.paused) "; paused" else ""}" }
            planChoice.adapter = adapter(planLabels.ifEmpty { listOf("No saved task plans") })
            planChoice.setSelection(plans.indexOfFirst { it.id == selectedPlanId }.coerceAtLeast(0))
            val plan = selectedPlan()
            if (plan?.steps?.none { it.id == selectedStepId } != false) selectedStepId = plan?.steps?.firstOrNull()?.id
            stepChoice.adapter = adapter(plan?.steps?.map { "${it.id}: ${it.status} — ${it.title}" }.orEmpty().ifEmpty { listOf("No queued steps") })
            stepChoice.setSelection(plan?.steps?.indexOfFirst { it.id == selectedStepId }?.coerceAtLeast(0) ?: 0)
            dashboard.text = plan?.let(::report) ?: "No saved local plan. Enter an explicit immutable scope and analyze the task to begin."
            pause.text = if (plan?.paused == true) "Resume selected local plan" else "Pause selected local plan"
            renderStep()
            rendering = false
            refresh()
        }

        private fun renderStep() {
            val wasRendering = rendering
            rendering = true
            val step = selectedStep()
            title.setText(step?.title.orEmpty())
            detail.setText(step?.detail.orEmpty())
            dependencies.setText(step?.dependsOn?.joinToString(", ").orEmpty())
            checkpoint.setText(step?.checkpoint.orEmpty())
            rendering = wasRendering
            refresh()
        }

        private fun refresh() {
            create.isEnabled = !busy && !failedStore
            reload.isEnabled = !busy
            planId.isEnabled = !busy && !failedStore
            scope.isEnabled = !busy && !failedStore
            planChoice.isEnabled = !busy && !failedStore && plans.isNotEmpty()
            stepChoice.isEnabled = !busy && !failedStore && selectedPlan() != null
            val usable = !busy && !failedStore && selectedPlan()?.let { it.id !in needsRecovery } == true
            actionButtons.forEach { it.isEnabled = usable }
            listOf(title, detail, dependencies, checkpoint, note).forEach { it.isEnabled = usable }
        }

        private fun adapter(items: List<String>) = ArrayAdapter(context, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        private fun report(plan: TaskPlanner.Plan): String = buildString {
            append("Plan ${plan.id} — revision ${plan.revision}\nTask: ${plan.task}\n")
            append("Source SHA: ${plan.baseCommitSha}\nAnalysis: ${plan.analysisId}\n")
            append("${plan.steps.count { it.status == TaskPlanner.Status.DONE }}/${plan.steps.size} steps DONE (user-reported); paused: ${plan.paused}\n")
            append("Immutable source scope:\n${plan.scope.joinToString("\n")}\n\n")
            when {
                plan.id in needsRecovery -> append("Saved RUNNING work requires explicit Reload / recover before resuming; no completion is assumed.\n")
                plan.paused -> append("Next action blocked: plan is paused. Review notes and resume explicitly.\n")
                else -> {
                    val ready = TaskPlanner.next(plan)
                    append(if (ready == null) "No ready step: review unfinished blockers/dependencies, or all steps are done.\n"
                        else "Next ready step: ${ready.id} — ${ready.title}\n")
                }
            }
            plan.steps.forEach { step ->
                append("\n${step.id} [${step.status}] ${step.title}\n${step.detail}\n")
                append("Depends on: ${step.dependsOn.joinToString(", ").ifEmpty { "none" }}\n")
                append("Source paths: ${step.paths.joinToString(", ").ifEmpty { "checkpoint only; no implementation scope" }}\n")
                append("User-reported checkpoint: ${step.checkpoint.ifBlank { "none" }}\n")
            }
            append("\nPreserved decisions:\n")
            plan.decisions.forEach { decision ->
                append("${decision.sequence} @ ${decision.atMillis}${decision.stepId?.let { " [$it]" }.orEmpty()}: ${decision.message}\n")
            }
            append("\nThese local notes do not establish passing tests, signing, publication, installation or recovery readiness.")
        }

        private fun now() = maxOf(System.currentTimeMillis(), selectedPlan()?.updatedAtMillis ?: 0L)

        private fun safeInput(value: String): Boolean {
            if (value.any { (Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t') ||
                    it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }) return false
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (Character.isHighSurrogate(char)) {
                    if (index >= value.length || !Character.isLowSurrogate(value[index++])) return false
                } else if (Character.isLowSurrogate(char)) return false
            }
            return RepositoryRedaction.line(value) == value &&
                !Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----").containsMatchIn(value) &&
                !Regex("(?i)\\b(?:api[_-]?key|access[_-]?token|token|password|secret|authorization)\\s*+[:=]\\s*+(?:Bearer\\s++)?[A-Za-z0-9_./+=-]{8,}").containsMatchIn(value)
        }
    }
}
