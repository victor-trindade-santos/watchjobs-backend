package com.watchjobs.profile

import com.watchjobs.classification.SeniorityBand
import com.watchjobs.profile.inference.InferenceExperience
import com.watchjobs.profile.inference.LevelConfidence
import com.watchjobs.profile.inference.ProfileInferenceEngine
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// F3-06: testes de referência do perfil automático (regra pura, sem banco).
class ProfileInferenceEngineTest {
    private val today = LocalDate.of(2026, 10, 1)
    private val bands = listOf(
        SeniorityBand(null, 0.0, 2.0, "JUNIOR"),
        SeniorityBand(null, 2.0, 5.0, "MID"),
        SeniorityBand(null, 5.0, null, "SENIOR")
    )
    private val engine = ProfileInferenceEngine({ years, _ ->
        bands.firstOrNull { years >= it.minYears && (it.maxYears == null || years < it.maxYears!!) }?.level
    }, today)

    private fun exp(
        title: String,
        start: String,
        end: String?,
        family: String? = "BACKEND",
        level: String? = null,
        internship: Boolean = false,
        skills: Set<String> = emptySet()
    ) = InferenceExperience(title, LocalDate.parse(start), end?.let(LocalDate::parse), internship, family, level, skills)

    @Test
    fun `six years in backend with senior title is senior with high confidence`() {
        val result = engine.infer(listOf(exp("Desenvolvedor Sênior", "2020-10-01", null, level = "SENIOR")))
        assertEquals("BACKEND", result.primaryRoleFamily)
        assertEquals("SENIOR", result.experienceLevel)
        assertEquals(LevelConfidence.HIGH, result.levelConfidence)
        assertEquals(6.0, result.totalYears)
    }

    @Test
    fun `two simultaneous jobs do not count twice`() {
        val result = engine.infer(
            listOf(
                exp("Desenvolvedor", "2022-10-01", "2024-10-01"),
                exp("Desenvolvedor freelancer", "2023-01-01", "2024-01-01")
            )
        )
        assertEquals(2.0, result.totalYears)
        assertEquals(2.0, result.yearsByFamily["BACKEND"])
    }

    @Test
    fun `only internships give intern`() {
        val result = engine.infer(
            listOf(
                exp("Estagiário de desenvolvimento", "2025-01-01", "2026-06-30", level = "INTERN", internship = true)
            )
        )
        assertEquals("INTERN", result.experienceLevel)
        assertEquals(0.0, result.totalYears)
        assertTrue(result.internshipYears > 1.0)
    }

    @Test
    fun `internship without keyword still gives intern`() {
        val result = engine.infer(listOf(exp("Desenvolvimento", "2026-01-01", null, internship = true)))
        assertEquals("INTERN", result.experienceLevel)
        assertEquals(LevelConfidence.LOW, result.levelConfidence)
    }

    @Test
    fun `without keyword the band decides with medium confidence`() {
        val result = engine.infer(listOf(exp("Desenvolvedor", "2023-10-01", null)))
        assertEquals("MID", result.experienceLevel)
        assertEquals(LevelConfidence.MEDIUM, result.levelConfidence)
    }

    @Test
    fun `old keyword is ignored after twelve months`() {
        val result = engine.infer(listOf(exp("Desenvolvedor Júnior", "2018-01-01", "2024-06-01", level = "JUNIOR")))
        assertEquals("SENIOR", result.experienceLevel)
    }

    @Test
    fun `keyword wins when it disagrees with the band`() {
        val result = engine.infer(listOf(exp("Tech Lead", "2023-10-01", null, level = "LEAD")))
        assertEquals("LEAD", result.experienceLevel)
        assertEquals(LevelConfidence.MEDIUM, result.levelConfidence)
    }

    @Test
    fun `primary family uses the last five years`() {
        val result = engine.infer(
            listOf(
                exp("Analista de dados", "2010-01-01", "2019-12-31", family = "DATA_ANALYTICS"),
                exp("Desenvolvedor", "2020-01-01", null, family = "BACKEND")
            )
        )
        assertEquals("BACKEND", result.primaryRoleFamily)
        assertTrue(result.yearsByFamily.getValue("DATA_ANALYTICS") > result.yearsByFamily.getValue("BACKEND"))
    }

    @Test
    fun `less than a year of history has low confidence`() {
        val result = engine.infer(listOf(exp("Desenvolvedor Júnior", "2026-03-01", null, level = "JUNIOR")))
        assertEquals("JUNIOR", result.experienceLevel)
        assertEquals(LevelConfidence.LOW, result.levelConfidence)
    }

    @Test
    fun `years by skill merge overlapping experiences`() {
        val result = engine.infer(
            listOf(
                exp("Dev Kotlin", "2021-10-01", "2023-10-01", skills = setOf("Kotlin")),
                exp("Dev Kotlin e Java", "2022-10-01", "2024-10-01", skills = setOf("Kotlin", "Java"))
            )
        )
        assertEquals(3.0, result.yearsBySkill["Kotlin"])
        assertEquals(2.0, result.yearsBySkill["Java"])
    }

    @Test
    fun `no experience gives empty profile`() {
        val result = engine.infer(emptyList())
        assertNull(result.experienceLevel)
        assertNull(result.primaryRoleFamily)
        assertEquals(0.0, result.totalYears)
    }

    @Test
    fun `future experiences are ignored`() {
        val result = engine.infer(listOf(exp("Desenvolvedor", "2027-01-01", null)))
        assertNull(result.experienceLevel)
    }
}
