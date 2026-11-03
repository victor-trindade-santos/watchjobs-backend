package com.watchjobs.ingestion

import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.ConnectorException
import com.watchjobs.ingestion.connector.FetchResult
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.JobSourceConnector
import com.watchjobs.ingestion.connector.SourceContext
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.store.RunStatus
import com.watchjobs.ingestion.store.SourceRow
import com.watchjobs.ingestion.store.SourceTargetStore
import com.watchjobs.ingestion.store.TriggerType
import com.watchjobs.sources.SourceType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fase B: o intervalo dos segmentos é por fonte; um agregador não esgota o segmento para os outros.
class SegmentSchedulingPostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var pipeline: IngestionPipeline

    @Autowired
    lateinit var targets: SourceTargetStore

    @Autowired
    lateinit var classifier: JobClassifier

    @Autowired
    lateinit var catalog: CatalogStore

    private val segmentIds = mutableListOf<Long>()

    @AfterEach
    fun deleteSegments() {
        segmentIds.forEach {
            jdbc.update("DELETE FROM discovery.ingestion_run WHERE segment_id = ?", it)
            jdbc.update("DELETE FROM discovery.coverage_segment WHERE segment_id = ?", it)
        }
    }

    // País fictício (ZZ): não colide com os segmentos do seed nem com os das preferências.
    private fun createSegment(workMode: String): FetchTarget {
        val id = jdbc.queryForObject(
            "INSERT INTO discovery.coverage_segment (role_family, country_code, work_mode, keywords) VALUES ('BACKEND', 'ZZ', ?, ?) RETURNING segment_id",
            Long::class.java, workMode, "$externalIdPrefix $workMode"
        )!!
        segmentIds += id
        return FetchTarget(segmentId = id, roleFamily = "BACKEND", countryCode = "ZZ", workMode = workMode, keywords = "$externalIdPrefix $workMode")
    }

    private fun aggregator(code: String): SourceRow {
        val id = jdbc.queryForObject("SELECT job_source_id FROM core.job_source WHERE code = ?", Long::class.java, code)!!
        return SourceRow(id, code, code, SourceType.AGGREGATOR, null, false, null, null, emptyMap())
    }

    private fun connector(sourceCode: String, fails: Boolean) = object : JobSourceConnector {
        override val code = sourceCode

        override fun fetch(source: SourceContext, target: FetchTarget): FetchResult =
            if (fails) throw ConnectorException("$sourceCode respondeu HTTP 401") else FetchResult(emptyList(), fullSnapshot = false)
    }

    private fun collect(source: SourceRow, target: FetchTarget, fails: Boolean) = pipeline.processTarget(
        source, SourceContext(source.id, source.code, null), connector(source.code, fails), target, TriggerType.SCHEDULED,
        JobNormalizer(classifier, SkillExtractor(catalog.skillAliases()))
    )

    private fun dueIds(source: SourceRow, force: Boolean = false, workModes: List<String>? = null) =
        targets.segments(source.id, force, workModes).mapNotNull { it.segmentId }.filter { it in segmentIds }

    @Test
    fun `two aggregators get the same segments and one failure does not starve the other`() {
        val adzuna = aggregator("ADZUNA")
        val jooble = aggregator("JOOBLE")
        val remote = createSegment("REMOTE")
        val any = createSegment("ANY")
        assertEquals(segmentIds, dueIds(adzuna).sorted())
        assertEquals(segmentIds, dueIds(jooble).sorted())

        // A primeira fonte da rodada falha (como o Adzuna com 401): os segmentos continuam vencidos para as duas.
        assertEquals(RunStatus.FAILED, collect(adzuna, remote, fails = true).status)
        assertTrue(remote.segmentId in dueIds(adzuna))
        assertTrue(remote.segmentId in dueIds(jooble))

        // A segunda coleta com sucesso: só ela deixa de ver o segmento.
        assertEquals(RunStatus.COMPLETED, collect(jooble, remote, fails = false).status)
        assertFalse(remote.segmentId in dueIds(jooble))
        assertTrue(remote.segmentId in dueIds(adzuna))
        assertTrue(any.segmentId in dueIds(jooble))

        assertTrue(remote.segmentId in dueIds(jooble, force = true))
    }

    @Test
    fun `work modes restrict the segments of a source`() {
        val remotive = aggregator("REMOTIVE")
        val remote = createSegment("REMOTE")
        val any = createSegment("ANY")
        val due = dueIds(remotive, workModes = listOf("REMOTE"))
        assertTrue(remote.segmentId in due)
        assertFalse(any.segmentId in due)
    }
}
