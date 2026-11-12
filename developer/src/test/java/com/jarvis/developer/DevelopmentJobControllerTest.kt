package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Test

class DevelopmentJobControllerTest {
    @Test fun approvedBuildCompletes() {
        val controller = DevelopmentJobController
        var job = DevelopmentJobController.Job("developer", "Add settings")
        job = controller.advance(job, DevelopmentJobController.Event.ANALYZED)
        job = controller.advance(job, DevelopmentJobController.Event.DESIGN_ACCEPTED)
        job = controller.advance(job, DevelopmentJobController.Event.SOURCE_APPROVED, proposalId = "proposal_1234")
        job = controller.advance(job, DevelopmentJobController.Event.BUILD_STARTED, runId = 42L)
        job = controller.advance(job, DevelopmentJobController.Event.BUILD_PASSED)
        assertEquals(DevelopmentJobController.Stage.VERIFIED, job.stage)
    }

    @Test fun buildCannotSkipApproval() {
        val controller = DevelopmentJobController
        val job = DevelopmentJobController.Job("developer", "Add settings")
        assertThrows(IllegalArgumentException::class.java) {
            controller.advance(job, DevelopmentJobController.Event.BUILD_STARTED, runId = 42L)
        }
        val review = controller.advance(controller.advance(job, DevelopmentJobController.Event.ANALYZED), DevelopmentJobController.Event.DESIGN_ACCEPTED)
        assertThrows(IllegalArgumentException::class.java) {
            controller.advance(review, DevelopmentJobController.Event.SOURCE_APPROVED)
        }
    }

    @Test fun failedBuildNeedsRepairReview() {
        val controller = DevelopmentJobController
        var job = DevelopmentJobController.Job("developer", "Repair settings")
        job = controller.advance(job, DevelopmentJobController.Event.ANALYZED)
        job = controller.advance(job, DevelopmentJobController.Event.DESIGN_ACCEPTED)
        job = controller.advance(job, DevelopmentJobController.Event.SOURCE_APPROVED, proposalId = "proposal_1234")
        job = controller.advance(job, DevelopmentJobController.Event.BUILD_STARTED, runId = 42L)
        job = controller.advance(job, DevelopmentJobController.Event.BUILD_FAILED)
        assertEquals(DevelopmentJobController.Stage.REPAIR_REVIEW, job.stage)
        assertThrows(IllegalArgumentException::class.java) {
            controller.advance(job, DevelopmentJobController.Event.BUILD_STARTED, runId = 43L)
        }
    }
}
