package com.assistant.core.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningAdmissionPolicyTest {
    @Test fun informationalLessonsRequireHighConfidenceAndLearnableFlag() {
        assertTrue(LearningAdmissionPolicy.canRecordWithoutActionResult(
            FallbackIntentLesson("GENERAL_QUESTION", 0.95, learnable = true)
        ))
        assertFalse(LearningAdmissionPolicy.canRecordWithoutActionResult(
            FallbackIntentLesson("GENERAL_QUESTION", 0.89, learnable = true)
        ))
        assertFalse(LearningAdmissionPolicy.canRecordWithoutActionResult(
            FallbackIntentLesson("GENERAL_QUESTION", 0.99, learnable = false)
        ))
    }

    @Test fun unverifiedDeviceActionsCannotTeachTheLocalRouter() {
        for (intent in listOf("CALL_CONTACT", "SEND_MESSAGE", "NAVIGATE", "SET_ALARM", "OPEN_APP", "DEVICE_SETTING")) {
            assertFalse(LearningAdmissionPolicy.canRecordWithoutActionResult(
                FallbackIntentLesson(intent, 0.99, learnable = true)
            ))
        }
    }

    @Test fun unknownIntentIsRejected() {
        assertFalse(LearningAdmissionPolicy.canRecordWithoutActionResult(
            FallbackIntentLesson("RUN_ARBITRARY_CODE", 0.99, learnable = true)
        ))
    }
}
