package com.jarvis.developer

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.json.JSONObject
import kotlin.concurrent.thread

/** Existing inbox, real artifact inspection, then a separate owner and Android install decision. */
internal object DevelopmentArtifactWorkbench {
    fun attach(activity: Activity, parent: LinearLayout, inbox: DevelopmentJobInbox, job: JSONObject, githubToken: () -> String) {
        if (job.optJSONObject("engineering") == null) return
        fun label(text: String) = TextView(activity).apply {
            this.text = text; textSize = 15f; setTextColor(activity.getColor(R.color.developer_secondary)); setPadding(8, 12, 8, 12)
        }
        fun button(text: String, action: () -> Unit) = Button(activity).apply {
            this.text = text; isAllCaps = false; setTextColor(activity.getColor(R.color.developer_primary))
            backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.developer_panel))
            setOnClickListener { action() }
        }
        val status = label("APK inspection is read only. An installation needs a separate owner request and Android confirmation.")
        val summary = job.optJSONObject("artifact")
        if (summary?.has("versionCode") == true) parent.addView(label(
            "Inspected APK: ${summary.getString("packageName")} · ${RepositoryRedaction.line(summary.getString("versionName"))} " +
                "(${summary.getLong("versionCode")}) · ${summary.getString("signing")} signing.\n" +
                "Android installation: ${summary.optString("installation").ifEmpty { "not requested" }}. Device acceptance is still separate.\n" +
                Wire.installationDiagnostic(summary.optInt("androidStatus", Int.MIN_VALUE))))
        if (job.getString("state") == "READY_FOR_REVIEW") {
            val run = EditText(activity).apply {
                hint = "Workflow run number for this exact source (optional)"
                contentDescription = "APK build run ID"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            parent.addView(run)
            var cancellation: PatchPreviewCancellation? = null
            parent.addView(button("Inspect this job's APK from GitHub") {
                if (cancellation != null) return@button
                val token = githubToken()
                val chosen = run.text.toString().trim().let { if (it.isEmpty()) null else it.toLongOrNull() }
                if (run.text.isNotBlank() && (chosen == null || chosen <= 0)) {
                    status.text = "Choose a valid workflow run for this source."; return@button
                }
                val signal = PatchPreviewCancellation(); cancellation = signal
                status.text = "Checking source provenance, download digest, package, signature and device compatibility."
                thread(name = "developer-job-apk-read", isDaemon = true) {
                    var source: DevelopmentArtifact.Source? = null
                    val result = runCatching {
                        DevelopmentArtifactReads.begin(job.getString("id"), signal)
                        source = inbox.beginArtifact(job.getString("id"), job.getLong("revision"), Wire.personalPackage(activity.packageName))
                        val lease = requireNotNull(source)
                        val files = DevelopmentArtifactFiles(activity.cacheDir)
                        val record = DevelopmentArtifactDownload(BuildFeedback.http(signal), files,
                            { file, metadata -> DevelopmentArtifactInspection.inspect(activity.applicationContext, file, metadata) }, signal)
                            .read(lease, chosen ?: lease.buildRun, token, Build.SUPPORTED_ABIS.toList())
                        check(inbox.recordArtifact(lease, record))
                        record
                    }
                    result.onFailure { failure -> source?.let { s -> runCatching {
                        inbox.failArtifact(s, (failure as? DevelopmentArtifact.Failure)?.problem ?: DevelopmentArtifact.Problem.PROVENANCE)
                    } } }
                    DevelopmentArtifactReads.finish(job.getString("id"), signal)
                    activity.runOnUiThread {
                        cancellation = null
                        if (!activity.isFinishing && !activity.isDestroyed) status.text = result.fold(
                            { "APK verified. Refresh this job to review its exact version and signing identity before requesting Android installation." },
                            { DevelopmentArtifact.explain(it) })
                    }
                }
            })
            parent.addView(button("Cancel APK inspection") {
                cancellation?.cancel(); status.text = "Inspection cancellation requested. No installation was requested."
            })
            if (job.getJSONObject("verification").getString("SIGNED_APK") == "PASSED") {
                parent.addView(button("Show this APK's verification details") {
                    runCatching {
                        val record = requireNotNull(inbox.artifactRecord(job.getString("id")))
                        status.text = "Source commit: ${record.commit}\nAPK SHA-256: ${record.apkSha256}\n" +
                            "Verified signer SHA-256: ${record.signerSha256}\nWorkflow: ${record.run}, attempt ${record.attempt}.\n" +
                            "The signature matches this installed Developer. Installation is a separate approval."
                    }.onFailure { status.text = DevelopmentArtifact.explain(it) }
                })
                parent.addView(button("Approve this APK and ask Android to install") {
                    runCatching {
                        val record = requireNotNull(inbox.artifactRecord(job.getString("id")))
                        JarvisPackageInstaller.installVerified(activity, job.getString("id"), job.getLong("revision"), record) { status.text = it }
                    }.onFailure { status.text = DevelopmentArtifact.explain(it) }
                })
            }
        }
        if (summary?.optString("installation") in DevelopmentArtifact.activeInstalls) {
            parent.addView(button("Continue Android's installation confirmation") {
                runCatching { JarvisPackageInstaller.continueConfirmation(activity, requireNotNull(inbox.installationRecord(job.getString("id")))) }
                    .onFailure { status.text = "The earlier Android confirmation is unavailable. Check the recorded outcome, cancel tracking, then review and request again. No installation was repeated." }
            })
            parent.addView(button("Cancel this installation request") {
                runCatching { JarvisPackageInstaller.cancel(activity, requireNotNull(inbox.installationRecord(job.getString("id")))) }
                    .onSuccess { status.text = "Local installation tracking cancelled. Android may already have completed an approved operation; this does not roll back an installed package." }
                    .onFailure { status.text = DevelopmentArtifact.explain(it) }
            })
        }
        if (summary?.optString("installation") == "PACKAGE_PRESENT") status.text =
            "The installed package matches the requested APK bytes, version and signer. Device acceptance tests have not been recorded."
        parent.addView(button("Recheck installed state (read only)") {
            thread(name = "developer-install-read", isDaemon = true) {
                val result = runCatching { JarvisPackageInstaller.recover(activity.applicationContext) }
                activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed)
                    status.text = if (result.isSuccess) "Installed-state read finished. Refresh for the recorded result; no installation was repeated."
                        else "Installed state could not be read. Refresh and review diagnostics; no installation was repeated." }
            }
        })
        parent.addView(status)
    }
}
