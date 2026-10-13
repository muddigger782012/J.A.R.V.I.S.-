package com.assistant.core.services

/** Bounded, read-only feasibility review. No executor, learned store or cloud is consulted. */
object CapabilityRequestReview {
    enum class Status { EXISTING_HANDLER, APP_UNAVAILABLE, DETAILS_REQUIRED, REVIEW_REQUIRED }
    data class Definition(val capability: String, val parameters: Map<String, String>, val description: String,
        val command: String, val prerequisite: String, val verification: String)
    data class Assessment(val status: Status, val definition: Definition? = null)

    fun assess(text: String, resolveApp: (String) -> Pair<String, String>?): Assessment {
        val target = text.trim().trimEnd('.', '?', '!').trim().replace(Regex("\\s+"), " ")
        ExactVolumeLearning.parse(target)?.let { percent ->
            return Assessment(Status.EXISTING_HANDLER, Definition(ExactVolumeLearning.CAPABILITY,
                mapOf("percent" to percent.toString()), "set media volume to $percent% (nearest supported level)",
                "set media volume to $percent%", "Android must expose a supported media-volume range.",
                "Device readback must match the requested supported level after execution."))
        }
        ExplicitCommandTeaching.resolveDevice(target, resolveApp)?.let { action ->
            val timer = action.capabilityId == "timer"
            return Assessment(Status.EXISTING_HANDLER, Definition(action.capabilityId, action.parameters, action.description(),
                if (timer) "start a timer for ${action.parameters.getValue("seconds")} seconds" else "open ${action.parameters.getValue("label")}",
                if (timer) "A timer app must accept Android's timer request." else "The app must remain uniquely named, installed and launchable.",
                "Check the timer or app after execution, then report 'it worked' or 'it failed'."))
        }
        LocalDeviceCommand.parse(target)?.let { action ->
            val updater = action == LocalDeviceCommand.Action.UPDATE_APP
            val up = action == LocalDeviceCommand.Action.VOLUME_UP
            return Assessment(Status.EXISTING_HANDLER, Definition(if (updater) "app_update" else if (up) "volume_up" else "volume_down",
                emptyMap(), if (updater) "open the J.A.R.V.I.S. updater" else if (up) "raise media volume one step" else "lower media volume one step",
                if (updater) "update app" else if (up) "volume up" else "volume down",
                if (updater) "Fetching an update needs internet; Android asks you to approve installation." else "Android must permit a change to media volume.",
                if (updater) "Opening the updater does not verify that an update was installed." else "Before/after device readings must verify a change after execution."))
        }
        return Assessment(when {
            Regex("(?i)^(?:open|launch) .+$").matches(target) -> Status.APP_UNAVAILABLE
            Regex("(?i)^(?:set|start) (?:a )?timer(?: |$).*$").matches(target) -> Status.DETAILS_REQUIRED
            else -> Status.REVIEW_REQUIRED
        })
    }

    fun render(entry: CapabilityRequestStore.Entry, assessment: Assessment): String = buildString {
        appendLine("Capability request review: ${entry.text}")
        when (assessment.status) {
            Status.EXISTING_HANDLER -> {
                val definition = assessment.definition ?: error("Existing handler needs a definition")
                appendLine("An existing local capability matches this wording. No new app feature is required for this bounded action.")
                appendLine("Action: ${definition.description}")
                appendLine("Prerequisites: ${definition.prerequisite}")
                appendLine("Verification: ${definition.verification}")
                appendLine("If you want to execute it, state a new request: ${definition.command}")
            }
            Status.APP_UNAVAILABLE -> appendLine("I couldn't resolve one launchable installed app with that exact name. Check its launcher name and availability; this does not establish that new app code is needed.")
            Status.DETAILS_REQUIRED -> appendLine("This looks like a timer request, but its bounded duration could not be resolved. Specify digits or English number words with seconds/minutes/hours, from 1 second to 24 hours.")
            Status.REVIEW_REQUIRED -> appendLine("No single bounded handler matched this wording. Clearer parameters or an existing approved routine may cover it; otherwise it needs implementation review. This is not proof that the whole Android app lacks the feature.")
        }
        append("Review covers media volume, bounded timers, exact app launches and the updater. Nothing was run, approved, learned or installed. Review does not change the request’s saved status or verify an outcome.")
    }
}
