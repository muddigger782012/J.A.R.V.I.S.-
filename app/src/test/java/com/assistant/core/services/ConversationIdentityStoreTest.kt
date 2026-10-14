package com.assistant.core.services

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationIdentityStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun identities() = ConversationIdentityStore(temporary.root)
    private fun pending(name: String = "volume") = ConversationPendingStore(temporary.root, name)
    private fun <T> request(identity: ConversationIdentityStore.Identity, block: () -> T): T {
        ConversationRequestScope.identity.set(identity)
        try { return block() } finally { ConversationRequestScope.identity.remove() }
    }
    @Test fun legacyConversationMigratesAndOrdinaryPersistenceDoesNotRotateEpoch() {
        File(temporary.root, "live-chat-conversation.txt").writeText("saved")
        assertEquals("saved", identities().snapshot().id)
        val selected = identities().activate("saved")
        assertNotEquals("legacy", selected.epoch)
        assertEquals(selected, identities().snapshot())
        assertEquals(selected, identities().activate("saved"))
        assertEquals("saved", File(temporary.root, "live-chat-conversation.txt").readText())
        assertNotEquals(selected, identities().activate("saved", force = true))
    }
    @Test fun reopeningChatRejectsOldWakeRequestEvenBeforeThatInstanceResynchronizes() {
        val old = identities().activate("first")
        val wake = ConversationContext(temporary.root)
        val token = wake.bind(old.id, epoch = old.epoch)
        assertEquals(true, identities().withCurrent(old) { wake.remember(token, "old question", "completed answer") })
        val requestTurns = wake.snapshot()
        identities().activate("second")
        val reopened = identities().activate("first")
        assertNotEquals(old.epoch, reopened.epoch)
        assertNull(identities().withCurrent(old) { wake.remember(token, "late reply", "must not be saved") })
        assertEquals(requestTurns, wake.snapshot())
        wake.bind(reopened.id, epoch = reopened.epoch)
        assertFalse(wake.accepts(token))
    }
    @Test fun currentChatPendingProposalSurvivesRestartAndCanBeConsumedOnlyOnce() {
        identities().activate("first")
        assertTrue(pending().write("proposal"))
        val restarted = pending()
        assertEquals("proposal", restarted.read())
        assertTrue(restarted.write(null))
        assertNull(pending().read())
        assertFalse(restarted.write(null))
    }
    @Test fun staleReaderCannotDeleteNewProposalOrStageOldWorkInReopenedChat() {
        val oldIdentity = identities().activate("first")
        val oldReader = pending()
        assertTrue(oldReader.write("old proposal"))
        assertEquals("old proposal", oldReader.read())
        assertTrue(pending().write("newer proposal"))
        assertFalse(oldReader.write(null))
        assertEquals("newer proposal", pending().read())
        identities().activate("second")
        identities().activate("first")
        assertNull(pending().read())
        assertTrue(pending().write("current epoch"))
        request(oldIdentity) {
            assertNull(oldReader.read())
            assertFalse(oldReader.write("late cloud action"))
            assertFalse(oldReader.write(null))
        }
        assertEquals("current epoch", pending().read())
    }
    @Test fun approvalAndOutcomeStoresShareWithinChatAndRejectEveryOldChatProposal() {
        identities().activate("first")
        fun volume(): VolumeConfirmationStore { val file = pending("volume"); return VolumeConfirmationStore(file::read, file::write) }
        fun device(): DeviceLearningSession { val file = pending("device"); return DeviceLearningSession(file::read, file::write) }
        fun preference(): PreferenceConfirmationStore { val file = pending("preference"); return PreferenceConfirmationStore(file::read, file::write) }
        fun routine(): RoutineConfirmationStore { val file = pending("routine"); return RoutineConfirmationStore(file::read, file::write) }
        val lesson = FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume", "direction" to "down"), learnable = true)
        val action = LearnableDeviceAction("timer", mapOf("seconds" to "60"))
        val definition = com.assistant.core.learning.ApprovedRoutineRegistry.Routine("quiet break", listOf("volume_down"))
        assertTrue(volume().propose("less noise", lesson))
        assertTrue(volume().consume("yes")!!.approved)
        assertTrue(device().propose(DeviceLearningSession.Pending("short break", action, true)))
        assertTrue(device().consume("yes")!!.accepted)
        assertTrue(preference().propose("clear"))
        assertTrue(preference().consume("yes")!!.approved)
        assertTrue(routine().propose(definition))
        assertTrue(routine().consume("confirm routine")!!.approved)
        assertTrue(volume().propose("less noise", lesson))
        assertTrue(device().propose(DeviceLearningSession.Pending("short break", action, true, awaitingOutcome = true)))
        assertTrue(preference().propose("clear"))
        assertTrue(routine().propose(definition))
        identities().activate("second"); identities().activate("first")
        assertNull(volume().consume("yes"))
        assertNull(device().consume("it worked"))
        assertNull(preference().consume("yes"))
        assertNull(routine().consume("confirm routine"))
    }
    @Test fun brokenPendingStorageFailsClosed() {
        identities().activate("first")
        File(temporary.root, "conversation-pending-volume.json").writeText("not json")
        assertNull(pending().read())
        assertFalse(pending().write(null))
    }
    @Test fun stagingAndApprovalCanNestInsideTheDurableEpochTransaction() {
        val identity = identities().activate("first")
        val file = pending()
        val store = VolumeConfirmationStore(file::read, file::write)
        val lesson = FallbackIntentLesson("DEVICE_SETTING", .95, mapOf("setting" to "media_volume", "direction" to "down"), learnable = true)
        assertEquals(true, identities().withCurrent(identity) { store.propose("less noise", lesson) })
        assertEquals(true, identities().withCurrent(identity) { store.consume("yes")?.approved })
        assertNull(store.consume("yes"))
    }
}
