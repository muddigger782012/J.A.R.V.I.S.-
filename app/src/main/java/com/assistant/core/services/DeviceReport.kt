package com.assistant.core.services

import com.assistant.core.engine.PrivilegedAccess
import java.util.Locale

/** On-demand, read-only measurements. No transcript, identifier, network address or file list. */
object DeviceReport {
    enum class Command { ALL, BATTERY, STORAGE, MEMORY, NETWORK, THERMAL, ACCESS, APP, INVALID }
    enum class Problem { UNAVAILABLE, PERMISSION, UNSUPPORTED, INVALID }
    data class Reading<T>(val value: T? = null, val problem: Problem? = null) {
        init { require((value != null) != (problem != null)) }
    }
    enum class Charging { CHARGING, FULL, DISCHARGING, NOT_CHARGING, UNKNOWN }
    data class Battery(val level: Int, val scale: Int, val charging: Charging, val temperatureDeciC: Int? = null)
    data class Storage(val available: Long, val total: Long)
    data class Memory(val available: Long, val total: Long, val low: Boolean)
    enum class Transport(val label: String) { WIFI("Wi-Fi"), CELLULAR("cellular"), ETHERNET("Ethernet"), VPN("VPN"), OTHER("other") }
    data class Network(val active: Boolean, val internet: Boolean = false, val validated: Boolean = false,
                       val metered: Boolean? = null, val transports: Set<Transport> = emptySet())
    enum class Thermal(val label: String) { NONE("no thermal throttling reported"), LIGHT("light"), MODERATE("moderate"),
        SEVERE("severe"), CRITICAL("critical"), EMERGENCY("emergency"), SHUTDOWN("shutdown threshold") }
    enum class Accessibility { CONNECTED, REMOTE_CONNECTED, CONFIGURED, OFF }
    data class App(val version: String?, val code: Long)
    interface Platform {
        fun battery(): Reading<Battery>
        fun storage(): Reading<Storage>
        fun memory(): Reading<Memory>
        fun network(): Reading<Network>
        fun thermal(): Reading<Thermal>
        fun app(): Reading<App>
        fun contacts(): Reading<Boolean>
        fun calls(): Reading<Boolean>
        fun notifications(): Reading<Boolean>
        fun accessibility(): Reading<Accessibility>
        fun bridges(): PrivilegedAccess.Snapshot
    }

    fun command(text: String): Command? {
        val value = text.trim().lowercase(Locale.ROOT).trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
            .removePrefix("please ").removePrefix("can you ").removePrefix("could you ")
        // Voice recognition often prepends the assistant name (or a close phonetic spelling).
        // Strip only an address at the beginning, never arbitrary words in the request.
        val addressed = value.replace(Regex("^(?:hey |okay |ok )?(?:jarvis|jorvis|jarviss|jervis)[, :]*\\s*"), "")
        val exact = when (addressed) {
            "device report", "show device report", "show my device status", "show device status", "my device status", "phone report", "show phone report", "device diagnostics", "phone diagnostics",
            "check my phone", "how is my phone doing", "how's my phone doing" -> Command.ALL
            "battery status", "battery level", "check my battery", "what is my battery level", "what's my battery level", "how much battery do i have" -> Command.BATTERY
            "storage status", "check my storage", "how much storage is available", "how much storage do i have" -> Command.STORAGE
            "memory status", "ram status", "check my ram", "how much ram is available" -> Command.MEMORY
            "network status", "show network status", "device network status" -> Command.NETWORK
            "thermal status", "phone thermal status", "is my phone overheating" -> Command.THERMAL
            "device capabilities", "show device capabilities", "device permission status", "show device permission status",
            "what services can you provide", "what services do you provide", "what can you do",
            "what are your capabilities", "what are your screen reading capabilities",
            "what are your screen-reading capabilities", "can you read my screen",
            "what are you able to do through diagnostics and development",
            "what can you do with diagnostics", "what diagnostic capabilities do you have" -> Command.ACCESS
            "installed jarvis version", "what version of jarvis is installed", "what jarvis version is installed",
            "what version are you", "what version are you running", "what is your version", "what version of the app are you running" -> Command.APP
            else -> null
        }
        if (exact != null) return exact
        return if (Regex("^(?:(?:show|read) )?(?:device|phone) (?:report|diagnostics|capabilities|permission status)\\b|^(?:battery|storage|memory|ram|network|thermal) status\\b|^installed jarvis version\\b")
                .containsMatchIn(addressed)) Command.INVALID else null
    }

