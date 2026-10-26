package com.watchjobs.ingestion.store

import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.sources.SourceType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

data class SourceRow(
    val id: Long,
    val code: String,
    val name: String,
    val type: SourceType,
    val baseUrl: String?,
    val allowsStorage: Boolean,
    val credentialsRef: String?,
    val rateLimitPerDay: Int?,
    val config: Map<String, Any?>
)

// Planejamento da rodada (fontes ativas e alvos vencidos) e saúde das fontes e empresas.
@Repository
class SourceTargetStore(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper
) {
    // Respeita is_active e o circuit breaker (next_attempt_at).
    fun activeSources(): List<SourceRow> =
        jdbc.sql(
            """
            SELECT job_source_id, code, name, source_type, base_url, allows_storage, credentials_ref,
                   rate_limit_per_day, config::text AS config
              FROM core.job_source
             WHERE is_active AND (next_attempt_at IS NULL OR next_attempt_at <= now())
             ORDER BY trust_rank, job_source_id
            """
        ).query { rs, _ ->
            SourceRow(
                id = rs.getLong("job_source_id"),
                code = rs.getString("code"),
                name = rs.getString("name"),
                type = SourceType.valueOf(rs.getString("source_type")),
                baseUrl = rs.getString("base_url"),
                allowsStorage = rs.getBoolean("allows_storage"),
                credentialsRef = rs.getString("credentials_ref"),
                rateLimitPerDay = (rs.getObject("rate_limit_per_day") as Number?)?.toInt(),
                config = parseConfig(rs.getString("config"))
            )
        }.list()

    // force = ignora o intervalo de coleta (execução manual).
    fun companies(sourceId: Long, onlyKey: String?, force: Boolean): List<FetchTarget> {
        val filters = buildList {
            if (onlyKey != null) add("AND sc.external_key = :key")
            if (!force) add("AND (sc.last_fetched_at IS NULL OR sc.last_fetched_at <= now() - make_interval(mins => sc.fetch_interval_minutes))")
        }.joinToString(" ")
        var spec = jdbc.sql(
            """
            SELECT sc.source_company_id, sc.external_key, sc.organization_id, o.name, sc.last_job_count
              FROM core.source_company sc
              JOIN core.organization o ON o.organization_id = sc.organization_id
             WHERE sc.job_source_id = :sourceId AND sc.is_active $filters
             ORDER BY sc.last_fetched_at NULLS FIRST, sc.source_company_id
            """
        ).param("sourceId", sourceId)
        if (onlyKey != null) spec = spec.param("key", onlyKey)
        return spec.query { rs, _ ->
            FetchTarget(
                sourceCompanyId = rs.getLong("source_company_id"),
                externalKey = rs.getString("external_key"),
                organizationId = rs.getLong("organization_id"),
                organizationName = rs.getString("name"),
                lastJobCount = (rs.getObject("last_job_count") as Number?)?.toInt()
            )
        }.list()
    }

    // Segmentos com mais demanda primeiro; sem force, só os não coletados nas últimas 6 horas.
    fun segments(force: Boolean): List<FetchTarget> {
        val due = if (force) "" else "AND (last_ingested_at IS NULL OR last_ingested_at <= now() - interval '6 hours')"
        return jdbc.sql(
            """
            SELECT segment_id, role_family, country_code, work_mode, keywords
              FROM discovery.coverage_segment
             WHERE is_active $due
             ORDER BY demand_score DESC, last_ingested_at NULLS FIRST, segment_id
            """
        ).query { rs, _ ->
            FetchTarget(
                segmentId = rs.getLong("segment_id"),
                roleFamily = rs.getString("role_family"),
                countryCode = rs.getString("country_code"),
                workMode = rs.getString("work_mode"),
                keywords = rs.getString("keywords")
            )
        }.list()
    }

    fun companySucceeded(sourceCompanyId: Long, jobCount: Int?) {
        jdbc.sql(
            """
            UPDATE core.source_company
               SET last_fetched_at = now(), last_success_at = now(), consecutive_failures = 0,
                   last_job_count = COALESCE(CAST(:jobCount AS int4), last_job_count), last_error = NULL
             WHERE source_company_id = :id
            """
        )
            .param("jobCount", jobCount)
            .param("id", sourceCompanyId)
            .update()
    }

    fun companyFailed(sourceCompanyId: Long, error: String?) {
        jdbc.sql(
            """
            UPDATE core.source_company
               SET last_fetched_at = now(), last_error_at = now(), last_error = :error,
                   consecutive_failures = consecutive_failures + 1
             WHERE source_company_id = :id
            """
        )
            .param("error", error?.take(2000))
            .param("id", sourceCompanyId)
            .update()
    }

    fun segmentIngested(segmentId: Long) {
        jdbc.sql("UPDATE discovery.coverage_segment SET last_ingested_at = now() WHERE segment_id = :id")
            .param("id", segmentId)
            .update()
    }

    fun sourceSucceeded(sourceId: Long) {
        jdbc.sql(
            """
            UPDATE core.job_source
               SET consecutive_failures = 0, last_success_at = now(), health_status = 'HEALTHY',
                   circuit_breaker_state = 'CLOSED', circuit_opened_at = NULL, next_attempt_at = NULL,
                   last_health_check_at = now()
             WHERE job_source_id = :id
            """
        ).param("id", sourceId).update()
    }

    // Depois de 5 rodadas seguidas com falha, o circuito abre e a fonte descansa por 1 hora.
    fun sourceFailed(sourceId: Long) {
        jdbc.sql(
            """
            UPDATE core.job_source
               SET consecutive_failures = consecutive_failures + 1,
                   health_status = CASE WHEN consecutive_failures + 1 >= 5 THEN 'DOWN' ELSE 'DEGRADED' END,
                   circuit_breaker_state = CASE WHEN consecutive_failures + 1 >= 5 THEN 'OPEN' ELSE circuit_breaker_state END,
                   circuit_opened_at = CASE WHEN consecutive_failures + 1 >= 5 THEN now() ELSE circuit_opened_at END,
                   next_attempt_at = CASE WHEN consecutive_failures + 1 >= 5 THEN now() + interval '1 hour' ELSE next_attempt_at END,
                   last_health_check_at = now()
             WHERE job_source_id = :id
            """
        ).param("id", sourceId).update()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(json: String?): Map<String, Any?> =
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { mapper.readValue(json, Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
}
