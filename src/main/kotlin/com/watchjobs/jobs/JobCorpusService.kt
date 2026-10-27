package com.watchjobs.jobs

import com.watchjobs.board.ApplicationEventService
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.exception.ConflictException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.matching.FeedService
import com.watchjobs.service.SavedJobService
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class JobCorpusService(
    private val queries: JobCorpusQueries,
    private val jdbc: JdbcClient,
    private val savedJobService: SavedJobService,
    private val feed: FeedService,
    private val events: ApplicationEventService,
    private val mapper: ObjectMapper
) {
    @Transactional(readOnly = true)
    fun search(userId: Int, filter: JobSearchFilter): JobSearchResponse {
        val safe = filter.copy(page = filter.page.coerceAtLeast(0), size = filter.size.coerceIn(1, MAX_PAGE_SIZE))
        val (rows, total) = queries.search(userId, safe)
        return JobSearchResponse(rows.map { it.response }, safe.page, safe.size, total)
    }

    @Transactional
    fun detail(userId: Int, id: Long): JobDetailResponse {
        val row = queries.find(userId, id) ?: throw NotFoundException("Vaga não encontrada")
        return JobDetailResponse(
            job = row.response,
            description = row.fullDescription,
            skills = queries.skills(id, row.discoveredAt),
            corpusStatus = row.corpusStatus,
            match = feed.matchFor(userId, id)
        )
    }

    // Salvar é a forma de guardar a vaga além dos 30 dias: a cópia em core.saved_job nunca expira.
    @Transactional
    fun save(userId: Int, id: Long): SavedJobResponse {
        val row = queries.find(userId, id) ?: throw NotFoundException("Vaga não encontrada")
        if (row.response.alreadySaved) throw ConflictException(ALREADY_SAVED)
        val job = row.response
        val skills = queries.skills(id, row.discoveredAt)
        val requirements = mapper.writeValueAsString(
            mapOf(
                "skills" to skills.map { mapOf("name" to it.name, "required" to it.required) },
                "roleFamily" to job.roleFamily,
                "source" to job.sourceName,
                "discoveredJobId" to job.id,
                "rawLocation" to job.rawLocation
            )
        )
        // F4-04: a nota e o porquê do momento ficam congelados na vaga salva.
        val match = feed.matchFor(userId, id)
        val savedJobId = try {
            jdbc.sql(
                """
                INSERT INTO core.saved_job (
                    users_id, source_id, external_id, position_role, organization_name, location_city, location_state,
                    location_country_code, work_mode, experience_level, job_type, salary_min, salary_max,
                    salary_currency, salary_period, full_description, job_url, requirements_snapshot, status,
                    match_score_at_save, match_breakdown_at_save, discovered_job_id, role_family, board_rank)
                VALUES (
                    :userId, :sourceId, :externalId, :title, :organizationName, :city, :state, :countryCode,
                    :workMode, :level, :jobType, :salaryMin, :salaryMax, :salaryCurrency, :salaryPeriod,
                    :description, :jobUrl, CAST(:requirements AS jsonb), 'SAVED',
                    :score, CAST(:breakdown AS jsonb), :discoveredJobId, :roleFamily,
                    (SELECT COALESCE(max(board_rank), 0) + 1000 FROM core.saved_job
                      WHERE users_id = :userId AND status IN ('SAVED', 'PLANNING_TO_APPLY')))
                RETURNING saved_job_id
                """
            )
                .param("userId", userId)
                .param("sourceId", row.sourceId)
                .param("externalId", row.externalId)
                .param("title", job.title.take(255))
                .param("organizationName", job.organizationName.take(150))
                .param("city", job.locationCity)
                .param("state", job.locationState)
                .param("countryCode", job.locationCountryCode)
                .param("workMode", job.workMode?.name)
                .param("level", job.experienceLevel?.name)
                .param("jobType", job.jobType?.name)
                .param("salaryMin", job.salaryMin)
                .param("salaryMax", job.salaryMax)
                .param("salaryCurrency", job.salaryCurrency)
                .param("salaryPeriod", row.salaryPeriod)
                // A descrição completa só é copiada quando a fonte permite guardar; senão, fica o trecho.
                .param("description", row.fullDescription?.takeIf { row.allowsStorage } ?: job.descriptionSnippet)
                .param("jobUrl", job.jobUrl)
                .param("requirements", requirements)
                .param("score", match?.score?.toBigDecimal())
                .param("breakdown", match?.let { mapper.writeValueAsString(it) })
                .param("discoveredJobId", job.id)
                .param("roleFamily", job.roleFamily)
                .query(Long::class.java)
                .single()
        } catch (ex: DuplicateKeyException) {
            throw ConflictException(ALREADY_SAVED)
        }
        events.saved(savedJobId, userId)
        return savedJobService.findById(userId, savedJobId)
    }

    companion object {
        const val MAX_PAGE_SIZE = 50
        const val ALREADY_SAVED = "Você já salvou esta vaga"
    }
}
