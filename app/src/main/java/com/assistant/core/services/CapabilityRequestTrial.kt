package com.assistant.core.services

/** Explicit handoff to existing approval stores. Backlog entries remain inert and unchanged. */
object CapabilityRequestTrial {
    fun prepare(entry: CapabilityRequestStore.Entry, resolveApp: (String) -> Pair<String, String>?,
        proposeDevice: (DeviceLearningSession.Pending) -> Boolean,
        proposeVolume: (String, FallbackIntentLesson) -> Boolean): String {
        // Reassess now: a prior review is neither authority nor a cached app binding.
        val assessment = CapabilityRequestReview.assess(entry.text, resolveApp)
        val definition = assessment.definition
            ?: return CapabilityRequestReview.render(entry, assessment)
        val retained = when (definition.capability) {
            "timer", "open_app" -> proposeDevice(DeviceLearningSession.Pending(definition.command,
                LearnableDeviceAction(definition.capability, definition.parameters), learnable = false))
            ExactVolumeLearning.CAPABILITY -> proposeVolume(definition.command,
                FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume",
                    "percent" to definition.parameters.getValue("percent")), learnable = false))
            else -> return "This trial workflow supports timers, installed apps and exact media-volume levels. Review this request and issue its separate command if appropriate. Nothing was run, approved or learned."
        }
        if (!retained) return "Could not retain the approval proposal. Nothing was run or learned. State a new request before approving."
        return "Review: ${definition.description}. Say yes to request it or no to cancel. " +
            if (definition.capability == ExactVolumeLearning.CAPABILITY)
                "Android's volume readback will verify the result. Nothing has changed yet; no alias will be learned and the saved request stays unverified."
            else "After checking the app or timer, say 'it worked' to remember the confirmed action, or 'it failed' to discard the result. Nothing has run yet; no alias will be learned and the saved request stays unverified."
    }
}
