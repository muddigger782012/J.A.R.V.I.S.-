package com.assistant.core.services

import java.util.Locale

/** Local, explicit screen commands. No transcript, node or approval is stored on disk. */
class ScreenControls(private val platform: Platform, private val now: () -> Long = System::currentTimeMillis) {
    enum class Kind { HELP, STATUS, SETUP, APP, READ, LIST, TAP_LABEL, TAP_NUMBER, CONFIRM, CANCEL, SCROLL_DOWN, SCROLL_UP, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, INVALID }
    data class Command(val kind: Kind, val label: String = "", val number: Int = 0,
                       val role: ScreenSemantics.Role? = null, val contextLabel: String = "")
    data class Control(val path: String, val label: String, val clickable: Boolean, val scrollable: Boolean,
                       val role: ScreenSemantics.Role = ScreenSemantics.Role.CONTROL,
                       val contextLabel: String = "", val enabled: Boolean = true,
                       val checked: Boolean? = null, val selected: Boolean = false)
    data class Snapshot(val key: String, val packageName: String, val controls: List<Control>, val text: List<String>,
                        val blocked: Boolean = false, val appLabel: String = packageName, val headings: List<String> = emptyList())
    enum class Result { ACCEPTED, REJECTED, CHANGED, UNKNOWN }
    data class Confirmation(val text: String, val outcome: Result? = null, val beforeKey: String? = null)
    interface Platform {
        fun connected(): Boolean
        fun snapshot(): Snapshot
        fun perform(snapshot: Snapshot, control: Control, scroll: Kind? = null): Result
        fun global(kind: Kind): Result
    }
    private data class Review(val snapshot: Snapshot, val control: Control, val created: Long,
                              val token: String = java.util.UUID.randomUUID().toString())
    private data class Choices(val snapshot: Snapshot, val created: Long)
    @Volatile private var review: Review? = null
    val pendingToken: String? get() = review?.takeIf { now() - it.created in 0..30_000 }?.token
    private var choices: Choices? = null
    @Synchronized fun discard() { review = null; choices = null }
    @Synchronized fun reply(command: Command): String = try {
        if (review?.let { now() - it.created !in 0..30_000 } == true) review = null
        if (choices?.let { now() - it.created !in 0..120_000 } == true) choices = null
        when (command.kind) {
            Kind.HELP -> { discard(); help() }
            Kind.CANCEL -> { discard(); "Cancelled the pending screen action and numbered controls. Nothing was tapped." }
            Kind.INVALID -> { discard(); "Use 'show screen controls', 'tap' and an exact visible label, 'tap screen item one', or 'scroll down'. A tap needs a fresh review and 'confirm screen action'. Nothing was run." }
            Kind.STATUS -> if (platform.connected()) "Jarvis Accessibility is connected through the private screen bridge. " +
                if (review != null) "A tap review is pending; confirm screen action checks the same screen again before one attempt." else "No tap review is pending. Say 'show screen controls' to inspect the active app."
                else { discard(); setup() }
            Kind.SETUP -> { discard(); setup() }
            Kind.CONFIRM -> confirmReviewedTap().text
            else -> {
                review = null
                if (!platform.connected()) { discard(); setup() }
                else if (command.kind in globals) { choices = null; result(platform.global(command.kind), command.kind.name.lowercase(Locale.ROOT).replace('_', ' ')) }
                else {
                    val snapshot = platform.snapshot()
                    when {
                        snapshot.blocked -> { discard(); "This is a locked, protected, password-entry or Android permission/security screen. Use Android's own controls there; no screen action was attempted." }
                        command.kind == Kind.APP -> { choices = null; "The active screen belongs to ${snapshot.appLabel}. This is Android's current Accessibility window, not proof of an app's background activity." }
                        command.kind == Kind.READ -> { choices = null; "Current screen in ${snapshot.appLabel}:\n" + snapshot.text.take(40).joinToString("\n").ifBlank { "No readable accessibility text is exposed." } + "\nRead locally on request; screen content is not saved to assistant/provider memory." }
                        command.kind == Kind.LIST -> list(snapshot)
                        command.kind == Kind.TAP_LABEL -> {
                            choices = null
                            val matches = snapshot.controls.filter { ScreenSemantics.matches(it, command) }
                            val targets = matches.filter { it.clickable && it.enabled }
                            if (targets.size == 1) propose(snapshot, targets.single())
                            else if (matches.isNotEmpty() && matches.none { it.enabled }) "That control is currently disabled. " + list(snapshot)
                            else (if (targets.isEmpty()) "No unique clickable control has that exact label. " else "That label identifies several controls. ") + list(snapshot)
                        }
                        else -> select(command, snapshot)
                    }
                }
            }
        }
    } catch (_: Exception) { discard(); "Screen access could not be verified. Check Jarvis Accessibility and the current app before trying again. No automatic action retry was made." }

