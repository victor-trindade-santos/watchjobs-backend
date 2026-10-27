package com.watchjobs.profile

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.profile.importer.ImportResponse
import com.watchjobs.profile.importer.ItemStatus
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Currículo enviado: prévia, aplicar sem sobrescrever o digitado e a sugestão de preferências.
class ResumeImportPostgresTest : AuthSessionPostgresTestSupport() {
    private val resume = """
        Resumo
        Engenheira backend com foco em APIs.
        Experiência
        Empresa Teste Alfa
        Engenheira de Software
        janeiro de 2021 - presente
        São Paulo, Brasil
        Desenvolvimento de APIs em Kotlin.
        Formação acadêmica
        Universidade de São Paulo
        Bacharelado, Ciência da Computação · (2013 - 2017)
        Competências
        Kotlin
        Idiomas
        Inglês (Full Professional)
    """.trimIndent()

    private fun upload(bearer: String, name: String, content: ByteArray) = mockMvc.multipart("/profile/import/resume") {
        file(MockMultipartFile("file", name, "text/plain", content))
        header("Authorization", bearer)
    }

    @Test
    fun `resume preview, apply and suggestion`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val preview = objectMapper.readValue(
            upload(bearer, "curriculo.txt", resume.toByteArray()).andExpect {
                status { isCreated() }
                jsonPath("$.importSource") { value("RESUME") }
            }.andReturn().response.contentAsString,
            ImportResponse::class.java
        )
        assertEquals("Engenheira de Software", preview.preview.experiences.single().jobTitle)
        assertEquals(ItemStatus.NEW, preview.preview.experiences.single().status)
        assertEquals(ItemStatus.NEW, preview.preview.skills.single { it.name == "Kotlin" }.status)
        assertEquals(ItemStatus.NEW, preview.preview.languages.single().status)

        val applied = objectMapper.readValue(
            mockMvc.post("/profile/import/${preview.id}/apply") { header("Authorization", bearer) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
            ImportResponse::class.java
        )
        assertEquals("APPLIED", applied.status)
        assertTrue(applied.itemsApplied >= 4)

        mockMvc.get("/profile/experiences") { header("Authorization", bearer) }.andExpect {
            jsonPath("$[0].source") { value("RESUME_IMPORT") }
        }
        mockMvc.get("/profile/personal") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.objective") { value("Engenheira backend com foco em APIs.") }
        }
        mockMvc.get("/profile/preferences/suggestion") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.preferredRoles[0]") { value("Engenheira de Software") }
        }

        val again = objectMapper.readValue(
            upload(bearer, "curriculo.txt", resume.toByteArray()).andReturn().response.contentAsString,
            ImportResponse::class.java
        )
        assertTrue(again.preview.experiences.all { it.status == ItemStatus.EXISTS })
    }

    @Test
    fun `unreadable resume is a clear bad request`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        upload(bearer, "foto.txt", ByteArray(64) { 0 }).andExpect { status { isBadRequest() } }
        upload(bearer, "vazio.txt", "Lorem ipsum dolor sit amet".toByteArray()).andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { exists() }
        }
    }
}
