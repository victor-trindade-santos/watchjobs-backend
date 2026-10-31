package com.watchjobs.documents

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.dto.request.SavedJobRequest
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.exception.ServiceUnavailableException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F10: geração (com IA falsa), versões, edição, restauração, exportação e isolamento por usuário.
@Import(DocumentsPostgresTest.FakeAi::class)
class DocumentsPostgresTest : AuthSessionPostgresTestSupport() {

    @TestConfiguration
    class FakeAi {
        @Bean
        @Primary
        fun fakeGenerator(): FakeGenerator = FakeGenerator()
    }

    class FakeGenerator : TextGenerator {
        val prompts = CopyOnWriteArrayList<String>()
        var fail = false
        override val enabled = true
        override fun generate(system: String, user: String, maxTokens: Int): GeneratedText {
            if (fail) throw ServiceUnavailableException("IA fora do ar")
            prompts += user
            return GeneratedText("```\n# Ana Test\n## Resumo profissional\nBackend com Kotlin e Terraform. Aumentei vendas em 300%.\n```", "fake-model", 100, 50)
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var fake: FakeGenerator

    private fun json(value: Any) = objectMapper.writeValueAsString(value)

    private fun savedJob(bearer: String): Long = objectMapper.readValue(
        mockMvc.post("/saved-jobs") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(SavedJobRequest(positionRole = "Backend Kotlin", organizationName = "Globex", status = SavedJobStatus.SAVED))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
        SavedJobResponse::class.java
    ).id

    private fun preparedUser(): Pair<String, Long> {
        val bearer = "Bearer ${signupAndLogin().token}"
        mockMvc.post("/profile/personal") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("objective" to "Engenheira backend com Kotlin."))
        }.andExpect { status { isOk() } }
        val job = savedJob(bearer)
        jdbc.update("UPDATE core.saved_job SET full_description = 'Construir APIs em Kotlin na AWS.' WHERE saved_job_id = ?", job)
        return bearer to job
    }

    @Test
    fun `generate, edit, restore and export a tailored resume`() {
        fake.fail = false
        val (bearer, job) = preparedUser()

        val generated = objectMapper.readTree(
            mockMvc.post("/documents/generate") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = json(mapOf("savedJobId" to job, "type" to "RESUME", "instructions" to "Foque em APIs"))
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        )
        val id = generated["id"].asLong()
        assertEquals(1, generated["currentVersion"].asInt())
        assertTrue(generated["content"].asString().startsWith("# Ana Test"))
        val warnings = generated["warnings"].values().map { it.asString() }.toList()
        assertTrue(warnings.any { "300%" in it }, "deveria apontar o número inventado: $warnings")
        val prompt = fake.prompts.last()
        assertTrue("Construir APIs em Kotlin na AWS." in prompt && "Engenheira backend com Kotlin." in prompt && "Foque em APIs" in prompt)

        mockMvc.put("/documents/$id") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("content" to "# Ana Test\n## Resumo profissional\nBackend com Kotlin."))
        }.andExpect {
            status { isOk() }
            jsonPath("$.currentVersion") { value(2) }
            jsonPath("$.versions.length()") { value(2) }
            jsonPath("$.versions[0].source") { value("EDIT") }
        }
        mockMvc.post("/documents/$id/versions/1/restore") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.currentVersion") { value(3) }
            jsonPath("$.versions[0].source") { value("RESTORE") }
        }
        mockMvc.get("/documents/$id/versions/2") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.content") { value("# Ana Test\n## Resumo profissional\nBackend com Kotlin.") }
        }

        val pdf = objectMapper.readTree(
            mockMvc.get("/documents/$id/export") { header("Authorization", bearer); param("format", "PDF"); param("version", "2") }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString
        )
        assertEquals("application/pdf", pdf["mimeType"].asString())
        assertTrue(pdf["fileName"].asString().startsWith("curriculo-globex-backend-kotlin"))
        val text = Loader.loadPDF(Base64.getDecoder().decode(pdf["base64"].asString())).use { PDFTextStripper().getText(it) }
        assertTrue("Backend com Kotlin." in text)

        mockMvc.get("/documents/$id/export") { header("Authorization", bearer); param("format", "DOCX") }.andExpect {
            status { isOk() }
            jsonPath("$.fileName") { value(org.hamcrest.Matchers.endsWith(".docx")) }
        }
        mockMvc.get("/documents/base-resume") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.content") { value(org.hamcrest.Matchers.containsString("Engenheira backend com Kotlin.")) }
        }
        mockMvc.get("/documents") { header("Authorization", bearer); param("savedJobId", job.toString()) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].type") { value("RESUME") }
        }

        // Outro usuário não acessa nem gera para a vaga de outra pessoa.
        val other = "Bearer ${signupAndLogin().token}"
        mockMvc.get("/documents/$id") { header("Authorization", other) }.andExpect { status { isNotFound() } }
        mockMvc.post("/documents/generate") {
            header("Authorization", other)
            contentType = MediaType.APPLICATION_JSON
            content = json(mapOf("savedJobId" to job, "type" to "COVER_LETTER"))
        }.andExpect { status { isNotFound() } }

        mockMvc.delete("/documents/$id") { header("Authorization", bearer) }.andExpect { status { isNoContent() } }
        mockMvc.get("/documents/$id") { header("Authorization", bearer) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `llm failure returns 503 and saves nothing`() {
        val (bearer, job) = preparedUser()
        fake.fail = true
        try {
            mockMvc.post("/documents/generate") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = json(mapOf("savedJobId" to job, "type" to "COVER_LETTER"))
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
            }
        } finally {
            fake.fail = false
        }
        mockMvc.get("/documents") { header("Authorization", bearer) }.andExpect { jsonPath("$.length()") { value(0) } }
    }
}
