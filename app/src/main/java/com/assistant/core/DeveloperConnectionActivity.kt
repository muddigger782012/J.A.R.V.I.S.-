package com.assistant.core

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.assistant.core.services.DeveloperBridgeOperations
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import com.jarvis.integration.DevelopmentPeerIdentity

/**
 * Explicit, on-device link from commercial J.A.R.V.I.S. Beta to J.A.R.V.I.S. Developer.
 * The authenticated Binder handshake is performed on each request; this screen never
 * grants source-write, signing, publication, install, or Owner Development authority.
 * Personal J.A.R.V.I.S. has a separate connection flow and cannot open this screen.
 */
class DeveloperConnectionActivity : MenuActivity("Developer connection") {
    private lateinit var status: TextView
    private lateinit var review: EditText
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName != BETA_PACKAGE) {
            finish()
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = SettingsMenuViews.dp(this@DeveloperConnectionActivity, 16)
            setPadding(inset, inset, inset, inset)
            setBackgroundResource(R.drawable.glass_panel_surface)
        }

        fun label(value: String, size: Float = 14f, subdued: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(SettingsMenuViews.color(this@DeveloperConnectionActivity,
                    if (subdued) R.attr.jarvis_on_dark_muted else R.attr.jarvis_on_dark))
                setPadding(0, SettingsMenuViews.dp(this@DeveloperConnectionActivity, 6),
                    0, SettingsMenuViews.dp(this@DeveloperConnectionActivity, 6))
                panel.addView(this)
            }

        label("J.A.R.V.I.S. ↔ Developer", 20f)
        label("Direct on-device connection. No cloud bridge, address, access token, or separate AI provider is required.", subdued = true)
        status = label("Connection not checked. Tap Verify secure connection.")
        panel.addView(SettingsMenuViews.row(this, "Verify secure connection",
            "Check Developer's package identity, signature, permissions, protocol and available operations",
            R.drawable.ic_jarvis_core) {
            runBridge("Verifying Developer") { DeveloperBridgeOperations(this).capabilities() }
        })

        label("Send a task to Developer", 17f)
        label("This submits a review draft. It does not authorize source edits, builds, installation or publication.", subdued = true)
        review = EditText(this).apply {
            hint = "Describe what Developer should inspect or propose…"
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(1500))
            setTextColor(SettingsMenuViews.color(this@DeveloperConnectionActivity, R.attr.jarvis_on_dark))
            setHintTextColor(SettingsMenuViews.color(this@DeveloperConnectionActivity, R.attr.jarvis_on_dark_muted))
            backgroundTintList = ColorStateList.valueOf(SettingsMenuViews.color(this@DeveloperConnectionActivity, R.attr.jarvis_primary))
        }
        panel.addView(review, LinearLayout.LayoutParams(-1, -2))
        panel.addView(SettingsMenuViews.row(this, "Send review request",
            "Queue one traceable request through the verified local bridge",
            R.drawable.ic_composer_tools) {
            val message = review.text.toString().trim()
            when {
                message.length !in 12..1500 -> status.text = "Enter a task description between 12 and 1,500 characters."
                message.any { it == '\u0000' || it.isISOControl() && it !in "\n\r\t" } ->
                    status.text = "Remove control characters from the task description."
                else -> runBridge("Submitting review") { DeveloperBridgeOperations(this).submit(message) }
            }
        })
        panel.addView(SettingsMenuViews.row(this, "Latest Developer job",
            "Read the state and separately verified source, build and APK stages",
            R.drawable.ic_jarvis_core) {
            runBridge("Checking latest job") { DeveloperBridgeOperations(this).status() }
        })
        panel.addView(SettingsMenuViews.row(this, "Open J.A.R.V.I.S. Developer",
            "Open the independent app to inspect the review and approve any proposed changes",
            R.drawable.ic_composer_tools) { openVerifiedDeveloper() })
        label("Both apps must be installed with compatible signing certificates. The bridge never enables Owner Development in a commercial edition. Personal J.A.R.V.I.S. remains separate.", subdued = true)

        setContentView(ScrollView(this).apply {
            val margin = SettingsMenuViews.dp(this@DeveloperConnectionActivity, 12)
            setPadding(margin, margin, margin, margin)
            addView(panel)
        })
        FontPreferences.applyTo(panel, this)
    }

    private fun runBridge(action: String, block: () -> String) {
        if (busy) return
        busy = true
        status.text = "$action…"
        Thread({
            val response = runCatching {
                preflight()?.let { return@runCatching it }
                block()
            }.getOrElse(DeveloperBridgeOperations::failure)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    busy = false
                    status.text = response
                }
            }
        }, "jarvis-beta-developer-link").start()
    }

    /** A failed signature check is never replaced by an unauthenticated share fallback. */
    private fun preflight(): String? {
        val uid = try {
            packageManager.getApplicationInfo(Wire.DEVELOPER_PACKAGE, 0).uid
        } catch (_: Exception) {
            return "J.A.R.V.I.S. Developer is not installed or not visible to Android. Install the compatible Developer APK, then retry."
        }
        return if (DevelopmentPeerIdentity.packageForUid(this, uid, setOf(Wire.DEVELOPER_PACKAGE)) == null)
            "Developer's signing identity does not match this J.A.R.V.I.S. install. Use compatible, correctly signed APKs; no connection was authorized."
        else null
    }

    private fun openVerifiedDeveloper() {
        preflight()?.let { status.text = it; return }
        try {
            startActivity(Intent().setComponent(ComponentName(
                Wire.DEVELOPER_PACKAGE, "com.jarvis.developer.MainActivity")))
            status.text = "Developer opened. Verify the task inside Developer before authorizing any change."
        } catch (_: Exception) {
            status.text = "Android could not open Developer. Check that its launcher activity is available."
        }
    }

    private companion object {
        const val BETA_PACKAGE = "com.assistant.core.beta"
    }
}
