package com.assistant.core.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeRestartPolicyTest {
    @Test fun backgroundWakeSurvivesPushToTalkAndVoiceCaptureCompletion() {
        assertTrue(WakeRestartPolicy.shouldRemainSticky(true))
    }

    @Test fun pushToTalkWithoutBackgroundWakeDoesNotAutoStartStandby() {
        assertFalse(WakeRestartPolicy.shouldRemainSticky(false))
    }

    @Test fun explicitStopIsNeverRearmedByAnotherCommand() {
        assertFalse(WakeRestartPolicy.shouldRemainSticky(true, explicitlyStopped = true))
    }
}
