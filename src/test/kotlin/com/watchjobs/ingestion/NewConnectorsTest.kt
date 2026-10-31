package com.watchjobs.ingestion

import com.watchjobs.ingestion.connector.AshbyConnector
import com.watchjobs.ingestion.connector.CareerPageConnector
import com.watchjobs.ingestion.connector.ConnectorException
import com.watchjobs.ingestion.connector.FetchTarget
import com.watchjobs.ingestion.connector.FreeQuery
import com.watchjobs.ingestion.connector.HttpGateway
import com.watchjobs.ingestion.connector.JoobleConnector
import com.watchjobs.ingestion.connector.LeverConnector
import com.watchjobs.ingestion.connector.RemotiveConnector
import com.watchjobs.ingestion.connector.RobotsTxt
import com.watchjobs.ingestion.connector.SalaryText
import com.watchjobs.ingestion.connector.SmartRecruitersConnector
import com.watchjobs.ingestion.connector.SourceContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// F07: conectores novos contra respostas no formato das APIs oficiais, sem internet.
class NewConnectorsTest {
    private val mapper = IngestionFixtures.mapper
    private val requested = mutableListOf<URI>()
    private fun gateway(body: String) = HttpGateway { uri, _ -> requested += uri; body }
    private fun source(code: String, credentials: Map<String, String> = emptyMap()) =
        SourceContext(1, code, null, mapOf("minDelayMs" to 0), credentials)

    @Test
    fun `lever normalizes postings with workplace, commitment and salary`() {
        val json = """
            [{"id":"abc-1","text":"Senior Backend Engineer","hostedUrl":"https://jobs.lever.co/acme/abc-1","createdAt":1761825600000,
              "workplaceType":"remote","descriptionPlain":"Kotlin e Spring","categories":{"location":"São Paulo","commitment":"Contract","team":"Platform"},
              "salaryRange":{"min":15000,"max":20000,"currency":"BRL","interval":"per-month-salary"}},
             {"text":"sem id"}]
        """
        val result = LeverConnector(gateway(json), mapper).fetch(source("LEVER"), FetchTarget(externalKey = "acme", organizationName = "ACME"))
        assertTrue(result.fullSnapshot)
        val posting = result.postings.single()
        assertEquals("acme:abc-1", posting.externalId)
        assertEquals("REMOTE", posting.workMode)
        assertEquals("PJ", posting.jobType)
        assertEquals(15000.0, posting.salaryMin)
        assertEquals("MONTH", posting.salaryPeriod)
        assertTrue(requested.single().toString().startsWith("https://api.lever.co/v0/postings/acme?mode=json"))
    }

    @Test
    fun `ashby skips unlisted jobs and reads compensation`() {
        val json = """
            {"jobs":[{"id":"j1","title":"Data Engineer","location":"Remote - Brazil","isListed":true,"workplaceType":"Hybrid",
                      "employmentType":"Intern","jobUrl":"https://jobs.ashbyhq.com/acme/j1","publishedAt":"2026-10-20T10:00:00Z",
                      "compensation":{"summaryComponents":[{"compensationType":"Salary","interval":"1 YEAR","currencyCode":"USD","minValue":80000,"maxValue":100000}]}},
                     {"id":"j2","title":"Hidden","isListed":false,"jobUrl":"https://jobs.ashbyhq.com/acme/j2"}]}
        """
        val postings = AshbyConnector(gateway(json), mapper).fetch(source("ASHBY"), FetchTarget(externalKey = "acme")).postings
        val job = postings.single()
        assertEquals("HYBRID", job.workMode)
        assertEquals("INTERNSHIP", job.jobType)
        assertEquals("YEAR", job.salaryPeriod)
        assertEquals(100000.0, job.salaryMax)
    }

    @Test
    fun `smartrecruiters pages until the total and builds the public url`() {
        val pages = listOf(
            """{"offset":0,"limit":100,"totalFound":2,"content":[{"id":"111","name":"Dev Java","location":{"city":"Recife","country":"br","remote":true}}]}""",
            """{"offset":1,"limit":100,"totalFound":2,"content":[{"id":"222","name":"Dev Go","location":{"city":"Recife","country":"br"}}]}"""
        )
        var call = 0
        val connector = SmartRecruitersConnector(HttpGateway { _, _ -> pages[call++] }, mapper)
        val result = connector.fetch(source("SMARTRECRUITERS"), FetchTarget(externalKey = "Acme"))
        assertTrue(result.fullSnapshot)
        assertEquals(listOf("Acme:111", "Acme:222"), result.postings.map { it.externalId })
        assertEquals("https://jobs.smartrecruiters.com/Acme/111", result.postings[0].url)
        assertEquals("REMOTE", result.postings[0].workMode)
    }

