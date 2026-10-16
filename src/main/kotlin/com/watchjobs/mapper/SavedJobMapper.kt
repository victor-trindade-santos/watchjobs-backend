package com.watchjobs.mapper

import com.watchjobs.dto.request.SavedJobRequest
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.SavedJob
import com.watchjobs.entity.User
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class SavedJobMapper {
    fun toEntity(request: SavedJobRequest, user: User): SavedJob {
        val now = LocalDateTime.now()
        return SavedJob(
            user = user,
            positionRole = request.positionRole.trim(),
            organizationName = request.organizationName.trim(),
            jobUrl = request.jobUrl,
            locationCity = request.locationCity,
            locationState = request.locationState,
            locationCountryCode = request.locationCountryCode,
            workMode = request.workMode,
            experienceLevel = request.experienceLevel,
            jobType = request.jobType,
            salaryMin = request.salaryMin,
            salaryMax = request.salaryMax,
            salaryCurrency = request.salaryCurrency,
            status = request.status,
            notes = request.notes,
            savedAt = now,
            statusUpdatedAt = now
        )
    }

    fun updateEntity(entity: SavedJob, request: SavedJobRequest) {
        entity.positionRole = request.positionRole.trim()
        entity.organizationName = request.organizationName.trim()
        entity.jobUrl = request.jobUrl
        entity.locationCity = request.locationCity
        entity.locationState = request.locationState
        entity.locationCountryCode = request.locationCountryCode
        entity.workMode = request.workMode
        entity.experienceLevel = request.experienceLevel
        entity.jobType = request.jobType
        entity.salaryMin = request.salaryMin
        entity.salaryMax = request.salaryMax
        entity.salaryCurrency = request.salaryCurrency
        entity.status = request.status
        entity.notes = request.notes
    }

    fun toResponse(entity: SavedJob): SavedJobResponse = SavedJobResponse(
        id = entity.id ?: error("Vaga sem id"),
        positionRole = entity.positionRole,
        organizationName = entity.organizationName,
        jobUrl = entity.jobUrl,
        locationCity = entity.locationCity,
        locationState = entity.locationState,
        locationCountryCode = entity.locationCountryCode,
        workMode = entity.workMode,
        experienceLevel = entity.experienceLevel,
        jobType = entity.jobType,
        salaryMin = entity.salaryMin,
        salaryMax = entity.salaryMax,
        salaryCurrency = entity.salaryCurrency,
        status = entity.status,
        notes = entity.notes,
        savedAt = entity.savedAt,
        statusUpdatedAt = entity.statusUpdatedAt
    )
}