package com.jarvis.developer

import android.app.Activity
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** Share receipt never calls a provider or writes source. The existing diff controls own approval. */
object PersonalRepairWorkbench {
    internal class Services(
        val read: (String, PersonalRepair.Draft, String, List<String>) -> PersonalRepair.Review = { token, draft, branch, tests ->
            RepositoryIntelligence.personalRepair(token, draft, branch, tests)
        },
        val worker: (()->Unit) -> Unit = { operation -> thread(name = "DeveloperPersonalReview") { operation() }; Unit }
    )

    fun attach(activity: Activity, root: LinearLayout, draft: EditText, token: EditText,
        output: TextView, status: TextView, patches: PatchWorkbench.Handle) =
        attach(activity, root, draft, token, output, status, patches, Services())

    internal fun attach(activity: Activity, root: LinearLayout, draft: EditText, token: EditText,
        output: TextView, status: TextView, patches: PatchWorkbench.Handle, services: Services) {
        val context = root.context
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(panel)
        panel.addView(TextView(context).apply { text = "Personal Jarvis diagnostic repair review" })
        panel.addView(TextView(context).apply {
            text = "Share a recommendation from Personal Self Diagnostics, then explicitly read its source here. Imported prose is discarded. Existing ordinary Kotlin/Java logic and up to three new test paths are supported. Protected controllers, existing tests, manifests, build files, UI resources and release controls stay read-only."
        })
        val branch = EditText(context).apply {
            hint = "Personal source branch"; setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(140)); setText("main")
        }
        val tests = EditText(context).apply {
            hint = "Optional new Personal test paths, one per line (maximum three)"
            minLines = 2; filters = arrayOf(InputFilter.LengthFilter(750))
        }
        val read = Button(context).apply { text = "Read Personal diagnostic source (GET only)" }
        val stage = Button(context).apply { text = "Stage Personal source for a NEW exact diff review"; isEnabled = false }
        val clear = Button(context).apply { text = "Discard Personal source review" }
        listOf(branch, tests, read, stage, clear).forEach(panel::addView)
        var review: PersonalRepair.Review? = null
        var revision = 0L
        var busy = false
        fun refresh() { read.isEnabled = !busy; stage.isEnabled = !busy && review != null }
        fun invalidate() {
            revision++; review = null
            // Shared coding state owns its active-operation lock; stale callbacks never stage.
            patches.invalidate(); refresh()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = invalidate()
        }
        listOf(draft, branch, tests).forEach { it.addTextChangedListener(watcher) }
        clear.setOnClickListener { invalidate(); status.text = "Personal review discarded. No source or approval was created." }
        read.setOnClickListener {
            if (busy || !patches.invalidate()) return@setOnClickListener
            invalidate()
            val parsed: PersonalRepair.Draft
            val selectedBranch = branch.text.toString().trim()
            val newTests = tests.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            try {
                parsed = PersonalRepair.parse(draft.text.toString())
                require(PatchEngine.validSourceBranch(selectedBranch, PatchEngine.Target.PERSONAL))
                require(newTests.size <= 3 && newTests.distinct().size == newTests.size && newTests.all {
                    (it.startsWith("app/src/test/") || it.startsWith("app/src/androidTest/")) && PatchEngine.allowedPath(it, PatchEngine.Target.PERSONAL)
                })
            } catch (_: Exception) {
                status.text = "Use a supported Personal diagnostic share, permitted source branch and up to three new test paths. No request was sent."
                return@setOnClickListener
            }
            val ticket = revision
            val access = token.text.toString().trim()
            busy = true; refresh()
            status.text = "Reading the exact Personal source and checking the imported revision. No write or model call."
            services.worker {
                val result = runCatching { services.read(access, parsed, selectedBranch, newTests) }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    busy = false
                    if (ticket != revision) status.text = "Discarded a stale Personal source review. Read the current inputs again."
                    else result.fold(onSuccess = { selected ->
                        try {
                            PersonalRepair.validate(selected)
                            require(selected.draft == parsed && selected.branch == selectedBranch &&
                                selected.scope == (listOf(parsed.path) + newTests).sorted())
                            review = selected; output.text = PersonalRepair.render(selected)
                            status.text = "Personal source verified. Stage it explicitly, then generate and approve a complete new diff."
                        } catch (_: Exception) { status.text = "Personal source review identity was rejected. No approval was created." }
                    }, onFailure = {
                        status.text = "Personal source could not be verified. Check access, source branch and revision; re-analyze stale diagnostics. Nothing was approved."
                    })
                    refresh()
                }
            }
        }
        stage.setOnClickListener {
            val selected = review ?: return@setOnClickListener
            val accepted = patches.preparePersonal(selected)
            status.text = if (accepted) "Personal scope staged. Review every generated change and approve its exact diff in the coding controls."
                else "Staging refused. Finish active coding work or read the Personal source again."
        }
    }
}
