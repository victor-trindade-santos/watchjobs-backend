package com.watchjobs.controller

import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.ingestion.normalize.NormalizedJob
import com.watchjobs.ingestion.store.DiscoveredJobStore
import com.watchjobs.jobs.JobDetailResponse
import com.watchjobs.jobs.JobSearchResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F2-12: busca no corpus, detalhe e salvar do corpus (com 409 na segunda vez).
class JobControllerPostgresTest : AuthSessionPostgresTestSupport() {
    @Autowired
    lateinit var store: DiscoveredJobStore

    private val token = "zx" + UUID.randomUUID().toString().take(8)

    @AfterEach
    fun deleteJobs() {
        jdbc.update("DELETE FROM discovery.discovered_job WHERE external_id LIKE ?", "$token%")
    }

    private fun job(suffix: String, title: String, organization: String) = NormalizedJob(
        sourceId = requireNotNull(jdbc.queryForObject("SELECT job_source_id FROM core.job_source WHERE code = 'GREENHOUSE'", Long::class.java)),
        sourceCompanyId = null,
        externalId = "$token-$suffix",
        title = title,
        organizationName = organization,
        jobUrl = "https://example.com/jobs/$token-$suffix",
        city = "São Paulo",
        state = "SP",
        countryCode = "BR",
        rawLocation = "São Paulo, Brazil",
        workMode = "REMOTE",
        experienceLevel = "SENIOR",
        jobType = null,
        roleFamily = "BACKEND",
        classificationConfidence = 0.6,
        rulesVersion = "test",
        salaryMin = 10000.0,
        salaryMax = 15000.0,
        salaryCurrency = "BRL",
        salaryPeriod = "MONTH",
        fullDescription = null,
        descriptionSnippet = "Trecho de $title",
        skills = emptyList(),
        postedAt = null,
        contentHash = "c".repeat(64),
        fingerprint = "d".repeat(64)
    )

    @Test
    fun `search finds active jobs from more than one company and saving twice conflicts`() {
        val login = signupAndLogin()
        val bearer = "Bearer ${login.token}"
        val platform = store.insert(job("1", "Platform Engineer $token", "Alpha $token"), null, null)
        store.insert(job("2", "Data Engineer $token", "Beta $token"), null, null)

        val search = mockMvc.get("/jobs/search") {
            param("q", token)
            header("Authorization", bearer)
        }.andExpect { status { isOk() } }.andReturn()
        val page = objectMapper.readValue(search.response.contentAsString, JobSearchResponse::class.java)
        assertEquals(2L, page.total)
        assertEquals(setOf("Alpha $token", "Beta $token"), page.items.map { it.organizationName }.toSet())
        assertTrue(page.items.all { it.expiresInDays in 29..30 && !it.alreadySaved && it.sourceName == "Greenhouse" })

        val engineers = objectMapper.readValue(
            mockMvc.get("/jobs/search") {
                param("q", "engineer")
                param("size", "50")
                header("Authorization", bearer)
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
            JobSearchResponse::class.java
        )
        assertTrue(engineers.total >= 2)

        val detail = objectMapper.readValue(
            mockMvc.get("/jobs/${platform.id}") { header("Authorization", bearer) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
            JobDetailResponse::class.java
        )
        assertEquals("Platform Engineer $token", detail.job.title)
        assertEquals("ACTIVE", detail.corpusStatus)

        val saved = objectMapper.readValue(
            mockMvc.post("/jobs/${platform.id}/save") { header("Authorization", bearer) }
                .andExpect { status { isCreated() } }.andReturn().response.contentAsString,
            SavedJobResponse::class.java
        )
        assertEquals("Platform Engineer $token", saved.positionRole)
        assertEquals(10000.0, saved.salaryMin)

        mockMvc.post("/jobs/${platform.id}/save") { header("Authorization", bearer) }
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("CONFLICT") }
                jsonPath("$.message") { value("Você já salvou esta vaga") }
            }

        val list = objectMapper.readValue(
            mockMvc.get("/saved-jobs") { header("Authorization", bearer) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
            Array<SavedJobResponse>::class.java
        )
        assertEquals(listOf(saved.id), list.map { it.id })

        val again = objectMapper.readValue(
            mockMvc.get("/jobs/search") {
                param("q", token)
                header("Authorization", bearer)
            }.andReturn().response.contentAsString,
            JobSearchResponse::class.java
        )
        assertEquals(true, again.items.single { it.id == platform.id }.alreadySaved)
    }

    @Test
    fun `filters, missing jobs and anonymous access`() {
        val login = signupAndLogin()
        val bearer = "Bearer ${login.token}"
        store.insert(job("3", "Backend Engineer $token", "Gamma $token"), null, null)

        val remote = objectMapper.readValue(
            mockMvc.get("/jobs/search") {
                param("q", token)
                param("workMode", "REMOTE")
                param("level", "SENIOR")
                param("location", "paulo")
                header("Authorization", bearer)
            }.andReturn().response.contentAsString,
            JobSearchResponse::class.java
        )
        assertEquals(1L, remote.total)

        val onSite = objectMapper.readValue(
            mockMvc.get("/jobs/search") {
                param("q", token)
                param("workMode", "ON_SITE")
                header("Authorization", bearer)
            }.andReturn().response.contentAsString,
            JobSearchResponse::class.java
        )
        assertEquals(0L, onSite.total)

        mockMvc.get("/jobs/search") {
            param("workMode", "MARTE")
            header("Authorization", bearer)
        }.andExpect { status { isBadRequest() } }
        mockMvc.get("/jobs/999999999999") { header("Authorization", bearer) }.andExpect { status { isNotFound() } }
        mockMvc.post("/jobs/999999999999/save") { header("Authorization", bearer) }.andExpect { status { isNotFound() } }
        mockMvc.get("/jobs/search").andExpect { status { isUnauthorized() } }
    }
}
