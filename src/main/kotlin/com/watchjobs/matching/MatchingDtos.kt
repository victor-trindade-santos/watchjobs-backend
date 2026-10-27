package com.watchjobs.matching

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.WorkMode
import com.watchjobs.jobs.JobPostingResponse

// SCORE e DATE são da maior para a menor / da mais nova para a mais antiga (valores antigos da API).
enum class FeedSort { SCORE, SCORE_ASC, DATE, DATE_ASC, SALARY_DESC, SALARY_ASC }

enum class SkillMatch { ANY, ALL }

enum class InteractionType { VIEWED, DISMISSED, HIDDEN }

data class FeedFilter(
    val q: String? = null,
    val workModes: List<WorkMode> = emptyList(),
    val levels: List<ExperienceLevel> = emptyList(),
    val jobTypes: List<JobType> = emptyList(),
    val salaryMin: Double? = null,
    val salaryMax: Double? = null,
    val skillIds: List<Long> = emptyList(),
    val skillMatch: SkillMatch = SkillMatch.ANY,
    val roleFamilies: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val cities: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    val minScore: Double? = null,
    val sort: FeedSort = FeedSort.SCORE,
    val page: Int = 0,
    val size: Int = 20
)

data class FeedItem(
    val job: JobPostingResponse,
    val score: Double,
    val highlights: List<String>,
    val skills: List<String> = emptyList()
)

data class FacetValue(val value: String, val count: Long)

data class FeedFacets(
    val roleFamilies: List<FacetValue>,
    val countries: List<FacetValue>,
    val states: List<FacetValue>,
    val cities: List<FacetValue>,
    val companies: List<FacetValue>
)

data class JobSkillFacet(val id: Long, val name: String, val count: Long)

data class FeedResponse(
    val items: List<FeedItem>,
    val page: Int,
    val size: Int,
    val total: Long,
    val profileVersion: Int
)

data class InteractionRequest(val type: InteractionType? = null)

data class RefreshResponse(val scored: Int, val profileVersion: Int)
