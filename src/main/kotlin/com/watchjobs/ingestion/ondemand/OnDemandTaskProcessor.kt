package com.watchjobs.ingestion.ondemand

import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.pipeline.TargetResult
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.store.SourceTargetStore
import com.watchjobs.ingestion.store.TriggerType
import com.watchjobs.matching.FeedService
import com.watchjobs.notification.NotificationService
import com.watchjobs.notification.NotificationType
import com.watchjobs.sources.SourceType
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

// Parte em segundo plano da busca sob demanda, no motor (3.7.1, passos 7 e 8): atende a fila antes dos
// alvos agendados, recoleta as empresas ATS não vistas na última hora, filtra pela consulta e avisa.
@Service
class OnDemandTaskProcessor(
    connectors: List<JobSourceConnector>,
    private val jdbc: JdbcClient,
    private val queries: OnDemandQueries,
    private val targets: SourceTargetStore,
    private val pipeline: IngestionPipeline,
    private val catalog: CatalogStore,
    private val classifier: JobClassifier,
    private val feed: FeedService,
    private val notifications: NotificationService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connectorsByCode = connectors.associateBy { it.code }

    fun process(): List<TargetResult> {
        val tasks = claim()
        if (tasks.isEmpty()) return emptyList()
        log.info("Busca sob demanda: {} pedidos na fila", tasks.size)
        jdbc.sql("SELECT discovery.ensure_partitions(14)").query().singleRow()
        val startedAt = OffsetDateTime.now()

        val normalizer = JobNormalizer(classifier, SkillExtractor(catalog.skillAliases()))
        val results = mutableListOf<TargetResult>()
        targets.activeSources().filter { it.type == SourceType.ATS_PUBLIC }.forEach { source ->
            val connector = connectorsByCode[source.code] ?: return@forEach
            val context = runCatching { pipeline.contextOf(source) }.getOrNull() ?: return@forEach
            targets.companiesNotFetchedSince(source.id, 60).forEach { target ->
                results += pipeline.processTarget(source, context, connector, target, TriggerType.ON_DEMAND, normalizer)
            }
        }

        tasks.forEach { taskId ->
            try {
                queries.byTask(taskId).forEach { request ->
                    val fresh = queries.matchingIds(request.queryText, request.locationText, request.workMode, seenSince = startedAt)
                    val all = queries.matchingIds(request.queryText, request.locationText, request.workMode)
                    feed.ensureScores(request.userId, all)
                    queries.completed(request.id, fresh.size)
                    notifications.create(
                        userId = request.userId,
                        type = NotificationType.SEARCH_COMPLETED,
                        title = "Encontramos ${all.size} vagas para \"${request.queryText}\"",
                        body = if (fresh.isEmpty()) "Nenhuma vaga nova nas outras fontes." else "${fresh.size} vieram das outras fontes.",
                        payload = mapOf("searchRequestId" to request.id, "query" to request.queryText, "found" to all.size),
                        dedupKey = "search:${request.id}"
                    )
                }
                finish(taskId, "COMPLETED", null)
            } catch (ex: Exception) {
                log.error("Pedido de busca {} falhou: {}", taskId, ex.message)
                finish(taskId, "FAILED", ex.message)
            }
        }
        return results
    }

    // Trava de linha: duas rodadas ao mesmo tempo não pegam o mesmo pedido.
    private fun claim(): List<Long> =
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task
               SET status = 'RUNNING', attempts = attempts + 1, locked_until = now() + interval '15 minutes'
             WHERE ingestion_task_id IN (
                   SELECT ingestion_task_id FROM discovery.ingestion_task
                    WHERE task_type = 'ON_DEMAND_SEARCH' AND run_after <= now() AND attempts < max_attempts
                      AND (status = 'PENDING' OR (status = 'RUNNING' AND locked_until < now()))
                    ORDER BY priority, created_at
                    LIMIT 20
                    FOR UPDATE SKIP LOCKED)
            RETURNING ingestion_task_id
            """
        ).query(Long::class.java).list().filterNotNull()

    private fun finish(taskId: Long, status: String, error: String?) {
        jdbc.sql(
            """
            UPDATE discovery.ingestion_task
               SET status = :status, completed_at = now(), locked_until = NULL, last_error = :error
             WHERE ingestion_task_id = :id
            """
        ).param("status", status).param("error", error?.take(2000)).param("id", taskId).update()
    }
}
