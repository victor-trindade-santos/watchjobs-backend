package com.watchjobs.ingestion.partner

import com.watchjobs.classification.JobClassifier
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.ingestion.connector.RawPosting
import com.watchjobs.ingestion.normalize.Hashing
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
import com.watchjobs.sources.SourceType
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.OffsetDateTime

data class PartnerJob(
    val externalId: String? = null,
    val title: String? = null,
    val company: String? = null,
    val url: String? = null,
    val location: String? = null,
    val description: String? = null,
    val postedAt: String? = null,
    val salaryMin: Double? = null,
    val salaryMax: Double? = null,
    val salaryCurrency: String? = null,
    val salaryPeriod: String? = null,
    val workMode: String? = null,
    val jobType: String? = null
)

data class PartnerBatch(val batchReference: String? = null, val jobs: List<PartnerJob> = emptyList())

data class RecordError(val index: Int, val externalId: String?, val message: String)

data class PartnerBatchResult(
    val runId: Long?,
    val duplicate: Boolean,
    val received: Int,
    val created: Int,
    val updated: Int,
    val unchanged: Int,
    val rejected: Int,
    val errors: List<RecordError>
)

// Lote de vagas de parceiro (PARTNER_FEED): checksum contra reenvio, validação e gravação registro a registro.
// Um registro inválido é anotado em ingestion_record_error e o resto do lote segue.
@Service
class PartnerFeedService(
    private val targets: SourceTargetStore,
    private val runs: IngestionRunStore,
    private val pipeline: IngestionPipeline,
    private val writer: JobWriter,
    private val catalog: CatalogStore,
    private val classifier: JobClassifier,
    private val transactions: TransactionTemplate,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun authorize(sourceCode: String, key: String?) {
        val source = targets.sourceByCode(sourceCode.uppercase())
            ?.takeIf { it.type == SourceType.PARTNER_FEED } ?: throw NotFoundException("Feed de parceiro não encontrado")
        val expected = runCatching { pipeline.contextOf(source).credentials.values.firstOrNull() }.getOrNull()
        val valid = !expected.isNullOrBlank() && !key.isNullOrBlank() &&
            MessageDigest.isEqual(expected.toByteArray(), key.toByteArray())
        if (!valid) throw BadCredentialsException("Chave do parceiro inválida")
    }

    fun ingest(sourceCode: String, rawBody: String): PartnerBatchResult {
        val source = targets.sourceByCode(sourceCode.uppercase()) ?: throw NotFoundException("Feed de parceiro não encontrado")
        val batch = try {
            mapper.readValue(rawBody, PartnerBatch::class.java)
        } catch (ex: Exception) {
            throw BusinessException("Lote em formato inválido: envie {\"batchReference\": ..., \"jobs\": [...]}")
        }
        if (batch.jobs.isEmpty()) throw BusinessException("Lote sem vagas")
        if (batch.jobs.size > MAX_RECORDS) throw BusinessException("Lote com mais de $MAX_RECORDS vagas: divida em lotes menores")

        val checksum = Hashing.sha256(rawBody)
        existingRun(source.id, checksum)?.let { runId ->
            return PartnerBatchResult(runId, duplicate = true, batch.jobs.size, 0, 0, 0, 0, emptyList())
        }
        val runId = try {
            runs.openBatch(source.id, batch.batchReference?.take(255), checksum)
        } catch (ex: DuplicateKeyException) {
            // Dois envios simultâneos do mesmo lote: o índice único do checksum deixa só um passar.
            return PartnerBatchResult(existingRun(source.id, checksum), duplicate = true, batch.jobs.size, 0, 0, 0, 0, emptyList())
        }

        val normalizer = JobNormalizer(classifier, SkillExtractor(catalog.skillAliases()))
        val context = NormalizeContext(source.id, source.type, source.allowsStorage, defaultCountry = source.config["country"]?.toString()?.uppercase())
        val counters = RunCounters(fetched = batch.jobs.size)
        val errors = mutableListOf<RecordError>()
        val seenIds = mutableSetOf<String>()
        batch.jobs.forEachIndexed { index, record ->
            val problem = validate(record, seenIds)
            if (problem != null) {
                errors += RecordError(index, record.externalId, problem)
                counters.rejected++
                return@forEachIndexed
            }
            val job = normalizer.normalize(toRaw(record), context)
            if (job == null) {
                errors += RecordError(index, record.externalId, "Vaga sem título, empresa ou URL válida")
                counters.rejected++
                return@forEachIndexed
            }
            try {
                when (transactions.execute { writer.write(job, null, runId) }) {
                    WriteOutcome.NEW -> counters.new++
                    WriteOutcome.CHANGED -> counters.updated++
                    else -> counters.deduplicated++
                }
            } catch (ex: Exception) {
                errors += RecordError(index, record.externalId, "Falha ao gravar: ${ex.message?.take(300)}")
                counters.rejected++
            }
        }
        errors.forEach { error ->
            jdbc.sql(
                "INSERT INTO discovery.ingestion_record_error (ingestion_run_id, record_index, external_id, error_message) VALUES (:run, :index, :externalId, :message)"
            ).param("run", runId).param("index", error.index).param("externalId", error.externalId?.take(255)).param("message", error.message).update()
        }
        val status = if (counters.rejected == 0) RunStatus.COMPLETED else RunStatus.PARTIAL
        runs.close(runId, status, counters, false, errors.takeIf { it.isNotEmpty() }?.let { "${it.size} registros rejeitados" })
        log.info("Lote {} de {}: {} novas, {} alteradas, {} rejeitadas", batch.batchReference, source.code, counters.new, counters.updated, counters.rejected)
        return PartnerBatchResult(runId, false, batch.jobs.size, counters.new, counters.updated, counters.deduplicated, counters.rejected, errors)
    }

    private fun existingRun(sourceId: Long, checksum: String): Long? =
        jdbc.sql("SELECT ingestion_run_id FROM discovery.ingestion_run WHERE source_id = :sourceId AND payload_checksum = :checksum AND status <> 'FAILED' LIMIT 1")
            .param("sourceId", sourceId).param("checksum", checksum).query(Long::class.java).list().firstOrNull()

    private fun validate(record: PartnerJob, seenIds: MutableSet<String>): String? = when {
        record.externalId.isNullOrBlank() -> "externalId é obrigatório"
        !seenIds.add(record.externalId) -> "externalId repetido no lote"
        record.title.isNullOrBlank() -> "title é obrigatório"
        record.company.isNullOrBlank() -> "company é obrigatório"
        record.url.isNullOrBlank() || !JobNormalizer.isValidUrl(record.url.trim()) -> "url inválida (use http ou https)"
        record.salaryMin != null && record.salaryMax != null && record.salaryMin > record.salaryMax -> "salaryMin maior que salaryMax"
        record.postedAt != null && runCatching { OffsetDateTime.parse(record.postedAt) }.isFailure -> "postedAt inválido (ISO-8601 com fuso)"
        else -> null
    }

    private fun toRaw(record: PartnerJob) = RawPosting(
        externalId = record.externalId!!.trim(),
        title = record.title.orEmpty(),
        url = record.url?.trim(),
        organizationName = record.company,
        locationText = record.location,
        description = record.description,
        postedAt = record.postedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
        salaryMin = record.salaryMin,
        salaryMax = record.salaryMax,
        salaryCurrency = record.salaryCurrency,
        salaryPeriod = record.salaryPeriod,
        workMode = record.workMode?.uppercase()?.takeIf { it in setOf("REMOTE", "HYBRID", "ON_SITE") },
        jobType = record.jobType?.uppercase()?.takeIf { it in setOf("CLT", "PJ", "INTERNSHIP", "FREELANCE", "TEMPORARY") }
    )

    companion object {
        const val MAX_RECORDS = 5000
    }
}

// Recebimento dos lotes: autenticado pela chave do parceiro (cabeçalho X-Partner-Key), não por usuário.
@RestController
@RequestMapping("/partner-feed")
class PartnerFeedController(private val service: PartnerFeedService) {
    @PostMapping("/{source}/batches", consumes = ["application/json"])
    fun upload(
        @PathVariable source: String,
        @RequestHeader("X-Partner-Key", required = false) key: String?,
        @RequestBody body: String
    ): PartnerBatchResult {
        service.authorize(source, key)
        return service.ingest(source, body)
    }
}
