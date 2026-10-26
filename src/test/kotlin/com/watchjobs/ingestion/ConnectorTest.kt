package com.watchjobs.ingestion

import com.watchjobs.ingestion.connector.AdzunaConnector
import com.watchjobs.ingestion.connector.ConnectorException
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.FreeQuery
import com.watchjobs.ingestion.connector.GreenhouseConnector
import com.watchjobs.ingestion.connector.HttpGateway
import com.watchjobs.ingestion.connector.SourceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Conectores contra respostas gravadas em src/test/resources/fixtures, sem internet.
class ConnectorTest {
    private val requested = mutableListOf<URI>()

    private fun gateway(body: String) = HttpGateway { uri, _ ->
        requested += uri
        body
    }

    private val greenhouseSource = SourceContext(1, "GREENHOUSE", null, mapOf("minDelayMs" to 0))

    private val adzunaSource = SourceContext(
        sourceId = 5,
        code = "ADZUNA",
        baseUrl = null,
        config = mapOf("country" to "br"),
        credentials = mapOf("ADZUNA_APP_ID" to "app-id", "ADZUNA_APP_KEY" to "super-secret-key")
    )

    @Test
    fun `greenhouse reads every job of a board as a full snapshot`() {
        val connector = GreenhouseConnector(gateway(IngestionFixtures.read("greenhouse/vtex-jobs.json")), IngestionFixtures.mapper)

        val result = connector.fetch(greenhouseSource, FetchTarget(sourceCompanyId = 1, externalKey = "vtex", organizationName = "VTEX"))

        assertTrue(result.fullSnapshot)
        assertEquals(4, result.postings.size)
        val first = result.postings.first()
        assertEquals("vtex:6100504004", first.externalId)
        assertEquals("Senior Backend Engineer (Java)", first.title)
        assertEquals("VTEX", first.organizationName)
        assertEquals("São Paulo, Brazil", first.locationText)
        assertEquals("https://job-boards.greenhouse.io/vtex/jobs/6100504004", first.url)
        assertEquals(OffsetDateTime.parse("2026-10-10T09:00:00-04:00"), first.postedAt)
        assertEquals(listOf("Engineering"), first.departments)
        assertTrue(first.description!!.contains("&lt;p&gt;"))
        val uri = requested.single().toString()
        assertTrue(uri.startsWith("https://boards-api.greenhouse.io/v1/boards/vtex/jobs"), uri)
        assertTrue(uri.contains("content=true"), uri)
    }

    @Test
    fun `greenhouse turns transport and format problems into connector errors`() {
        val down = GreenhouseConnector(HttpGateway { _, _ -> throw IllegalStateException("boom") }, IngestionFixtures.mapper)
        assertThrows<ConnectorException> { down.fetch(greenhouseSource, FetchTarget(externalKey = "vtex")) }

        val broken = GreenhouseConnector(gateway("<html>not json</html>"), IngestionFixtures.mapper)
        assertThrows<ConnectorException> { broken.fetch(greenhouseSource, FetchTarget(externalKey = "vtex")) }

        val empty = GreenhouseConnector(gateway("""{"jobs":[],"meta":{"total":0}}"""), IngestionFixtures.mapper)
        assertTrue(empty.fetch(greenhouseSource, FetchTarget(externalKey = "vtex")).postings.isEmpty())
    }

    @Test
    fun `adzuna parses results and never claims a full snapshot`() {
        val connector = AdzunaConnector(gateway(IngestionFixtures.read("adzuna/backend-br.json")), IngestionFixtures.mapper)

        val result = connector.search(adzunaSource, FreeQuery("desenvolvedor backend", location = "São Paulo"))

        assertFalse(result.fullSnapshot)
        assertEquals(3, result.postings.size)
        val first = result.postings[0]
        assertEquals("4901234567", first.externalId)
        assertEquals("Acme Tecnologia Ltda.", first.organizationName)
        assertEquals(9000.0, first.salaryMin)
        assertEquals("BRL", first.salaryCurrency)
        assertEquals("4901234568", result.postings[1].externalId)
        assertNull(result.postings[1].salaryCurrency)
        val uri = requested.single().toString()
        assertTrue(uri.startsWith("https://api.adzuna.com/v1/api/jobs/br/search/1"), uri)
        assertTrue(uri.contains("results_per_page=50"), uri)
        assertTrue(uri.contains("what=desenvolvedor%20backend"), uri)
        assertTrue(uri.contains("where=S%C3%A3o%20Paulo"), uri)
    }

    @Test
    fun `adzuna segment for remote work adds the keyword`() {
        val connector = AdzunaConnector(gateway("""{"results":[]}"""), IngestionFixtures.mapper)

        connector.fetch(adzunaSource, FetchTarget(segmentId = 1, keywords = "desenvolvedor backend", workMode = "REMOTE"))

        assertTrue(requested.single().toString().contains("what=desenvolvedor%20backend%20remoto"))
    }

    @Test
    fun `adzuna errors never leak the api key`() {
        val missing = AdzunaConnector(gateway("{}"), IngestionFixtures.mapper)
        assertThrows<ConnectorException> { missing.search(adzunaSource.copy(credentials = emptyMap()), FreeQuery("java")) }

        val failing = AdzunaConnector(
            HttpGateway { uri, _ -> throw IllegalStateException("I/O error on GET request for \"$uri\"") },
            IngestionFixtures.mapper
        )
        val error = assertThrows<ConnectorException> { failing.search(adzunaSource, FreeQuery("java")) }
        assertFalse(error.message!!.contains("super-secret-key"))
        assertNull(error.cause)
    }
}
