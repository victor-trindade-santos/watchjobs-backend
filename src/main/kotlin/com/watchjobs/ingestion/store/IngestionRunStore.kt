package com.watchjobs.ingestion.store

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

enum class RunStatus { RUNNING, COMPLETED, PARTIAL, FAILED }

enum class TriggerType { SCHEDULED, MANUAL, ON_DEMAND, PARTNER_UPLOAD, BACKFILL }

data class RunCounters(
    var fetched: Int = 0,
    var new: Int = 0,
    var updated: Int = 0,
    var deduplicated: Int = 0,
    var rejected: Int = 0,
    var closed: Int = 0
) {
    operator fun plusAssign(other: RunCounters) {
        fetched += other.fetched
        new += other.new
        updated += other.updated
        deduplicated += other.deduplicated
        rejected += other.rejected
        closed += other.closed
    }
}

// Auditoria de cada coleta (uma linha por alvo). Também é a contagem da cota diária de cada fonte.
@Repository
class IngestionRunStore(private val jdbc: JdbcClient) {

    fun open(sourceId: Long, sourceCompanyId: Long?, segmentId: Long?, trigger: TriggerType): Long =
        jdbc.sql(
            """
            INSERT INTO discovery.ingestion_run (source_id, source_company_id, segment_id, trigger_type, status)
            VALUES (:sourceId, :sourceCompanyId, :segmentId, :trigger, 'RUNNING')
            RETURNING ingestion_run_id
            """
        )
            .param("sourceId", sourceId)
            .param("sourceCompanyId", sourceCompanyId)
            .param("segmentId", segmentId)
            .param("trigger", trigger.name)
            .query(Long::class.java)
            .single()

    fun close(runId: Long, status: RunStatus, counters: RunCounters, fullSnapshot: Boolean, error: String? = null) {
        jdbc.sql(
            """
            UPDATE discovery.ingestion_run
               SET status = :status, completed_at = now(), is_full_snapshot = :fullSnapshot,
                   jobs_fetched = :fetched, jobs_new = :new, jobs_updated = :updated,
                   jobs_deduplicated = :deduplicated, jobs_rejected = :rejected, jobs_closed = :closed,
                   error_message = :error
             WHERE ingestion_run_id = :runId
            """
        )
            .param("status", status.name)
            .param("fullSnapshot", fullSnapshot)
            .param("fetched", counters.fetched)
            .param("new", counters.new)
            .param("updated", counters.updated)
            .param("deduplicated", counters.deduplicated)
            .param("rejected", counters.rejected)
            .param("closed", counters.closed)
            .param("error", error?.take(2000))
            .param("runId", runId)
            .update()
    }

    // Chamadas do dia (UTC) a uma fonte: cada coleta de agregador é uma chamada.
    fun countToday(sourceId: Long): Int =
        jdbc.sql(
            """
            SELECT count(*) FROM discovery.ingestion_run
             WHERE source_id = :sourceId AND started_at >= date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
            """
        )
            .param("sourceId", sourceId)
            .query(Int::class.java)
            .single()
}
