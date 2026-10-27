package com.watchjobs.ingestion.ondemand

import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.classification.JobClassifier
import com.watchjobs.classification.RuleSet
import com.watchjobs.entity.enums.WorkMode
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.exception.TooManyRequestsException
import com.watchjobs.ingestion.connector.FreeQuery
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.connector.QueryableConnector
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.NormalizeContext
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.pipeline.JobWriter
import com.watchjobs.ingestion.pipeline.WriteOutcome
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.store.IngestionRunStore
import com.watchjobs.ingestion.store.RunCounters
import com.watchjobs.ingestion.store.RunStatus
import com.watchjobs.ingestion.store.SourceTargetStore
import com.watchjobs.ingestion.store.TriggerType
import com.watchjobs.matching.FeedItem
import com.watchjobs.matching.FeedService
import com.watchjobs.sources.SourceType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class DiscoverRequest(
    @field:NotBlank(message = "Informe o que você procura")
    @field:Size(min = 2, max = 200, message = "A busca deve ter entre 2 e 200 caracteres")
    val q: String = "",
    @field:Size(max = 150) val location: String? = null,
    val workMode: WorkMode? = null
)

data class DiscoverResponse(
    val id: Long,
    val status: String,
    val partial: Boolean,
    val backgroundPending: Boolean,
    val cacheHit: Boolean,
    val syncFound: Int,
    val asyncFound: Int,
    val query: String,
    val items: List<FeedItem>
)

