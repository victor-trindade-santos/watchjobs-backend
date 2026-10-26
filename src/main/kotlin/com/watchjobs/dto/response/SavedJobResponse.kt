package com.watchjobs.dto.response

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.entity.enums.WorkMode
import com.watchjobs.jobs.JobSkillResponse
import java.time.LocalDateTime

data class SavedJobResponse(
    val id: Long,
    val positionRole: String,
    val jobUrl: String?,
    val organizationName: String,
    val locationCity: String?,
    val locationState: String?,
    val locationCountryCode: String?,
    val workMode: WorkMode?,
    val experienceLevel: ExperienceLevel?,
    val jobType: JobType?,
    val salaryMin: Double?,
    val salaryMax: Double?,
    val salaryCurrency: String?,
    val status: SavedJobStatus,
    val notes: String?,
    val savedAt: LocalDateTime,
    val statusUpdatedAt: LocalDateTime,
    // Conteúdo copiado da vaga ao salvar do corpus; só vem no GET /saved-jobs/{id}.
    val fullDescription: String? = null,
    val skills: List<JobSkillResponse> = emptyList(),
    val sourceName: String? = null,
    val roleFamily: String? = null,
    val rawLocation: String? = null
)