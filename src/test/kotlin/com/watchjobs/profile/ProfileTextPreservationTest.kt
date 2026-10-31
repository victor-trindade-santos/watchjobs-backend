package com.watchjobs.profile

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.profile.importer.ResumeParser
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F08: o resumo e as experiências lidos do currículo chegam inteiros ao banco e voltam inteiros pela API.
class ProfileTextPreservationTest : AuthSessionPostgresTestSupport() {
    private val longSentence = "Lidero squads de produto e plataforma com foco em APIs de alta disponibilidade, observabilidade e custo. "

    @Test
    fun `resume parser keeps the whole summary and experience with paragraphs and bullets`() {
        val summaryLines = (1..12).map { longSentence.trim() }
        val text = buildString {
            appendLine("Resumo")
            summaryLines.forEach { appendLine(it) }
            appendLine("Experiência")
            appendLine("Engenheiro de Software | ACME | jan 2020 - presente")
            appendLine("Responsável pela migração de monólito para")
            appendLine("microsserviços em Kotlin.")
            appendLine("• Reduzi o custo de nuvem em 30%.")
            appendLine("• Criei a esteira de deploy contínuo.")
            repeat(60) { appendLine(longSentence.trim()) }
        }
        val data = ResumeParser.parse(text)

        val summary = data.profile?.headline.orEmpty()
        assertTrue(summary.length > 500, "resumo cortado: ${summary.length}")
        assertEquals(summaryLines.joinToString("\n"), summary)

        val description = data.positions.single().description.orEmpty()
        assertTrue(description.startsWith("Responsável pela migração de monólito para microsserviços em Kotlin."))
        assertTrue(description.contains("\n• Reduzi o custo de nuvem em 30%.\n• Criei a esteira de deploy contínuo."))
        assertTrue(description.length > 3000, "experiência cortada: ${description.length}")
    }

    @Test
    fun `long summary and long experience survive the api round trip`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val summary = (1..40).joinToString("\n") { "$it. ${longSentence.trim()}" }
        assertTrue(summary.length > 2000)
        mockMvc.post("/profile/personal") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("objective" to summary))
        }.andExpect { status { isOk() } }

        val description = longSentence.repeat(80)
        mockMvc.post("/profile/experiences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                ExperienceRequest("Tech Lead", "ACME", ExperienceType.EMPLOYMENT, description, LocalDate.of(2020, 1, 1), null, true)
            )
        }.andExpect { status { isCreated() } }

        mockMvc.get("/profile") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.personal.objective") { value(summary) }
            jsonPath("$.experiences[0].description") { value(description.trim()) }
        }
    }
}
