package com.watchjobs.jobs

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.WorkMode
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.ceil

data class CorpusJobRow(
    val response: JobPostingResponse,
    val discoveredAt: OffsetDateTime,
    val sourceId: Long,
    val externalId: String,
    val salaryPeriod: String?,
    val fullDescription: String?,
    val allowsStorage: Boolean,
    val corpusStatus: String
)

// Consultas do corpus para o usuário. Só vagas não vencidas (expires_at > now()).
@Repository
class JobCorpusQueries(private val jdbc: JdbcClient) {

    fun search(userId: Int, filter: JobSearchFilter): Pair<List<CorpusJobRow>, Long> {
        val where = StringBuilder("WHERE j.corpus_status = 'ACTIVE' AND j.expires_at > now()")
        val params = mutableMapOf<String, Any?>()
        filter.q?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (j.title ILIKE :q ESCAPE '\\' OR j.organization_name ILIKE :q ESCAPE '\\')")
            params["q"] = likePattern(it)
        }
        filter.location?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (j.raw_location ILIKE :location ESCAPE '\\' OR l.city ILIKE :location ESCAPE '\\' OR l.state ILIKE :location ESCAPE '\\')")
            params["location"] = likePattern(it)
        }
        filter.workMode?.let { where.append(" AND j.work_mode = :workMode"); params["workMode"] = it.name }
        filter.level?.let { where.append(" AND j.experience_level = :level"); params["level"] = it.name }
        filter.jobType?.let { where.append(" AND j.job_type = :jobType"); params["jobType"] = it.name }

        val total = jdbc.sql(
            """
            SELECT count(*) FROM discovery.discovered_job j
              LEFT JOIN core."location" l ON l.location_id = j.location_id
            $where
            """
        ).params(params).query(Long::class.java).single()

        val rows = jdbc.sql(
            """
            $SELECT
            $where
            ORDER BY COALESCE(j.posted_at, j.discovered_at) DESC, j.discovered_job_id DESC
            LIMIT :size OFFSET :offset
            """
        )
            .params(params)
            .param("userId", userId)
            .param("size", filter.size)
            .param("offset", filter.page.toLong() * filter.size)
            .query { rs, _ -> map(rs) }
            .list()
        return rows to total
    }

    fun find(userId: Int, id: Long): CorpusJobRow? =
        jdbc.sql(
            """
            $SELECT
            WHERE j.discovered_job_id = :id AND j.expires_at > now()
            """
        )
            .param("userId", userId)
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun skills(id: Long, discoveredAt: OffsetDateTime): List<JobSkillResponse> =
        jdbc.sql(
            """
            SELECT s.skill_name, js.is_required
              FROM discovery.discovered_job_skill js
              JOIN core.skill s ON s.skill_id = js.skill_id
             WHERE js.discovered_job_id = :id AND js.discovered_at = :at
             ORDER BY js.is_required DESC, s.skill_name
            """
        )
            .param("id", id)
            .param("at", discoveredAt)
            .query { rs, _ -> JobSkillResponse(rs.getString("skill_name"), rs.getBoolean("is_required")) }
            .list()

    fun map(rs: ResultSet): CorpusJobRow {
        val expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java)
        return CorpusJobRow(
            response = JobPostingResponse(
                id = rs.getLong("discovered_job_id"),
                title = rs.getString("title"),
                organizationName = rs.getString("organization_name"),
                locationCity = rs.getString("location_city"),
                locationState = rs.getString("location_state"),
                locationCountryCode = rs.getString("country_code"),
                rawLocation = rs.getString("raw_location"),
                workMode = rs.getString("work_mode")?.let(WorkMode::valueOf),
                experienceLevel = rs.getString("experience_level")?.let(ExperienceLevel::valueOf),
                jobType = rs.getString("job_type")?.let(JobType::valueOf),
                roleFamily = rs.getString("role_family"),
                salaryMin = rs.getBigDecimal("salary_min")?.toDouble(),
                salaryMax = rs.getBigDecimal("salary_max")?.toDouble(),
                salaryCurrency = rs.getString("salary_currency")?.trim(),
                jobUrl = rs.getString("job_url"),
                sourceName = rs.getString("source_name"),
                requiresAttribution = rs.getBoolean("requires_attribution"),
                descriptionSnippet = rs.getString("description_snippet"),
                postedAt = rs.getObject("posted_at", OffsetDateTime::class.java),
                discoveredAt = rs.getObject("discovered_at", OffsetDateTime::class.java),
                expiresAt = expiresAt,
                expiresInDays = expiresInDays(expiresAt),
                alreadySaved = rs.getBoolean("already_saved")
            ),
            discoveredAt = rs.getObject("discovered_at", OffsetDateTime::class.java),
            sourceId = rs.getLong("source_id"),
            externalId = rs.getString("external_id"),
            salaryPeriod = rs.getString("salary_period"),
            fullDescription = rs.getString("full_description"),
            allowsStorage = rs.getBoolean("allows_storage"),
            corpusStatus = rs.getString("corpus_status")
        )
    }

    companion object {
        val SELECT = """
            SELECT j.discovered_job_id, j.discovered_at, j.source_id, j.external_id, j.title, j.organization_name,
                   j.job_url, j.work_mode, j.experience_level, j.job_type, j.role_family, j.salary_min, j.salary_max,
                   j.salary_currency, j.salary_period, j.description_snippet, j.full_description, j.posted_at,
                   j.expires_at, j.corpus_status, j.raw_location, j.country_code,
                   l.city AS location_city, l.state AS location_state,
                   s.name AS source_name, s.requires_attribution, s.allows_storage,
                   EXISTS (
                       SELECT 1 FROM core.saved_job sj
                        WHERE sj.users_id = :userId AND sj.source_id = j.source_id AND sj.external_id = j.external_id
                   ) AS already_saved
              FROM discovery.discovered_job j
              JOIN core.job_source s ON s.job_source_id = j.source_id
              LEFT JOIN core."location" l ON l.location_id = j.location_id
        """.trimIndent()

        fun likePattern(value: String): String =
            "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        fun expiresInDays(expiresAt: OffsetDateTime, now: OffsetDateTime = OffsetDateTime.now()): Int =
            ceil(Duration.between(now, expiresAt).seconds / 86_400.0).toInt().coerceAtLeast(0)
    }
}
