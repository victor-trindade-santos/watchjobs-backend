package com.watchjobs.jobs

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.WorkMode
import java.time.OffsetDateTime

data class JobSearchFilter(
    val q: String? = null,
    val location: String? = null,
    val workMode: WorkMode? = null,
    val level: ExperienceLevel? = null,
    val jobType: JobType? = null,
    val page: Int = 0,
    val size: Int = 20
)

data class JobPostingResponse(
    val id: Long,
    val title: String,
    val organizationName: String,
    val locationCity: String?,
    val locationState: String?,
    val locationCountryCode: String?,
    val rawLocation: String?,
    val workMode: WorkMode?,
    val experienceLevel: ExperienceLevel?,
    val jobType: JobType?,
    val roleFamily: String?,
    val salaryMin: Double?,
    val salaryMax: Double?,
    val salaryCurrency: String?,
    val jobUrl: String,
    val sourceName: String,
    val requiresAttribution: Boolean,
    val descriptionSnippet: String?,
    val postedAt: OffsetDateTime?,
    val discoveredAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val expiresInDays: Int,
    val alreadySaved: Boolean
)

data class JobSearchResponse(
    val items: List<JobPostingResponse>,
    val page: Int,
    val size: Int,
    val total: Long
)

data class JobSkillResponse(val name: String, val required: Boolean)

data class JobDetailResponse(
    val job: JobPostingResponse,
    val description: String?,
    val skills: List<JobSkillResponse>,
    val corpusStatus: String,
    val match: com.watchjobs.matching.MatchResult? = null
)