    private fun select(command: Command, snapshot: Snapshot): String {
        val numbered = command.number != 0 || command.kind == Kind.TAP_NUMBER
        val target = if (numbered) {
            val saved = choices
            if (saved == null || now() - saved.created !in 0..120_000 || saved.snapshot.key != snapshot.key) {
                discard(); return "The numbered screen controls are missing, expired or changed. Say 'show screen controls' again; nothing was run."
            }
            saved.snapshot.controls.take(20).getOrNull(command.number - 1)
        } else snapshot.controls.filter { it.scrollable && it.enabled }.singleOrNull()
        choices = null
        if (target == null) return "Choose one current numbered control. For several scroll areas, use 'show screen controls', then 'scroll screen item one down' or 'scroll screen item one up'. Nothing was run."
        if (!target.enabled) return "That screen control is currently disabled. Nothing was run."
        if (command.kind == Kind.TAP_NUMBER) {
            if (!target.clickable) return "That screen control is not clickable. Nothing was tapped."
            return propose(snapshot, target)
        }
        if (!target.scrollable) return "That screen control is not scrollable. Nothing was scrolled."
        return result(platform.perform(snapshot, target, command.kind), "scroll ${if (command.kind == Kind.SCROLL_DOWN) "down" else "up"}")
    }
    private fun propose(snapshot: Snapshot, control: Control): String {
        review = Review(snapshot.copy(controls = listOf(control), text = emptyList(), headings = emptyList()), control, now())
        return "Review screen tap in ${snapshot.appLabel}: '${control.label}' (${ScreenSemantics.describe(control)}). Say 'confirm screen action' for one attempt on this unchanged screen, or 'cancel screen action'. Nothing was tapped."
    }
    @Synchronized internal fun review(snapshot: Snapshot, control: Control): String {
        discard()
        require(!snapshot.blocked && control in snapshot.controls && control.clickable && control.enabled)
        return propose(snapshot, control)
    }
    @Synchronized fun confirmReviewedTap(): Confirmation {
        var dispatchStarted = false
        return try {
        val saved = review
        discard() // Consume before any platform read or dispatch; failures never revive an approval.
        if (saved == null || now() - saved.created !in 0..30_000) return Confirmation("No current screen tap review is available. Request the exact control again; nothing was tapped.")
        if (!platform.connected()) return Confirmation(setup())
        val fresh = platform.snapshot()
        if (fresh.blocked || fresh.key != saved.snapshot.key || saved.control !in fresh.controls)
            return Confirmation("The screen or control changed after review. Request the control again; nothing was tapped.")
        dispatchStarted = true
        val outcome = platform.perform(fresh, saved.control)
        Confirmation(result(outcome, "tap '${saved.control.label}'"), outcome, fresh.key)
    } catch (_: Exception) { discard(); Confirmation("Screen access could not be verified. Check Jarvis Accessibility and the current app before trying again. No automatic action retry was made.", if (dispatchStarted) Result.UNKNOWN else null) } }
    private fun list(snapshot: Snapshot): String {
        choices = Choices(snapshot.copy(controls = snapshot.controls.take(20), text = emptyList(), headings = emptyList()), now())
        if (snapshot.controls.isEmpty()) return "This app exposes no supported clickable or scrollable accessibility controls. Use its own controls or request Back/Home."
        return "Screen controls in ${snapshot.appLabel} (${minOf(20, snapshot.controls.size)} of ${snapshot.controls.size}):\n" +
            snapshot.controls.take(20).mapIndexed { i, c -> "${i + 1}. ${c.label} — ${ScreenSemantics.describe(c)}; " + listOfNotNull(if (c.clickable && c.enabled) "tap" else null, if (c.scrollable && c.enabled) "scroll" else null).joinToString(", ").ifBlank { "no supported action" } }.joinToString("\n") +
            "\nSay 'tap screen item one' to review it, or 'scroll screen item one down'. Numbers expire when the screen changes, after two minutes or another topic. Unlisted controls can be requested by their exact label."
    }
    private fun result(result: Result, operation: String): String = when (result) {
        Result.ACCEPTED -> "Android accepted the $operation request. The resulting screen or app outcome is not verified. No automatic retry was made."
        Result.REJECTED -> "Android did not accept the $operation request. Check the current app and Accessibility access; no automatic retry was made."
        Result.CHANGED -> "The screen or control changed before dispatch. Request it again; no action was run."
        Result.UNKNOWN -> "The $operation outcome is uncertain. Check the screen before trying again; no automatic retry was made."
    }
    companion object {
        private val globals = setOf(Kind.BACK, Kind.HOME, Kind.RECENTS, Kind.NOTIFICATIONS, Kind.QUICK_SETTINGS)
        private fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        fun setup() = "Enable Jarvis in Android Accessibility settings for voice screen control. Say 'open accessibility settings', select Jarvis and approve Android's access prompt yourself. If Android marks it restricted, review Jarvis App info and its restricted-settings option first. No access is granted automatically."
        fun help() = "Voice screen control: show screen controls; read my screen; tap followed by an exact label; tap screen item one; confirm screen action; cancel screen action; scroll down/up; scroll screen item one down/up; go back; go home; show recent apps; open notifications; open quick settings. For a labelled section, say 'press the button named Account under Work'. Task help is available with 'screen task help'. Tap requests are reviewed before one confirmed attempt. Android Accessibility must be enabled manually. Screen data stays local and transient. Protected/password/security screens, arbitrary gestures, typing and automatic multi-app sequences are not supported by this slice."
        fun parse(text: String): Command? {
            val raw = text.trim().trimEnd('.', '?', '!').trim()
            val value = clean(raw).removePrefix("please ")
            val fixed = mapOf("screen control help" to Kind.HELP, "voice screen control help" to Kind.HELP,
                "screen control status" to Kind.STATUS, "accessibility status" to Kind.STATUS,
                "open accessibility settings" to Kind.SETUP, "enable voice screen control" to Kind.SETUP,
                "which app am i in" to Kind.APP, "what app is on screen" to Kind.APP, "foreground app" to Kind.APP,
                "what can you do on this screen" to Kind.LIST,
                "show screen controls" to Kind.LIST, "list screen controls" to Kind.LIST,
                "confirm screen action" to Kind.CONFIRM, "cancel screen action" to Kind.CANCEL,
                "scroll down" to Kind.SCROLL_DOWN, "scroll up" to Kind.SCROLL_UP,
                "go back" to Kind.BACK, "back" to Kind.BACK, "go home" to Kind.HOME, "home screen" to Kind.HOME, "home" to Kind.HOME,
                "show recent apps" to Kind.RECENTS, "recent apps" to Kind.RECENTS, "recents" to Kind.RECENTS,
                "open notifications" to Kind.NOTIFICATIONS, "show notifications" to Kind.NOTIFICATIONS,
                "open quick settings" to Kind.QUICK_SETTINGS)
            if (value.length > 240) return if (Regex("^(?:tap|click|press|scroll|confirm screen|cancel screen|show screen|list screen)\\b").containsMatchIn(value)) Command(Kind.INVALID) else null
            fixed[value]?.let { return Command(it) }
            if (ConversationPolicy.isScreenQuery(text)) return Command(Kind.READ)
            val numbers = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
            fun number(token: String) = token.toIntOrNull() ?: numbers.indexOf(token).let { if (it < 0) 0 else it + 1 }
            Regex("^(?:tap|click|press) screen item (\\w+)$").matchEntire(value)?.let {
                val n = number(it.groupValues[1]); return if (n in 1..20) Command(Kind.TAP_NUMBER, number = n) else Command(Kind.INVALID)
            }
            Regex("^scroll screen item (\\w+) (up|down)$").matchEntire(value)?.let {
                val n = number(it.groupValues[1]); return if (n in 1..20) Command(if (it.groupValues[2] == "down") Kind.SCROLL_DOWN else Kind.SCROLL_UP, number = n) else Command(Kind.INVALID)
            }
            if (Regex("(?i)\\bthen\\b|[;\\n\\r]").containsMatchIn(raw) || value.startsWith("tap screen item") || value.startsWith("click screen item") || value.startsWith("press screen item")) {
                if (Regex("^(?:tap|click|press|scroll|confirm screen|cancel screen|show screen|list screen)\\b").containsMatchIn(value)) return Command(Kind.INVALID)
            }
            val roles = "button|switch|checkbox|check box|radio button|text field|menu item|link|control"
            Regex("(?i)^(?:please )?(?:tap|click|press) (?:($roles) )?\"([^\"]{1,100})\"(?: under \"([^\"]{1,100})\")?$").matchEntire(raw)?.let {
                return Command(Kind.TAP_LABEL, label = it.groupValues[2], role = ScreenSemantics.namedRole(it.groupValues[1]), contextLabel = it.groupValues[3])
            }
            Regex("(?i)^(?:please )?(?:tap|click|press) (?:the )?($roles) (?:named|labelled|labeled) (.{1,100}?)(?: under (.{1,100}))?$").matchEntire(raw)?.let {
                return Command(Kind.TAP_LABEL, label = it.groupValues[2], role = ScreenSemantics.namedRole(it.groupValues[1]), contextLabel = it.groupValues[3])
            }
            Regex("(?i)^(?:please )?(?:tap|click|press) (.{1,100})$").matchEntire(raw)?.let { return Command(Kind.TAP_LABEL, label = it.groupValues[1]) }
            return if (Regex("^(?:tap|click|press|scroll|confirm screen action|cancel screen action|(?:show|list) screen controls|open accessibility settings|enable voice screen control)\\b").containsMatchIn(value)) Command(Kind.INVALID) else null
        }
    }
}
