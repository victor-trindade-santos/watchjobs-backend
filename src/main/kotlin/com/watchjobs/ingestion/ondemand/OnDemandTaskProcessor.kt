package com.watchjobs.ingestion.ondemand

import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.pipeline.TargetResult
import com.watchjobs.ingestion.queue.TaskOutcome
import com.watchjobs.ingestion.queue.TaskQueue
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
    private val notifications: NotificationService,
    private val queue: TaskQueue
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connectorsByCode = connectors.associateBy { it.code }

    fun process(): List<TargetResult> {
        queue.recoverAbandoned()
        val tasks = queue.claim(listOf("ON_DEMAND_SEARCH"))
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

        tasks.forEach { task ->
            try {
                queries.byTask(task.id).forEach { request ->
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
                queue.complete(task)
            } catch (ex: Exception) {
                log.error("Pedido de busca {} falhou (tentativa {}): {}", task.id, task.attempt, ex.message)
                if (queue.fail(task, ex) == TaskOutcome.DEAD) {
                    jdbc.sql(
                        """
                        UPDATE discovery.search_request SET status = 'FAILED', completed_at = now(), error_message = :error
                         WHERE ingestion_task_id = :id AND status IN ('RUNNING', 'SYNC_DONE')
                        """
                    ).param("error", (ex.message ?: "Falha na busca").take(500)).param("id", task.id).update()
                }
            }
        }
        return results
    }
}
