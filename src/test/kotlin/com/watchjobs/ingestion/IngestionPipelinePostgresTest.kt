package com.watchjobs.ingestion

import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.FetchResult
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.GreenhouseConnector
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.connector.RawPosting
import com.watchjobs.ingestion.connector.SourceContext
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.pipeline.RoundOptions
import com.watchjobs.ingestion.pipeline.TargetResult
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.store.RunStatus
import com.watchjobs.ingestion.store.TriggerType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// F2-10: o pipeline contra respostas gravadas do Greenhouse, sem internet.
class IngestionPipelinePostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var pipeline: IngestionPipeline

    @Autowired
    lateinit var classifier: JobClassifier

    @Autowired
    lateinit var catalog: CatalogStore

    private class RecordedConnector : JobSourceConnector {
        override val code = "GREENHOUSE"
        var postings: List<RawPosting> = emptyList()

        override fun fetch(source: SourceContext, target: FetchTarget) = FetchResult(postings, fullSnapshot = true)
    }

    private val connector = RecordedConnector()

    private fun postingsFor(target: FetchTarget): List<RawPosting> =
        GreenhouseConnector({ _, _ -> "" }, IngestionFixtures.mapper)
            .parse(IngestionFixtures.read("greenhouse/vtex-jobs.json"), target.externalKey!!, target.organizationName)

    private fun run(target: FetchTarget, postings: List<RawPosting>): TargetResult {
        connector.postings = postings
        val source = greenhouse()
        val normalizer = JobNormalizer(classifier, SkillExtractor(catalog.skillAliases()))
        val context = SourceContext(source.id, source.code, null, source.config)
        return pipeline.processTarget(source, context, connector, target.copy(lastJobCount = lastJobCount(target)), TriggerType.MANUAL, normalizer)
    }

    private fun statusOf(externalId: String): String =
        jdbc.queryForObject(
            """
            SELECT j.corpus_status FROM discovery.job_seen s
              JOIN discovery.discovered_job j ON j.discovered_job_id = s.discovered_job_id AND j.discovered_at = s.discovered_at
             WHERE s.external_id = ?
            """,
            String::class.java, externalId
        )!!

    private fun corpusCount(target: FetchTarget): Long =
        jdbc.queryForObject("SELECT count(*) FROM discovery.discovered_job WHERE source_company_id = ?", Long::class.java, target.sourceCompanyId)!!

    @Test
    fun `running twice in a row creates nothing new`() {
        val target = createCompany()
        val postings = postingsFor(target)

        val first = run(target, postings)
        assertEquals(RunStatus.COMPLETED, first.status)
        assertEquals(4, first.counters.new)
        assertEquals(4L, corpusCount(target))
        assertEquals(4, lastJobCount(target))

        val second = run(target, postings)
        assertEquals(0, second.counters.new)
        assertEquals(0, second.counters.updated)
        assertEquals(4, second.counters.deduplicated)
        assertEquals(4L, corpusCount(target))

        val lastRun = jdbc.queryForMap(
            "SELECT * FROM discovery.ingestion_run WHERE source_company_id = ? ORDER BY ingestion_run_id DESC LIMIT 1",
            target.sourceCompanyId
        )
        assertEquals("COMPLETED", lastRun["status"])
        assertEquals(true, lastRun["is_full_snapshot"])
        assertEquals("MANUAL", lastRun["trigger_type"])
        val skills = jdbc.queryForObject(
            "SELECT count(*) FROM discovery.discovered_job_skill k JOIN discovery.discovered_job j USING (discovered_job_id, discovered_at) WHERE j.source_company_id = ?",
            Long::class.java, target.sourceCompanyId
        )!!
        assertTrue(skills > 0)
    }

    @Test
    fun `a job missing from a full snapshot becomes probably closed and comes back when listed again`() {
        val target = createCompany()
        val postings = postingsFor(target)
        run(target, postings)

        val missing = postings.last().externalId
        val second = run(target, postings.dropLast(1))
        assertEquals(1, second.counters.closed)
        assertEquals("PROBABLY_CLOSED", statusOf(missing))
        assertNotNull(jdbc.queryForObject("SELECT closed_at FROM discovery.job_seen WHERE external_id = ?", java.time.OffsetDateTime::class.java, missing))
        assertEquals("ACTIVE", statusOf(postings.first().externalId))

        run(target, postings)
        assertEquals("ACTIVE", statusOf(missing))
        assertNull(jdbc.queryForObject("SELECT closed_at FROM discovery.job_seen WHERE external_id = ?", java.time.OffsetDateTime::class.java, missing))
    }

    @Test
    fun `an empty snapshot from a board with many jobs closes nothing`() {
        val target = createCompany()
        val base = postingsFor(target)
        val many = base + base.take(2).map { it.copy(externalId = it.externalId + "0", url = it.url + "0") }
        run(target, many)
        assertEquals(6, lastJobCount(target))

        val empty = run(target, emptyList())
        assertEquals(RunStatus.PARTIAL, empty.status)
        assertEquals(0, empty.counters.closed)
        many.forEach { assertEquals("ACTIVE", statusOf(it.externalId)) }
        assertEquals(6, lastJobCount(target))
    }

    @Test
    fun `a changed job is rewritten with a new version`() {
        val target = createCompany()
        val postings = postingsFor(target)
        run(target, postings)

        val changed = postings.mapIndexed { index, posting -> if (index == 0) posting.copy(title = "Staff Backend Engineer (Java)") else posting }
        val second = run(target, changed)

        assertEquals(1, second.counters.updated)
        assertEquals(3, second.counters.deduplicated)
        val id = postings.first().externalId
        assertEquals(2, jdbc.queryForObject("SELECT version FROM discovery.job_seen WHERE external_id = ?", Int::class.java, id))
        assertEquals(
            "Staff Backend Engineer (Java)",
            jdbc.queryForObject(
                "SELECT j.title FROM discovery.job_seen s JOIN discovery.discovered_job j ON j.discovered_job_id = s.discovered_job_id AND j.discovered_at = s.discovered_at WHERE s.external_id = ?",
                String::class.java, id
            )
        )
    }

    @Test
    fun `runner arguments`() {
        val options = RoundOptions.parse(arrayOf("--spring.profiles.active=ingestion", "--source=greenhouse", "--company=vtex", "--force"))
        assertEquals("GREENHOUSE", options.sourceCode)
        assertEquals("vtex", options.companyKey)
        assertTrue(options.force)
        assertEquals(TriggerType.MANUAL, options.trigger)
        assertEquals(TriggerType.SCHEDULED, RoundOptions.parse(emptyArray()).trigger)
    }
}
