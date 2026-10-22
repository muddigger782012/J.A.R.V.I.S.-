package com.assistant.core.services

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenRequestEpochTest {
    @Test fun cancellationInvalidatesEveryEarlierRequestButAllowsFreshWork() {
        val gate = ScreenRequestEpoch()
        val first = gate.token(); val second = gate.token()
        assertTrue(gate.valid(first)); assertTrue(gate.valid(second))
        gate.cancel()
        assertFalse(gate.valid(first)); assertFalse(gate.valid(second))
        assertTrue(gate.valid(gate.token()))
    }

    @Test fun timeoutInvalidatesTheUncertainReviewDomain() {
        val gate = ScreenRequestEpoch(); val token = gate.token()
        gate.timeout(token)
        assertFalse(gate.valid(token))
        assertTrue(gate.valid(gate.token()))
    }

    @Test fun lateTimeoutCannotCancelWorkCreatedAfterAnExplicitCancellation() {
        val gate = ScreenRequestEpoch(); val old = gate.token()
        gate.cancel(); val fresh = gate.token()
        gate.timeout(old)
        assertTrue(gate.valid(fresh)); assertFalse(gate.valid(old))
    }

    @Test fun cancellationIsVisibleToAWorkerWithoutWaitingForItsQueue() {
        val gate = ScreenRequestEpoch(); val token = gate.token()
        val entered = CountDownLatch(1); val resume = CountDownLatch(1); val done = CountDownLatch(1)
        var couldDispatch = true
        Thread {
            entered.countDown(); resume.await()
            couldDispatch = gate.valid(token)
            done.countDown()
        }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.cancel(); resume.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS)); assertFalse(couldDispatch)
    }
    @Test fun pausingCanRetainIntentButAResultTimeoutMustDiscardApproval() {
        val gate = ScreenRequestEpoch(); val beforePause = gate.token()
        gate.cancel(preserveTask = true)
        assertTrue(gate.state().preserveTask); assertFalse(gate.valid(beforePause))
        gate.timeout(beforePause); assertTrue(gate.state().preserveTask)
        gate.timeout(gate.token()); assertFalse(gate.state().preserveTask)
    }
    @Test fun delayedNotificationCannotDiscardAReplacementTaskButTimeoutStillInvalidatesAllApprovals() {
        val gate = ScreenRequestEpoch(); val queued = gate.token()
        gate.cancel(taskKey = "original")
        assertFalse(gate.valid(queued))
        assertTrue(gate.state().appliesTo("original"))
        assertFalse(gate.state().appliesTo("replacement"))
        gate.timeout(gate.token())
        assertTrue(gate.state().appliesTo("replacement"))
        assertTrue(gate.state().appliesTo(null))
    }
}
