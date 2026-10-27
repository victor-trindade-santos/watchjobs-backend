package com.watchjobs.matching

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.ingestion.normalize.ExtractedSkill
import com.watchjobs.ingestion.normalize.NormalizedJob
import com.watchjobs.ingestion.store.DiscoveredJobStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals

// Filtros do feed: multi-seleção, faixa salarial, skills E/OU e ordenação.
class FeedFiltersPostgresTest : AuthSessionPostgresTestSupport() {
    @Autowired
    lateinit var store: DiscoveredJobStore

    private val token = "fx" + UUID.randomUUID().toString().take(8)

    @AfterEach
    fun deleteJobs() {
        jdbc.update("DELETE FROM discovery.discovered_job WHERE external_id LIKE ?", "$token%")
    }

    private fun job(
        suffix: String,
        level: String,
        mode: String,
        salary: Pair<Double, Double>?,
        skills: List<ExtractedSkill>
    ) = NormalizedJob(
        sourceId = requireNotNull(jdbc.queryForObject("SELECT job_source_id FROM core.job_source WHERE code = 'GREENHOUSE'", Long::class.java)),
        sourceCompanyId = null,
        externalId = "$token-$suffix",
        title = "Dev $suffix",
        organizationName = "Empresa $token",
        jobUrl = "https://example.com/jobs/$token-$suffix",
        city = null, state = null, countryCode = "BR", rawLocation = "Remote - Brazil",
        workMode = mode, experienceLevel = level, jobType = null, roleFamily = "BACKEND",
        classificationConfidence = 1.0, rulesVersion = "test",
        salaryMin = salary?.first, salaryMax = salary?.second, salaryCurrency = salary?.let { "BRL" }, salaryPeriod = salary?.let { "MONTH" },
        fullDescription = null, descriptionSnippet = "Vaga $suffix", skills = skills, postedAt = null,
        contentHash = "e".repeat(64), fingerprint = suffix.padEnd(64, 'f')
    )

    private fun titles(bearer: String, query: String): List<String> {
        val body = mockMvc.get("/jobs/feed?q=$token&$query") { header("Authorization", bearer) }
            .andReturn().response.contentAsString
        return objectMapper.readTree(body)["items"].values().map { it["job"]["title"].asString() }.toList()
    }

    @Test
    fun `filters combine, skills accept any or all and sorting follows the salary`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        mockMvc.put("/profile/preferences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"preferredRoles":["Dev"],"preferredWorkMode":"ANY"}"""
        }.andExpect { status { isOk() } }

        val skills = jdbc.query("SELECT skill_id, skill_name FROM core.skill ORDER BY skill_id LIMIT 2") { rs, _ ->
            ExtractedSkill(rs.getLong(1), rs.getString(2), true)
        }
        val (first, second) = skills
        val insertedA = store.insert(job("A", "JUNIOR", "REMOTE", 5000.0 to 8000.0, listOf(first, second)), null, null)
        val insertedB = store.insert(job("B", "SENIOR", "HYBRID", 12000.0 to 15000.0, listOf(first)), null, null)
        store.insertSkills(insertedA, listOf(first, second))
        store.insertSkills(insertedB, listOf(first))
        store.insert(job("C", "SENIOR", "REMOTE", null, emptyList()), null, null)

        assertEquals(3, titles(bearer, "").size)
        assertEquals(listOf("Dev A"), titles(bearer, "level=JUNIOR"))
        assertEquals(3, titles(bearer, "level=JUNIOR&level=SENIOR").size)
        assertEquals(setOf("Dev A", "Dev C"), titles(bearer, "workMode=REMOTE").toSet())

        assertEquals(listOf("Dev B"), titles(bearer, "salaryMin=10000"))
        assertEquals(listOf("Dev A"), titles(bearer, "salaryMax=9000"))
        assertEquals(listOf("Dev B"), titles(bearer, "salaryMin=9000&salaryMax=20000"))

        assertEquals(setOf("Dev A", "Dev B"), titles(bearer, "skill=${first.skillId}&skill=${second.skillId}").toSet())
        assertEquals(listOf("Dev A"), titles(bearer, "skill=${first.skillId}&skill=${second.skillId}&skillMatch=ALL"))

        assertEquals("Dev B", titles(bearer, "sort=SALARY_DESC").first())
        assertEquals("Dev A", titles(bearer, "sort=SALARY_ASC").first())

        mockMvc.get("/jobs/feed?salaryMin=9000&salaryMax=1000") { header("Authorization", bearer) }
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `skills and facets come from the active corpus`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val skill = jdbc.query("SELECT skill_id, skill_name FROM core.skill ORDER BY skill_id LIMIT 1") { rs, _ ->
            ExtractedSkill(rs.getLong(1), rs.getString(2), true)
        }.single()
        store.insert(job("D", "MID", "REMOTE", null, listOf(skill)), null, null)

        val skills = objectMapper.readTree(
            mockMvc.get("/jobs/skills?q=${skill.skillName.take(3)}") { header("Authorization", bearer) }.andReturn().response.contentAsString
        )
        assertEquals(true, skills.values().any { it["id"].asLong() == skill.skillId })

        val facets = objectMapper.readTree(
            mockMvc.get("/jobs/facets") { header("Authorization", bearer) }.andReturn().response.contentAsString
        )
        assertEquals(true, facets["companies"].values().any { it["value"].asString() == "Empresa $token" })
    }
}
