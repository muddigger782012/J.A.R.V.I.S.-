package com.assistant.core.services

import java.text.Normalizer
import java.util.Locale

/** Visible launcher identities only. Partial matches are suggestions, never launch authority. */
object InstalledApps {
    data class App(val packageName: String, val label: String) {
        fun valid() = LearnableDeviceAction("open_app", mapOf("package" to packageName, "label" to label)).isValid() &&
            label.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
        fun action() = LearnableDeviceAction("open_app", mapOf("package" to packageName, "label" to label))
    }
    data class Version(val name: String?, val code: Long)
    interface Platform {
        fun apps(): List<App>
        fun canLaunch(packageName: String): Boolean
        fun version(packageName: String): Version
    }
    private const val MAPS = "com.google.android.apps.maps"
    private val mapsAliases = setOf("maps", "google maps", "googlemaps", "g maps", "gmaps")
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    fun validQuery(text: String) = text.isNotBlank() && text.length <= 100 &&
        text.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } &&
        !Regex("(?i)\\b(?:then|and then)\\b|[;\\n\\r]").containsMatchIn(text) && normalized(text).isNotBlank()
    private fun ordered(apps: List<App>) = apps.filter { it.valid() }.sortedWith(compareBy({ normalized(it.label) }, { it.packageName }, { it.label }))
        .distinctBy { it.packageName }
    fun unique(query: String, apps: List<App>): App? {
        if (!validQuery(query)) return null
        val q = normalized(query)
        val valid = apps.filter { it.valid() }
        if (q in mapsAliases && q != "maps") return ordered(valid.filter { it.packageName == MAPS }).singleOrNull()
        return ordered(valid.filter { it.packageName == query.trim() || normalized(it.label) == q ||
            (q == "maps" && it.packageName == MAPS) }).singleOrNull()
    }
    fun candidates(query: String, apps: List<App>): List<App> {
        if (!validQuery(query)) return emptyList()
        val q = normalized(query)
        return ordered(apps.filter { normalized(it.label).contains(q) || it.packageName == query.trim() ||
            (q in mapsAliases && it.packageName == MAPS) })
    }
}

/** Numbered choices belong to this conversation instance and expire; there is no saved inventory. */
class AppDiscovery(private val platform: InstalledApps.Platform, private val open: (LearnableDeviceAction) -> String,
                   private val now: () -> Long = System::currentTimeMillis) {
    enum class Kind { FIND, DETAILS, OPEN_CHOICE, DETAILS_CHOICE, REVIEW, CANCEL, INVALID }
    data class Command(val kind: Kind, val query: String = "", val number: Int = 0)
    private data class Choices(val items: List<InstalledApps.App>, val created: Long, val total: Int)
    private var choices: Choices? = null
    @Synchronized fun discard() { choices = null }
    private fun current(): Choices? = choices?.takeIf { now() - it.created in 0..120_000 }.also { choices = it }
    private fun read(): List<InstalledApps.App> = platform.apps().also { check(it.size <= 2048) }
    @Synchronized fun reply(command: Command): String = try {
        when (command.kind) {
            Kind.CANCEL -> { discard(); "Discarded the app choices. No app was opened or preference changed." }
            Kind.INVALID -> { discard(); "Use 'find installed app' or 'app details' followed by an app name. Choose only a number from the current app choices; nothing was opened." }
            Kind.REVIEW -> current()?.let(::render) ?: missing()
            Kind.FIND, Kind.DETAILS -> {
                discard()
                val apps = read()
                val target = if (command.kind == Kind.DETAILS) InstalledApps.unique(command.query, apps) else null
                if (target != null) details(target) else lookup(command.query, apps)
            }
            Kind.OPEN_CHOICE, Kind.DETAILS_CHOICE -> {
                val frozen = current()?.items?.getOrNull(command.number - 1)
                if (frozen == null) { discard(); missing() }
                else if (frozen !in read() || !platform.canLaunch(frozen.packageName)) {
                    discard(); "That app choice is no longer available with its reviewed identity. Find the app again; nothing was opened."
                } else if (command.kind == Kind.DETAILS_CHOICE) details(frozen)
                else { discard(); runCatching { open(frozen.action()) }.getOrElse {
                    "The selected app request has an unverified outcome. Check the app before trying again; its choice was discarded and no automatic retry was made."
                } }
            }
        }
    } catch (_: Exception) { discard(); "Android's app information could not be verified. Check that the app is installed and enabled, then find it again. No automatic launch or retry was attempted." }
    @Synchronized fun find(query: String): String = reply(Command(if (InstalledApps.validQuery(query)) Kind.FIND else Kind.INVALID, query))
    private fun lookup(query: String, apps: List<InstalledApps.App>): String {
        val matches = InstalledApps.candidates(query, apps)
        if (matches.isEmpty()) return "No visible launchable app matched that name. Try another part of its launcher name or its package identity, or install/enable the app and ask again. Android visibility can limit these results."
        val snapshot = Choices(matches.take(5), now(), matches.size)
        choices = snapshot
        return render(snapshot)
    }
    private fun render(snapshot: Choices) = "Visible launchable app choices (${snapshot.items.size} of ${snapshot.total} matches):\n" +
        snapshot.items.mapIndexed { index, app -> "${index + 1}. ${app.label} (${app.packageName})" }.joinToString("\n") +
        "\nSay 'open app choice 1' to request that app, or 'app details choice 1' for its installed version. Refine the app name for other matches. Choices expire after two minutes or another topic; they are local and not saved to assistant/provider memory."
    private fun missing() = "No current app choice with that number is available. Find the installed app again and review its identity first; nothing was opened."
    private fun details(app: InstalledApps.App): String {
        val version = platform.version(app.packageName)
        check(version.code >= 0)
        val name = version.name?.takeIf { it.length in 1..100 && it.none(Char::isISOControl) &&
            it.none { c -> c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069' } } ?: "unavailable"
        return "${app.label} (${app.packageName})\nInstalled version: $name; version code ${version.code}. Read from Android's package record; update availability and installation progress were not checked. No app was opened or preference changed."
    }
    companion object {
        fun parse(text: String): Command? {
            val clean = text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
            val lower = clean.lowercase(Locale.ROOT).removePrefix("please ")
            if (lower == "show app choices" || lower == "review app choices") return Command(Kind.REVIEW)
            if (lower == "cancel app choices") return Command(Kind.CANCEL)
            Regex("^(open app choice|app details choice) ([1-5]|one|two|three|four|five)$").matchEntire(lower)?.let {
                val number = it.groupValues[2].toIntOrNull() ?: listOf("one", "two", "three", "four", "five").indexOf(it.groupValues[2]) + 1
                return Command(if (it.groupValues[1].startsWith("open")) Kind.OPEN_CHOICE else Kind.DETAILS_CHOICE, number = number)
            }
            Regex("(?i)^(?:please )?(find installed app|find an installed app|app details|show app details) (.+)$").matchEntire(clean)?.let {
                val query = it.groupValues[2]
                if (query.lowercase(Locale.ROOT).startsWith("choice ") || !InstalledApps.validQuery(query)) return Command(Kind.INVALID)
                return Command(if (it.groupValues[1].contains("find", true)) Kind.FIND else Kind.DETAILS, query)
            }
            return if (Regex("^(?:(?:find (?:an )?installed app)|(?:open app choice)|(?:app details)|(?:show app details)|(?:(?:show|review|cancel) app choices))(?:\\b|$)")
                .containsMatchIn(lower)) Command(Kind.INVALID) else null
        }
    }
}
