package com.watchjobs.mapper

import com.watchjobs.dto.request.ApplicationRequest
import com.watchjobs.dto.response.ApplicationResponse
import com.watchjobs.entity.Application
import com.watchjobs.entity.ApplicationStage
import com.watchjobs.entity.SavedJob
import com.watchjobs.entity.User
import org.springframework.stereotype.Component

@Component
class ApplicationMapper {
    fun toEntity(
        request: ApplicationRequest,
        user: User,
        savedJob: SavedJob,
        stage: ApplicationStage
    ): Application = Application(
        user = user,
        savedJob = savedJob,
        stage = stage,
        status = request.status,
        startDate = request.applicationDate,
        endDate = request.endDate,
        interviewAt = request.interviewAt,
        notes = request.notes,
        feedback = request.feedback
    )

    fun updateEntity(entity: Application, request: ApplicationRequest, stage: ApplicationStage) {
        entity.stage = stage
        entity.status = request.status
        entity.startDate = request.applicationDate
        entity.endDate = request.endDate
        entity.interviewAt = request.interviewAt
        entity.notes = request.notes
        entity.feedback = request.feedback
    }

    fun toResponse(entity: Application): ApplicationResponse = ApplicationResponse(
        id = entity.id ?: error("Candidatura sem id"),
        savedJobId = entity.savedJob?.id ?: error("Candidatura sem vaga"),
        positionRole = entity.savedJob?.positionRole ?: "",
        organizationName = entity.savedJob?.organizationName ?: "",
        stageId = entity.stage?.id ?: error("Candidatura sem etapa"),
        stageName = entity.stage?.name ?: "",
        status = entity.status,
        startDate = entity.startDate,
        endDate = entity.endDate,
        interviewAt = entity.interviewAt,
        notes = entity.notes,
        feedback = entity.feedback
    )
}