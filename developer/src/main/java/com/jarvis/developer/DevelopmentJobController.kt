package com.jarvis.developer

/** Pure state machine for a development job. Side effects belong to reviewed adapters. */
object DevelopmentJobController {
    enum class Stage { INTAKE, DESIGN_REVIEW, SOURCE_REVIEW, APPROVED, BUILDING, REPAIR_REVIEW, VERIFIED, FAILED }
    data class Job(val projectId: String, val instruction: String, val stage: Stage = Stage.INTAKE,
                   val approvedProposalId: String? = null, val buildRunId: Long? = null) {
        init {
            require(projectId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")))
            require(instruction.isNotBlank() && instruction.length <= 4000)
        }
    }
    enum class Event { ANALYZED, DESIGN_ACCEPTED, SOURCE_PREPARED, SOURCE_APPROVED, BUILD_STARTED,
                       BUILD_PASSED, BUILD_FAILED, REPAIR_PROPOSED, REPAIR_APPROVED, ABORT }
    fun advance(job: Job, event: Event, proposalId: String? = null, runId: Long? = null): Job {
        if (event == Event.ABORT) return job.copy(stage = Stage.FAILED)
        val next = when (job.stage to event) {
            Stage.INTAKE to Event.ANALYZED -> Stage.DESIGN_REVIEW
            Stage.DESIGN_REVIEW to Event.DESIGN_ACCEPTED -> Stage.SOURCE_REVIEW
            Stage.SOURCE_REVIEW to Event.SOURCE_PREPARED -> Stage.SOURCE_REVIEW
            Stage.SOURCE_REVIEW to Event.SOURCE_APPROVED -> Stage.APPROVED
            Stage.APPROVED to Event.BUILD_STARTED -> Stage.BUILDING
            Stage.BUILDING to Event.BUILD_PASSED -> Stage.VERIFIED
            Stage.BUILDING to Event.BUILD_FAILED -> Stage.REPAIR_REVIEW
            Stage.REPAIR_REVIEW to Event.REPAIR_PROPOSED -> Stage.REPAIR_REVIEW
            Stage.REPAIR_REVIEW to Event.REPAIR_APPROVED -> Stage.APPROVED
            else -> throw IllegalArgumentException("Illegal development job transition")
        }
        if (event == Event.SOURCE_APPROVED || event == Event.REPAIR_APPROVED)
            require(proposalId?.matches(Regex("[A-Za-z0-9_-]{8,128}")) == true) { "Explicit proposal approval required" }
        if (event == Event.BUILD_STARTED) require(runId != null && runId > 0) { "Real build run ID required" }
        return job.copy(stage = next,
            approvedProposalId = if (event == Event.SOURCE_APPROVED || event == Event.REPAIR_APPROVED) proposalId else job.approvedProposalId,
            buildRunId = if (event == Event.BUILD_STARTED) runId else job.buildRunId)
    }
}
