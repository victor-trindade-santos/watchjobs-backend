package com.watchjobs.ingestion.store

import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.pipeline.Circuit
import com.watchjobs.ingestion.pipeline.CircuitBreaker
import com.watchjobs.ingestion.pipeline.CircuitPolicy
import com.watchjobs.ingestion.pipeline.CircuitState
import com.watchjobs.sources.SourceType
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

data class SourceRow(
    val id: Long,
    val code: String,
    val name: String,
    val type: SourceType,
    val baseUrl: String?,
    val allowsStorage: Boolean,
    val credentialsRef: String?,
    val rateLimitPerDay: Int?,
    val config: Map<String, Any?>,
    val trustRank: Int = type.defaultTrustRank,
    val circuit: Circuit = Circuit()
)

// Planejamento da rodada (fontes ativas e alvos vencidos) e saúde das fontes e empresas.
@Repository
class SourceTargetStore(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    @Value("\${app.ingestion.circuit.failure-threshold:5}") private val defaultFailureThreshold: Int,
    @Value("\${app.ingestion.circuit.recovery-minutes:60}") private val defaultRecoveryMinutes: Long
) {
    // Respeita is_active e o circuit breaker: circuito aberto só volta depois de next_attempt_at.
    fun activeSources(): List<SourceRow> =
        jdbc.sql(
            """
            SELECT job_source_id, code, name, source_type, base_url, allows_storage, credentials_ref,
                   rate_limit_per_day, config::text AS config, trust_rank, circuit_breaker_state,
                   consecutive_failures, circuit_opened_at, next_attempt_at
              FROM core.job_source
             WHERE is_active AND (next_attempt_at IS NULL OR next_attempt_at <= now())
             ORDER BY trust_rank, job_source_id
            """
        ).query { rs, _ ->
            val type = SourceType.valueOf(rs.getString("source_type"))
            SourceRow(
                id = rs.getLong("job_source_id"),
                code = rs.getString("code"),
                name = rs.getString("name"),
                type = type,
                baseUrl = rs.getString("base_url"),
                allowsStorage = rs.getBoolean("allows_storage"),
                credentialsRef = rs.getString("credentials_ref"),
                rateLimitPerDay = (rs.getObject("rate_limit_per_day") as Number?)?.toInt(),
                config = parseConfig(rs.getString("config")),
                trustRank = rs.getInt("trust_rank"),
                circuit = Circuit(
                    state = CircuitState.valueOf(rs.getString("circuit_breaker_state")),
                    failures = rs.getInt("consecutive_failures"),
                    openedAt = rs.getObject("circuit_opened_at", OffsetDateTime::class.java),
                    nextAttemptAt = rs.getObject("next_attempt_at", OffsetDateTime::class.java)
                )
            )
        }.list()

    fun sourceByCode(code: String): SourceRow? = jdbc.sql(
        """
        SELECT job_source_id, code, name, source_type, base_url, allows_storage, credentials_ref,
               rate_limit_per_day, config::text AS config, trust_rank, is_active
          FROM core.job_source WHERE code = :code
        """
    ).param("code", code).query { rs, _ ->
        val type = SourceType.valueOf(rs.getString("source_type"))
        SourceRow(
            rs.getLong("job_source_id"), rs.getString("code"), rs.getString("name"), type, rs.getString("base_url"),
            rs.getBoolean("allows_storage"), rs.getString("credentials_ref"), (rs.getObject("rate_limit_per_day") as Number?)?.toInt(),
            parseConfig(rs.getString("config")), rs.getInt("trust_rank")
        ).takeIf { rs.getBoolean("is_active") }
    }.optional().orElse(null)

    // force = ignora o intervalo de coleta (execução manual). Empresa que falha seguido entra em backoff
    // (1 h, 2 h, 4 h... até 24 h) sem afetar as outras empresas da mesma fonte.
    fun companies(sourceId: Long, onlyKey: String?, force: Boolean): List<FetchTarget> {
        val filters = buildList {
            if (onlyKey != null) add("AND sc.external_key = :key")
            if (!force) {
                add("AND (sc.last_fetched_at IS NULL OR sc.last_fetched_at <= now() - make_interval(mins => sc.fetch_interval_minutes))")
                add("AND (sc.consecutive_failures < 3 OR sc.last_error_at IS NULL OR sc.last_error_at <= now() - make_interval(mins => LEAST(1440, 60 * power(2, sc.consecutive_failures - 3)::int)))")
            }
        }.joinToString(" ")
        var spec = jdbc.sql(
            """
            SELECT sc.source_company_id, sc.external_key, sc.organization_id, o.name, sc.last_job_count, sc.careers_url
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
                lastJobCount = (rs.getObject("last_job_count") as Number?)?.toInt(),
                careersUrl = rs.getString("careers_url")
            )
        }.list()
    }

    // Busca sob demanda: empresas que não foram coletadas na última hora (independe do intervalo da empresa).
    fun companiesNotFetchedSince(sourceId: Long, minutes: Int): List<FetchTarget> =
        jdbc.sql(
            """
            SELECT sc.source_company_id, sc.external_key, sc.organization_id, o.name, sc.last_job_count
              FROM core.source_company sc
              JOIN core.organization o ON o.organization_id = sc.organization_id
             WHERE sc.job_source_id = :sourceId AND sc.is_active
               AND (sc.last_fetched_at IS NULL OR sc.last_fetched_at <= now() - make_interval(mins => :minutes))
             ORDER BY sc.last_fetched_at NULLS FIRST, sc.source_company_id
            """
        ).param("sourceId", sourceId).param("minutes", minutes).query { rs, _ ->
            FetchTarget(
                sourceCompanyId = rs.getLong("source_company_id"),
                externalKey = rs.getString("external_key"),
                organizationId = rs.getLong("organization_id"),
                organizationName = rs.getString("name"),
                lastJobCount = (rs.getObject("last_job_count") as Number?)?.toInt()
            )
        }.list()

    // Segmentos com mais demanda primeiro. O intervalo é por fonte: sem force, só os que esta fonte não coletou
    // (COMPLETED ou PARTIAL) nas últimas 6 horas. workModes restringe os modos aceitos (ex.: só REMOTE).
    fun segments(sourceId: Long, force: Boolean, workModes: List<String>? = null): List<FetchTarget> {
        val filters = buildList {
            if (!force) add("AND (r.last_run IS NULL OR r.last_run <= now() - interval '6 hours')")
            if (workModes != null) add("AND cs.work_mode IN (:workModes)")
        }.joinToString(" ")
        var spec = jdbc.sql(
            """
            SELECT cs.segment_id, cs.role_family, cs.country_code, cs.work_mode, cs.keywords
              FROM discovery.coverage_segment cs
              LEFT JOIN LATERAL (
                   SELECT max(ir.started_at) AS last_run
                     FROM discovery.ingestion_run ir
                    WHERE ir.source_id = :sourceId AND ir.segment_id = cs.segment_id
                      AND ir.status IN ('COMPLETED', 'PARTIAL')
              ) r ON true
             WHERE cs.is_active $filters
             ORDER BY cs.demand_score DESC, r.last_run NULLS FIRST, cs.segment_id
            """
        ).param("sourceId", sourceId)
        if (workModes != null) spec = spec.param("workModes", workModes)
        return spec.query { rs, _ ->
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

    fun sourceSucceeded(sourceId: Long) = saveCircuit(sourceId, CircuitBreaker.onSuccess(), success = true)

    // Circuito aberto e vencido: a rodada faz uma tentativa de teste (HALF_OPEN).
    fun markHalfOpen(sourceId: Long) {
        jdbc.sql("UPDATE core.job_source SET circuit_breaker_state = 'HALF_OPEN', last_health_check_at = now() WHERE job_source_id = :id AND circuit_breaker_state = 'OPEN'")
            .param("id", sourceId).update()
    }

    // Limite de falhas e descanso por fonte (config circuitFailureThreshold e circuitRecoveryMinutes).
    fun sourceFailed(source: SourceRow) {
        val policy = CircuitPolicy(
            failureThreshold = (source.config["circuitFailureThreshold"] as? Number)?.toInt() ?: defaultFailureThreshold,
            recovery = Duration.ofMinutes((source.config["circuitRecoveryMinutes"] as? Number)?.toLong() ?: defaultRecoveryMinutes)
        )
        val current = jdbc.sql(
            "SELECT circuit_breaker_state, consecutive_failures, circuit_opened_at, next_attempt_at FROM core.job_source WHERE job_source_id = :id"
        ).param("id", source.id).query { rs, _ ->
            Circuit(
                CircuitState.valueOf(rs.getString(1)), rs.getInt(2),
                rs.getObject(3, OffsetDateTime::class.java), rs.getObject(4, OffsetDateTime::class.java)
            )
        }.single()
        saveCircuit(source.id, CircuitBreaker.onFailure(current, OffsetDateTime.now(), policy), success = false)
    }

    private fun saveCircuit(sourceId: Long, circuit: Circuit, success: Boolean) {
        val health = when {
            success -> "HEALTHY"
            circuit.state == CircuitState.OPEN -> "DOWN"
            else -> "DEGRADED"
        }
        jdbc.sql(
            """
            UPDATE core.job_source
               SET consecutive_failures = :failures, circuit_breaker_state = :state, circuit_opened_at = :openedAt,
                   next_attempt_at = :nextAttemptAt, health_status = :health, last_health_check_at = now(),
                   last_success_at = CASE WHEN :success THEN now() ELSE last_success_at END
             WHERE job_source_id = :id
            """
        )
            .param("failures", circuit.failures)
            .param("state", circuit.state.name)
            .param("openedAt", circuit.openedAt)
            .param("nextAttemptAt", circuit.nextAttemptAt)
            .param("health", health)
            .param("success", success)
            .param("id", sourceId)
            .update()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(json: String?): Map<String, Any?> =
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { mapper.readValue(json, Map::class.java) as Map<String, Any?> }.getOrDefault(emptyMap())
}