    fun <T> read(block: () -> Reading<T>): Reading<T> = try { block() }
        catch (_: SecurityException) { Reading(problem = Problem.PERMISSION) }
        catch (_: Exception) { Reading(problem = Problem.UNAVAILABLE) }
        catch (_: LinkageError) { Reading(problem = Problem.UNAVAILABLE) }

    fun thermal(sdk: Int, current: () -> Int): Reading<Thermal> = if (sdk < 29) Reading(problem = Problem.UNSUPPORTED)
        else read { current().let { code -> Thermal.values().getOrNull(code)?.let { Reading(it) } ?: Reading(problem = Problem.INVALID) } }

    private fun bytes(value: Long) = String.format(Locale.ROOT, "%.1f GiB", value / 1073741824.0)
    private fun problem(value: Problem?) = when (value) {
        Problem.PERMISSION -> "permission unavailable; check Android app permissions or build configuration"
        Problem.UNSUPPORTED -> "not supported by this Android version"
        Problem.INVALID -> "Android returned an incomplete or invalid measurement"
        else -> "unavailable from Android; try again after checking system access"
    }
    private fun <T> line(label: String, sample: () -> Reading<T>, render: (T) -> String): String = read(sample).let {
        "$label: " + (it.value?.let(render) ?: problem(it.problem))
    }

