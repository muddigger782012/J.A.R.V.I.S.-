package com.assistant.core.services

import com.assistant.core.services.IntelligenceResultHistory.Outcome
import com.assistant.core.services.SupervisedImprovement.ActionFamily
import com.assistant.core.services.SupervisedImprovement.Category
import com.assistant.core.services.SupervisedImprovement.Command
import com.assistant.core.services.SupervisedImprovement.State
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SupervisedImprovementTest {
    private val volume = "set media volume to 50% (nearest supported level)"
    private fun entry(time: Long, outcome: Outcome = Outcome.USER_FAILED, action: String = volume,
        phrase: String = "my private command", detail: String = "Local outcome detail.") =
        IntelligenceResultHistory.Entry(time, phrase, action, outcome, detail)

    private fun report(vararg rows: IntelligenceResultHistory.Entry) =
        SupervisedImprovement.generate(rows.sortedByDescending { it.time })

    @Test fun recurrentReportsProduceActionableReviewWithSyntheticRegressions() {
        val result = report(entry(1), entry(2, phrase = "another way to ask"))
        val proposal = result.proposals.single()
        assertEquals(Category.USER_REPORTED_FAILURE, proposal.category)
        assertEquals(ActionFamily.MEDIA_VOLUME, proposal.family)
        assertEquals(State.NEEDS_REVIEW, proposal.state)
        assertEquals(2, proposal.evidence.occurrences)
        assertEquals(2, proposal.evidence.activeOccurrences)
        assertEquals(listOf(1L, 2L), proposal.evidence.problemTimes)
        assertTrue(proposal.suggestedReview.contains("device readback"))
        assertTrue(proposal.suggestedReview.contains("human approval"))
        assertTrue(proposal.regressionCases.all { it.scenario.isNotBlank() && it.expected.isNotBlank() })
        assertTrue(proposal.regressionCases.any { it.name == "Target-specific recovery" })
        assertEquals(0, result.deviceVerifiedSuccesses)
    }

    @Test fun isolatedAndDifferentKindsOfEvidenceCannotInventRecurrence() {
        assertTrue(report().proposals.isEmpty())
        val result = report(entry(1), entry(2, Outcome.NOT_VERIFIED), entry(3, Outcome.UNCLEAR),
            entry(4, action = "start a 60-second timer"))
        assertTrue(result.proposals.isEmpty())
        assertEquals(4, result.belowThresholdGroups)
        assertEquals(2, result.userReportedFailures)
        assertEquals(1, result.unverifiedOutcomes)
        assertEquals(1, result.unclearOutcomes)
    }

    @Test fun unverifiedAndUnclearOutcomesAreNotReportedFailuresOrSuccesses() {
        val result = report(entry(1, Outcome.NOT_VERIFIED), entry(2, Outcome.NOT_VERIFIED),
            entry(3, Outcome.UNCLEAR), entry(4, Outcome.UNCLEAR))
        assertEquals(setOf(Category.VERIFICATION_GAP, Category.UNCLEAR_OUTCOME),
            result.proposals.map { it.category }.toSet())
        assertEquals(0, result.userReportedFailures)
        assertEquals(0, result.userConfirmations)
        assertEquals(0, result.deviceVerifiedSuccesses)
        assertEquals(2, result.unverifiedOutcomes)
        assertEquals(2, result.unclearOutcomes)
        assertTrue(result.proposals.single { it.category == Category.VERIFICATION_GAP }
            .suggestedReview.contains("does not prove execution failed"))
    }

    @Test fun laterUserConfirmationIsLabeledAndDoesNotResolveDeviceVerification() {
        val result = report(entry(1), entry(2), entry(3, Outcome.USER_CONFIRMED))
        val proposal = result.proposals.single()
        assertEquals(State.AWAITING_VERIFICATION, proposal.state)
        assertEquals(2, proposal.evidence.activeOccurrences)
        assertEquals(1, proposal.evidence.userConfirmations)
        assertEquals(0, proposal.evidence.deviceVerifiedSuccesses)
        assertNull(proposal.evidence.lastDeviceVerification)
        assertEquals(3L, proposal.evidence.lastUserConfirmation)
        assertEquals(1, result.openProposals)
        assertEquals(0, result.resolvedProposals)
    }

    @Test fun laterDeviceVerificationResolvesOnlyTheExactTarget() {
        val result = report(entry(1), entry(2),
            entry(3, Outcome.DEVICE_VERIFIED, "set media volume to 75% (nearest supported level)"),
            entry(4, Outcome.DEVICE_VERIFIED))
        val proposal = result.proposals.single()
        assertEquals(State.RESOLVED, proposal.state)
        assertEquals(0, proposal.evidence.activeOccurrences)
        assertEquals(4L, proposal.evidence.lastDeviceVerification)
        assertEquals(1, proposal.evidence.deviceVerifiedSuccesses)
        assertEquals(2, result.deviceVerifiedSuccesses)
        assertEquals(0, result.openProposals)
        assertEquals(1, result.resolvedProposals)
        val differentTargetOnly = report(entry(1), entry(2),
            entry(3, Outcome.DEVICE_VERIFIED, "set media volume to 75% (nearest supported level)"))
        assertEquals(State.NEEDS_REVIEW, differentTargetOnly.proposals.single().state)
    }

    @Test fun earlierSuccessDoesNotResolveLaterFailures() {
        val result = report(entry(1, Outcome.DEVICE_VERIFIED), entry(2), entry(3))
        assertEquals(State.NEEDS_REVIEW, result.proposals.single().state)
        assertEquals(2, result.proposals.single().evidence.activeOccurrences)
    }

    @Test fun separatedIsolatedProblemsDoNotCombineAcrossVerifiedRecovery() {
        val result = report(entry(1), entry(2, Outcome.DEVICE_VERIFIED), entry(3))
        assertTrue(result.proposals.isEmpty())
        assertEquals(1, result.belowThresholdGroups)
    }

    @Test fun newProblemAfterResolvedEpisodeReopensMonitoringThenReview() {
        val base = listOf(entry(1), entry(2), entry(3, Outcome.DEVICE_VERIFIED))
        val monitoring = report(*(base + entry(4)).toTypedArray()).proposals.single()
        assertEquals(State.MONITORING, monitoring.state)
        assertEquals(1, monitoring.evidence.activeOccurrences)
        val reopened = report(*(base + entry(4) + entry(5)).toTypedArray()).proposals.single()
        assertEquals(State.NEEDS_REVIEW, reopened.state)
        assertEquals(2, reopened.evidence.activeOccurrences)
        assertEquals(monitoring.id, reopened.id)
    }

    @Test fun duplicateRowsAndDetailsForOneTimestampCannotReachThreshold() {
        val rows = List(100) { entry(1, phrase = "private phrase $it", detail = "detail $it") }
        val result = SupervisedImprovement.generate(rows)
        assertEquals(1, result.distinctAttempts)
        assertEquals(99, result.coalescedRecords)
        assertEquals(1, result.userReportedFailures)
        assertTrue(result.proposals.isEmpty())
    }

    @Test fun conflictingSameTimeResultsNeverCountAsVerifiedRecovery() {
        val result = report(entry(1), entry(2), entry(3), entry(3, Outcome.DEVICE_VERIFIED))
        assertEquals(State.NEEDS_REVIEW, result.proposals.single().state)
        assertEquals(1, result.conflictingOutcomes)
        assertEquals(0, result.deviceVerifiedSuccesses)
        assertNull(result.proposals.single().evidence.lastDeviceVerification)
        val conflicts = report(entry(1), entry(1, Outcome.USER_CONFIRMED),
            entry(2, Outcome.NOT_VERIFIED), entry(2, Outcome.DEVICE_VERIFIED))
        assertEquals(Category.CONFLICTING_OUTCOMES, conflicts.proposals.single().category)
        assertEquals(2, conflicts.distinctAttempts)
        assertEquals(0, conflicts.deviceVerifiedSuccesses)
        assertEquals(0, conflicts.userConfirmations)
    }

    @Test fun appNamesTimerDurationsAndVolumeDirectionsStaySeparate() {
        val targets = listOf("open Test App", "open Other App", "start a 60-second timer",
            "start a 120-second timer", "change media volume up", "change media volume down")
        val singleRows = targets.mapIndexed { i, target -> entry(i.toLong(), action = target) }
        assertTrue(SupervisedImprovement.generate(singleRows).proposals.isEmpty())
        val repeated = singleRows + singleRows.map { it.copy(time = it.time + 10) }
        val result = SupervisedImprovement.generate(repeated)
        assertEquals(6, result.proposals.size)
        assertEquals(6, result.proposals.map { it.id }.distinct().size)
        assertEquals(2, result.proposals.count { it.family == ActionFamily.TIMER })
        assertEquals(2, result.proposals.count { it.family == ActionFamily.APP_LAUNCH })
    }

    @Test fun knownDescriptionsCanonicalizeWithoutConflatingParameters() {
        val result = report(entry(1, action = "change media volume up"),
            entry(2, action = "  RAISE   MEDIA VOLUME ONE STEP.  "),
            entry(3, Outcome.DEVICE_VERIFIED, "volume up"))
        assertEquals(State.RESOLVED, result.proposals.single().state)
        assertEquals(2, result.proposals.single().evidence.occurrences)
        val timer = report(entry(1, action = "start a 060-second timer"),
            entry(2, action = "start a 60-second timer"))
        assertEquals(2, timer.proposals.single().evidence.occurrences)
    }

    @Test fun allOutputUsesStaticTextAndLocalReferencesInsteadOfPrivateContent() {
        val rows = listOf(entry(1, action = "open PrivateTarget", phrase = "private@example.test",
            detail = "Bearer synthetic-secret"), entry(2, action = "open PrivateTarget",
            phrase = "private@example.test", detail = "Bearer synthetic-secret"))
        val result = SupervisedImprovement.generate(rows)
        val output = result.toString() + SupervisedImprovement.render(result)
        for (privateText in listOf("PrivateTarget", "privatetarget", "private@example.test", "synthetic-secret", "Bearer"))
            assertFalse(output.contains(privateText))
        val rewritten = rows.map { it.copy(phrase = "different wording", detail = "success verified approved") }
        assertEquals(result, SupervisedImprovement.generate(rewritten))
        assertTrue(SupervisedImprovement.render(result).contains("No code, learning, permissions or deployment was changed"))
    }

    @Test fun scanAndDisplayAreBoundedAndOmissionsAreVisible() {
        val rows = (1..150).map { entry(it.toLong(), action = "open Synthetic App ${it / 2}") }
            .sortedByDescending { it.time }
        val result = SupervisedImprovement.generate(rows)
        assertTrue(result.inputTruncated)
        assertEquals(SupervisedImprovement.MAX_RECORDS, result.inspectedRecords)
        assertEquals(100, result.distinctAttempts)
        assertEquals(SupervisedImprovement.MAX_PROPOSALS, result.proposals.size)
        assertEquals(49, result.openProposals)
        assertEquals(29, result.omittedProposals)
        assertTrue(result.proposals.flatMap { it.evidence.problemTimes }.all { it >= 51 })
        assertTrue(SupervisedImprovement.render(result).contains("bounded newest journal window"))
        assertTrue(SupervisedImprovement.render(result).contains("outside this bounded display"))
    }

    @Test fun activeProposalsAreDisplayedBeforeResolvedHistory() {
        val active = listOf(entry(1), entry(2))
        val resolved = (1..25).flatMap { index ->
            val start = index * 3L + 10
            val action = "open Synthetic App $index"
            listOf(entry(start, action = action), entry(start + 1, action = action),
                entry(start + 2, Outcome.DEVICE_VERIFIED, action))
        }
        val result = report(*(active + resolved).toTypedArray())
        assertEquals(State.NEEDS_REVIEW, result.proposals.first().state)
        assertEquals(1, result.openProposals)
        assertEquals(25, result.resolvedProposals)
    }

    @Test fun invalidRowsCannotCreateProposalsOrFakeRecovery() {
        val invalid = listOf(entry(-1), entry(1, phrase = ""), entry(2, phrase = "x".repeat(241)),
            entry(3, action = " "), entry(4, action = "x".repeat(161)), entry(5, detail = ""),
            entry(6, Outcome.DEVICE_VERIFIED, detail = "x".repeat(321)))
        val result = report(*(invalid + entry(10) + entry(11)).toTypedArray())
        assertEquals(invalid.size, result.ignoredInvalidRecords)
        assertEquals(2, result.distinctAttempts)
        assertEquals(State.NEEDS_REVIEW, result.proposals.single().state)
        assertEquals(0, result.deviceVerifiedSuccesses)
    }

    @Test fun generationIsDeterministicReadOnlyAndResetFollowsExistingJournal() {
        var raw: String? = null
        var writes = 0
        var clock = 1L
        val history = IntelligenceResultHistory({ raw }, { raw = it; writes++; true }, { clock++ })
        assertTrue(history.record("private", volume, Outcome.USER_FAILED, "local"))
        assertTrue(history.record("private", volume, Outcome.USER_FAILED, "local"))
        val before = raw
        val beforeWrites = writes
        val snapshot = history.snapshot()
        val result = SupervisedImprovement.generate(snapshot)
        assertEquals(result, SupervisedImprovement.generate(snapshot.reversed()))
        SupervisedImprovement.render(result, Command.STATUS)
        assertEquals(before, raw)
        assertEquals(beforeWrites, writes)
        assertEquals(snapshot, history.snapshot())
        assertTrue(history.clear())
        assertTrue(SupervisedImprovement.generate(history.snapshot()).proposals.isEmpty())
        assertEquals(0, SupervisedImprovement.generate(emptyList()).openProposals)
    }

    @Test fun statusUsesEvidenceCountsWithoutReplayingProposalDetails() {
        val result = report(entry(1), entry(2), entry(3, Outcome.USER_CONFIRMED))
        val status = SupervisedImprovement.render(result, Command.STATUS)
        assertTrue(status.contains("1 open"))
        assertTrue(status.contains("User-confirmed successes: 1; device-verified successes: 0"))
        assertTrue(status.contains("Additional feedback collection is off"))
        assertFalse(status.contains("Regression:"))
        assertFalse(status.contains("Review proposal:"))
    }

    @Test fun commandParsingMatchesOnlyWholeManagementCommands() {
        assertEquals(Command.REVIEW_PROPOSALS, SupervisedImprovement.parseCommand("review improvement proposals"))
        assertEquals(Command.STATUS, SupervisedImprovement.parseCommand("  IMPROVEMENT\n STATUS! "))
        for (text in listOf("", "please review improvement proposals", "don't review improvement proposals",
            "what is improvement status", "review improvement proposals and deploy", "improvement status tomorrow",
            "review improvement proposals; enable feedback", "enable improvement feedback", "disable improvement feedback",
            "clear improvement feedback", "x".repeat(100))) {
            assertNull(text, SupervisedImprovement.parseCommand(text))
        }
    }

    @Test fun groupingAndCommandsDoNotDependOnTheDeviceLocale() {
        val before = Locale.getDefault()
        val rows = arrayOf(entry(1, action = "OPEN TEST APP"), entry(2, action = "open test app"))
        val expected = report(*rows)
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(expected, report(*rows))
            assertEquals(Command.STATUS, SupervisedImprovement.parseCommand("IMPROVEMENT STATUS"))
        } finally {
            Locale.setDefault(before)
        }
    }
}
