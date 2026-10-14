package com.assistant.core.services

/** Explicit handoff to existing approval stores. Backlog entries remain inert and unchanged. */
object CapabilityRequestTrial {
    fun prepare(entry: CapabilityRequestStore.Entry, resolveApp: (String) -> Pair<String, String>?,
        proposeDevice: (DeviceLearningSession.Pending) -> Boolean,
        proposeVolume: (String, FallbackIntentLesson) -> Boolean, alias: String? = null,
        proposeLinkedVolume: ((String, FallbackIntentLesson, String) -> Boolean)? = null): String {
        if (alias != null && !RoutineProposal.isUsableName(alias))
            return "Choose a short non-reserved alias, such as work break. Nothing was run or learned."
        // Reassess now: a prior review is neither authority nor a cached app binding.
        val assessment = CapabilityRequestReview.assess(entry.text, resolveApp)
        val definition = assessment.definition
            ?: return CapabilityRequestReview.render(entry, assessment)
        val phrase = alias ?: definition.command
        val retained = when (definition.capability) {
            "timer", "open_app" -> proposeDevice(DeviceLearningSession.Pending(phrase,
                LearnableDeviceAction(definition.capability, definition.parameters), learnable = alias != null, explicitTeaching = alias != null, requestId = entry.id))
            ExactVolumeLearning.CAPABILITY -> (proposeLinkedVolume?.let { linked -> { text: String, lesson: FallbackIntentLesson -> linked(text, lesson, entry.id) } } ?: proposeVolume)(phrase,
                FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume",
                    "percent" to definition.parameters.getValue("percent")), learnable = alias != null))
            else -> return "This trial workflow supports timers, installed apps and exact media-volume levels. Review this request and issue its separate command if appropriate. Nothing was run, approved or learned."
        }
        if (!retained) return "Could not retain the approval proposal. Nothing was run or learned. State a new request before approving."
        if (alias != null) return "Saved request: ${entry.text}\nReview: '$alias' will ${definition.description}. Say yes to request it or no to cancel. " +
            if (definition.capability == ExactVolumeLearning.CAPABILITY)
                "The alias is saved only after device readback verifies the requested level. Nothing has changed or been learned yet; the backlog request stays unverified."
            else "Check the app or timer, then say 'it worked' to save this alias for offline reuse, or 'it failed' to discard it. Nothing has run or been learned yet; the backlog request stays unverified."
        return "Saved request: ${entry.text}\nReview: ${definition.description}. Say yes to request it or no to cancel. " +
            if (definition.capability == ExactVolumeLearning.CAPABILITY)
                "Android's volume readback will verify the result. Nothing has changed yet; no alias will be learned and the saved request stays unverified."
            else "After checking the app or timer, say 'it worked' to remember the confirmed action, or 'it failed' to discard the result. Nothing has run yet; no alias will be learned and the saved request stays unverified."
    }
}