    fun render(command: Command, platform: Platform): String {
        if (command == Command.INVALID) return "Use 'device report', 'battery status', 'storage status', 'memory status', 'network status', 'thermal status' or 'device capabilities' as a separate request. Nothing was changed, opened, searched or approved."
        val lines = mutableListOf<String>()
        if (command in setOf(Command.ALL, Command.APP, Command.ACCESS)) lines += line("Installed Jarvis", platform::app) {
            if (it.code <= 0) problem(Problem.INVALID) else "${it.version?.takeIf { v -> v.length in 1..40 && v.matches(Regex("[A-Za-z0-9._+\\-]+")) } ?: "version name unavailable"} (build ${it.code}); installed version read from Android."
        }
        if (command in setOf(Command.ALL, Command.BATTERY)) lines += line("Battery", platform::battery) {
            if (it.scale <= 0 || it.level !in 0..it.scale) problem(Problem.INVALID) else
                "${100L * it.level / it.scale}% — ${it.charging.name.lowercase(Locale.ROOT).replace('_', ' ')}" +
                    (it.temperatureDeciC?.takeIf { t -> t in -500..1000 }?.let { t -> String.format(Locale.ROOT, "; battery sensor %.1f°C", t / 10.0) } ?: "; battery temperature unavailable")
        }
        if (command in setOf(Command.ALL, Command.STORAGE)) lines += line("App data storage", platform::storage) {
            if (it.total <= 0 || it.available !in 0..it.total) problem(Problem.INVALID)
            else "${bytes(it.available)} available of ${bytes(it.total)} on the app data filesystem; not a measurement of every device volume."
        }
        if (command in setOf(Command.ALL, Command.MEMORY)) lines += line("RAM", platform::memory) {
            if (it.total <= 0 || it.available !in 0..it.total) problem(Problem.INVALID)
            else "${bytes(it.available)} available of ${bytes(it.total)}; Android low-memory signal ${if (it.low) "on" else "off"}. Available RAM is not storage space or an app memory limit."
        }
        if (command in setOf(Command.ALL, Command.NETWORK)) lines += line("Network", platform::network) {
            when {
                !it.active -> "Android reports no active default network; this does not prove every connection or endpoint is unavailable."
                it.validated && !it.internet -> problem(Problem.INVALID)
                else -> (if (it.transports.isEmpty()) "active default network; transport unspecified" else it.transports.sortedBy { t -> t.ordinal }.joinToString(" + ") { t -> t.label }) +
                    "; internet capability ${if (it.internet) "reported" else "not reported"}; Android validation ${if (it.validated) "reported" else "not reported"}; " +
                    when (it.metered) { true -> "metered"; false -> "unmetered"; null -> "metering unknown" }
            }
        }
        if (command in setOf(Command.ALL, Command.THERMAL)) lines += line("Thermal", platform::thermal) {
            "${it.label}. Android's thermal status is not a CPU temperature or a diagnosis of hardware health."
        }
        if (command == Command.ACCESS) {
            lines += line("Contacts", platform::contacts) { if (it) "Contacts permission granted; lookup still depends on the provider." else "Contacts permission needed for name lookup; a complete number can be reviewed without reading contacts." }
            lines += line("Direct calls", platform::calls) { if (it) "Phone permission granted; recipient review and explicit call approval still required." else "Phone permission needed for direct calling; the reviewed Android dialer remains an alternative when available." }
            lines += line("Notification posting", platform::notifications) { if (it) "app-level posting allowed; individual channels and delivery remain unverified." else "blocked; check Jarvis notification permission, app and channel settings." }
            lines += "Notification reading/replies: not implemented in this personal assistant; posting permission does not provide access to other apps' notifications."
            lines += line("Accessibility", platform::accessibility) { when (it) {
                Accessibility.CONNECTED -> "service connected in this process; an explicit supported screen/navigation request is still required."
                Accessibility.REMOTE_CONNECTED -> "service connected through Jarvis's private screen bridge; supported voice screen commands can reach it. Taps still require a current review and explicit screen confirmation."
                Accessibility.CONFIGURED -> "enabled in Android, but this process has no connected service. Reconnect Jarvis Accessibility or use the main app; action availability is unverified."
                Accessibility.OFF -> "not enabled; enable Jarvis in Android Accessibility settings only if you want supported screen/navigation access."
            } }
            val bridges = runCatching { platform.bridges() }.getOrElse { PrivilegedAccess.Snapshot(PrivilegedAccess.State.UNKNOWN, PrivilegedAccess.State.UNKNOWN, null) }
            fun bridge(name: String, state: PrivilegedAccess.State) = "$name: " + when (state) {
                PrivilegedAccess.State.AUTHORIZED -> "service connected and permission granted; each privileged action still needs its existing approval and system checks."
                PrivilegedAccess.State.NEEDS_PERMISSION -> "service connected; grant Jarvis permission from the $name tab before using it."
                PrivilegedAccess.State.NOT_CONNECTED -> "not connected; install/start $name, then review and grant access in its Jarvis tab."
                PrivilegedAccess.State.UNKNOWN -> "readiness could not be verified; inspect the $name tab and refresh."
            }
            lines += bridge("Shizuku", bridges.shizuku); lines += bridge("Dhizuku", bridges.dhizuku)
            lines += "Device owner: " + when (bridges.deviceOwner) { true -> "Android reports Jarvis is device owner; consequential actions still require confirmation and system authorization."; false -> "Jarvis is not device owner."; null -> "could not be verified." }
            lines += "The special-access placeholder supplies no extra execution route. These checks cover current implemented paths, not all Android capabilities."
        }
        if (command !in setOf(Command.ALL, Command.ACCESS)) return lines.joinToString("\n") +
            (if (command == Command.NETWORK) "\nNo network probe was sent; network flags do not verify a website or AI provider." else "")
        return "Current device snapshot\n" + lines.joinToString("\n") +
            "\nRead locally on request; not uploaded or added to assistant/provider memory. Settings and permissions were not changed." +
            (if (command in setOf(Command.ALL, Command.NETWORK)) " No network probe was sent; network flags do not verify a website or AI provider." else "") +
            (if (command == Command.ALL) " For permissions and bridge readiness, ask 'device capabilities'." else "") +
            (if (command == Command.ACCESS) " No screen/notification content was read and no device action was authorized." else "")
    }
}
