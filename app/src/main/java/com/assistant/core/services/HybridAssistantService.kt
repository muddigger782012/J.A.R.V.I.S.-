package com.assistant.core.services

import com.assistant.core.engine.ActionRegistry
import com.assistant.core.engine.AssistantEngine
import com.assistant.core.engine.IntentClassifier
import com.assistant.core.models.ActionResult
import com.assistant.core.models.CapabilityState
import com.assistant.core.workflow.RoutineExecutionCoordinator
import com.assistant.core.workflow.LocalRoutineWorkflowAccess
import com.assistant.core.workflow.RoutineWorkflowCheckpoint
import com.assistant.core.workflow.RoutineWorkflowStatus
import com.assistant.core.workflow.RoutineStepStatus
import com.assistant.core.workflow.RoutineStepOutcome
import com.assistant.core.workflow.RoutineOutcomeObservation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class HybridAssistantReply(
    val text: String,
    val actionResult: ActionResult? = null,
    val needsInterpretation: Boolean = false
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
    private val personalAssistant = systemService.context().packageName == "com.assistant.core"
    private val conversationMemory = if (personalAssistant) ConversationMemory(systemService.context()) else null
    private val goalPlans = if (personalAssistant) GoalPlanStore(systemService.context()) else null
    private val improvementEvaluation = if (personalAssistant) ImprovementEvaluation(systemService.context()) else null
    private var pendingLearningReset: Boolean = false
    private val routineConfirmations = RoutineConfirmationStore(systemService.context())
    private val routineRegistry = com.assistant.core.learning.ApprovedRoutineRegistry(systemService.context())
    private var pendingShellCommand: String? = null
    private var lastContactTarget: String? = null
    private var personalityTurn = 0
    private val history = ArrayDeque<Pair<String, String>>()
    private var lastResearchQuery: String? = null
    private val androidAssistant = AndroidAssistantService(systemService.context())
    private val learningStore = IntentLearningStore(systemService.context())
    private val directOnlineClient = DirectOnlineClient(systemService.context())
    private val assistantConnections = AssistantConnections(systemService.context())
    private val fallbackAiClient = FallbackAiClient(systemService.context())
    private val volumeConfirmations = VolumeConfirmationStore(systemService.context())
    private val deviceLearningSession = DeviceLearningSession(systemService.context())
    private val assistantMemory = AssistantMemory(systemService.context())
    private val trialEvidence = CapabilityTrialEvidence(systemService.context().filesDir)
    private val resultHistory = IntelligenceResultHistory(systemService.context())
    private val preferenceConfirmations = PreferenceConfirmationStore(systemService.context())
    @Volatile private var sessionRoutineId: String? = null
    private val routineCoordinator: RoutineExecutionCoordinator by lazy {
        RoutineExecutionCoordinator(access = LocalRoutineWorkflowAccess(systemService.context()), approvedRoutine = { key ->
            val goal = goalPlans?.find(key)
            val routine = routineRegistry.find(key)
            if (goal != null && routine != null) null else goal ?: routine
        },
            execute = { checkpoint, step ->
                RoutineStepOutcome.fromRoutineStatus(executeRoutineStep(checkpoint.phrase, step.actionId, step.target))
            }, resolvePreferred = assistantMemory::preferred, canLaunchApp = androidAssistant::canLaunchApp)
    }

    fun handleUserInput(rawInput: String): HybridAssistantReply {
        val userInput = rawInput.trim()
        val geminiFailure = GeminiFailureMemory(systemService.context())
        if (geminiFailure.shouldAnswer(userInput)) {
            routineConfirmations.consume("")
            volumeConfirmations.consume("")
            deviceLearningSession.consume(userInput)
            preferenceConfirmations.consume("")
            pendingPrompt = PendingPrompt.NONE
            pendingShellCommand = null
            pendingLearningReset = false
            return HybridAssistantReply(geminiFailure.explain())
        }
        // Run before pending prompts, learned aliases, permissive local parsers or AI.
        // A misheard control must not execute the timer/app embedded in its description.
        if (CapabilityDiscovery.needsClarification(userInput)) {
            routineConfirmations.consume("")
            volumeConfirmations.consume("")
            deviceLearningSession.consume("")
            preferenceConfirmations.consume("")
            pendingPrompt = PendingPrompt.NONE
            pendingShellCommand = null
            pendingLearningReset = false
            return HybridAssistantReply(CapabilityDiscovery.clarification())
        }
        val normalized = normalize(userInput)
        val engineIntent = IntentClassifier().classify(userInput)
        if (personalAssistant) {
            ConversationMemoryCommands.parse(userInput)?.let { command ->
                runCatching { goalPlans?.discardPending() }
                routineConfirmations.consume("")
                volumeConfirmations.consume("")
                deviceLearningSession.consume("")
                preferenceConfirmations.consume("")
                pendingPrompt = PendingPrompt.NONE
                pendingShellCommand = null
                pendingLearningReset = false
                val response = ConversationMemoryCommands.reply(userInput, conversationMemory!!)!!
                if (command == ConversationMemoryCommands.Command.CLEAR) synchronized(this) { history.clear() }
                return HybridAssistantReply(response)
            }
            SupervisedImprovement.parseCommand(userInput)?.let { command ->
                runCatching { goalPlans?.discardPending() }
                routineConfirmations.consume("")
                volumeConfirmations.consume("")
                deviceLearningSession.consume("")
                preferenceConfirmations.consume("")
                pendingPrompt = PendingPrompt.NONE
                pendingShellCommand = null
                pendingLearningReset = false
                return HybridAssistantReply(SupervisedImprovement.render(SupervisedImprovement.generate(resultHistory.snapshot()), command))
            }
            if (ImprovementEvaluation.isManagementInput(userInput)) {
                clearPendingActionPrompts()
                runCatching { goalPlans?.discardPending() }
                val command = ImprovementEvaluation.command(userInput)
                return HybridAssistantReply(if (command == null)
                    "Choose a scenario from 'review improvement evaluation', then record its checked result as passed, failed or unclear."
                    else runCatching { improvementEvaluation!!.reply(command) }.getOrElse {
                        "Improvement evaluation storage could not be accessed. Review local storage access or explicitly clear improvement evaluation to reset it. No test or code change was performed."
                    })
            }
            PersonalRoutineCommands.parse(userInput)?.let { command ->
                runCatching { goalPlans?.discardPending() }
                routineConfirmations.consume("")
                volumeConfirmations.consume("")
                deviceLearningSession.consume("")
                preferenceConfirmations.consume("")
                pendingPrompt = PendingPrompt.NONE
                pendingShellCommand = null
                pendingLearningReset = false
                return HybridAssistantReply(controlRoutineExecution(command))
            }
            handleGoalInput(userInput)?.let { return HybridAssistantReply(it) }
        }
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
                routineAppUnavailable(proposal.actions) -> "The routine's app is no longer available or launchable. Restore the app or preference and review the routine again. Nothing was saved or run."
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
            return HybridAssistantReply("I can help prepare a routine from supported actions. Its exact steps need review before saving; nothing has run.", needsInterpretation = true)
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
            val response = if (resolution.approved) executeConfirmedVolume(resolution.pending.phrase, resolution.pending.lesson, resolution.pending.explicitTeaching, resolution.pending.requestId)
                else "Media volume request cancelled. Nothing was changed or learned."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        deviceLearningSession.consume(userInput) { label ->
            androidAssistant.resolveLearnableApp(label)?.takeIf { androidAssistant.canLaunchApp(it.first) }
        }?.let { resolution ->
            val pending = resolution.pending
            val response = when {
                resolution.needsClarification -> {
                    if (!resolution.reminder) resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.UNCLEAR,
                        "Nothing learned or marked failed. The same outcome check remains pending.")
                    if (resolution.correction != DeviceLearningSession.Correction.NONE) when (resolution.correction) {
                        DeviceLearningSession.Correction.UPDATED -> "Revised the proposal to ${pending.action.description()}. Say yes to request it or no to cancel. Nothing has run or been learned yet." +
                            if (pending.explicitTeaching) " Your chosen alias will be saved only after you check the result and say 'it worked'." else " The original request wording won't be learned from this edited proposal."
                        DeviceLearningSession.Correction.ALREADY_REQUESTED -> "That action has already been requested. This follow-up cannot replace an opened app or change a running timer. No second action was requested. Check the existing result and say 'it worked' or 'it failed', or state a new request."
                        DeviceLearningSession.Correction.TARGET_LOCKED -> "This check isn't an editable proposal for that kind of action. State a new app or timer request. Nothing was changed; the original check remains pending."
                        DeviceLearningSession.Correction.INVALID_DURATION -> "Use a duration from 1 second to 24 hours, in digits or English number words—for example, 'make that 10 minutes'. The original proposal remains pending; nothing was changed."
                        DeviceLearningSession.Correction.INVALID_APP -> "I couldn't resolve one launchable installed app with that exact name. The original proposal remains pending; nothing was opened or changed."
                        DeviceLearningSession.Correction.SAVE_FAILED -> "I couldn't retain the revised target. The original proposal remains pending; nothing was changed or run."
                        DeviceLearningSession.Correction.NONE -> ""
                    } else if (!pending.awaitingOutcome)
                        "The pending request is to ${pending.action.description()}, from '${pending.phrase}'. Say yes to request it or no to cancel. Nothing has run or been learned yet."
                    else "The pending outcome check is for ${pending.action.description()}, from '${pending.phrase}'. Check the result, then say 'it worked' or 'it failed'. I'm keeping this check pending; nothing was learned or marked failed."
                }
                !resolution.accepted -> {
                    if (pending.awaitingOutcome) settleTrackedRoutine(pending, success = false)
                    if (pending.replayed && pending.awaitingOutcome) {
                        val memory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
                        val paused = VerifiedFailureFeedback.pause(resolution, memory)
                        resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.USER_FAILED,
                            if (paused) "Learned phrase paused. Nothing learned." else "Could not pause matching phrase. Nothing learned.")
                        if (paused)
                            "You reported it failed. Paused this learned phrase on this device. Review it with 'show learned commands'. Go online and verify a new successful lesson to restore the same meaning, or forget the phrase before teaching a different meaning."
                        else "You reported it failed, but the matching learned phrase could not be paused. Its storage may have changed or failed. Check 'show learned commands'; nothing was learned."
                    } else {
                        if (pending.awaitingOutcome) {
                            resultHistory.record(pending.phrase, pending.action.description(), IntelligenceResultHistory.Outcome.USER_FAILED, "Nothing learned; recent confirmed action unchanged.")
                            trialEvidence.record(pending.requestId, pending.action, CapabilityTrialEvidence.Outcome.USER_FAILED)
                        }
                        "Request cancelled or reported unsuccessful. Nothing was learned."
                    }
                }
                pending.awaitingOutcome -> {
                    settleTrackedRoutine(pending, success = true)
                    val trialSaved = pending.requestId == null || trialEvidence.record(pending.requestId, pending.action, CapabilityTrialEvidence.Outcome.USER_CONFIRMED)
                    val contextSaved = assistantMemory.recordConfirmed(pending.action)
                    val prefs = systemService.context().getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
                    val memory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
                    val learned = pending.shouldLearn(prefs.getBoolean("automatic_command_learning", true)) &&
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
                    lessonReply + (if (contextSaved || !pending.learnable) "" else " Recent-action context could not be saved.") +
                        if (trialSaved) "" else " Linked trial evidence could not be saved; review the request before relying on its status."
                }
                !requestDeviceAction(pending.action) -> {
                    trialEvidence.record(pending.requestId, pending.action, CapabilityTrialEvidence.Outcome.REQUEST_FAILED)
                    "Android couldn't launch the requested action. Nothing was learned."
                }
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
        if (PendingAppCorrection.isRequest(userInput)) {
            return HybridAssistantReply("No app proposal is awaiting a target edit. State a new app request first. Nothing was opened or changed.")
        }
        if (PendingTimerCorrection.isRequest(userInput)) {
            return HybridAssistantReply("No timer proposal is awaiting a duration edit. State a new timer request first. Nothing was changed or run.")
        }
        if (DeviceLearningSession.isOutcome(userInput)) {
            val response = "No timer or app request is awaiting outcome confirmation. Run the action first, then report 'it worked' or 'it failed'. Nothing was added to memory."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (ConfirmationWords.isConfirmation(userInput)) {
            pendingLearningReset = false
            return HybridAssistantReply("No device action is awaiting approval. What would you like to continue with?", needsInterpretation = true)
        }
        // Discovery controls are local. Requested features never enter AI history or learning.
        if (CapabilityDiscovery.isHelp(userInput)) return HybridAssistantReply(CapabilityDiscovery.help())
        val capabilityRequests = CapabilityRequestStore(systemService.context())
        CapabilityDiscovery.page(userInput)?.let { page ->
            return HybridAssistantReply(CapabilityDiscovery.render(page, capabilityRequests.snapshot()) { entry ->
                trialEvidence.latest(entry)?.let { "last linked trial: ${it.outcome.label}; tested ${CapabilityTrialEvidence.description(it.action)}; review to compare the requested target" }.orEmpty()
            })
        }
        CapabilityDiscovery.review(userInput)?.let { description ->
            val entry = capabilityRequests.findReference(description)
                ?: return HybridAssistantReply("No saved capability request matched. Use its exact description or 'number 1' from the current 'show capability requests' list. Nothing was run or approved.")
            val assessment = CapabilityRequestReview.assess(entry.text) { label ->
                androidAssistant.resolveLearnableApp(label)?.takeIf { androidAssistant.canLaunchApp(it.first) }
            }
            return HybridAssistantReply(CapabilityRequestReview.render(entry, assessment) + "\n\n" + trialEvidence.render(entry, assessment.definition))
        }
        CapabilityDiscovery.trial(userInput)?.let { description ->
            val entry = capabilityRequests.findReference(description)
                ?: return HybridAssistantReply("No saved capability request matched. Use its exact description or 'number 1' from the current 'show capability requests' list. Nothing was run or approved.")
            return HybridAssistantReply(CapabilityRequestTrial.prepare(entry,
                resolveApp = { label -> androidAssistant.resolveLearnableApp(label)?.takeIf { androidAssistant.canLaunchApp(it.first) } },
                proposeDevice = deviceLearningSession::propose,
                proposeVolume = { phrase, lesson -> volumeConfirmations.propose(phrase, lesson) },
                proposeLinkedVolume = { phrase, lesson, id -> volumeConfirmations.propose(phrase, lesson, requestId = id) }))
        }
        CapabilityDiscovery.teaching(userInput)?.let { teaching ->
            if (!RoutineProposal.isUsableName(teaching.alias))
                return HybridAssistantReply("Choose a short non-reserved alias, such as work break. Nothing was run or learned.")
            if (routineNameIsLearned(teaching.alias, deviceMemory) || routineRegistry.find(teaching.alias) != null)
                return HybridAssistantReply("That alias is already saved. Review and forget its existing command or routine before teaching another meaning. Nothing was changed or learned.")
            val entry = capabilityRequests.findReference(teaching.reference)
                ?: return HybridAssistantReply("No saved capability request matched that number. Check 'show capability requests'. Nothing was run or approved.")
            return HybridAssistantReply(CapabilityRequestTrial.prepare(entry,
                resolveApp = { label -> androidAssistant.resolveLearnableApp(label)?.takeIf { androidAssistant.canLaunchApp(it.first) } },
                proposeDevice = deviceLearningSession::propose,
                proposeVolume = { phrase, lesson -> volumeConfirmations.propose(phrase, lesson, explicitTeaching = true) },
                alias = teaching.alias,
                proposeLinkedVolume = { phrase, lesson, id -> volumeConfirmations.propose(phrase, lesson, explicitTeaching = true, requestId = id) }))
        }
        CapabilityDiscovery.request(userInput)?.let { description ->
            val response = when (capabilityRequests.add(description)) {
                CapabilityRequestStore.Result.ADDED -> "Recorded that capability request on this device for review. It is not a new installed capability; nothing was run or learned. Say 'show capability requests' to review it."
                CapabilityRequestStore.Result.DUPLICATE -> "That capability request is already saved. Nothing was installed, run or learned."
                CapabilityRequestStore.Result.FULL -> "The local list holds ${CapabilityRequestStore.LIMIT} requests. Review and forget an old request before adding another; nothing was replaced or run."
                CapabilityRequestStore.Result.INVALID -> "Use a feature description from 1 to ${CapabilityRequestStore.MAX_TEXT} characters. Nothing was recorded or run."
                CapabilityRequestStore.Result.FAILED -> "Could not save the capability request. Nothing was installed, run or learned."
            }
            return HybridAssistantReply(response)
        }
        CapabilityDiscovery.forget(userInput)?.let { description ->
            return HybridAssistantReply(if (capabilityRequests.forget(description)) "Removed that capability request. Saved commands, preferences and routines remain available."
                else "No matching request was removed. Use its exact description from 'show capability requests'.")
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
            val response = "This learned phrase is paused after an unsuccessful result. I can help review its meaning; a new successful, verified lesson is needed before reuse."
            return HybridAssistantReply(response, needsInterpretation = true)
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
            if (personalAssistant) {
                val response = runTrackedRoutine(routine.phrase)
                remember(userInput, response)
                return HybridAssistantReply(response)
            }
            var requestedTarget: LearnableDeviceAction? = null
            val outcome = RoutineExecution.runWithPreferred(routine.actions, assistantMemory, androidAssistant::canLaunchApp) { step, preferred ->
                val target = preferred ?: RoutineSteps.timerAction(step)
                if (target != null) requestedTarget = target
                executeRoutineStep(routine.phrase, step, target)
            }
            if (outcome.unavailableApp)
                return HybridAssistantReply("The routine's approved app is no longer launchable. No routine steps were run. Restore the app, or forget and recreate the routine for a different target.")
            if (outcome.unavailablePreferred)
                return HybridAssistantReply("The routine's preferred app is missing or cannot be launched. Restore the saved preference first. No routine steps were run.")
            val results = outcome.steps.joinToString("; ") { (step, status) ->
                (if (RoutineSteps.preferredSlot(step) != null) requestedTarget?.description() ?: RoutineProposal.describe(step) else RoutineProposal.describe(step)) + ": " + when (status) {
                    RoutineExecution.Status.VERIFIED -> if (RoutineSteps.volumePercent(step) != null) "target level verified" else "changed and verified"
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

        if (ExplicitCommandTeaching.isRequest(userInput)) {
            val teaching = ExplicitCommandTeaching.parse(userInput)
            val response = when {
                teaching == null -> "Choose a short non-reserved phrase and one supported target: a timer, installed app, or media-volume percentage. Nothing was changed or learned."
                routineNameIsLearned(teaching.phrase, volumeMemory) || routineRegistry.find(teaching.phrase) != null ->
                    "That phrase is already saved. Review and forget its existing command or routine before teaching another meaning. Nothing was changed or learned."
                else -> {
                    val percent = ExactVolumeLearning.parse(teaching.target)
                    if (percent != null) {
                        val lesson = FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume", "percent" to percent.toString()), learnable = true)
                        if (volumeConfirmations.propose(teaching.phrase, lesson, explicitTeaching = true))
                            "Review: '${teaching.phrase}' will set media volume to $percent%, using the nearest supported level. Say yes to execute and verify before saving, or no to cancel."
                        else "Could not retain the teaching proposal. Nothing was changed or learned."
                    } else {
                        val action = ExplicitCommandTeaching.resolveDevice(teaching.target, androidAssistant::resolveLearnableApp)
                        if (action == null || (action.capabilityId == "open_app" && !androidAssistant.canLaunchApp(action.parameters.getValue("package"))))
                            "I couldn't match one supported timer or uniquely named installed app. Specify a timer duration from 1 second to 24 hours or an exact app launcher name. Nothing was changed or learned."
                        else if (deviceLearningSession.propose(DeviceLearningSession.Pending(teaching.phrase, action, learnable = true, explicitTeaching = true)))
                            "Review: '${teaching.phrase}' will ${action.description()}. Say yes to request it or no to cancel. After checking the result, say 'it worked' to save the phrase. Nothing is learned yet."
                        else "Could not retain the teaching proposal. Nothing was changed or learned."
                    }
                }
            }
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        ExactVolumeLearning.parse(userInput)?.let { percent ->
            val response = executeExactVolume(userInput, percent, learn = false)
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        volumeMemory.match(userInput, ExactVolumeLearning.capabilities)?.let { learned ->
            val percent = ExactVolumeLearning.fromParameters(learned.parameters)
            val response = if (percent == null) "This saved volume level is invalid. Forget the phrase before teaching it again."
                else executeExactVolume(userInput, percent, learn = false, replayed = true)
            remember(userInput, response)
            return HybridAssistantReply(response)
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

        AssistantPersonalityPreferences.load(systemService.context()).localReply(userInput, personalityTurn)?.let { response ->
            personalityTurn++
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (normalized in setOf("what can you do", "help", "capabilities", "features", "show help", "show capabilities", "show features", "what are your capabilities", "what are your features")) {
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

        parseTimerSeconds(userInput)?.let { seconds ->
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
            val contactResult = if (directNumber == null) androidAssistant.resolveContact(target) else null
            val number = directNumber ?: contactResult?.number
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
                number == null && contactResult?.status == ContactNameMatcher.MatchStatus.AMBIGUOUS -> "I found more than one possible phone number or contact for $target. Please specify the exact contact and number in your Contacts app before calling."
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
            val response = if (ok) "Requested the alarm in Android's Clock app. Check the time there." else "I couldn't open the Android alarm service. Check that a Clock app is installed."
            remember(userInput, response); return HybridAssistantReply(response)
        }

        if (CommandPhrases.isAmazonMusicRequest(userInput)) {
            val response = androidAssistant.amazonMusic(Regex("(?i)\\bplay\\b").containsMatchIn(userInput))
            remember(userInput, response); return HybridAssistantReply(response)
        }

        parseAppName(normalized)?.let { appName ->
            val target = androidAssistant.resolveLearnableApp(appName)
            if (target == null) return HybridAssistantReply("No unique installed app matched $appName. I can help identify the app or another way to reach your objective.", needsInterpretation = true)
            val response = requestContextAction(userInput, LearnableDeviceAction("open_app", mapOf("package" to target.first, "label" to target.second)))
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
        if (ConversationPolicy.isScreenQuery(userInput)) {
            return replyFromResult(userInput, "Reading the current screen.", assistantEngine.executeAction(actionRegistry.readScreenRequest()))
        }

        // Return a search card in the conversation instead of interrupting chat
        // by opening another activity. The user opens the embedded browser by
        // tapping the card; the same response works for voice and text input.
        (WebSearchCommand.videoQuery(userInput)
            ?: WebSearchCommand.videoFollowupQuery(userInput, conversationTurns().lastOrNull()?.first))?.let { query ->
            val url = android.net.Uri.parse("https://www.google.com/search").buildUpon()
                .appendQueryParameter("tbm", "vid").appendQueryParameter("q", query).build()
            val response = "Video search ready: $query\n$url"
            remember(userInput, response)
            return HybridAssistantReply(response)
        }
        if (WebSearchCommand.isVideoLinkFollowup(userInput)) {
            val response = "I can search for video links. Which video title or subject should I look up? Searching does not start playback."
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

        (WebSearchCommand.query(userInput) ?: WebSearchCommand.musicQuery(userInput) ?: WebSearchCommand.titledMusicQuery(userInput) ?: WebSearchCommand.currentInfoQuery(userInput)
            ?: WebSearchCommand.downloadFollowupQuery(userInput, conversationTurns().lastOrNull()?.first))?.let { query ->
            val url = android.net.Uri.parse("https://www.google.com/search")
                .buildUpon().appendQueryParameter("q", query).build()
            val response = "Web search ready: $query\\n$url".replace("\\n", "\n")
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (WebSearchCommand.isDownloadSearchFollowup(userInput)) {
            val response = "I can search online for public download pages and links. Tell me what you want to find, for example 'find me downloadable offline voice models'. Finding a link does not download or install it."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (engineIntent == ActionRegistry.CREATE_PROJECT) {
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

        if (engineIntent == ActionRegistry.RUN_SHELL) {
            val command = extractShellCommand(userInput)
            pendingShellCommand = command
            pendingPrompt = PendingPrompt.SHELL_CONFIRMATION
            val response = "This privileged command requires a separate confirmation before execution."
            remember(userInput, response)
            return HybridAssistantReply(response)
        }

        if (engineIntent == ActionRegistry.SHOW_STATUS) {
            val result = assistantEngine.executeAction(actionRegistry.showStatusRequest())
            return replyFromResult(
                userText = userInput,
                preface = "Here is your status summary.",
                result = result
            )
        }

        if (engineIntent == ActionRegistry.REBOOT_DEVICE) {
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
            // This is routing state, not a user-visible refusal or a completed conversation turn.
            return HybridAssistantReply("I can help work through that objective using the available tools and conversation.",
                needsInterpretation = true)
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
        if (CapabilityDiscovery.needsClarification(rawInput)) {
            callback(handleUserInput(rawInput))
            return
        }
        AssistantConnectionProtocol.parse(rawInput)?.let { request ->
            routineConfirmations.consume("")
            Thread {
                val response = assistantConnections.ask(request)
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        // Resolve a short affirmative reply against an immediately preceding manual-verification offer.
        // Never infer a serial number or an equipment-specific procedure from the offer.
        val priorTurn = conversationTurns().lastOrNull()
        val manualConfirmation = rawInput.trim().lowercase() in setOf(
            "yes", "yes please", "please", "sure", "go ahead", "please do", "do it"
        ) && priorTurn?.second?.let { reply ->
            reply.contains("shall i verify", ignoreCase = true) &&
                reply.contains("manual", ignoreCase = true)
        } == true
        val resolvedInput = if (manualConfirmation) {
            "Find and verify the official service manual for the equipment in the preceding conversation. " +
                "Check any serial-number applicability against official documentation; if no serial number was provided, ask for it. " +
                "Do not invent manual links or equipment-specific service procedures."
        } else rawInput
        val local = handleUserInput(resolvedInput)
        val manualRetrieval = Regex(
            "(?i)\\b(?:technical|service|repair|shop|workshop|operator|operators|operator[’']s)\\s+manual\\b|technical documentation|service documentation"
        ).containsMatchIn(rawInput) && listOf("pull up", "find", "locate", "get me", "show me", "look up", "search", "retrieve").any {
            rawInput.contains(it, ignoreCase = true)
        }
        val unsupportedManualReply = manualRetrieval &&
            local.actionResult == null &&
            (local.text.contains("cannot access", ignoreCase = true) ||
                local.text.contains("cannot retrieve", ignoreCase = true))
        val unresolved = local.needsInterpretation || unsupportedManualReply || manualConfirmation || (manualRetrieval && local.actionResult == null)
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
            else ConversationPolicy.fallback(rawInput, ConversationPolicy.Obstacle.OFFLINE)
            remember(rawInput, response)
            callback(HybridAssistantReply(response, local.actionResult))
            return
        }
        if (directOnlineClient.hasGeminiKey()) {
            val previousResearch = synchronized(this) { lastResearchQuery }
            val researchDecision = ConversationalResearchPolicy.decide(resolvedInput, online = true,
                previousResearchQuery = previousResearch)
            if (researchDecision == ConversationalResearchPolicy.Decision.RESEARCH &&
                !RoutineProposal.isCreationRequest(rawInput)) {
                Thread {
                    val isManualRequest = Regex(
                        "(?i)\\b(?:technical|service|repair|shop|workshop|operator|operators|operator[’']s)\\s+manual\\b|technical documentation|service documentation"
                    ).containsMatchIn(resolvedInput)
                    val recentContext = if (isManualRequest && (previousResearch == null || manualConfirmation))
                        getConversationSnapshot(limit = 5).take(1200) else ""
                    val contextualQuery = when {
                        isManualRequest && recentContext.isNotBlank() ->
                            "Using the conversation context below, identify the equipment and find its official technical manual or authorized documentation. Provide verifiable links; do not invent procedures or claim access to restricted manuals.\n$recentContext\nRequest: $resolvedInput"
                        previousResearch != null && resolvedInput.length < 100 &&
                            !resolvedInput.contains("look up", ignoreCase = true) &&
                            !resolvedInput.contains("search for", ignoreCase = true) ->
                            "$previousResearch. Follow-up question: $resolvedInput"
                        else -> resolvedInput
                    }
                    val searchAttempt = runCatching { directOnlineClient.groundedSearch(contextualQuery, "web") }
                    val grounded = searchAttempt.getOrNull()
                    val valid = grounded?.takeIf { result ->
                        result.query == contextualQuery && !result.answer.isNullOrBlank() &&
                            result.items.any { item -> item.title.isNotBlank() && ChatSearchResults.safeUrl(item.url) }
                    }
                    if (valid != null) {
                        val answer = valid.answer.orEmpty()
                        remember(rawInput, answer)
                        synchronized(this) { lastResearchQuery = contextualQuery.take(300) }
                        callback(HybridAssistantReply(ChatSearchResults.encode(rawInput, valid.items,
                            answer, valid.searchSuggestions, retrievedAt = System.currentTimeMillis())))
                    } else {
                        if (isManualRequest) {
                            val diagnostic = when ((searchAttempt.exceptionOrNull() as? SearchProviderFailure)?.kind) {
                                SearchProviderFailure.Kind.RATE_LIMIT -> "The search provider reported a rate or quota limit."
                                SearchProviderFailure.Kind.AUTHENTICATION -> "The search provider rejected authentication. Check the Gemini API key."
                                SearchProviderFailure.Kind.UNAVAILABLE -> "The search provider is unavailable or rejected the configured model. Check the selected Gemini model."
                                null -> if (searchAttempt.isFailure) "The online search request failed. Check network access and Gemini settings."
                                    else "The search returned no verifiable source citations. Try a different Gemini model or a more specific manual search."
                            }
                            val response = "$diagnostic I won't invent a manual link or equipment-specific procedure."
                            remember(rawInput, response)
                            callback(HybridAssistantReply(response))
                            return@Thread
                        }
                        val cloud = onlineInterpretation(rawInput)
                        val response = proposeCloudAction(rawInput, cloud.lessonJson) ?: cloud.reply
                        remember(rawInput, response)
                        callback(HybridAssistantReply(response))
                    }
                }.start()
                return
            }
            Thread {
                val cloud = onlineInterpretation(rawInput)
                val response = proposeCloudAction(rawInput, cloud.lessonJson) ?: cloud.reply
                remember(rawInput, response)
                callback(HybridAssistantReply(response))
            }.start()
            return
        }
        if (!fallbackAiClient.isConfigured()) {
            val response = ConversationPolicy.fallback(rawInput, ConversationPolicy.Obstacle.NOT_CONFIGURED)
            remember(rawInput, response)
            callback(HybridAssistantReply(response, local.actionResult))
            return
        }
        Thread {
            val cloud = fallbackAiClient.ask(rawInput, capabilityContext = ContextualAction.aiContext(assistantMemory), conversation = conversationTurns())
            if (cloud == null) {
                val response = ConversationPolicy.fallback(rawInput, ConversationPolicy.Obstacle.PROVIDER_FAILED)
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

    private fun onlineInterpretation(userText: String): FallbackAiResult {
        val primary = { directOnlineClient.assistant(userText, getConversationSnapshot(), ContextualAction.aiContext(assistantMemory)) }
        val alternative: (() -> FallbackAiResult?)? = if (personalAssistant && fallbackAiClient.isConfigured()) {
            { fallbackAiClient.ask(userText, capabilityContext = ContextualAction.aiContext(assistantMemory), conversation = conversationTurns()) }
        } else null
        val result = InterpretationFallback.ask(primary, alternative)
        return if (result.providerFailed) result.copy(reply = result.reply + " " +
            ConversationPolicy.fallback(userText, ConversationPolicy.Obstacle.PROVIDER_FAILED), lessonJson = null)
        else result
    }

    private fun runTrackedRoutine(phrase: String): String =
        runCatching {
            val result = routineCoordinator.invoke(phrase, java.util.UUID.randomUUID().toString())
            if (!result.busy) sessionRoutineId = result.checkpoint?.id
            renderRoutineControl(result)
        }.getOrElse { "This routine could not start safely. Check its saved apps and preferences, then review 'routine execution status'. No automatic retry was made." }

    private fun clearPendingActionPrompts() {
        pendingPrompt = PendingPrompt.NONE
        pendingShellCommand = null
        pendingLearningReset = false
        routineConfirmations.consume("")
        volumeConfirmations.consume("")
        deviceLearningSession.consume("")
        preferenceConfirmations.consume("")
    }

    private fun handleGoalInput(input: String): String? {
        val plans = goalPlans ?: return null
        GoalPlanning.command(input)?.let { command ->
            return runCatching {
                clearPendingActionPrompts()
                val pending = plans.pending()
                when (command) {
                    GoalPlanning.Command.REVIEW, GoalPlanning.Command.STATUS -> if (pending != null) GoalPlanning.review(pending)
                        else if (plans.durationDraft() != null) "How long should the focus session last? Give a duration such as '25 minutes'; I will show the proposed steps before execution."
                        else renderCurrentGoal()
                    GoalPlanning.Command.APPROVE -> {
                        val result = if (pending != null) routineCoordinator.invokePrepared(pending.key, java.util.UUID.randomUUID().toString()) { plans.approve(pending) }
                        else {
                            val checkpoint = routineCoordinator.status().checkpoint
                            val canApprove = checkpoint != null && (checkpoint.status == RoutineWorkflowStatus.AWAITING_APPROVAL ||
                                (checkpoint.status == RoutineWorkflowStatus.FAILED && !checkpoint.approved && !checkpoint.cancellationRequested && checkpoint.steps.all { it.attempts == 0 }))
                            if (checkpoint == null || plans.find(checkpoint.phrase) == null || !canApprove)
                                return@runCatching "No current goal plan is awaiting approval. Ask for a focus, music or navigation session and review its exact steps first."
                            routineCoordinator.approvePrepared(checkpoint.id, checkpoint.steps.map { it.step })
                        }
                        if (!result.busy) sessionRoutineId = result.checkpoint?.id
                        renderRoutineControl(result)
                    }
                    GoalPlanning.Command.CANCEL -> if (pending != null || plans.durationDraft() != null) {
                        if (plans.discardPending()) "Discarded the pending goal plan. No steps were run for that proposal."
                        else "Could not discard the goal proposal; do not approve it until its status is checked."
                    } else controlCurrentGoal(PersonalRoutineCommands.Command.CANCEL)
                    GoalPlanning.Command.RESUME -> controlCurrentGoal(PersonalRoutineCommands.Command.RESUME)
                    GoalPlanning.Command.WORKED -> controlCurrentGoal(PersonalRoutineCommands.Command.WORKED)
                    GoalPlanning.Command.FAILED -> controlCurrentGoal(PersonalRoutineCommands.Command.FAILED)
                }
            }.getOrElse { "The goal plan could not be accessed or approved safely. Review 'goal plan status' and local storage access. No automatic retry was made." }
        }
        val request = GoalPlanning.parse(input) ?: SpokenTimerDuration.seconds(input)?.let { seconds ->
            runCatching { plans.durationDraft()?.copy(seconds = seconds) }.getOrNull()
        }
        if (request == null) {
            // A new topic invalidates a pending review; an unrelated yes cannot revive it.
            runCatching { plans.discardPending() }
            return null
        }
        return runCatching {
            clearPendingActionPrompts()
            check(plans.discardPending()) { "The previous goal review could not be invalidated." }
            if (request.volume !in 0..100) return@runCatching "Choose a media-volume percentage from 0 to 100 for this goal. Nothing has run."
            if (request.kind == GoalPlanning.Kind.FOCUS && (request.seconds == null || request.invalidDuration)) {
                if (!plans.askDuration(request)) return@runCatching "Could not retain this focus request. Restate its duration and volume when local storage is available."
                return@runCatching "How long should the focus session last? Give a duration from one second to 24 hours, such as '25 minutes'. I propose media volume ${request.volume}%; the exact steps still need review and approval."
            }
            val plan = GoalPlanning.plan(request, assistantMemory::preferred, androidAssistant::resolveLearnableApp, androidAssistant::canLaunchApp)
                ?: return@runCatching "I can prepare the volume step and app launch once one launchable target is available. Save a preferred ${if (request.kind == GoalPlanning.Kind.MUSIC) "music" else "maps"} app, or repeat this goal using its exact installed app name. Nothing has run."
            if (plans.propose(plan)) GoalPlanning.review(plan) else "Could not retain this goal for review. Nothing has run."
        }.getOrElse { "This goal could not be prepared safely. Check its duration, installed app or saved preference and local storage, then request the plan again. Nothing has run." }
    }

    private fun renderCurrentGoal(): String {
        val result = routineCoordinator.status()
        val checkpoint = result.checkpoint
        return if (checkpoint != null && goalPlans?.find(checkpoint.phrase) != null) renderRoutineControl(result)
        else "No goal execution is tracked. Try 'help me focus for 25 minutes', 'prepare a music session' or 'prepare a navigation session'. I will propose exact steps before requesting approval."
    }

    private fun controlCurrentGoal(command: PersonalRoutineCommands.Command): String {
        val current = routineCoordinator.status().checkpoint
        if (current == null || goalPlans?.find(current.phrase) == null) return "No goal execution is tracked. Review a proposed goal plan first."
        val result = when (command) {
            PersonalRoutineCommands.Command.CANCEL -> routineCoordinator.cancel(current.id)
            PersonalRoutineCommands.Command.RESUME -> routineCoordinator.resume(current.id)
            PersonalRoutineCommands.Command.WORKED -> routineCoordinator.report(RoutineOutcomeObservation.USER_CONFIRMED, current.id)
            PersonalRoutineCommands.Command.FAILED -> routineCoordinator.report(RoutineOutcomeObservation.FAILED, current.id)
            PersonalRoutineCommands.Command.STATUS -> routineCoordinator.status()
        }
        return renderRoutineControl(result)
    }

    private fun controlRoutineExecution(command: PersonalRoutineCommands.Command): String {
        return runCatching {
            val result = when (command) {
                PersonalRoutineCommands.Command.STATUS -> routineCoordinator.status()
                PersonalRoutineCommands.Command.RESUME -> routineCoordinator.resume()
                PersonalRoutineCommands.Command.CANCEL -> routineCoordinator.cancel()
                PersonalRoutineCommands.Command.WORKED -> routineCoordinator.report(RoutineOutcomeObservation.USER_CONFIRMED)
                PersonalRoutineCommands.Command.FAILED -> routineCoordinator.report(RoutineOutcomeObservation.FAILED)
            }
            renderRoutineControl(result)
        }.getOrElse { "Routine checkpoints could not be accessed safely. No automatic retry was made. Restore local storage access and review 'routine execution status'." }
    }

    private fun renderRoutineControl(result: RoutineExecutionCoordinator.Result): String {
        val status = result.checkpoint?.let(::renderRoutineExecution)
            ?: "No routine execution is tracked on this device. Invoke a saved, approved routine first."
        return listOf(status, result.message).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun renderRoutineExecution(checkpoint: RoutineWorkflowCheckpoint): String {
        val goal = runCatching { goalPlans?.current()?.takeIf { it.key == checkpoint.phrase } }.getOrNull()
        val resumeCommand = if (goal == null) "resume routine execution" else "resume goal plan"
        val outcomeCommand = if (goal == null) "the routine" else "the goal"
        val steps = checkpoint.steps.joinToString("; ") { state ->
            val description = state.step.target?.description() ?: RoutineProposal.describe(state.step.actionId)
            description + ": " + when (state.status) {
                RoutineStepStatus.VERIFIED -> "device result verified"
                RoutineStepStatus.USER_CONFIRMED -> "you confirmed the result"
                RoutineStepStatus.REQUESTED, RoutineStepStatus.AWAITING_OUTCOME -> "requested; outcome not yet verified"
                RoutineStepStatus.REQUESTED_WITHOUT_TRACKING -> "requested; outcome tracking unavailable"
                RoutineStepStatus.AMBIGUOUS -> "effect uncertain; will not repeat automatically"
                RoutineStepStatus.FAILED -> "not applied; remaining steps stopped"
                RoutineStepStatus.RUNNING -> "dispatch in progress"
                RoutineStepStatus.PENDING -> "pending"
            }
        }
        val next = when (checkpoint.status) {
            RoutineWorkflowStatus.AWAITING_APPROVAL -> if (goal != null) " Review this goal's exact steps, then say 'approve goal plan' to authorize them. Nothing has run."
                else " Invoke the saved routine by name to authorize its reviewed steps. Nothing has run."
            RoutineWorkflowStatus.READY -> " Say '$resumeCommand' to run the pending steps. Nothing resumes automatically."
            RoutineWorkflowStatus.AWAITING_OUTCOME -> " Check the requested result, then say '$outcomeCommand worked' or '$outcomeCommand failed'. An updater launch is not proof of installation."
            RoutineWorkflowStatus.FAILED, RoutineWorkflowStatus.PAUSED -> if (goal != null && !checkpoint.approved && checkpoint.steps.all { it.attempts == 0 })
                " Restore storage access, review the exact steps, then say 'approve goal plan' to approve again or 'cancel goal plan' to stop. No step has run."
                else " Review the cause before saying '$resumeCommand'. Completed steps will not repeat."
            RoutineWorkflowStatus.AMBIGUOUS -> " Inspect the device result before any new request; this workflow will not retry an uncertain effect. Report a checked result with '$outcomeCommand worked' or '$outcomeCommand failed'."
            RoutineWorkflowStatus.CANCELLED, RoutineWorkflowStatus.CANCELLING -> " Subsequent steps are cancelled. Already requested actions are not undone."
            else -> ""
        }
        val title = if (goal != null) "Goal '${goal.kind.title}'" else if (GoalPlanning.isKey(checkpoint.phrase)) "Goal execution"
            else "Routine '${checkpoint.phrase}'"
        return "$title: $steps. ${checkpoint.detail}$next".trim()
    }

    private fun settleTrackedRoutine(pending: DeviceLearningSession.Pending, success: Boolean) {
        if (!personalAssistant) return
        val id = sessionRoutineId ?: return
        runCatching {
            routineCoordinator.reportFor(id, pending.phrase, pending.action,
                if (success) RoutineOutcomeObservation.USER_CONFIRMED else RoutineOutcomeObservation.FAILED)
        }
    }

    private fun executeRoutineStep(phrase: String, step: String, target: LearnableDeviceAction?): RoutineExecution.Status {
        if (target != null) return when (deviceLearningSession.requestAndTrack(phrase, target) { requestDeviceAction(target) }) {
            DeviceLearningSession.RequestResult.FAILED -> RoutineExecution.Status.FAILED
            DeviceLearningSession.RequestResult.AWAITING_OUTCOME -> RoutineExecution.Status.AWAITING_OUTCOME
            DeviceLearningSession.RequestResult.REQUESTED_WITHOUT_TRACKING -> RoutineExecution.Status.REQUESTED_WITHOUT_TRACKING
        }
        RoutineSteps.volumePercent(step)?.let { percent ->
            val verified = androidAssistant.setMediaVolumePercent(percent)
            resultHistory.record(phrase, RoutineProposal.describe(step),
                if (verified) IntelligenceResultHistory.Outcome.DEVICE_VERIFIED else IntelligenceResultHistory.Outcome.NOT_VERIFIED,
                "Approved routine volume step. " + (if (verified) "Nearest supported target level verified." else "Target level could not be verified; remaining steps stopped.") + " No single-action phrase learned.")
            return if (verified) RoutineExecution.Status.VERIFIED else RoutineExecution.Status.FAILED
        }
        return when (step) {
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

    private fun proposeCloudAction(userText: String, lessonJson: String?): String? {
        if (CapabilityDiscovery.isManagementText(userText)) return CapabilityDiscovery.clarification()
        val lesson = lessonJson?.let(FallbackIntentLesson::fromJson)
        if (RoutineProposal.isCreationRequest(userText)) {
            if (lesson?.intent != "CREATE_ROUTINE")
                return "I couldn't produce a supported routine definition. Give a name and explicit steps: volume up/down one step or a media-volume percentage, opening the updater, a timer with an explicit duration, or opening a saved preferred browser/maps/music app. Nothing was saved or run."
            return proposeFallbackDeviceAction(userText, lessonJson)
        }
        if (lesson?.intent == "CREATE_ROUTINE")
            return "Creating a routine needs an explicit request to create a named routine and its steps. Nothing was saved or run."
        return proposeFallbackVolume(userText, lessonJson) ?: proposeFallbackDeviceAction(userText, lessonJson)
    }

    private fun proposeFallbackVolume(userText: String, lessonJson: String?): String? {
        val lesson = lessonJson?.let(FallbackIntentLesson::fromJson) ?: return null
        ExactVolumeLearning.fromLesson(lesson)?.let { percent ->
            if (!volumeConfirmations.propose(userText, lesson)) return "I couldn't retain this volume-level request for approval. Nothing was changed or learned."
            return "Would you like me to set media volume to $percent%, using the nearest supported level? Say yes to confirm or no to cancel."
        }
        val capability = VerifiedVolumeLearning.capability(lesson) ?: return null
        if (!volumeConfirmations.propose(userText, lesson)) return "I couldn't retain this volume request for confirmation. Nothing was changed or learned. Please repeat it."
        return "Would you like me to ${if (capability == "volume_up") "raise" else "lower"} the media volume? Say yes to confirm or no to cancel."
    }

    private fun proposeFallbackDeviceAction(userText: String, lessonJson: String?): String? {
        val lesson = lessonJson?.let(FallbackIntentLesson::fromJson) ?: return null
        if (lesson.intent == "CREATE_ROUTINE") {
            val routine = RoutineProposal.fromAiLesson(lesson, androidAssistant::resolveLearnableApp)
                ?: return "I couldn't approve that routine definition. Supported steps are media volume up/down one step or a media-volume percentage from 0 to 100, opening the updater, a final timer from 1 second to 24 hours, or a final installed app by its exact launcher name or saved preferred browser/maps/music app. Choose a short routine name and explicit steps. Nothing was saved or run."
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

    private fun routineAppUnavailable(steps: List<String>): Boolean = steps.any { step ->
        (RoutineSteps.preferredSlot(step) != null && RoutineSteps.preferredAction(step, assistantMemory, androidAssistant::canLaunchApp) == null) ||
            (RoutineSteps.appAction(step)?.let { !androidAssistant.canLaunchApp(it.parameters.getValue("package")) } == true)
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
            ?: return "That routine uses an unsupported step or reserved name. Supported steps are volume up, volume down, a media-volume percentage from 0 to 100, opening the updater, a final timer from 1 second to 24 hours, and a final installed app or saved preferred browser/maps/music app. Nothing was saved or run."
        if (routineAppUnavailable(proposal.actions))
            return "The routine needs its installed app or saved preferred app to be launchable. Restore it first, then create the routine again. Nothing was saved or run."
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

    private fun executeExactVolume(phrase: String, percent: Int, learn: Boolean, replayed: Boolean = false, requestId: String? = null): String {
        val memory = com.assistant.core.learning.ValidatedCommandMemory(systemService.context())
        val outcome = ExactVolumeLearning.execute(phrase, percent, learn, androidAssistant::setMediaVolumePercent,
            record = { original, parameters -> memory.recordSuccess(original, ExactVolumeLearning.CAPABILITY, ExactVolumeLearning.capabilities, parameters) })
        val paused = !outcome.executed && replayed && memory.recordFailure(phrase, ExactVolumeLearning.CAPABILITY, mapOf("percent" to percent.toString()))
        resultHistory.record(phrase, "set media volume to $percent% (nearest supported level)",
            if (outcome.executed) IntelligenceResultHistory.Outcome.DEVICE_VERIFIED else IntelligenceResultHistory.Outcome.NOT_VERIFIED,
            if (outcome.learned) "Original phrase saved for offline reuse." else if (paused) "Learned phrase paused after failed verification." else "No new phrase saved.")
        val evidenceSaved = requestId == null || trialEvidence.record(requestId,
            LearnableDeviceAction(ExactVolumeLearning.CAPABILITY, mapOf("percent" to percent.toString())),
            if (outcome.executed) CapabilityTrialEvidence.Outcome.DEVICE_VERIFIED else CapabilityTrialEvidence.Outcome.NOT_VERIFIED)
        return (when {
            !outcome.executed -> "Could not verify the requested media-volume level. Nothing was learned." +
                if (paused) " Paused this learned phrase; review 'show learned commands'." else ""
            outcome.learned -> "Media volume set to the nearest supported level for $percent% and verified. Learned your original phrase for offline reuse."
            learn -> "Media volume level verified for $percent%, but the phrase could not be saved. It may conflict with another command or storage may have failed."
            else -> "Media volume level verified for $percent%, using the nearest supported level."
        }) + if (evidenceSaved) "" else " Linked trial evidence could not be saved; review will not show this result."
    }

    private fun executeConfirmedVolume(userText: String, lesson: FallbackIntentLesson, explicitTeaching: Boolean = false, requestId: String? = null): String {
        ExactVolumeLearning.fromLesson(lesson)?.let { percent ->
            val enabled = systemService.context().getSharedPreferences("jarvis_cloud_ai", android.content.Context.MODE_PRIVATE)
                .getBoolean("automatic_command_learning", true)
            return executeExactVolume(userText, percent, learn = (explicitTeaching || enabled) && lesson.canTeachLocally(), requestId = requestId)
        }
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
        if (CapabilityDiscovery.isControl(userText)) return false
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
        return conversationTurns(limit.coerceIn(0, 30)).joinToString(separator = "\n\n") { (user, assistant) ->
            "You: $user\nJ.A.R.V.I.S.: $assistant"
        }
    }

    @Synchronized
    private fun conversationTurns(limit: Int = 8): List<Pair<String, String>> =
        conversationMemory?.snapshot(limit) ?: history.takeLast(limit.coerceIn(0, 30))

    private fun replyFromResult(userText: String, preface: String, result: ActionResult): HybridAssistantReply {
        val reply = buildString {
            appendLine(if (result.success) preface else "I couldn't complete that action.")
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
        conversationMemory?.record(userText, assistantText)
        history.addLast(userText to assistantText)
        while (history.size > 30) {
            history.removeFirst()
        }
    }

    private fun isTimeRequest(text: String): Boolean = ConversationPolicy.isTimeQuery(text)

    private fun calendarRangeFor(text: String): Pair<Long, Long>? {
        if (!ConversationPolicy.isCalendarQuery(text)) return null

        val start = Calendar.getInstance()
        val end = Calendar.getInstance()
        when {
            text.contains("next week") -> {
                start.add(Calendar.DAY_OF_YEAR, 7)
                start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0); start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
                end.timeInMillis = start.timeInMillis
                end.add(Calendar.DAY_OF_YEAR, 7)
            }
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

    private fun parseTimerSeconds(text: String): Int? = SpokenTimerDuration.command(text)

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
        return Regex("(?i)\\b(?:run shell|execute shell|run command)\\b(.*)$")
            .find(rawInput)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "id" }
    }

    private fun looksLikeYes(normalized: String): Boolean {
        return normalized in setOf("yes", "confirm", "confirm reboot", "confirm command", "do it", "sure")
    }
}
