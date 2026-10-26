package com.watchjobs.ingestion.store

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

data class JobSeen(
    val sourceId: Long,
    val externalId: String,
    val key: CorpusKey,
    val sourceCompanyId: Long?,
    val contentHash: String,
    val version: Int,
    val closedAt: OffsetDateTime?,
    val expiredAt: OffsetDateTime?
)

// Registro estável de cada vaga por (fonte, código externo): decide se ela é nova, igual, mudou ou expirou.
@Repository
class JobSeenStore(private val jdbc: JdbcClient) {

    fun find(sourceId: Long, externalId: String): JobSeen? =
        jdbc.sql("SELECT * FROM discovery.job_seen WHERE source_id = :sourceId AND external_id = :externalId")
            .param("sourceId", sourceId)
            .param("externalId", externalId)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun insert(sourceId: Long, externalId: String, key: CorpusKey, sourceCompanyId: Long?, contentHash: String, runId: Long) {
        jdbc.sql(
            """
            INSERT INTO discovery.job_seen
                (source_id, external_id, discovered_job_id, discovered_at, source_company_id, content_hash, last_ingestion_run_id)
            VALUES (:sourceId, :externalId, :id, :at, :sourceCompanyId, :contentHash, :runId)
            """
        )
            .param("sourceId", sourceId)
            .param("externalId", externalId)
            .param("id", key.id)
            .param("at", key.discoveredAt)
            .param("sourceCompanyId", sourceCompanyId)
            .param("contentHash", contentHash)
            .param("runId", runId)
            .update()
    }

    fun markSeen(seen: JobSeen, runId: Long) {
        jdbc.sql(
            """
            UPDATE discovery.job_seen
               SET last_seen_at = now(), last_ingestion_run_id = :runId, closed_at = NULL
             WHERE source_id = :sourceId AND external_id = :externalId
            """
        )
            .param("runId", runId)
            .param("sourceId", seen.sourceId)
            .param("externalId", seen.externalId)
            .update()
    }

    fun markChanged(seen: JobSeen, contentHash: String, runId: Long) {
        jdbc.sql(
            """
            UPDATE discovery.job_seen
               SET content_hash = :contentHash, version = version + 1, last_changed_at = now(),
                   last_seen_at = now(), last_ingestion_run_id = :runId, closed_at = NULL
             WHERE source_id = :sourceId AND external_id = :externalId
            """
        )
            .param("contentHash", contentHash)
            .param("runId", runId)
            .param("sourceId", seen.sourceId)
            .param("externalId", seen.externalId)
            .update()
    }

    // A linha do corpus já não existe (partição descartada): a vaga passa a ser ignorada.
    fun markExpired(seen: JobSeen) {
        jdbc.sql(
            """
            UPDATE discovery.job_seen SET expired_at = now()
             WHERE source_id = :sourceId AND external_id = :externalId AND expired_at IS NULL
            """
        )
            .param("sourceId", seen.sourceId)
            .param("externalId", seen.externalId)
            .update()
    }

    fun markClosed(seen: JobSeen) {
        jdbc.sql(
            """
            UPDATE discovery.job_seen SET closed_at = now()
             WHERE source_id = :sourceId AND external_id = :externalId AND closed_at IS NULL
            """
        )
            .param("sourceId", seen.sourceId)
            .param("externalId", seen.externalId)
            .update()
    }

    fun findOpenByCompany(sourceCompanyId: Long): List<JobSeen> =
        jdbc.sql(
            """
            SELECT * FROM discovery.job_seen
             WHERE source_company_id = :sourceCompanyId AND closed_at IS NULL AND expired_at IS NULL
            """
        )
            .param("sourceCompanyId", sourceCompanyId)
            .query { rs, _ -> map(rs) }
            .list()

    private fun map(rs: ResultSet) = JobSeen(
        sourceId = rs.getLong("source_id"),
        externalId = rs.getString("external_id"),
        key = CorpusKey(rs.getLong("discovered_job_id"), rs.getObject("discovered_at", OffsetDateTime::class.java)),
        sourceCompanyId = (rs.getObject("source_company_id") as Number?)?.toLong(),
        contentHash = rs.getString("content_hash").trim(),
        version = rs.getInt("version"),
        closedAt = rs.getObject("closed_at", OffsetDateTime::class.java),
        expiredAt = rs.getObject("expired_at", OffsetDateTime::class.java)
    )
}
