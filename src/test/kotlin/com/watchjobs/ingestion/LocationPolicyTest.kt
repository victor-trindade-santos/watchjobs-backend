package com.watchjobs.ingestion

import com.watchjobs.ingestion.connector.AshbyConnector
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.HttpGateway
import com.watchjobs.ingestion.connector.LeverConnector
import com.watchjobs.ingestion.connector.RawPosting
import com.watchjobs.ingestion.connector.RemotiveConnector
import com.watchjobs.ingestion.connector.SmartRecruitersConnector
import com.watchjobs.ingestion.connector.SourceContext
import com.watchjobs.ingestion.normalize.JobNormalizer
import com.watchjobs.ingestion.normalize.NormalizeContext
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.pipeline.LocationPolicy
import com.watchjobs.sources.SourceType
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Fase D: política de local (Brasil ou remoto) contra registros reais de Ashby, Lever e SmartRecruiters.
class LocationPolicyTest {
    private val mapper = IngestionFixtures.mapper
    private val normalizer = JobNormalizer(IngestionFixtures.classifier, SkillExtractor(IngestionFixtures.aliases))
    private val context = SourceContext(1, "ATS", null, mapOf("minDelayMs" to 0))
    private fun gateway(body: String) = HttpGateway { _, _ -> body }

    private fun accepted(postings: List<RawPosting>, policy: LocationPolicy = LocationPolicy.BR_OR_REMOTE): Map<String, Boolean> =
        postings.associate { raw ->
            val job = requireNotNull(normalizer.normalize(raw, NormalizeContext(1, SourceType.ATS_PUBLIC, allowsStorage = false, organizationName = "ACME")))
            raw.title to policy.accepts(job)
        }

    @Test
    fun `ashby keeps brazil and remote, drops on-site and hybrid abroad even when isRemote is true`() {
        val postings = AshbyConnector(gateway(IngestionFixtures.read("ashby/mixed-locations.json")), mapper)
            .fetch(context, FetchTarget(externalKey = "belvo")).postings
        assertEquals("BR", postings.first().countryCode)
        assertEquals(
            mapOf(
                "Senior Applied Data Scientist" to true,
                "Senior Technical Program Manager - Security" to true,
                "IT Support Specialist" to false,
                "Data Engineer" to false
            ),
            accepted(postings)
        )
    }

    @Test
    fun `lever uses workplaceType and the country field`() {
        val postings = LeverConnector(gateway(IngestionFixtures.read("lever/mixed-locations.json")), mapper)
            .fetch(context, FetchTarget(externalKey = "neon")).postings
        assertEquals(
            mapOf(
                "Staff Software Engineer" to true,
                "Senior Software Developer, Platform" to true,
                "Dentist" to false,
                "Staff Accountant" to false
            ),
            accepted(postings)
        )
    }

    @Test
    fun `smartrecruiters uses the structured country and remote flags`() {
        val postings = SmartRecruitersConnector(gateway(IngestionFixtures.read("smartrecruiters/experian-mixed.json")), mapper)
            .fetch(context, FetchTarget(externalKey = "Experian")).postings
        assertEquals(listOf("BR", "US", "IN", "US"), postings.map { it.countryCode })
        assertEquals(
            mapOf(
                "Gerente Executivo de Consultoria Analítica" to true,
                "Principal Product Manager, Contract Management" to true,
                "Demand & Software Asset management Specialist II" to false,
                "Operational Resilience Manager" to false
            ),
            accepted(postings)
        )
    }

    @Test
    fun `unknown country passes and ANY keeps everything`() {
        val postings = LeverConnector(gateway(IngestionFixtures.read("lever/mixed-locations.json")), mapper)
            .fetch(context, FetchTarget(externalKey = "neon")).postings
        assertTrue(accepted(postings, LocationPolicy.ANY).values.all { it })
        assertTrue(accepted(postings.map { it.copy(countryCode = null, locationText = "Houston") }).values.all { it })
        assertEquals(LocationPolicy.ANY, LocationPolicy.of(mapOf("locationPolicy" to "any")))
        assertEquals(LocationPolicy.BR_OR_REMOTE, LocationPolicy.of(emptyMap()))
    }

    @Test
    fun `remotive searches the english term of the segment area`() {
        val requested = mutableListOf<URI>()
        val connector = RemotiveConnector(HttpGateway { uri, _ -> requested += uri; """{"jobs":[]}""" }, mapper)
        val config = mapOf("minDelayMs" to 0, "searchTerms" to mapOf("BACKEND" to "backend"))
        connector.fetch(SourceContext(1, "REMOTIVE", null, config), FetchTarget(segmentId = 1, roleFamily = "BACKEND", keywords = "desenvolvedor backend remoto"))
        connector.fetch(SourceContext(1, "REMOTIVE", null, config), FetchTarget(segmentId = 2, roleFamily = "MOBILE", keywords = "desenvolvedor mobile"))
        assertTrue(requested[0].toString().contains("search=backend&"))
        assertTrue(requested[1].toString().contains("search=desenvolvedor%20mobile"))
    }
}
