package com.assistant.core.services

import java.util.Locale

/** Explicit discovery/review controls; request descriptions are inert data. */
object CapabilityDiscovery {
    private fun clean(text: String) = text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
    fun isHelp(text: String): Boolean = clean(text).lowercase(Locale.ROOT) in setOf(
        "what can you do offline", "show offline capabilities", "offline capabilities"
    )
    fun page(text: String): Int? {
        val match = Regex("(?i)^(?:show|list|review) (?:my )?capability requests(?: page ([0-9]+))?$")
            .matchEntire(clean(text)) ?: return null
        return if (match.groupValues[1].isEmpty()) 1 else match.groupValues[1].toIntOrNull() ?: 0
    }
    fun request(text: String): String? = Regex("(?i)^(?:request|propose) (?:a )?(?:new )?capability (?:to )?(.+)$")
        .matchEntire(clean(text))?.groupValues?.get(1)?.trim()
    fun forget(text: String): String? = Regex("(?i)^forget capability request (.+)$")
        .matchEntire(clean(text))?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
    fun review(text: String): String? = Regex("(?i)^review capability request(?: (.*))?$")
        .matchEntire(clean(text))?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
    fun trial(text: String): String? = Regex("(?i)^try capability request(?: (.*))?$")
        .matchEntire(clean(text))?.groupValues?.get(1)?.trim()?.removeSurrounding("\"")
    data class Teaching(val reference: String, val alias: String)
    fun teaching(text: String): Teaching? {
        val match = Regex("(?i)^teach capability request (number [^ ]+) as (.{3,140})$")
            .matchEntire(clean(text)) ?: return null
        return Teaching(match.groupValues[1], match.groupValues[2].trim().removeSurrounding("\""))
    }
    private fun isRecognizedControl(text: String) = isHelp(text) || page(text) != null || request(text) != null || forget(text) != null || review(text) != null || trial(text) != null || teaching(text) != null
    // The embedded action is data even when speech recognition loses the leading verb.
    fun isManagementText(text: String): Boolean =
        Regex("(?i)\\bcapability[\\s-]+requests?\\b").containsMatchIn(text) ||
            Regex("(?i)\\b(?:request|propose) (?:a )?(?:new )?capability\\b").containsMatchIn(clean(text))
    fun needsClarification(text: String) = isManagementText(text) && !isRecognizedControl(text)
    fun isControl(text: String) = isRecognizedControl(text) || isManagementText(text)
    fun clarification(): String = "I heard capability-request wording, but couldn't match a management command. Type 'try capability request' followed by the exact description from 'show capability requests'. Nothing was run or approved; earlier approvals and outcome checks were cleared."

    fun help(): String = """Available local learning workflows:
        |• Media volume: set media volume to 25%; exact levels use device readback.
        |• Timers: start a timer for 5 minutes; results need your outcome confirmation.
        |• Apps: open Chrome using one installed launcher identity. Maps and Google Maps recognize the Google Maps package even with a localized label. To discover alternatives: find installed app maps, then open app choice 1. App details Maps reads its installed version; choices remain local and expire.
        |• Voice screen control: enable Jarvis in Android Accessibility settings yourself, then show screen controls, tap screen item one, confirm screen action; scroll down/up; read my screen; Back/Home/Recents; open notifications or quick settings. Tap reviews expire on screen changes or after 30 seconds. Screen data stays local; screen control help explains supported controls.
        |• Device health: device report reads current battery, app-data storage, RAM, network flags and Android thermal status locally. Device capabilities checks permissions, Accessibility and Shizuku/Dhizuku readiness; installed Jarvis version reads Android's package record. Nothing is uploaded, changed or granted by these reports.
        |• Preferences: remember Chrome as my preferred browser; asks approval. Browser/maps/music slots are supported.
        |• Repeat: do that again; uses the most recent confirmed app or timer and asks approval.
        |• Teach: teach Jarvis that short break means start a timer for 5 minutes; approve, check, then say 'it worked'.
        |• Routines: saved approved routines can compose existing volume, timer and app actions.
        |• Goal plans: prepare my drive using saved music/maps apps, or plan a task to set media volume to twenty percent then open my music app then open my maps app. Before approval, change goal step two to open my browser app, or use my maps app instead when the plan has just one app. Review before approval; check each requested app result before explicitly resuming.
        |• Review: show saved commands; show assistant memory; show capability requests. To assess a saved idea: review capability request followed by its exact description.
        |• Research follow-up: research solar panels and summarize; remind me about this research in ten minutes. To review before scheduling: prepare a research reminder in ten minutes, then approve reminder. Review reminders lists the saved sources command for each linked reminder; those copies remain available offline after newer research replaces the main brief.
        |• Reminder corrections: during a pending reminder review, actually make that five minutes. For an approved reminder, review reminders, then change reminder one to five minutes; approve reminder changes only the reviewed alarm time. Cancel reminder plan keeps its existing approved time.
        |• Try: try capability request followed by its exact saved description or number 1 from the current list; supported timers, apps and exact volume levels ask approval before execution. Review also accepts number 1. This does not create a learned alias or mark the request complete.
        |• Name a saved action: teach capability request number one as work break; review, approve and verify before the alias is learned.
        |
        |This is the local learning catalogue, not a guarantee that every Android action is available. Installed apps and permissions still matter. Cloud answers and web search need internet. This system does not generate new executable features or provide offline generative AI.
        |To record an addition for review: request a capability to followed by its description. Recording a request does not install or run it.""".trimMargin()

    const val PAGE_SIZE = 5
    fun render(page: Int, requests: List<CapabilityRequestStore.Entry>, evidence: (CapabilityRequestStore.Entry) -> String = { "" }): String {
        if (page < 1) return "Choose a page starting at 1: show capability requests."
        if (requests.isEmpty()) return "No capability requests are saved on this device. Say 'request a capability to' followed by the feature you want. Nothing is installed or run by recording a request."
        val pages = (requests.size + PAGE_SIZE - 1) / PAGE_SIZE
        if (page > pages) return "There are $pages capability-request pages. Say 'show capability requests page $pages'."
        return "Requested features for review: ${requests.size}. Page $page of $pages. These entries are feature requests, not proof of implementation.\n" +
            requests.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).mapIndexed { index, entry ->
                "${(page - 1) * PAGE_SIZE + index + 1}. ${entry.text} — requested; implementation not verified" + evidence(entry).takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty()
            }.joinToString("\n") + "\nReview or try by current list number: review capability request number 1; try capability request number 1. Numbers can change when the list changes; check the displayed request and action before approving.\nTo assess one by wording: review capability request followed by its exact description.\nTo remove one: forget capability request followed by its exact description." +
            if (page < pages) "\nMore: show capability requests page ${page + 1}." else ""
    }
}
