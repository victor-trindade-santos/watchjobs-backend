package com.watchjobs.ingestion.store

import com.watchjobs.ingestion.normalize.ExtractedSkill
import com.watchjobs.ingestion.normalize.NormalizedJob
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

// Chave da vaga no corpus: o id e o dia da partição (a PK é composta por causa do particionamento).
data class CorpusKey(val id: Long, val discoveredAt: OffsetDateTime)

// Acesso ao corpus com SQL direto: partições, chave composta e upsert ficam mais previsíveis sem JPA.
@Repository
class DiscoveredJobStore(
    private val jdbc: JdbcClient,
    @Value("\${app.corpus.retention-days:30}") private val retentionDays: Int
) {
    // As filhas usam o mesmo discovered_at devolvido aqui, para caírem na mesma partição do dia.
    fun insert(
        job: NormalizedJob,
        organizationId: Long?,
        locationId: Long?,
        discoveredAt: OffsetDateTime = OffsetDateTime.now()
    ): CorpusKey =
        jdbc.sql(
            """
            INSERT INTO discovery.discovered_job (
                discovered_at, source_id, external_id, organization_id, location_id, title, organization_name,
                job_url, work_mode, experience_level, job_type, salary_min, salary_max, salary_currency,
                salary_period, full_description, posted_at, expires_at, corpus_status, fingerprint, content_hash,
                last_seen_at, source_company_id, role_family, country_code, raw_location, description_snippet,
                classification_source, classification_confidence, rules_version)
            VALUES (
                :discoveredAt, :sourceId, :externalId, :organizationId, :locationId, :title, :organizationName,
                :jobUrl, :workMode, :experienceLevel, :jobType, :salaryMin, :salaryMax, :salaryCurrency,
                :salaryPeriod, :fullDescription, :postedAt,
                CAST(:discoveredAt AS timestamptz) + make_interval(days => :retentionDays),
                'ACTIVE', :fingerprint, :contentHash, now(), :sourceCompanyId, :roleFamily, :countryCode,
                :rawLocation, :snippet, 'RULES', :confidence, :rulesVersion)
            RETURNING discovered_job_id, discovered_at
            """
        )
            .params(fields(job, organizationId, locationId))
            .param("discoveredAt", discoveredAt)
            .param("retentionDays", retentionDays)
            .query { rs, _ -> CorpusKey(rs.getLong(1), rs.getObject(2, OffsetDateTime::class.java)) }
            .single()

    fun update(key: CorpusKey, job: NormalizedJob, organizationId: Long?, locationId: Long?): Boolean =
        jdbc.sql(
            """
            UPDATE discovery.discovered_job
               SET organization_id = :organizationId, location_id = :locationId, title = :title,
                   organization_name = :organizationName, job_url = :jobUrl, work_mode = :workMode,
                   experience_level = :experienceLevel, job_type = :jobType, salary_min = :salaryMin,
                   salary_max = :salaryMax, salary_currency = :salaryCurrency, salary_period = :salaryPeriod,
                   full_description = :fullDescription, posted_at = :postedAt, fingerprint = :fingerprint,
                   content_hash = :contentHash, last_seen_at = now(), source_company_id = :sourceCompanyId,
                   role_family = :roleFamily, country_code = :countryCode, raw_location = :rawLocation,
                   description_snippet = :snippet, classification_source = 'RULES',
                   classification_confidence = :confidence, rules_version = :rulesVersion,
                   corpus_status = 'ACTIVE'
             WHERE discovered_job_id = :id AND discovered_at = :discoveredAt
            """
        )
            .params(fields(job, organizationId, locationId))
            .param("id", key.id)
            .param("discoveredAt", key.discoveredAt)
            .update() == 1

    fun replaceSkills(key: CorpusKey, skills: List<ExtractedSkill>) {
        jdbc.sql("DELETE FROM discovery.discovered_job_skill WHERE discovered_job_id = :id AND discovered_at = :at")
            .param("id", key.id)
            .param("at", key.discoveredAt)
            .update()
        insertSkills(key, skills)
    }

    fun insertSkills(key: CorpusKey, skills: List<ExtractedSkill>) {
        skills.forEach { skill ->
            jdbc.sql(
                """
                INSERT INTO discovery.discovered_job_skill (discovered_job_id, discovered_at, skill_id, is_required)
                VALUES (:id, :at, :skillId, :required)
                ON CONFLICT DO NOTHING
                """
            )
                .param("id", key.id)
                .param("at", key.discoveredAt)
                .param("skillId", skill.skillId)
                .param("required", skill.required)
                .update()
        }
    }

    // Vaga vista de novo sem mudança: só last_seen_at, e volta a ACTIVE se tinha sido dada como fechada.
    fun touch(key: CorpusKey): Boolean =
        jdbc.sql(
            """
            UPDATE discovery.discovered_job
               SET last_seen_at = now(),
                   corpus_status = CASE WHEN corpus_status = 'PROBABLY_CLOSED' THEN 'ACTIVE' ELSE corpus_status END
             WHERE discovered_job_id = :id AND discovered_at = :at
            """
        )
            .param("id", key.id)
            .param("at", key.discoveredAt)
            .update() == 1

    fun markProbablyClosed(key: CorpusKey): Boolean =
        jdbc.sql(
            """
            UPDATE discovery.discovered_job SET corpus_status = 'PROBABLY_CLOSED'
             WHERE discovered_job_id = :id AND discovered_at = :at AND corpus_status = 'ACTIVE'
            """
        )
            .param("id", key.id)
            .param("at", key.discoveredAt)
            .update() == 1

    fun skillIds(key: CorpusKey): List<Long> =
        jdbc.sql(
            """
            SELECT skill_id FROM discovery.discovered_job_skill
             WHERE discovered_job_id = :id AND discovered_at = :at ORDER BY skill_id
            """
        )
            .param("id", key.id)
            .param("at", key.discoveredAt)
            .query(Long::class.java)
            .list()
            .filterNotNull()

    private fun fields(job: NormalizedJob, organizationId: Long?, locationId: Long?): Map<String, Any?> = mapOf(
        "sourceId" to job.sourceId,
        "externalId" to job.externalId,
        "organizationId" to organizationId,
        "locationId" to locationId,
        "title" to job.title,
        "organizationName" to job.organizationName,
        "jobUrl" to job.jobUrl,
        "workMode" to job.workMode,
        "experienceLevel" to job.experienceLevel,
        "jobType" to job.jobType,
        "salaryMin" to job.salaryMin,
        "salaryMax" to job.salaryMax,
        "salaryCurrency" to job.salaryCurrency,
        "salaryPeriod" to job.salaryPeriod,
        "fullDescription" to job.fullDescription,
        "postedAt" to job.postedAt,
        "fingerprint" to job.fingerprint,
        "contentHash" to job.contentHash,
        "sourceCompanyId" to job.sourceCompanyId,
        "roleFamily" to job.roleFamily,
        "countryCode" to job.countryCode,
        "rawLocation" to job.rawLocation,
        "snippet" to job.descriptionSnippet,
        "confidence" to job.classificationConfidence,
        "rulesVersion" to job.rulesVersion
    )
}
