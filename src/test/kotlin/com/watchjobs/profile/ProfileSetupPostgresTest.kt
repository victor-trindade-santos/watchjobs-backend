package com.watchjobs.profile

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate

// F05: assistente de perfil com requisitos mínimos validados no backend e rotas bloqueadas até a conclusão.
@TestPropertySource(properties = ["app.profile-setup.enforce=true"])
class ProfileSetupPostgresTest : AuthSessionPostgresTestSupport() {
    private fun json(value: Any) = objectMapper.writeValueAsString(value)

    @Test
    fun `setup blocks the app until the minimum profile is saved and completed`() {
        val bearer = "Bearer ${signupAndLogin().token}"

        mockMvc.get("/saved-jobs") { header("Authorization", bearer) }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("PROFILE_SETUP_REQUIRED") }
        }
        mockMvc.get("/profile/setup") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.completed") { value(false) }
            jsonPath("$.canComplete") { value(false) }
            jsonPath("$.missing.length()") { value(4) }
        }
        mockMvc.post("/profile/setup/complete") { header("Authorization", bearer) }.andExpect { status { isBadRequest() } }

        mockMvc.put("/profile/setup/step") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"step":"EXPERIENCE"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.step") { value("EXPERIENCE") }
        }

        mockMvc.post("/profile/personal") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"objective":"Desenvolvedor backend com foco em Kotlin e APIs."}"""
        }.andExpect { status { isOk() } }
        mockMvc.post("/profile/experiences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(ExperienceRequest("Desenvolvedor Backend", "ACME", ExperienceType.EMPLOYMENT, null, LocalDate.of(2020, 1, 1), null, true))
        }.andExpect { status { isCreated() } }
        listOf("Kotlin", "Java", "SQL").forEach { name ->
            val skillId = requireNotNull(jdbc.queryForObject("SELECT skill_id FROM core.skill WHERE skill_name = ?", Long::class.java, name))
            mockMvc.post("/profile/skills") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = json(UserSkillRequest(skillId, SkillLevel.INTERMEDIATE, null))
            }.andExpect { status { isCreated() } }
        }
        mockMvc.put("/profile/preferences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(PreferencesRequest(preferredRoles = listOf("Desenvolvedor Backend"), preferredWorkMode = PreferredWorkMode.REMOTE))
        }.andExpect { status { isOk() } }

        mockMvc.post("/profile/setup/complete") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.completed") { value(true) }
        }
        mockMvc.get("/saved-jobs") { header("Authorization", bearer) }.andExpect { status { isOk() } }
    }

    // F06: o tour começa pendente e fica concluído depois de concluir ou pular (a rota é liberada antes do assistente).
    @Test
    fun `onboarding tour is persisted once`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        mockMvc.get("/profile/onboarding") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.completed") { value(false) }
        }
        repeat(2) {
            mockMvc.post("/profile/onboarding/complete") { header("Authorization", bearer) }.andExpect {
                status { isOk() }
                jsonPath("$.completed") { value(true) }
            }
        }
    }
}
