package com.watchjobs.ingestion

import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.FetchResult
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.GreenhouseConnector
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.connector.RawPosting
import com.watchjobs.ingestion.connector.SourceContext
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
import com.watchjobs.ingestion.store.TriggerType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Fase A: o lote de vagas iguais tem o mesmo resultado do caminho antigo (uma transação por vaga).
class BatchWritePostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var pipeline: IngestionPipeline

    @Autowired
    lateinit var writer: JobWriter

    @Autowired
    lateinit var runs: IngestionRunStore

    @Autowired
    lateinit var transactions: TransactionTemplate

    @Autowired
    lateinit var classifier: JobClassifier

    @Autowired
    lateinit var catalog: CatalogStore

    private val normalizer by lazy { JobNormalizer(classifier, SkillExtractor(catalog.skillAliases())) }

    private class RecordedConnector(var postings: List<RawPosting> = emptyList()) : JobSourceConnector {
        override val code = "GREENHOUSE"

        override fun fetch(source: SourceContext, target: FetchTarget) = FetchResult(postings, fullSnapshot = true)
    }

    // URL própria por empresa: as duas cópias do teste não viram um grupo de vagas iguais.
    private fun fixturePostings(target: FetchTarget): List<RawPosting> =
        GreenhouseConnector({ _, _ -> "" }, IngestionFixtures.mapper)
            .parse(IngestionFixtures.read("greenhouse/vtex-jobs.json"), target.externalKey!!, target.organizationName)
            .map { it.copy(url = "${it.url}?empresa=${target.externalKey}") }

    private fun syntheticPostings(target: FetchTarget, count: Int): List<RawPosting> = (1..count).map { i ->
        RawPosting(
            externalId = "${target.externalKey}:$i",
            title = "Backend Engineer $i",
            url = "https://example.com/${target.externalKey}/$i",
            locationText = "São Paulo, Brazil",
            description = "Vaga com Kotlin, Spring Boot e PostgreSQL. Item $i."
        )
    }

    private fun newPath(target: FetchTarget, postings: List<RawPosting>): RunCounters {
        val source = greenhouse()
        val context = SourceContext(source.id, source.code, null, source.config)
        return pipeline.processTarget(
            source, context, RecordedConnector(postings), target.copy(lastJobCount = lastJobCount(target)), TriggerType.MANUAL, normalizer
        ).also { assertEquals(RunStatus.COMPLETED, it.status) }.counters
    }

    // O laço de processTarget antes do lote: find, touch e markSeen por vaga, cada vaga na sua transação.
    private fun legacyPath(target: FetchTarget, postings: List<RawPosting>): RunCounters {
        val source = greenhouse()
        val runId = runs.open(source.id, target.sourceCompanyId, null, TriggerType.MANUAL)
        val context = NormalizeContext(source.id, source.type, source.allowsStorage, target.sourceCompanyId, target.organizationName)
        val counters = RunCounters(fetched = postings.size)
        val fetchedIds = mutableSetOf<String>()
        for (raw in postings) {
            fetchedIds += raw.externalId
            val job = normalizer.normalize(raw, context)
            if (job == null) {
                counters.rejected++
            } else {
                when (transactions.execute { writer.write(job, target.organizationId, runId) }) {
                    WriteOutcome.NEW -> counters.new++
                    WriteOutcome.CHANGED -> counters.updated++
                    WriteOutcome.UNCHANGED, WriteOutcome.EXPIRED -> counters.deduplicated++
                    null -> counters.rejected++
                }
            }
        }
        counters.closed = transactions.execute { writer.closeMissing(target.sourceCompanyId!!, fetchedIds) }!!
        runs.close(runId, RunStatus.COMPLETED, counters, true)
        return counters
    }

    // Estado final por vaga, sem o prefixo do board: versão, fechada, expirada e status no corpus (null = linha ausente).
    private fun states(target: FetchTarget): List<List<Any?>> =
        jdbc.queryForList(
            """
            SELECT split_part(s.external_id, ':', 2) AS id, s.version, s.closed_at IS NULL AS open, s.expired_at IS NULL AS live,
                   j.corpus_status
              FROM discovery.job_seen s
              LEFT JOIN discovery.discovered_job j ON j.discovered_job_id = s.discovered_job_id AND j.discovered_at = s.discovered_at
             WHERE s.source_company_id = ?
             ORDER BY 1
            """,
            target.sourceCompanyId
        ).map { it.values.toList() }

    private fun seenId(externalId: String): Long =
        jdbc.queryForObject("SELECT discovered_job_id FROM discovery.job_seen WHERE external_id = ?", Long::class.java, externalId)!!

    private fun lastRunOf(externalId: String): Long =
        jdbc.queryForObject("SELECT last_ingestion_run_id FROM discovery.job_seen WHERE external_id = ?", Long::class.java, externalId)!!

    private fun latestRun(target: FetchTarget): Long =
        jdbc.queryForObject(
            "SELECT max(ingestion_run_id) FROM discovery.ingestion_run WHERE source_company_id = ?", Long::class.java, target.sourceCompanyId
        )!!

    @Test
    fun `a batch of unchanged jobs only touches them`() {
        val target = createCompany()
        val postings = fixturePostings(target)
        newPath(target, postings)
        val before = jdbc.queryForObject(
            "SELECT max(last_seen_at) FROM discovery.discovered_job WHERE source_company_id = ?", OffsetDateTime::class.java, target.sourceCompanyId
        )!!

        val second = newPath(target, postings)

        assertEquals(RunCounters(fetched = 4, deduplicated = 4), second)
        val run = latestRun(target)
        postings.forEach { assertEquals(run, lastRunOf(it.externalId)) }
        val after = jdbc.queryForObject(
            "SELECT min(last_seen_at) FROM discovery.discovered_job WHERE source_company_id = ?", OffsetDateTime::class.java, target.sourceCompanyId
        )!!
        assertTrue(after > before)
    }

    @Test
    fun `new, unchanged, changed, expired, reactivated and missing corpus rows match the old path`() {
        val legacy = createCompany()
        val batch = createCompany()
        val results = listOf(legacy, batch).map { target ->
            val base = fixturePostings(target)
            val round1 = (if (target == legacy) ::legacyPath else ::newPath)(target, base)
            // Rodada 2: a última some do retrato e fica PROBABLY_CLOSED.
            val round2 = (if (target == legacy) ::legacyPath else ::newPath)(target, base.dropLast(1))
            // A 3ª cumpriu os 30 dias (expired_at) e a 2ª perdeu a linha do corpus (partição descartada).
            jdbc.update("UPDATE discovery.job_seen SET expired_at = now() WHERE external_id = ?", base[2].externalId)
            jdbc.update("DELETE FROM discovery.discovered_job WHERE discovered_job_id = ?", seenId(base[1].externalId))
            val extra = base[0].copy(externalId = base[0].externalId + "9", url = base[0].url + "&nova=1", title = "Platform Engineer")
            val round3Postings = listOf(base[0].copy(title = "Staff Backend Engineer (Java)")) + base.drop(1) + extra
            val round3 = (if (target == legacy) ::legacyPath else ::newPath)(target, round3Postings)
            Triple(listOf(round1, round2, round3), states(target), base)
        }

        val (legacyCounters, legacyStates) = results[0]
        val (batchCounters, batchStates, base) = results[1]
        assertEquals(legacyCounters, batchCounters)
        assertEquals(legacyStates, batchStates)
        assertEquals(RunCounters(fetched = 5, new = 1, updated = 1, deduplicated = 3), batchCounters[2])

        // Reativada: PROBABLY_CLOSED volta a ACTIVE e o job_seen reabre.
        val reactivated = base[3].externalId
        assertEquals("ACTIVE", jdbc.queryForObject(
            "SELECT j.corpus_status FROM discovery.job_seen s JOIN discovery.discovered_job j USING (discovered_job_id, discovered_at) WHERE s.external_id = ?",
            String::class.java, reactivated
        ))
        assertNull(jdbc.queryForObject("SELECT closed_at FROM discovery.job_seen WHERE external_id = ?", OffsetDateTime::class.java, reactivated))
        // Linha do corpus ausente: a vaga expira e não volta como nova.
        val missing = base[1].externalId
        assertTrue(jdbc.queryForObject("SELECT expired_at IS NOT NULL FROM discovery.job_seen WHERE external_id = ?", Boolean::class.java, missing)!!)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM discovery.discovered_job WHERE external_id = ?", Long::class.java, missing))
    }

    @Test
    fun `a repeated external id in the same snapshot is written like the old path`() {
        val legacy = createCompany()
        val batch = createCompany()
        val counters = listOf(legacy, batch).map { target ->
            val base = fixturePostings(target)
            val path = if (target == legacy) ::legacyPath else ::newPath
            path(target, base)
            path(target, base + base[0] + base[1].copy(title = "Staff Backend Engineer"))
        }
        assertEquals(counters[0], counters[1])
        assertEquals(states(legacy), states(batch))
    }

    // Medição local (não é asserção): o caminho antigo contra o lote, com o mesmo número de vagas iguais.
    @Test
    fun `measure unchanged jobs, old path versus batch`() {
        val count = 300
        val legacy = createCompany()
        val batch = createCompany()
        legacyPath(legacy, syntheticPostings(legacy, count))
        newPath(batch, syntheticPostings(batch, count))

        val legacyStart = System.nanoTime()
        val legacyCounters = legacyPath(legacy, syntheticPostings(legacy, count))
        val legacyMs = (System.nanoTime() - legacyStart) / 1_000_000
        val batchStart = System.nanoTime()
        val batchCounters = newPath(batch, syntheticPostings(batch, count))
        val batchMs = (System.nanoTime() - batchStart) / 1_000_000

        assertEquals(legacyCounters, batchCounters)
        assertEquals(count, batchCounters.deduplicated)
        println("MEDICAO $count vagas iguais: caminho antigo $legacyMs ms (${"%.2f".format(legacyMs.toDouble() / count)} ms/vaga), lote $batchMs ms (${"%.2f".format(batchMs.toDouble() / count)} ms/vaga)")
    }
}
