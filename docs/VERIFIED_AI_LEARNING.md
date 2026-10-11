# Verified AI command learning: first milestone

The shared `HybridAssistantService.handleUserInputAsync` path already serves text
and background voice. This change connects unresolved Gemini requests and gateway
lessons to an allowlisted executor for one-step **media** volume changes.

## Execution and persistence

1. Existing deterministic commands and exact learned aliases run locally first.
2. Direct Gemini returns a conversational reply plus an optional structured lesson.
3. Only `DEVICE_SETTING` with exactly `setting=media_volume` and `direction=up|down`,
   valid confidence of at least 0.90, can reach this executor.
4. Android volume is read before and after adjustment. No change, a wrong-direction
   change, an exception, or a blocked action is not a verified success.
5. A verified action is persisted only when the lesson is learnable and
   `jarvis_cloud_ai.automatic_command_learning` is enabled (default true).
6. The existing exact-match validated-command memory handles subsequent offline
   requests. Disk-write failures and conflicting phrase meanings are not reported
   as learned. Foreground/background instances share the memory mutation lock.

No new arbitrary code execution or privileged capability is introduced. The new
interpreter must not act on hypothetical, quoted, negated or history-only commands.
Other actions continue through existing handlers. A volume at its limit produces
an unverified result and is not learned.

## Validation

`VerifiedVolumeLearningTest` covers persistence across memory recreation, offline
capability lookup, failed/no-op actions, exceptions, confidence/allowlist rejection,
disabled learning, conflicting phrases and failed writes.

Run `bash gradlew --no-daemon testDebugUnitTest assembleDebug` in Android CI.
The repository currently disables its UI emulator job; a successful source build
is not a claim of device or UI verification.

## Phone acceptance check

With media volume above zero and below maximum, ask an unfamiliar phrase such as
“less racket please.” Check that JARVIS actually lowers media volume and reports
verified learning. Restart JARVIS, disable networking, and repeat the phrase by
text and voice. The exact alias should run locally. Use “forget learned command
less racket please” to remove it.

At zero volume, try a new lowering phrase. Confirm that no verified success or
new learned phrase is reported. Recheck the saved-command count. These phone
checks, live provider behavior and always-on microphone reliability remain to be
verified on hardware. Full shared conversational memory and capability expansion
are separate remaining milestones.
