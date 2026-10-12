package com.assistant.core.services

import com.assistant.core.engine.ActionRegistry
import com.assistant.core.engine.AssistantEngine
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class HybridAssistantReply(
    val text: String,
    val actionResult: ActionResult? = null
)

private enum class PendingPrompt {
    NONE,
    PROJECT_NAME,
    SHELL_CONFIRMATION,
    REBOOT_CONFIRMATION
}

class HybridAssistantService(
    private val assistantEngine: AssistantEngine,
    private val actionRegistry: ActionRegistry,
    private val systemService: SystemService,
    private val capabilityProvider: () -> CapabilityState
) {

    private var pendingPrompt: PendingPrompt = PendingPrompt.NONE
    private var pendingLearningReset: Boolean = false
    private val routineConfirmations = RoutineConfirmationStore(systemService.context())
    private val routineRegistry = com.assistant.core.learning.ApprovedRoutineRegistry(systemService.context())
    private var pendingShellCommand: String? = null
    private var lastContactTarget: String? = null
    private val history = ArrayDeque<Pair<String, String>>()
    private val androidAssistant = AndroidAssistantService(systemService.context())
    private val learningStore = IntentLearningStore(systemService.context())
    private val directOnlineClient = DirectOnlineClient(systemService.context())
    private val assistantConnections = AssistantConnections(systemService.context())
    private val fallbackAiClient = FallbackAiClient(systemService.context())
    private val volumeConfirmations = VolumeConfirmationStore(systemService.context())
    private val deviceLearningSession = DeviceLearningSession(systemService.context())
    private val assistantMemory = AssistantMemory(systemService.context())
    private val resultHistory = IntelligenceResultHistory(systemService.context())
    private val preferenceConfirmations = PreferenceConfirmationStore(systemService.context())

    fun handleUserInput(rawInput: String): HybridAssistantReply {
        val userInput = rawInput.trim()
        val normalized = normalize(userInput)
        routineConfirmations.consume(userInput)?.let { resolution ->
            pendingPrompt = PendingPrompt.NONE
            pendingShellCommand = null
            pendingLearningReset = false
            volumeConfirmations.consume("")
            deviceLearningSession.consume("")
            preferenceConfirmations.consume("")
            val proposal = resolution.routine
            val commands = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
            val response = when {
                !resolution.approved -> "Routine proposal cancelled. Nothing was saved or run."
                routinePreferredUnavailable(proposal.actions) -> "The routine's preferred app is no longer saved or launchable. Restore the preference and review the routine again. Nothing was saved or run."
                routineNameIsLearned(proposal.phrase, commands) -> "That routine name now conflicts with a learned phrase. Choose a different name. Nothing was saved or run."
                routineRegistry.saveApproved(proposal) -> "Saved approved routine '${proposal.phrase}'. Say '${proposal.phrase}' to run it, including offline. Nothing has run yet."
                else -> "Could not save this routine. Its name may already have different steps, storage may be full, or saving may have failed. Review 'list routines'; nothing has run."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (RoutineProposal.isCreationRequest(userInput)) {
            pendingPrompt = PendingPrompt.NONE
            pendingShellCommand = null
            pendingLearningReset = false
            volumeConfirmations.consume("")
            deviceLearningSession.consume("")
            preferenceConfirmations.consume("")
            // Names and requested steps are data, never an immediate device command.
            return HybridAssistantReply("I don't understand that locally yet. This routine creation request needs a supported AI proposal and explicit review. Nothing was saved or run.")
        }
        if (userInput.isBlank()) {
            val result = assistantEngine.executeAction(actionRegistry.showStatusRequest())
            return replyFromResult(
                userText = userInput,
                preface = "Here is your current system status.",
                result = result
            )
        }

        if (pendingPrompt != PendingPrompt.NONE) {
            volumeConfirmations.consume("")
            deviceLearningSession.consume("")
            preferenceConfirmations.consume("")
        }
        when (pendingPrompt) {
            PendingPrompt.PROJECT_NAME -> {
                pendingPrompt = PendingPrompt.NONE
                val name = sanitizeProjectName(userInput)
                val result = assistantEngine.executeAction(actionRegistry.createProjectRequest(name))
                return replyFromResult(
                    userText = userInput,
                    preface = "Great. Creating project \"$name\" now.",
                    result = result
                )
            }
            PendingPrompt.SHELL_CONFIRMATION -> {
                pendingPrompt = PendingPrompt.NONE
                val command = pendingShellCommand
                pendingShellCommand = null
                return if (looksLikeYes(normalized) && !command.isNullOrBlank()) {
                    replyFromResult(userInput, "Executing confirmed privileged command.", assistantEngine.executeAction(actionRegistry.runShellRequest(command, confirmed = true)))
                } else {
                    remember(userInput, "Privileged command cancelled.")
                    HybridAssistantReply("Privileged command cancelled.")
                }
            }
            PendingPrompt.REBOOT_CONFIRMATION -> {
                pendingPrompt = PendingPrompt.NONE
                return if (looksLikeYes(normalized)) {
                    val result = assistantEngine.executeAction(actionRegistry.rebootDeviceRequest(confirmed = true))
                    replyFromResult(
                        userText = userInput,
                        preface = "Understood. Executing reboot request.",
                        result = result
                    )
                } else {
                    remember(userInput, "Reboot cancelled.")
                    HybridAssistantReply("Reboot cancelled. I won't make any system changes.")
                }
            }
            PendingPrompt.NONE -> Unit
        }

        volumeConfirmations.consume(userInput)?.let { resolution ->
            val response = if (resolution.approved) executeConfirmedVolume(resolution.pending.phrase, resolution.pending.lesson)
                else "Media volume request cancelled. Nothing was changed or learned."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        deviceLearningSession.consume(userInput)?.let { resolution ->
            val pending = resolution.pending
            val response = when {
                resolution.needsClarification -> {
                    resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.UNCLEAR,
                        "Nothing learned or marked failed. The same outcome check remains pending.")
                    "Did it work? The pending action is to ${pending.action.description()}. Say 'it worked' or 'it failed'. I'm keeping this outcome check pending; nothing was learned or marked failed."
                }
                !resolution.accepted -> {
                    if (pending.replayed && pending.awaitingOutcome) {
                        val memory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
                        val paused = VerifiedFailureFeedback.pause(resolution, memory)
                        resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.USER_FAILED,
                            if (paused) "Learned phrase paused. Nothing learned." else "Could not pause matching phrase. Nothing learned.")
                        if (paused)
                            "You reported it failed. Paused this learned phrase on this device. Review it with 'show learned commands'. Go online and verify a new successful lesson to restore the same meaning, or forget the phrase before teaching a different meaning."
                        else "You reported it failed, but the matching learned phrase could not be paused. Its storage may have changed or failed. Check 'show learned commands'; nothing was learned."
                    } else {
                        if (pending.awaitingOutcome) resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.USER_FAILED, "Nothing learned; recent confirmed action unchanged.")
                        "Request cancelled or reported unsuccessful. Nothing was learned."
                    }
                }
                pending.awaitingOutcome -> {
                    val contextSaved = assistantMemory.recordConfirmed(pending.action)
                    val prefs = systemService.context().getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
                    val memory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
                    val learned = pending.learnable && prefs.getBoolean("automatic_command_learning", true) &&
                        memory.recordSuccess(pending.phrase, pending.contextual?.capabilityId ?: pending.action.capabilityId, LearnableDeviceAction.capabilities + ContextualAction.capabilities, pending.contextual?.parameters ?: pending.action.parameters)
                    resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.USER_CONFIRMED,
                        (if (learned) "Original phrase saved for offline reuse." else "No new phrase saved.") +
                            (if (contextSaved) " Recent-action context saved." else " Recent-action context could not be saved."))
                    val lessonReply = if (!pending.learnable) {
                        if (contextSaved) "You confirmed it worked. Remembered ${pending.action.description()} as your last confirmed action on this device."
                        else "You confirmed the result, but I couldn't save its recent-action context."
                    } else if (learned)
                        "You confirmed it worked. Learned \"${pending.phrase}\" on this device for offline reuse."
                    else "Your successful result was noted, but this phrase was not saved. Learning may be disabled or the phrase may conflict with another command."
                    lessonReply + if (contextSaved || !pending.learnable) "" else " Recent-action context could not be saved."
                }
                !requestDeviceAction(pending.action) -> "Android couldn't launch the requested action. Nothing was learned."
                !deviceLearningSession.propose(pending.copy(awaitingOutcome = true)) -> "The action was requested, but its learning outcome could not be retained. Nothing was learned."
                else -> "Requested: ${pending.action.description()}. Check the timer or app, then say 'it worked' to " +
                    if (pending.learnable) "save your original phrase, or 'it failed' to discard it. Nothing is learned yet."
                    else "remember this as your last confirmed action, or 'it failed' to discard the result."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        val deviceMemory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
        fun previousAction(kind: String) = deviceMemory.latestParameterized(kind)?.let { LearnableDeviceAction(it.capabilityId, it.parameters) }?.takeIf { it.isValid() }
        assistantMemory.seedRecentOnce(previousAction("timer"), previousAction("open_app"))
        preferenceConfirmations.consume(userInput)?.let { pending ->
            val response = when {
                !pending.approved -> "Assistant memory change cancelled."
                pending.slot == "clear" -> if (assistantMemory.clear()) "Cleared saved assistant preferences and recent-action context on this device." else "Could not clear assistant memory."
                pending.action != null -> {
                    val action = pending.action
                    val prefs = systemService.context().getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
                    val outcome = PreferenceLearning.commit(pending, prefs.getBoolean("automatic_command_learning", true),
                        installed = { androidAssistant.resolveLearnableApp(it.parameters.getValue("label"))?.first == it.parameters.getValue("package") },
                        save = assistantMemory::savePreference, read = assistantMemory::preferred,
                        record = { phrase, preference -> deviceMemory.recordSuccess(phrase, LearnablePreference.CAPABILITY, LearnablePreference.capabilities, preference.parameters()) })
                    when {
                        !outcome.saved -> "Could not verify that preference was saved. Check that the app is still installed with a unique launcher name. No phrase was learned."
                        outcome.learned -> "Remembered ${action.parameters["label"]} as your preferred ${pending.slot} in J.A.R.V.I.S. Saved and verified. Learned your original phrase for offline reuse."
                        else -> "Remembered ${action.parameters["label"]} as your preferred ${pending.slot} in J.A.R.V.I.S." +
                            if (pending.learningPhrase != null) " The preference was saved and verified, but the phrase was not learned; learning may be disabled or the phrase may conflict." else ""
                    }
                }
                else -> "No valid preference was awaiting approval."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (DeviceLearningSession.isOutcome(userInput)) {
            val response = "No timer or app request is awaiting outcome confirmation. Run the action first, then report 'it worked' or 'it failed'. Nothing was added to memory."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (ConfirmationWords.isConfirmation(userInput)) {
            pendingLearningReset = false
            return HybridAssistantReply("No action is awaiting yes/no approval. State the action you want, such as 'open Chrome'. Nothing was changed.")
        }
        LearnedCommandReviewCommands.page(userInput)?.let { page ->
            pendingLearningReset = false
            // Full local review lists must not enter the fallback conversation history.
            return HybridAssistantReply(LearnedCommandReview.render(page, deviceMemory, assistantMemory, learningStore.snapshot()))
        }
        LearnedCommandReviewCommands.forgetPhrase(userInput)?.let { phrase ->
            pendingLearningReset = false
            val removedIntent = phrase.isNotBlank() && learningStore.forget(phrase)
            val removedCommand = phrase.isNotBlank() && deviceMemory.forget(phrase)
            return HybridAssistantReply(if (removedIntent || removedCommand) "Removed matching local learning for that phrase. Saved assistant preferences and recent actions remain available."
                else "No matching learned command was removed. Check its exact phrase with 'show learned commands'.")
        }
        if (normalized in setOf("show assistant memory", "assistant memory", "what do you remember about my apps and timers")) {
            val response = assistantMemory.summary()
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized == "clear assistant memory") {
            val response = if (preferenceConfirmations.propose("clear")) "Clear your saved J.A.R.V.I.S. app preferences and recent-action context on this device? Say yes to confirm or no to cancel."
                else "Could not retain the memory-clear request."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        AssistantMemoryCommands.forgetKey(userInput)?.let { key ->
            val response = if (assistantMemory.forget(key)) "Forgot that saved preference or recent-action context." else "No matching assistant memory was saved."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        AssistantMemoryCommands.preference(userInput)?.let { request ->
            val target = androidAssistant.resolveLearnableApp(request.appName)
            val action = target?.let { LearnableDeviceAction("open_app", mapOf("package" to it.first, "label" to it.second)) }
            val response = if (action == null || !preferenceConfirmations.propose(request.slot, action))
                "I couldn't find one installed app with that launcher name. Specify its exact name; no preference was saved."
            else "Would you like me to remember ${action.parameters["label"]} as your preferred ${request.slot} in J.A.R.V.I.S.? Say yes to confirm or no to cancel."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        AssistantMemoryCommands.preferredSlot(userInput)?.let { slot ->
            val action = assistantMemory.preferred(slot)
            val response = when {
                action == null -> "No preferred $slot is saved. Say 'remember my preferred $slot is' followed by the installed app's name."
                else -> requestContextAction(userInput, action, "using your saved $slot preference")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        AssistantMemoryCommands.repeatCapability(userInput)?.let { kind ->
            val reference = ContextualAction("repeat_action", mapOf("kind" to kind))
            val action = reference.resolve(assistantMemory)
            val response = when {
                kind == "most_recent" && action == null -> "No ordered, confirmed timer or app launch is saved yet. Complete a timer or app action and say 'it worked' first. You can still ask for the last confirmed timer or app separately."
                kind == "most_recent" && action != null -> if (deviceLearningSession.propose(DeviceLearningSession.Pending(userInput, action, false, contextual = reference)))
                    "Your most recent confirmed action is to ${action.description()}. Would you like me to repeat it? Say yes to confirm or no to cancel."
                    else "I couldn't retain this repeat request for confirmation. Nothing was changed."
                action == null -> "No confirmed ${if (kind == "timer") "timer" else "app launch"} is saved yet. Complete an action and confirm 'it worked' first."
                else -> requestContextAction(userInput, action, "using your last confirmed ${if (kind == "timer") "timer" else "app"}")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (deviceMemory.isPaused(userInput)) {
            val response = "I don't understand that locally yet. This learned phrase is paused after an unsuccessful result. A verified successful lesson is needed before it can be reused."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        deviceMemory.match(userInput, LearnablePreference.capabilities)?.let { learned ->
            val preference = LearnablePreference.fromParameters(learned.parameters)
            val response = if (preference == null) "That learned preference command is invalid. Nothing was changed."
                else proposePreference(preference)
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        deviceMemory.match(userInput, ContextualAction.capabilities)?.let { learned ->
            val reference = ContextualAction(learned.capabilityId, learned.parameters)
            val action = reference.resolve(assistantMemory)
            val response = if (action == null) "That learned phrase needs a saved preference or confirmed action. Restore its assistant memory first; nothing was requested."
                else requestContextAction(userInput, action, "using your learned reference and current assistant memory", replayed = true, contextual = reference)
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        deviceMemory.match(userInput, LearnableDeviceAction.capabilities)?.let { learned ->
            val action = LearnableDeviceAction(learned.capabilityId, learned.parameters)
            val response = requestContextAction(userInput, action, "using your learned command", replayed = true)
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        // Routine approval is consumed above, before any local handler or cloud fallback.
        if (normalized == "cancel routine") return HybridAssistantReply("No routine is awaiting approval. Nothing was saved or run.")
        if (normalized == "confirm routine") return HybridAssistantReply("No routine is awaiting approval. Ask J.A.R.V.I.S. to create it again, then confirm the review.")
        if (normalized.startsWith("forget routine ")) {
            val phrase = userInput.substringAfter(" ", "").substringAfter(" ", "").trim()
            val response = if (routineRegistry.forget(phrase)) "Forgot routine: $phrase."
                else "No matching approved routine was found."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized == "list routines" || normalized == "show routines") {
            val routines = routineRegistry.listSummaries()
            val response = if (routines.isEmpty()) "No approved routines are stored on this device."
                else "Approved routines (showing up to 10):\n${routines.joinToString("\n")}"
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized == "routine status") {
            val response = "Approved routines on this device: ${routineRegistry.count()}."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        val routineProposal = RoutineCommandParser.parse(userInput)
        if (routineProposal != null) {
            val response = proposeRoutine(routineProposal.phrase, routineProposal.actions)
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        // Keep malformed teaching requests local rather than letting cloud AI claim
        // that an unapproved or unsupported routine was learned.
        if (normalized.startsWith("teach jarvis that ") || normalized.startsWith("teach that ")) {
            if (normalized.contains(" and ") && normalized.contains(" means ")) {
                val response = "I could not approve that routine. Use supported steps: volume up, volume down, or update app. No routine was saved."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
        }
        routineRegistry.find(userInput)?.let { routine ->
            var requestedTarget: LearnableDeviceAction? = null
            val outcome = RoutineExecution.runWithPreferred(routine.actions, assistantMemory, androidAssistant::canLaunchApp) { step, preferred ->
                val target = preferred ?: RoutineSteps.timerAction(step)
                if (target != null) {
                    requestedTarget = target
                    when (deviceLearningSession.requestAndTrack(routine.phrase, target) { requestDeviceAction(target) }) {
                        DeviceLearningSession.RequestResult.FAILED -> RoutineExecution.Status.FAILED
                        DeviceLearningSession.RequestResult.AWAITING_OUTCOME -> RoutineExecution.Status.AWAITING_OUTCOME
                        DeviceLearningSession.RequestResult.REQUESTED_WITHOUT_TRACKING -> RoutineExecution.Status.REQUESTED_WITHOUT_TRACKING
                    }
                } else when (step) {
                    "volume_up", "volume_down" -> if (androidAssistant.adjustVolume(
                        if (step == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER))
                        RoutineExecution.Status.VERIFIED else RoutineExecution.Status.FAILED
                    "app_update" -> if (runCatching {
                        val context = systemService.context()
                        context.startActivity(android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("jarvis_feature", "app_update"))
                    }.isSuccess) RoutineExecution.Status.REQUESTED else RoutineExecution.Status.FAILED
                    else -> RoutineExecution.Status.FAILED
                }
            }
            if (outcome.unavailablePreferred)
                return HybridAssistantReply("The routine's preferred app is missing or cannot be launched. Restore the saved preference first. No routine steps were run.")
            val results = outcome.steps.joinToString("; ") { (step, status) ->
                (if (RoutineSteps.preferredSlot(step) != null) requestedTarget?.description() ?: RoutineProposal.describe(step) else RoutineProposal.describe(step)) + ": " + when (status) {
                    RoutineExecution.Status.VERIFIED -> "changed and verified"
                    RoutineExecution.Status.REQUESTED -> "requested"
                    RoutineExecution.Status.AWAITING_OUTCOME -> "requested; awaiting your outcome check"
                    RoutineExecution.Status.REQUESTED_WITHOUT_TRACKING -> "requested; outcome tracking unavailable"
                    RoutineExecution.Status.FAILED -> "failed; remaining steps stopped"
                }
            }
            val response = "Routine '${routine.phrase}': $results." + when {
                outcome.awaitingOutcome -> " Check the requested timer or app, then say 'it worked' to remember it as your last confirmed action, or 'it failed' to discard the result. The routine's approved steps remain saved."
                outcome.missingOutcomeTracking -> " The timer/app was requested, but its outcome could not be retained. No recent-action memory was saved."
                "app_update" in routine.actions -> " Android may still require approval for installation."
                else -> ""
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        // Route the built-in updater locally. Never let cloud AI claim it installed a shortcut.
        val updaterTeaching = LocalDeviceCommand.isUpdaterTeaching(userInput)
        if (updaterTeaching) {
            val response = "The command \"update app\" is built in. Say \"update app\" to open the J.A.R.V.I.S. updater."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (LocalDeviceCommand.parse(userInput) == LocalDeviceCommand.Action.UPDATE_APP) {
            val launched = runCatching {
                val context = systemService.context()
                val intent = android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("jarvis_feature", "app_update")
                context.startActivity(intent)
            }.isSuccess
            val response = if (launched) "Opening the J.A.R.V.I.S. updater. Android will ask you to approve installation." else "Could not open the updater. Open J.A.R.V.I.S., then go to Tools > System status and updates."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Explicit, on-device teaching for the verified clock capability.
        // This never authorizes arbitrary device actions or cloud-generated code.
        val teachTime = Regex("(?i)^teach (?:jarvis )?(?:that )?(.{1,200}?) (?:means|is the same as) (?:what time is it|tell me the time)[?.!]*$").matchEntire(userInput)
        if (teachTime != null) {
            val phrase = teachTime.groupValues[1].trim()
            val reserved = LearnedCommandReviewCommands.isReserved(phrase) || normalize(phrase) in setOf("learning status", "clear local learning", "confirm learning reset")
            val response = if (reserved || phrase.length < 3) {
                "That phrase is reserved or too short to learn."
            } else {
                learningStore.learn(phrase, "GENERAL_QUESTION", source = "verified_local_time")
                if (learningStore.classify(phrase)?.let { it.intent == "GENERAL_QUESTION" && it.source == "verified_local_time" && it.confidence == 1.0 } == true)
                    "Learned on this device: \"$phrase\" now requests the current time. Say the phrase to test it."
                else "Could not learn that phrase because it conflicts with an existing lesson."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Learn only allowlisted volume actions, and only after Android confirms execution.
        // Exact phrase matches avoid fuzzy routing of unrelated or sensitive commands.
        val volumeCapabilities = setOf("volume_up", "volume_down")
        val volumeMemory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
        val teachVolume = Regex("(?i)^teach (?:jarvis )?(?:that )?(.{3,160}?) (?:means|is the same as) (?:turn (?:the )?volume up|increase (?:the )?volume|turn (?:the )?volume down|decrease (?:the )?volume)[?.!]*$").matchEntire(userInput)
        if (teachVolume != null) {
            val phrase = teachVolume.groupValues[1].trim()
            val target = normalize(userInput).substringAfterLast(" means ", "").ifBlank {
                normalize(userInput).substringAfterLast(" is the same as ", "")
            }.trimEnd('?', '.', '!')
            val capability = if (target.endsWith("up") || target.startsWith("increase ")) "volume_up" else "volume_down"
            val reserved = LearnedCommandReviewCommands.isReserved(phrase) || phrase.startsWith("teach ", ignoreCase = true) ||
                normalize(phrase).matches(Regex(".*\\b(?:call|text|message|delete|reset|reboot|pay|purchase|send)\\b.*")) ||
                normalize(phrase) in setOf("learning status", "clear local learning", "confirm learning reset", "cancel learning reset")
            val response = if (reserved) {
                "That phrase is reserved and cannot be learned."
            } else {
                val direction = if (capability == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
                val executed = androidAssistant.adjustVolume(direction)
                if (!executed) "Android could not adjust the volume, so the command was not learned."
                else if (volumeMemory.recordSuccess(phrase, capability, volumeCapabilities))
                    "Volume adjusted and verified. Learned on this device: \"$phrase\"."
                else "Volume adjusted, but the phrase could not be learned because it conflicts with another command."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        // Teach aliases only for known, allowlisted local actions. An alias is stored
        // only after the corresponding action succeeds on the device.
        val teachDeviceAlias = Regex(
            "(?i)^teach (?:jarvis )?(?:that )?(.{3,160}?) (?:means|is the same as) " +
                "(update app|update jarvis|upgrade app|upgrade jarvis|check for jarvis update)[?.!]*$"
        ).matchEntire(userInput)
        if (teachDeviceAlias != null) {
            val alias = teachDeviceAlias.groupValues[1].trim()
            val target = teachDeviceAlias.groupValues[2]
            val reserved = LearnedCommandReviewCommands.isReserved(alias) || LocalDeviceCommand.parse(alias) != null ||
                alias.startsWith("teach ", ignoreCase = true) ||
                normalize(alias).matches(Regex(".*\\b(?:call|text|message|delete|reset|reboot|pay|purchase|send)\\b.*"))
            val response = if (reserved) {
                "That phrase is reserved and cannot be learned."
            } else {
                val context = systemService.context()
                val launched = runCatching {
                    val intent = android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("jarvis_feature", "app_update")
                    context.startActivity(intent)
                }.isSuccess
                if (!launched) "Could not open the updater, so the alias was not learned."
                else if (volumeMemory.recordSuccess(alias, "app_update", volumeCapabilities + "app_update"))
                    "Opened the updater and learned on this device: $alias means $target."
                else "Opened the updater, but the phrase conflicts with an existing learned command."
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        // A teaching request for an unsupported updater action must not reach
        // cloud AI, which cannot actually install local device shortcuts.
        if (normalized.startsWith("teach jarvis that update app ") ||
            normalized.startsWith("teach that update app ")) {
            val response = "Update app is already built in. To teach a new phrase, say: Teach Jarvis that check for upgrades means update app."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        volumeMemory.match(userInput, volumeCapabilities + "app_update")?.let { learnedUpdater ->
            if (learnedUpdater.capabilityId == "app_update") {
                val launched = runCatching {
                    val context = systemService.context()
                    context.startActivity(android.content.Intent(context, com.assistant.core.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("jarvis_feature", "app_update"))
                }.isSuccess
                val response = if (launched) "Opening the J.A.R.V.I.S. updater using your learned command."
                    else "Could not open the updater."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
        }

        volumeMemory.match(userInput, volumeCapabilities)?.let { learned ->
            val direction = if (learned.capabilityId == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
            val executed = androidAssistant.adjustVolume(direction)
            val response = if (executed) "Volume adjusted using your learned command." else "Android could not adjust the volume."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Learning diagnostics are handled locally, without an AI connection.
        if (normalized in setOf("learning status", "show learning status", "how many commands have you learned")) {
            val commandCount = com.assistant.core.learning.ValidatedCommandMemory(systemService.context()).learnedPhraseCount()
            val response = "Local learned intent examples: ${learningStore.count()}; validated command phrases: $commandCount. Say 'forget learned command' followed by the exact phrase, or 'clear learned database on this device' to erase both local stores."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized in setOf("reset learned commands", "reset learned commands on this device", "clear learned database on this device", "clear local learning")) {
            pendingLearningReset = true
            val response = "This will erase learned commands stored on this device only. Other devices are not affected. Say 'confirm learning reset on this device' to continue, or 'cancel learning reset' to keep them."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (normalized in setOf("confirm learning reset", "confirm learning reset on this device", "cancel learning reset")) {
            val response = when {
                !pendingLearningReset -> "No learning reset is pending."
                normalized == "cancel learning reset" -> "Learning reset cancelled."
                else -> {
                    val intentsCleared = learningStore.clear()
                    val commandsCleared = com.assistant.core.learning.ValidatedCommandMemory(systemService.context()).clear()
                    if (intentsCleared && commandsCleared) {
                        "Learned commands on this device have been cleared. Other devices are unchanged."
                    } else {
                        "Could not clear local learning. Please try again."
                    }
                }
            }
            pendingLearningReset = false
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // A reset confirmation is valid only for the immediately following command.
        // An unrelated command cancels it to avoid accidental deletion later.
        pendingLearningReset = false

        if (CommandPhrases.isLastMissedCall(userInput)) {
            val response = androidAssistant.lastMissedCall()
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "hello", "hi jarvis", "hey jarvis", "good morning", "good evening")) {
            val response = "Hello. I can help with calls, messages, maps, weather, music, and questions."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "what can you do", "help", "capabilities", "features")) {
            val response = buildString {
                appendLine("I can help with:")
                appendLine("- Call a number or contact with Phone permission")
                appendLine("- Read your latest missed call with Call Log permission")
                appendLine("- Open maps, apps, and compose messages")
                appendLine("- Set alarms and control music")
                appendLine("- Get US weather directly, without an AI key")
                appendLine("- Answer general questions with your optional Gemini key")
                appendLine("No custom server or companion app is needed for built-in commands.")
                appendLine("Advanced tools and home-server connections are optional.")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Only verified time examples can reuse the local time handler.
        // A generic GENERAL_QUESTION label must never authorize device actions.
        val learnedTime = learningStore.classify(userInput)?.let {
            it.intent == "GENERAL_QUESTION" &&
                it.source == "verified_local_time" &&
                it.confidence >= 0.99
        } == true
        if (isTimeRequest(normalized) || learnedTime) {
            val response = "It's " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) + "."
            if (isTimeRequest(normalized)) {
                learningStore.learn(userInput, "GENERAL_QUESTION", source = "verified_local_time")
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        LocalKnowledge.reply(userInput)?.let { response ->
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        calendarRangeFor(normalized)?.let { range ->
            if (!androidAssistant.hasCalendarReadPermission()) {
                val response = "Calendar permission is required. Open J.A.R.V.I.S. and grant Calendar access, then ask again."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            val events = androidAssistant.calendarEntries(range.first, range.second)
            val response = if (events.isEmpty()) {
                "You don't have any calendar events in that period."
            } else {
                val format = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
                events.joinToString(prefix = "Your calendar: ", separator = "; ") {
                    "${it.title}, ${format.format(Date(it.beginMillis))}"
                }
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        parseTimerSeconds(normalized)?.let { seconds ->
            val action = LearnableDeviceAction("timer", mapOf("seconds" to seconds.toString()))
            val response = if (action.isValid()) requestContextAction(userInput, action)
                else if (androidAssistant.setTimer(seconds)) "Requested a timer for $seconds seconds." else "I couldn't open the Android timer service."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        parseNavigationDestination(normalized)?.let { destination ->
            val ok = androidAssistant.navigate(destination)
            val response = if (ok) "Starting navigation to $destination." else "I couldn't start navigation."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (LocalDeviceCommand.parse(userInput) == LocalDeviceCommand.Action.VOLUME_UP) {
            val ok = androidAssistant.adjustVolume(android.media.AudioManager.ADJUST_RAISE)
            val response = if (ok) "Volume increased." else "I couldn't change the volume."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (LocalDeviceCommand.parse(userInput) == LocalDeviceCommand.Action.VOLUME_DOWN) {
            val ok = androidAssistant.adjustVolume(android.media.AudioManager.ADJUST_LOWER)
            val response = if (ok) "Volume decreased." else "I couldn't change the volume."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseCalendarRequest(userInput)?.let { title ->
            val ok = androidAssistant.createCalendarEvent(title)
            val response = if (ok) "Opening your calendar to create $title." else "I couldn't open the calendar."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseReminderRequest(userInput)?.let { title ->
            val ok = androidAssistant.createReminder(title)
            val response = if (ok) "Opening your calendar to create the reminder $title." else "I couldn't open a reminder provider."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("notification settings", "open notification settings", "manage notifications")) {
            val ok = androidAssistant.openNotificationSettings()
            val response = if (ok) "Opening J.A.R.V.I.S. notification settings." else "I couldn't open notification settings."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        parseCallTarget(normalized)?.let { rawTarget ->
            val target = cleanContactTarget(resolveConversationContact(rawTarget))
            lastContactTarget = target
            val directNumber = target.filter { it.isDigit() || it == '+' }.takeIf { it.any(Char::isDigit) }
            if (directNumber == null && !hasContactsPermission()) {
                val response = "I need Contacts permission before I can look up $target. Please allow Contacts access for J.A.R.V.I.S., then try again."
                remember(userInput, response); return HybridAssistantReply(response)
            }
            val number = directNumber ?: androidAssistant.resolveContactPhone(target)
            val dialOnly = normalized.startsWith("dial ")
            if (!dialOnly && number != null && !androidAssistant.hasCallPermission()) {
                val response = "Phone permission is required to place calls. Open J.A.R.V.I.S. Permissions, grant Phone access, then say call $target again."
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            val ok = number?.let { if (dialOnly) androidAssistant.dial(it) else androidAssistant.call(it) } ?: false
            val response = when {
                ok && dialOnly -> "Opening the dialer for $target."
                ok -> "Starting the call to $target."
                number == null -> "I couldn't find $target in your contacts with a phone number."
                else -> "Android couldn't start the call. Check Phone permission and your phone app."
            }
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseMessageRequest(userInput)?.let { (rawTarget, body) ->
            val target = resolveConversationContact(rawTarget)
            lastContactTarget = target
            val number = target.filter { it.isDigit() || it == '+' }.takeIf { it.any(Char::isDigit) }
                ?: androidAssistant.resolveContactPhone(target)
            val ok = number?.let { androidAssistant.composeSms(it, body) } ?: false
            val response = if (ok) "Opening your messaging app with the message ready for $target." else "I couldn't find a phone number for $target. Contact access may be required."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        parseAlarm(normalized)?.let { (hour, minute) ->
            val ok = androidAssistant.setAlarm(hour, minute)
            val response = if (ok) "Alarm set." else "I couldn't open the Android alarm service."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (CommandPhrases.isAmazonMusicRequest(userInput)) {
            val response = androidAssistant.amazonMusic(Regex("(?i)\\bplay\\b").containsMatchIn(userInput))
            remember(userInput, response); return HybridAssistantReply(response)
        }

        parseAppName(normalized)?.let { appName ->
            val target = androidAssistant.resolveLearnableApp(appName)
            val response = if (target != null) requestContextAction(userInput, LearnableDeviceAction("open_app", mapOf("package" to target.first, "label" to target.second)))
                else "I don't understand that locally yet. No unique installed app matched $appName."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("play", "pause", "play music", "pause music", "play pause", "resume music")) {
            val ok = androidAssistant.playPauseMedia()
            val response = if (ok) "Media control sent." else "I couldn't control media playback."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("next", "next song", "next track", "skip song", "skip track")) {
            val ok = androidAssistant.nextMedia()
            val response = if (ok) "Skipping to the next track." else "I couldn't control media playback."
            remember(userInput, response); return HybridAssistantReply(response)
        }
        if (normalized in setOf("previous song", "previous track", "go back a track")) {
            val ok = androidAssistant.previousMedia()
            val response = if (ok) "Going to the previous track." else "I couldn't control media playback."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (normalized in setOf("open settings", "device settings", "system settings")) {
            val ok = androidAssistant.openSettings()
            val response = if (ok) "Opening Android settings." else "I couldn't open Android settings."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (normalized in setOf("go back", "back")) {
            return replyFromResult(userInput, "Going back.", assistantEngine.executeAction(actionRegistry.accessibilityGlobalRequest(ActionRegistry.ACCESSIBILITY_BACK)))
        }
        if (normalized in setOf("go home", "home screen", "home")) {
            return replyFromResult(userInput, "Going home.", assistantEngine.executeAction(actionRegistry.accessibilityGlobalRequest(ActionRegistry.ACCESSIBILITY_HOME)))
        }
        if (normalized in setOf("show recent apps", "recent apps", "recents")) {
            return replyFromResult(userInput, "Opening recent apps.", assistantEngine.executeAction(actionRegistry.accessibilityGlobalRequest(ActionRegistry.ACCESSIBILITY_RECENTS)))
        }
        if (containsAny(normalized, "what is on my screen", "whats on my screen", "read my screen", "read screen", "screen context")) {
            return replyFromResult(userInput, "Reading the current screen.", assistantEngine.executeAction(actionRegistry.readScreenRequest()))
        }

        // Return a search card in the conversation instead of interrupting chat
        // by opening another activity. The user opens the embedded browser by
        // tapping the card; the same response works for voice and text input.
        WebSearchCommand.videoQuery(userInput)?.let { query ->
            val url = android.net.Uri.parse("https://www.google.com/search").buildUpon()
                .appendQueryParameter("tbm", "vid").appendQueryParameter("q", query).build()
            val response = "Video search ready: $query\n$url"
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        WebSearchCommand.imageQueryNatural(userInput)?.let { query ->
            val url = android.net.Uri.parse("https://www.google.com/search")
                .buildUpon().appendQueryParameter("tbm", "isch")
                .appendQueryParameter("q", query).build()
            val response = "Image search ready: $query\\n$url".replace("\\n", "\n")
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        (WebSearchCommand.query(userInput) ?: WebSearchCommand.currentInfoQuery(userInput))?.let { query ->
            val url = android.net.Uri.parse("https://www.google.com/search")
                .buildUpon().appendQueryParameter("q", query).build()
            val response = "Web search ready: $query\\n$url".replace("\\n", "\n")
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "create project", "new project", "generate project", "make project")) {
            val extracted = extractProjectName(normalized)
            if (extracted == null) {
                pendingPrompt = PendingPrompt.PROJECT_NAME
                val response = "Sure. What should I name the new project?"
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            val result = assistantEngine.executeAction(actionRegistry.createProjectRequest(extracted))
            return replyFromResult(
                userText = userInput,
                preface = "Creating project \"$extracted\".",
                result = result
            )
        }

        if (containsAny(normalized, "run shell", "execute shell", "run command")) {
            val command = extractShellCommand(userInput)
            pendingShellCommand = command
            pendingPrompt = PendingPrompt.SHELL_CONFIRMATION
            val response = "This privileged command requires a separate confirmation before execution."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (containsAny(normalized, "status", "system status", "show status")) {
            val result = assistantEngine.executeAction(actionRegistry.showStatusRequest())
            return replyFromResult(
                userText = userInput,
                preface = "Here is your status summary.",
                result = result
            )
        }

        if (containsAny(normalized, "reboot", "restart device")) {
            pendingPrompt = PendingPrompt.REBOOT_CONFIRMATION
            val response = "Reboot is a high-risk action. Confirm by saying \"yes\" or \"confirm reboot\"."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        // Learned intent labels are advisory until a vetted capability executor
        // exists. Never short-circuit cloud fallback with a non-actionable label.
        // This prevents previously learned phrases from becoming dead ends.

        val fallback = assistantEngine.handleUserCommand(userInput)
        if (!fallback.success && fallback.adapterUsed == "ENGINE") {
            val response = buildString {
                appendLine("I don't understand that locally yet.")
                appendLine("This request is eligible for fallback-AI interpretation and validated local learning.")
                append("Local learned examples: ${learningStore.count()}")
            }
            remember(userInput, response)
            return HybridAssistantReply(response, fallback)
        }
        return replyFromResult(
            userText = userInput,
            preface = "Done.",
            result = fallback
        )
    }

    fun canAnalyzeAttachments(): Boolean = directOnlineClient.hasGeminiKey()

    fun handleAttachmentsAsync(question: String, attachments: List<ChatAttachment>, callback: (HybridAssistantReply) -> Unit) {
        routineConfirmations.consume("")
        Thread {
            val response = directOnlineClient.gemini(question, getConversationSnapshot(), attachments)
            remember(question + "\nAttachments: " + attachments.joinToString { it.name }, response)
            callback(HybridAssistantReply(response))
        }.start()
    }

    fun handleUserInputAsync(rawInput: String, callback: (HybridAssistantReply) -> Unit) {
        AssistantConnectionProtocol.parse(rawInput)?.let { request ->
            routineConfirmations.consume("")
            Thread {
                val response = assistantConnections.ask(request)
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        val local = handleUserInput(rawInput)
        val unresolved = local.text.startsWith("I don't understand that locally yet.")
        if (unresolved && !RoutineProposal.isCreationRequest(rawInput) && OnlineAnswers.isWeather(rawInput)) {
            Thread {
                val response = directOnlineClient.weather(rawInput)
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        if (!unresolved) {
            callback(local)
            return
        }
        val network = systemService.context().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            val response = if (RoutineProposal.isCreationRequest(rawInput))
                "AI routine proposals require an online connection and configured AI provider. Existing approved routines still run offline. Nothing was saved or run."
            else if (com.assistant.core.learning.ValidatedCommandMemory(systemService.context()).isPaused(rawInput))
                "You're offline. This learned phrase is paused after an unsuccessful result. Go online and complete a verified successful lesson, or use 'forget learned command' followed by the phrase before teaching a different meaning."
            else "You're offline, and this phrase isn't saved as a local command yet. Go online, repeat the original request, and confirm it. It must execute successfully before it can be learned for offline use."
            remember(rawInput, response)
            callback(HybridAssistantReply(response, local.actionResult))
            return
        }
        if (directOnlineClient.hasGeminiKey()) {
            Thread {
                val cloud = directOnlineClient.assistant(rawInput, getConversationSnapshot(), ContextualAction.aiContext(assistantMemory))
                val response = proposeCloudAction(rawInput, cloud.lessonJson) ?: cloud.reply
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        if (!fallbackAiClient.isConfigured()) {
            val response = "I don't understand that locally yet. AI fallback needs configuration. Enter your Gemini API key in Voice Settings → Weather and AI."
            remember(rawInput, response)
            callback(HybridAssistantReply(response, local.actionResult))
            return
        }
        Thread {
            val cloud = fallbackAiClient.ask(rawInput, capabilityContext = ContextualAction.aiContext(assistantMemory))
            if (cloud == null) {
                val response = "I don't understand that locally yet. Cloud fallback failed or returned no usable reply. Check your connection and AI settings."
                remember(rawInput, response)
                callback(HybridAssistantReply(response, local.actionResult))
                return@Thread
            }
            // A plausible cloud interpretation is not proof of a successful action.
            // Do not train device-action intents from unverified cloud replies.
            val actionResponse = proposeCloudAction(rawInput, cloud.lessonJson)
            if (actionResponse == null) cloud.lessonJson?.let { acceptFallbackLesson(rawInput, it) }
            val response = actionResponse ?: cloud.reply
            remember(rawInput, response)
            callback(HybridAssistantReply(response))
        }.start()
    }

    private fun proposeCloudAction(userText: String, lessonJson: String?): String? {
        val lesson = lessonJson?.let(FallbackIntentLesson::fromJson)
        if (RoutineProposal.isCreationRequest(userText)) {
            if (lesson?.intent != "CREATE_ROUTINE")
                return "I couldn't produce a supported routine definition. Give a name and explicit steps: volume up/down one step, opening the updater, a timer with an explicit duration, or opening a saved preferred browser/maps/music app. Nothing was saved or run."
            return proposeFallbackDeviceAction(userText, lessonJson)
        }
        if (lesson?.intent == "CREATE_ROUTINE")
            return "Creating a routine needs an explicit request to create a named routine and its steps. Nothing was saved or run."
        return proposeFallbackVolume(userText, lessonJson) ?: proposeFallbackDeviceAction(userText, lessonJson)
    }

    private fun proposeFallbackVolume(userText: String, lessonJson: String?): String? {
        val lesson = lessonJson?.let(FallbackIntentLesson::fromJson) ?: return null
        val capability = VerifiedVolumeLearning.capability(lesson) ?: return null
        if (!volumeConfirmations.propose(userText, lesson)) return "I couldn't retain this volume request for confirmation. Nothing was changed or learned. Please repeat it."
        return "Would you like me to ${if (capability == "volume_up") "raise" else "lower"} the media volume? Say yes to confirm or no to cancel."
    }

    private fun proposeFallbackDeviceAction(userText: String, lessonJson: String?): String? {
        val lesson = lessonJson?.let(FallbackIntentLesson::fromJson) ?: return null
        if (lesson.intent == "CREATE_ROUTINE") {
            val routine = RoutineProposal.fromLesson(lesson)
                ?: return "I couldn't approve that routine definition. Supported steps are media volume up/down one step, opening the updater, a final timer from 1 second to 24 hours, or a final saved preferred browser/maps/music app. Choose a short routine name and explicit steps. Nothing was saved or run."
            return proposeRoutine(routine.phrase, routine.actions)
        }
        if (lesson.intent == "SET_APP_PREFERENCE") {
            val preference = LearnablePreference.fromLesson(lesson, androidAssistant::resolveLearnableApp)
                ?: return "I couldn't safely match that preference to one installed app. Specify browser, maps or music and the app's exact launcher name. No preference was saved."
            return proposePreference(preference, userText.takeIf { lesson.canTeachLocally() })
        }
        if (lesson.intent in setOf("OPEN_PREFERRED_APP", "REPEAT_ACTION")) {
            val reference = ContextualAction.fromLesson(lesson)
                ?: return "I couldn't safely interpret that saved-context reference. Specify which preferred app or confirmed timer/app you mean. Nothing was changed or learned."
            val action = reference.resolve(assistantMemory)
                ?: return "No matching preference or confirmed action is saved. Set the preference or complete an action and confirm 'it worked' first. Nothing was changed or learned."
            if (!deviceLearningSession.propose(DeviceLearningSession.Pending(userText, action, true, contextual = reference)))
                return "I couldn't retain this contextual request for confirmation. Nothing was changed or learned."
            return "Would you like me to ${action.description()}, using your saved assistant memory? Say yes to confirm or no to cancel."
        }
        if (lesson.intent !in setOf("SET_TIMER", "OPEN_APP")) return null
        val action = LearnableDeviceAction.fromLesson(lesson, androidAssistant::resolveLearnableApp)
            ?: return "I couldn't prepare a safe timer or unique installed-app match. Specify a timer duration from 1 second to 24 hours, or the app's exact launcher name. Nothing was changed or learned."
        if (!deviceLearningSession.propose(DeviceLearningSession.Pending(userText, action, lesson.canTeachLocally())))
            return "I couldn't retain this action for confirmation. Nothing was changed or learned."
        return "Would you like me to ${action.description()}? Say yes to confirm or no to cancel."
    }

    private fun routinePreferredUnavailable(steps: List<String>): Boolean = steps.any { step ->
        RoutineSteps.preferredSlot(step) != null && RoutineSteps.preferredAction(step, assistantMemory, androidAssistant::canLaunchApp) == null
    }

    private fun describeRoutineStep(step: String): String {
        val slot = RoutineSteps.preferredSlot(step) ?: return RoutineProposal.describe(step)
        val action = assistantMemory.preferred(slot)
        return "open your current saved preferred $slot app" + (action?.let { " (${it.parameters["label"]})" } ?: " (not saved)")
    }

    private fun routineNameIsLearned(phrase: String, commands: com.assistant.core.learning.ValidatedCommandMemory): Boolean =
        commands.hasSavedPhrase(phrase) || learningStore.snapshot().any { normalize(it.phrase) == normalize(phrase) }

    private fun proposeRoutine(phrase: String, actions: List<String>): String {
        val proposal = routineRegistry.propose(phrase, actions)
            ?: return "That routine uses an unsupported step or reserved name. Supported steps are volume up, volume down, opening the updater, a final timer from 1 second to 24 hours, and opening a saved preferred browser/maps/music app. Nothing was saved or run."
        if (routinePreferredUnavailable(proposal.actions))
            return "The routine needs a saved, launchable preferred browser, maps or music app. Set the preference first, then create the routine again. Nothing was saved or run."
        val commands = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
        if (routineNameIsLearned(proposal.phrase, commands))
            return "That name is already a learned phrase. Choose a different routine name. Nothing was saved or run."
        val existing = routineRegistry.find(proposal.phrase)
        if (existing != null && existing != proposal)
            return "That routine already has different steps. Review 'list routines' and forget the old routine before replacing it. Nothing was saved or run."
        if (!routineConfirmations.propose(proposal)) return "I couldn't retain this routine for approval. Nothing was saved or run."
        return "Review routine '${proposal.phrase}': " + proposal.actions.joinToString(" then ", transform = ::describeRoutineStep) +
            ". Say 'confirm routine' to save or 'cancel routine' to discard. Saving does not run the steps."
    }

    private fun proposePreference(preference: LearnablePreference, learningPhrase: String? = null): String {
        if (!preference.isValid() || androidAssistant.resolveLearnableApp(preference.action.parameters.getValue("label"))?.first != preference.action.parameters.getValue("package"))
            return "I couldn't find that uniquely named installed app. No preference was changed."
        if (!preferenceConfirmations.propose(preference.slot, preference.action, learningPhrase))
            return "I couldn't retain this preference for confirmation. Nothing was saved or learned."
        return "Would you like me to remember ${preference.action.parameters["label"]} as your preferred ${preference.slot} in J.A.R.V.I.S.? This changes J.A.R.V.I.S.'s preference only. Say yes to confirm or no to cancel."
    }

    private fun requestDeviceAction(action: LearnableDeviceAction): Boolean = runCatching {
        if (!action.isValid()) return@runCatching false
        when (action.capabilityId) {
            "timer" -> androidAssistant.setTimer(action.parameters.getValue("seconds").toInt(), showUi = true)
            "open_app" -> androidAssistant.launchApp(action.parameters.getValue("package"))
            else -> false
        }
    }.getOrDefault(false)

    private fun requestContextAction(phrase: String, action: LearnableDeviceAction, detail: String = "", replayed: Boolean = false, contextual: ContextualAction? = null): String {
        val description = "Requested: ${action.description()}${if (detail.isNotBlank()) ", $detail" else ""}."
        return when (deviceLearningSession.requestAndTrack(phrase, action, replayed, contextual) { requestDeviceAction(action) }) {
            DeviceLearningSession.RequestResult.FAILED -> "Android couldn't launch the saved timer/app action. Nothing was added to recent-action memory. Check that Clock or the app is installed."
            DeviceLearningSession.RequestResult.REQUESTED_WITHOUT_TRACKING -> "$description I couldn't retain its outcome check, so no recent-action context was saved."
            DeviceLearningSession.RequestResult.AWAITING_OUTCOME -> "$description After checking it, say 'it worked' to remember this as your last confirmed action, or 'it failed' to " +
                if (replayed) "pause this learned phrase." else "discard the result."
        }
    }

    private fun executeConfirmedVolume(userText: String, lesson: FallbackIntentLesson): String {
        val prefs = systemService.context().getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
        val memory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
        val outcome = VerifiedVolumeLearning.execute(
            userText, lesson, prefs.getBoolean("automatic_command_learning", true),
            adjust = { capability ->
                androidAssistant.adjustVolume(if (capability == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER)
            },
            record = { phrase, capability -> memory.recordSuccess(phrase, capability, VerifiedVolumeLearning.capabilities) }
        ) ?: return "This volume interpretation is unsupported. Nothing was changed or learned."
        resultHistory.record(userText, "change media volume ${lesson.entities["direction"]}",
            if (outcome.executed) IntelligenceResultHistory.Outcome.DEVICE_VERIFIED else IntelligenceResultHistory.Outcome.NOT_VERIFIED,
            if (outcome.learned) "Original phrase saved for offline reuse." else "No new phrase saved.")
        return when {
            !outcome.executed -> "I couldn't verify a media volume change. It may already be at its limit or Android may have blocked it. This request was not learned."
            outcome.learned -> "Media volume changed and verified. Learned this phrase on this device for offline reuse."
            else -> "Media volume changed and verified. This phrase was not saved for learning."
        }
    }

    /**
     * Accept a structured interpretation from the fallback AI. Only approved,
     * high-confidence lessons are persisted; execution remains behind normal
     * JARVIS capability/policy routing.
     */
    fun acceptFallbackLesson(userText: String, lessonJson: String): Boolean {
        val lesson = FallbackIntentLesson.fromJson(lessonJson) ?: return false
        if (!LearningAdmissionPolicy.canRecordWithoutActionResult(lesson)) return false
        val before = learningStore.count()
        learningStore.learn(
            utterance = userText,
            intent = lesson.intent,
            entities = lesson.entities,
            source = "validated_fallback_v${FallbackLessonProtocol.VERSION}"
        )
        return learningStore.count() > before || learningStore.classify(userText)?.let {
            it.intent == lesson.intent && it.confidence == 1.0
        } == true
    }

    @Synchronized
    fun getConversationSnapshot(limit: Int = 8): String {
        return history.filterNot { it.second.startsWith("I don’t understand that locally yet.") || it.second.startsWith("I don't understand that locally yet.") }.takeLast(limit).joinToString(separator = "\n\n") { (user, assistant) ->
            "You: $user\nJ.A.R.V.I.S.: $assistant"
        }
    }

    private fun replyFromResult(userText: String, preface: String, result: ActionResult): HybridAssistantReply {
        val reply = buildString {
            appendLine(preface)
            appendLine(result.message)
            result.output?.let {
                appendLine()
                append(it)
            }
        }.trim()
        // Record only actions that actually succeeded. Never learn a privileged
        // command, and never execute an AI-supplied label directly.
        if (result.success && result.adapterUsed != "ENGINE") {
            val safeIntent = when {
                isTimeRequest(normalize(userText)) -> "GENERAL_QUESTION"
                else -> null
            }
            if (safeIntent != null) {
                learningStore.learn(userText, safeIntent, source = "verified_local_action")
            }
        }
        remember(userText, reply)
        return HybridAssistantReply(reply, result)
    }

    @Synchronized
    private fun remember(userText: String, assistantText: String) {
        history.addLast(userText to assistantText)
        while (history.size > 30) {
            history.removeFirst()
        }
    }

    private fun isTimeRequest(text: String): Boolean {
        val cleaned = text
            .replace(Regex("\\b(?:hey\\s+)?jarvis\\b[,:]?\\s*"), "")
            .trim()
        if (cleaned in setOf(
                "time", "what time", "what time is it", "whats the time",
                "tell me the time", "current time", "what is the time"
            )) return true
        return Regex("\\b(?:what|whats|what is|tell me)\\b.*\\btime\\b").containsMatchIn(cleaned)
    }

    private fun calendarRangeFor(text: String): Pair<Long, Long>? {
        val asksCalendar = listOf(
            "calendar", "appointment", "appointments", "schedule",
            "what do i have", "whats on my", "what is on my", "next event"
        ).any { text.contains(it) }
        if (!asksCalendar) return null

        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        when {
            text.contains("tomorrow") -> {
                start.add(Calendar.DAY_OF_YEAR, 1)
                start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
                end.timeInMillis = start.timeInMillis
                end.add(Calendar.DAY_OF_YEAR, 1)
            }
            text.contains("next") && (text.contains("event") || text.contains("appointment")) -> {
                end.add(Calendar.DAY_OF_YEAR, 30)
            }
            else -> {
                start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
                end.timeInMillis = start.timeInMillis
                end.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return start.timeInMillis to end.timeInMillis
    }

    private fun parseTimerSeconds(text: String): Int? {
        val match = Regex("(?:set|start) (?:a )?timer (?:for )?(\\d+) (second|seconds|minute|minutes|hour|hours)").find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        return when (match.groupValues[2]) {
            "hour", "hours" -> amount * 3600
            "minute", "minutes" -> amount * 60
            else -> amount
        }
    }

    private fun parseCalendarRequest(raw: String): String? {
        val match = Regex("(?i)^(?:create|add|schedule) (?:a |an )?(?:calendar )?(?:event|appointment)(?: called| named)? (.+)$").find(raw.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private fun parseReminderRequest(raw: String): String? {
        val match = Regex("(?i)^(?:remind me to|create (?:a )?reminder(?: to)?|add (?:a )?reminder(?: to)?) (.+)$").find(raw.trim()) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotBlank() }
    }

    private fun resolveConversationContact(target: String): String {
        val normalized = target.trim().lowercase()
        if (normalized in setOf("him", "her", "them", "that person", "the same person")) {
            return lastContactTarget ?: target
        }
        return target
    }

    private fun parseCallTarget(text: String): String? {
        val prefixes = listOf("call ", "dial ", "phone ", "ring ")
        val prefix = prefixes.firstOrNull { text.startsWith(it) } ?: return null
        return text.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
    }

    private fun cleanContactTarget(target: String): String {
        return target.trim()
            .replace(Regex("^(?:contact|my contact|the contact)\\s+"), "")
            .trim()
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            systemService.context(),
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseMessageRequest(raw: String): Pair<String, String>? {
        val match = Regex("(?i)^(?:text|message)\\s+(.+?)\\s+(?:saying|say|that)\\s+(.+)$").find(raw.trim()) ?: return null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    private fun parseAlarm(text: String): Pair<Int, Int>? {
        val match = Regex("(?:set|create) (?:an )?alarm (?:for|at) (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?").find(text) ?: return null
        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        when (match.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    private fun parseAppName(text: String): String? = CommandPhrases.appName(text)

    private fun parseNavigationDestination(text: String): String? {
        CommandPhrases.navigationDestination(text)?.let { return it }
        val prefixes = listOf(
            "navigate to ",
            "directions to ",
            "give me directions to ",
            "get me directions to ",
            "show me directions to ",
            "take me to "
        )
        val prefix = prefixes.firstOrNull { text.startsWith(it) } ?: return null
        return text.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
    }

    private fun containsAny(text: String, vararg options: String): Boolean {
        return options.any { text.contains(it) }
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9_\\-\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractProjectName(normalized: String): String? {
        val named = Regex("(called|named)\\s+([a-z0-9_\\-]+)").find(normalized)?.groupValues?.getOrNull(2)
        if (!named.isNullOrBlank()) return sanitizeProjectName(named)
        return null
    }

    private fun sanitizeProjectName(name: String): String {
        return name.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "assistant_demo" }
    }

    private fun extractShellCommand(rawInput: String): String {
        val normalized = normalize(rawInput)
        return when {
            normalized.contains(".rish") -> rawInput.trim()
            normalized.contains("run shell") -> rawInput.substringAfter("run shell", "").trim().ifBlank { "id" }
            normalized.contains("execute shell") -> rawInput.substringAfter("execute shell", "").trim().ifBlank { "id" }
            normalized.contains("run command") -> rawInput.substringAfter("run command", "").trim().ifBlank { "id" }
            else -> "id"
        }
    }

    private fun looksLikeYes(normalized: String): Boolean {
        return normalized in setOf("yes", "confirm", "confirm reboot", "confirm command", "do it", "sure")
    }
}
