package com.watchjobs.ingestion

import com.watchjobs.ingestion.pipeline.JobWriter
import com.watchjobs.ingestion.pipeline.WriteOutcome
import com.watchjobs.ingestion.store.IngestionRunStore
import com.watchjobs.ingestion.store.JobGroupStore
import com.watchjobs.ingestion.store.TriggerType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// F07: a mesma vaga vinda de duas fontes vira um grupo e a busca mostra só a da fonte mais confiável.
class JobGroupSearchPostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var writer: JobWriter

    @Autowired
    lateinit var runs: IngestionRunStore

    @Autowired
    lateinit var transactions: TransactionTemplate

    private val runIds = mutableListOf<Long>()

    @AfterEach
    fun cleanupRuns() {
        jdbc.update("DELETE FROM discovery.discovered_job WHERE external_id LIKE ?", "$externalIdPrefix%")
        jdbc.update("DELETE FROM discovery.job_seen WHERE external_id LIKE ?", "$externalIdPrefix%")
        runIds.forEach { jdbc.update("DELETE FROM discovery.ingestion_run WHERE ingestion_run_id = ?", it) }
    }

    private fun sourceId(code: String) = requireNotNull(jdbc.queryForObject("SELECT job_source_id FROM core.job_source WHERE code = ?", Long::class.java, code))

    private fun visible(): List<String> = jdbc.queryForList(
        "SELECT j.external_id FROM discovery.discovered_job j WHERE j.external_id LIKE ? AND j.corpus_status = 'ACTIVE' ${JobGroupStore.CANONICAL_ONLY}",
        String::class.java, "$externalIdPrefix%"
    ).filterNotNull()

    @Test
    fun `same job from two sources is grouped and only the most trusted one is searchable`() {
        val fingerprint = UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
        val greenhouse = sourceId("GREENHOUSE")
        val adzuna = sourceId("ADZUNA")
        val ats = TestJobs.job(greenhouse, "https://boards.greenhouse.io/acme/jobs/$externalIdPrefix", fingerprint, "$externalIdPrefix-ats")
        val aggregator = TestJobs.job(adzuna, "https://www.adzuna.com.br/details/$externalIdPrefix", fingerprint, "$externalIdPrefix-agg")
        val other = TestJobs.job(adzuna, "https://www.adzuna.com.br/details/$externalIdPrefix-x", fingerprint.reversed(), "$externalIdPrefix-other")

        listOf(aggregator to adzuna, ats to greenhouse, other to adzuna).forEach { (job, source) ->
            val runId = runs.open(source, null, null, TriggerType.MANUAL).also { runIds += it }
            assertEquals(WriteOutcome.NEW, transactions.execute { writer.write(job, null, runId) })
        }

        val groups = jdbc.queryForList(
            "SELECT DISTINCT job_group_id FROM discovery.discovered_job WHERE external_id IN (?, ?)", Long::class.java, ats.externalId, aggregator.externalId
        )
        assertNotNull(groups.single())
        assertEquals(setOf(ats.externalId, other.externalId), visible().toSet())

        // A representante saiu do ar: a cópia do agregador volta a aparecer na busca.
        jdbc.update("UPDATE discovery.discovered_job SET corpus_status = 'PROBABLY_CLOSED' WHERE external_id = ?", ats.externalId)
        assertEquals(setOf(aggregator.externalId, other.externalId), visible().toSet())
    }
}
