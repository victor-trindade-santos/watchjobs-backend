package com.watchjobs.matching

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// F4-01: testes de referência do motor de nota (regra pura e reproduzível).
class MatchingEngineTest {
    private val profile = MatchProfile(
        preferredRoles = listOf("Desenvolvedor Backend"),
        preferredFamilies = setOf("BACKEND"),
        level = "SENIOR",
        workMode = "REMOTE",
        salaryMin = 12500.0,
        jobType = "CLT",
        skillIds = setOf(1, 2, 3, 4, 10)
    )

    private val job = MatchJob(
        id = 1,
        title = "Desenvolvedor Backend Sênior",
        roleFamily = "BACKEND",
        level = "SENIOR",
        workMode = "REMOTE",
        city = null,
        salaryMin = 6000.0,
        salaryMax = 8000.0,
        salaryCurrency = "BRL",
        salaryPeriod = "MONTH",
        jobType = "CLT",
        requiredSkills = mapOf(1L to "Kotlin", 2L to "Java", 3L to "SQL", 4L to "Docker", 5L to "Kafka"),
        optionalSkills = mapOf(10L to "AWS", 11L to "Terraform")
    )

    @Test
    fun `reference case gives 86,4`() {
        val result = MatchingEngine.score(profile, job)
        assertEquals(86.4, result.score)
        assertEquals(listOf("Kafka"), result.missingSkills)
        assertEquals(MatchingEngine.ALGO_VERSION, result.algoVersion)
    }

    @Test
    fun `same input always gives the same output`() {
        assertEquals(MatchingEngine.score(profile, job), MatchingEngine.score(profile, job))
    }

    @Test
    fun `perfect match is 100`() {
        val result = MatchingEngine.score(profile, job.copy(salaryMax = 15000.0, requiredSkills = mapOf(1L to "Kotlin"), optionalSkills = mapOf(10L to "AWS")))
        assertEquals(100.0, result.score)
    }

    @Test
    fun `same area without the exact title is partial`() {
        val role = MatchingEngine.score(profile, job.copy(title = "Engenheiro de Plataforma")).criteria.first { it.key == "role" }
        assertEquals(0.6, role.value)
    }

    @Test
    fun `neighbor level is partial and distant level is zero`() {
        assertEquals(0.5, MatchingEngine.score(profile, job.copy(level = "MID")).criteria.first { it.key == "seniority" }.value)
        assertEquals(0.0, MatchingEngine.score(profile, job.copy(level = "JUNIOR")).criteria.first { it.key == "seniority" }.value)
    }

    @Test
    fun `criteria without data leave the calculation`() {
        val result = MatchingEngine.score(
            MatchProfile(preferredRoles = listOf("Desenvolvedor Backend")),
            job.copy(level = null, workMode = null, salaryMin = null, salaryMax = null, jobType = null)
        )
        assertEquals(100.0, result.score)
        assertEquals(listOf("role"), result.criteria.filter { it.applicable }.map { it.key })
    }

    @Test
    fun `inferred profile is used and flagged when preferences are missing`() {
        val result = MatchingEngine.score(
            MatchProfile(inferredFamily = "BACKEND", level = "SENIOR", levelInferred = true),
            job.copy(title = "Engenheiro de Plataforma")
        )
        assertTrue(result.criteria.first { it.key == "role" }.inferred)
        assertTrue(result.criteria.first { it.key == "seniority" }.inferred)
    }

    @Test
    fun `remote preference against on site job scores zero on mode`() {
        val mode = MatchingEngine.score(profile, job.copy(workMode = "ON_SITE")).criteria.first { it.key == "workMode" }
        assertEquals(0.0, mode.value)
    }

    @Test
    fun `salary in another currency is not compared`() {
        val salary = MatchingEngine.score(profile, job.copy(salaryCurrency = "USD")).criteria.first { it.key == "salary" }
        assertFalse(salary.applicable)
    }

    @Test
    fun `yearly salary is converted to monthly`() {
        val salary = MatchingEngine.score(profile, job.copy(salaryMax = 180000.0, salaryPeriod = "YEAR")).criteria.first { it.key == "salary" }
        assertEquals(1.0, salary.value)
    }

    @Test
    fun `empty profile scores zero`() {
        assertEquals(0.0, MatchingEngine.score(MatchProfile(), job).score)
    }
}
