package com.watchjobs.ingestion.ondemand

import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.jobs.JobCorpusQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

data class SearchRequestRow(
    val id: Long,
    val userId: Int,
    val queryText: String,
    val locationText: String?,
    val workMode: String?,
    val queryKey: String,
    val status: String,
    val cacheHit: Boolean,
    val syncFound: Int,
    val asyncFound: Int,
    val ingestionTaskId: Long?,
    val requestedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val errorMessage: String?
)

// Consultas da busca sob demanda: pedidos, limites por usuário, cache e as vagas do corpus que casam com a consulta.
@Repository
class OnDemandQueries(private val jdbc: JdbcClient) {

    fun countSince(userId: Int, interval: String): Int =
        jdbc.sql("SELECT count(*) FROM discovery.search_request WHERE users_id = :userId AND requested_at > now() - CAST(:interval AS interval)")
            .param("userId", userId).param("interval", interval).query(Int::class.java).single()

    fun recentlyFetched(queryKey: String, cacheHours: Int): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM discovery.search_request
                            WHERE query_key = :key AND NOT cache_hit AND sync_completed_at IS NOT NULL
                              AND sync_completed_at > now() - make_interval(hours => :hours))
            """
        ).param("key", queryKey).param("hours", cacheHours).query(Boolean::class.java).single()

    fun create(userId: Int, q: String, location: String?, workMode: String?, queryKey: String, cacheHit: Boolean): Long =
        jdbc.sql(
            """
            INSERT INTO discovery.search_request (users_id, query_text, location_text, work_mode, query_key, cache_hit)
            VALUES (:userId, :q, :location, :workMode, :key, :cacheHit)
            RETURNING search_request_id
            """
        ).param("userId", userId).param("q", q).param("location", location).param("workMode", workMode)
            .param("key", queryKey).param("cacheHit", cacheHit)
            .query(Long::class.java).single()

    fun syncDone(id: Long, found: Int, taskId: Long?, error: String?) {
        jdbc.sql(
            """
            UPDATE discovery.search_request
               SET status = 'SYNC_DONE', sync_found = :found, sync_completed_at = now(), ingestion_task_id = :taskId,
                   error_message = :error
             WHERE search_request_id = :id
            """
        ).param("found", found).param("taskId", taskId).param("error", error?.take(500)).param("id", id).update()
    }

    fun completed(id: Long, found: Int) {
        jdbc.sql(
            "UPDATE discovery.search_request SET status = 'COMPLETED', async_found = :found, completed_at = now() WHERE search_request_id = :id"
        ).param("found", found).param("id", id).update()
    }

    fun find(id: Long, userId: Int? = null): SearchRequestRow? =
        jdbc.sql("$SELECT WHERE search_request_id = :id AND (CAST(:userId AS int4) IS NULL OR users_id = CAST(:userId AS int4))")
            .param("id", id).param("userId", userId)
            .query { rs, _ -> row(rs) }.optional().orElse(null)

    fun byTask(taskId: Long): List<SearchRequestRow> =
        jdbc.sql("$SELECT WHERE ingestion_task_id = :taskId AND status IN ('RUNNING', 'SYNC_DONE')")
            .param("taskId", taskId).query { rs, _ -> row(rs) }.list()

    // Tarefa do motor por consulta e hora: várias pessoas buscando a mesma coisa geram uma coleta só.
    fun enqueue(queryKey: String, hourKey: String, payload: String): Long {
        val key = "ondemand:$queryKey:$hourKey"
        jdbc.sql(
            """
            INSERT INTO discovery.ingestion_task (task_type, payload, priority, idempotency_key)
            VALUES ('ON_DEMAND_SEARCH', CAST(:payload AS jsonb), 1, :key)
            ON CONFLICT (idempotency_key) DO NOTHING
            """
        ).param("payload", payload).param("key", key).update()
        return jdbc.sql("SELECT ingestion_task_id FROM discovery.ingestion_task WHERE idempotency_key = :key")
            .param("key", key).query(Long::class.java).single()
    }

    // Todas as palavras da consulta precisam aparecer no cargo, na empresa ou no trecho da vaga.
    fun matchingIds(q: String, location: String?, workMode: String?, seenSince: OffsetDateTime? = null, limit: Int = 100): List<Long> {
        val tokens = TextNormalizer.collapseSpaces(q).split(' ').filter { it.length >= 2 }.take(6)
        if (tokens.isEmpty()) return emptyList()
        val where = StringBuilder("WHERE j.corpus_status = 'ACTIVE' AND j.expires_at > now()")
        val params = mutableMapOf<String, Any?>("limit" to limit)
        tokens.forEachIndexed { i, token ->
            where.append(
                " AND (j.title ILIKE :t$i ESCAPE '\\' OR j.organization_name ILIKE :t$i ESCAPE '\\'" +
                    " OR j.description_snippet ILIKE :t$i ESCAPE '\\')"
            )
            params["t$i"] = JobCorpusQueries.likePattern(token)
        }
        location?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where.append(" AND (j.raw_location ILIKE :location ESCAPE '\\' OR j.work_mode = 'REMOTE')")
            params["location"] = JobCorpusQueries.likePattern(it)
        }
        workMode?.let { where.append(" AND (j.work_mode = :workMode OR j.work_mode IS NULL)"); params["workMode"] = it }
        seenSince?.let { where.append(" AND j.last_seen_at >= :seenSince"); params["seenSince"] = it }
        return jdbc.sql(
            """
            SELECT j.discovered_job_id FROM discovery.discovered_job j
            $where
            ORDER BY COALESCE(j.posted_at, j.discovered_at) DESC
            LIMIT :limit
            """
        ).params(params).query(Long::class.java).list().filterNotNull()
    }

    fun idsByExternal(sourceId: Long, externalIds: Collection<String>): List<Long> =
        if (externalIds.isEmpty()) emptyList()
        else jdbc.sql(
            """
            SELECT discovered_job_id FROM discovery.discovered_job
             WHERE source_id = :sourceId AND external_id IN (:ids) AND corpus_status = 'ACTIVE' AND expires_at > now()
            """
        ).param("sourceId", sourceId).param("ids", externalIds).query(Long::class.java).list().filterNotNull()

    private fun row(rs: java.sql.ResultSet) = SearchRequestRow(
        id = rs.getLong("search_request_id"),
        userId = rs.getInt("users_id"),
        queryText = rs.getString("query_text"),
        locationText = rs.getString("location_text"),
        workMode = rs.getString("work_mode"),
        queryKey = rs.getString("query_key"),
        status = rs.getString("status"),
        cacheHit = rs.getBoolean("cache_hit"),
        syncFound = rs.getInt("sync_found"),
        asyncFound = rs.getInt("async_found"),
        ingestionTaskId = (rs.getObject("ingestion_task_id") as Number?)?.toLong(),
        requestedAt = rs.getObject("requested_at", OffsetDateTime::class.java),
        completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
        errorMessage = rs.getString("error_message")
    )

    companion object {
        private const val SELECT = """
            SELECT search_request_id, users_id, query_text, location_text, work_mode, query_key, status, cache_hit,
                   sync_found, async_found, ingestion_task_id, requested_at, completed_at, error_message
              FROM discovery.search_request
        """
    }
}