// Busca sob demanda híbrida (3.7.1): agregador na hora (com limite, cota, cache e tempo limite) e
// ATS em segundo plano pelo motor. Nada roda depois da resposta: a parte lenta é sempre do motor.
@Service
class OnDemandSearchService(
    connectors: List<JobSourceConnector>,
    private val queries: OnDemandQueries,
    private val targets: SourceTargetStore,
    private val runs: IngestionRunStore,
    private val pipeline: IngestionPipeline,
    private val writer: JobWriter,
    private val catalog: CatalogStore,
    private val classifier: JobClassifier,
    private val feed: FeedService,
    private val trigger: MotorTrigger,
    private val transactions: TransactionTemplate,
    private val mapper: ObjectMapper,
    @Value("\${app.ondemand.per-hour:5}") private val perHour: Int,
    @Value("\${app.ondemand.per-day:20}") private val perDay: Int,
    @Value("\${app.ondemand.timeout-seconds:8}") private val timeoutSeconds: Long,
    @Value("\${app.ondemand.cache-hours:6}") private val cacheHours: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val queryable = connectors.filterIsInstance<QueryableConnector>().associateBy { it.code }
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    private data class SyncResult(val ids: List<Long>, val partial: Boolean, val error: String?)

    fun discover(userId: Int, request: DiscoverRequest): DiscoverResponse {
        val q = TextNormalizer.collapseSpaces(request.q).take(200)
        if (q.length < 2) throw BusinessException("Informe o que você procura")
        val location = request.location?.trim()?.ifEmpty { null }
        val workMode = request.workMode?.name
        if (queries.countSince(userId, "1 hour") >= perHour) {
            throw TooManyRequestsException("Você fez $perHour buscas na última hora. Tente de novo mais tarde.")
        }
        if (queries.countSince(userId, "1 day") >= perDay) {
            throw TooManyRequestsException("Você chegou ao limite de $perDay buscas por dia.")
        }

        val queryKey = queryKey(q, location, workMode)
        val cacheHit = queries.recentlyFetched(queryKey, cacheHours)
        val id = queries.create(userId, q, location, workMode, queryKey, cacheHit)
        val sync = if (cacheHit) SyncResult(emptyList(), false, null) else fetchAggregators(q, location, workMode)

        val ids = (sync.ids + queries.matchingIds(q, location, workMode)).distinct()
        val payload = mapper.writeValueAsString(mapOf("q" to q, "location" to location, "workMode" to workMode, "queryKey" to queryKey))
        val hour = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHH"))
        val taskId = queries.enqueue(queryKey, hour, payload)
        queries.syncDone(id, sync.ids.size, taskId, sync.error)
        trigger.trigger()

        return DiscoverResponse(
            id = id,
            status = "SYNC_DONE",
            partial = sync.partial,
            backgroundPending = true,
            cacheHit = cacheHit,
            syncFound = sync.ids.size,
            asyncFound = 0,
            query = q,
            items = feed.scoredItems(userId, ids)
        )
    }

    fun status(userId: Int, id: Long): DiscoverResponse {
        val row = queries.find(id, userId) ?: throw NotFoundException("Busca não encontrada")
        val ids = queries.matchingIds(row.queryText, row.locationText, row.workMode)
        return DiscoverResponse(
            id = row.id,
            status = row.status,
            partial = row.errorMessage != null,
            backgroundPending = row.status != "COMPLETED" && row.status != "FAILED",
            cacheHit = row.cacheHit,
            syncFound = row.syncFound,
            asyncFound = row.asyncFound,
            query = row.queryText,
            items = feed.scoredItems(userId, ids)
        )
    }

    // Agregadores ativos, com cota do dia e tempo limite. Grava pelo mesmo pipeline do motor (ON_DEMAND).
    private fun fetchAggregators(q: String, location: String?, workMode: String?): SyncResult {
        val sources = targets.activeSources().filter { it.type == SourceType.AGGREGATOR && it.code in queryable }
        if (sources.isEmpty()) return SyncResult(emptyList(), true, "Nenhum agregador ativo")
        val ids = mutableListOf<Long>()
        var partial = false
        var error: String? = null
        val normalizer by lazy { JobNormalizer(classifier, SkillExtractor(catalog.skillAliases())) }
        for (source in sources) {
            val limit = source.rateLimitPerDay
            if (limit != null && runs.countToday(source.id) >= limit) {
                partial = true
                error = "Cota diária de ${source.name} esgotada"
                continue
            }
            val context = try {
                pipeline.contextOf(source)
            } catch (ex: IllegalStateException) {
                partial = true
                error = "${source.name} sem credenciais"
                continue
            }
            val runId = runs.open(source.id, null, null, TriggerType.ON_DEMAND)
            val counters = RunCounters()
            try {
                val connector = queryable.getValue(source.code)
                val fetched = CompletableFuture
                    .supplyAsync({ connector.search(context, FreeQuery(q, location, workMode)) }, executor)
                    .get(timeoutSeconds, TimeUnit.SECONDS)
                counters.fetched = fetched.postings.size
                val normalizeContext = NormalizeContext(
                    sourceId = source.id,
                    sourceType = source.type,
                    allowsStorage = source.allowsStorage,
                    defaultCountry = context.configString("country")?.uppercase()
                )
                val externalIds = mutableListOf<String>()
                fetched.postings.forEach { raw ->
                    val job = normalizer.normalize(raw, normalizeContext)
                    if (job == null) {
                        counters.rejected++
                        return@forEach
                    }
                    try {
                        when (transactions.execute { writer.write(job, null, runId) }) {
                            WriteOutcome.NEW -> counters.new++
                            WriteOutcome.CHANGED -> counters.updated++
                            else -> counters.deduplicated++
                        }
                        externalIds += job.externalId
                    } catch (ex: Exception) {
                        counters.rejected++
                    }
                }
                ids += queries.idsByExternal(source.id, externalIds)
                runs.close(runId, RunStatus.COMPLETED, counters, false)
            } catch (ex: TimeoutException) {
                partial = true
                error = "${source.name} não respondeu em $timeoutSeconds s"
                runs.close(runId, RunStatus.PARTIAL, counters, false, error)
            } catch (ex: Exception) {
                partial = true
                error = "${source.name} indisponível"
                log.warn("Busca sob demanda em {} falhou: {}", source.code, ex.cause?.message ?: ex.message)
                runs.close(runId, RunStatus.FAILED, counters, false, ex.cause?.message ?: ex.message)
            }
        }
        return SyncResult(ids, partial, error)
    }

    companion object {
        fun queryKey(q: String, location: String?, workMode: String?): String =
            RuleSet.sha256(listOf(TextNormalizer.forMatching(q), TextNormalizer.forMatching(location), workMode.orEmpty()).joinToString("|"))
    }
}