    @Test
    fun `jooble posts the query and never leaks the key`() {
        var sentBody = ""
        val json = """{"totalCount":1,"jobs":[{"id":-123,"title":"Desenvolvedor Kotlin","company":"ACME","location":"São Paulo, SP",
            "snippet":"Vaga CLT","salary":"R$ 8.000 - R$ 12.000 por mês","type":"Full-time","link":"https://jooble.org/desc/-123","updated":"2026-10-28T09:00:00.0000000"}]}"""
        val connector = JoobleConnector(object : HttpGateway {
            override fun get(uri: URI, headers: Map<String, String>) = error("GET não usado")
            override fun post(uri: URI, headers: Map<String, String>, body: String): String {
                requested += uri; sentBody = body; return json
            }
        }, mapper)
        val result = connector.search(source("JOOBLE", mapOf("JOOBLE_API_KEY" to "secret-key")), FreeQuery("kotlin", "São Paulo"))
        assertFalse(result.fullSnapshot)
        val job = result.postings.single()
        assertEquals(8000.0, job.salaryMin)
        assertEquals(12000.0, job.salaryMax)
        assertEquals("BRL", job.salaryCurrency)
        assertEquals("MONTH", job.salaryPeriod)
        assertTrue(sentBody.contains("\"keywords\":\"kotlin\""))

        assertThrows<ConnectorException> { connector.search(source("JOOBLE"), FreeQuery("kotlin")) }
        val failing = JoobleConnector(object : HttpGateway {
            override fun get(uri: URI, headers: Map<String, String>) = error("x")
            override fun post(uri: URI, headers: Map<String, String>, body: String): String = throw IllegalStateException("I/O error on POST $uri")
        }, mapper)
        val error = assertThrows<ConnectorException> { failing.search(source("JOOBLE", mapOf("JOOBLE_API_KEY" to "secret-key")), FreeQuery("kotlin")) }
        assertFalse(error.message!!.contains("secret-key"))
    }

    @Test
    fun `remotive marks every job as remote`() {
        val json = """{"jobs":[{"id":7,"url":"https://remotive.com/remote-jobs/software-dev/kotlin-7","title":"Kotlin Developer",
            "company_name":"Remote Co","category":"Software Development","job_type":"full_time","publication_date":"2026-10-29T10:00:00",
            "candidate_required_location":"Worldwide","salary":"$80k - $100k","description":"<p>Kotlin</p>"}]}"""
        val job = RemotiveConnector(gateway(json), mapper).search(source("REMOTIVE"), FreeQuery("kotlin")).postings.single()
        assertEquals("REMOTE", job.workMode)
        assertEquals("Remote - Worldwide", job.locationText)
        assertEquals(80000.0, job.salaryMin)
        assertEquals("USD", job.salaryCurrency)
        assertTrue(requested.single().toString().contains("search=kotlin"))
    }

    @Test
    fun `salary text only parses clear numbers`() {
        assertNull(SalaryText.parse("A combinar"))
        assertEquals(45000.0, SalaryText.parse("45,000 USD per year")?.min)
        assertEquals("YEAR", SalaryText.parse("45,000 USD per year")?.period)
    }

    @Test
    fun `robots txt picks our group and the most specific rule`() {
        val robots = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /

            User-agent: WatchJobsBot
            Disallow: /private
            Allow: /private/jobs
            Crawl-delay: 2
            """.trimIndent(),
            "WatchJobsBot/1.0 (+https://example.com)"
        )
        assertTrue(robots.allows("/careers"))
        assertFalse(robots.allows("/private/team"))
        assertTrue(robots.allows("/private/jobs/1"))
        assertEquals(2L, robots.crawlDelaySeconds)
        assertFalse(RobotsTxt.parse("User-agent: *\nDisallow: /", "OtherBot").allows("/x"))
    }

    @Test
    fun `career pages read json-ld job postings and respect robots`() {
        val pages = mapOf(
            "https://acme.example/robots.txt" to "User-agent: *\nDisallow: /internal",
            "https://acme.example/careers" to """
                <html><body>
                <a href="/jobs/1">Dev</a><a href="/internal/jobs/2">Interna</a>
                <script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting","title":"Analista de Dados",
                  "identifier":{"value":"A-10"},"url":"https://acme.example/careers#a10","datePosted":"2026-10-25",
                  "hiringOrganization":{"name":"ACME"},"jobLocation":{"address":{"addressLocality":"Curitiba","addressRegion":"PR","addressCountry":"BR"}},
                  "employmentType":"FULL_TIME","jobLocationType":"TELECOMMUTE"}</script></body></html>
            """.trimIndent(),
            "https://acme.example/jobs/1" to """
                <html><script type="application/ld+json">{"@graph":[{"@type":"JobPosting","title":"Dev Kotlin","url":"/jobs/1",
                  "baseSalary":{"currency":"BRL","value":{"minValue":9000,"maxValue":12000,"unitText":"MONTH"}}}]}</script></html>
            """.trimIndent()
        )
        val visited = mutableListOf<String>()
        val connector = CareerPageConnector(mapper, "WatchJobsBot/1.0", 5).apply {
            fetcher = { url -> visited += url; pages[url] ?: throw IllegalStateException("404 $url") }
        }
        val result = connector.fetch(
            SourceContext(1, "CAREER_PAGES", null, mapOf("minDelayMs" to 0)),
            FetchTarget(sourceCompanyId = 1, externalKey = "acme", organizationName = "ACME", careersUrl = "https://acme.example/careers")
        )
        assertFalse(result.fullSnapshot)
        assertEquals(setOf("A-10", "https://acme.example/jobs/1"), result.postings.map { it.externalId }.toSet())
        val remote = result.postings.first { it.externalId == "A-10" }
        assertEquals("REMOTE", remote.workMode)
        assertEquals("Curitiba, PR, BR", remote.locationText)
        assertEquals(12000.0, result.postings.first { it.title == "Dev Kotlin" }.salaryMax)
        assertFalse(visited.any { it.contains("/internal") })
    }
}
