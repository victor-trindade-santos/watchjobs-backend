package com.watchjobs.profile

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.profile.importer.ImportResponse
import com.watchjobs.profile.importer.ItemStatus
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F3-03, F3-04, F3-07, F3-08 e F3-10 contra o PostgreSQL local.
class ProfilePostgresTest : AuthSessionPostgresTestSupport() {

    private fun version(bearer: String): Int =
        objectMapper.readTree(
            mockMvc.get("/profile/preferences") { header("Authorization", bearer) }.andReturn().response.contentAsString
        )["profileVersion"].asInt()

    private fun json(value: Any) = objectMapper.writeValueAsString(value)

    private fun id(sql: String, vararg args: Any): Long = requireNotNull(jdbc.queryForObject(sql, Long::class.java, *args))

    @Test
    fun `crud of each profile type bumps the version once per change`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val start = LocalDate.now().minusYears(6)

        val created = mockMvc.post("/profile/experiences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(ExperienceRequest("Desenvolvedor Backend Sênior", "Nubank", ExperienceType.EMPLOYMENT, "Kotlin e Java", start, null, true))
        }.andExpect { status { isCreated() } }.andReturn()
        val experience = objectMapper.readValue(created.response.contentAsString, ExperienceResponse::class.java)
        assertEquals("BACKEND", experience.roleFamily)
        assertEquals(2, version(bearer))

        mockMvc.put("/profile/experiences/${experience.id}") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(ExperienceRequest("Desenvolvedor Backend Sênior", "Nubank", ExperienceType.EMPLOYMENT, "Kotlin", start, null, true))
        }.andExpect { status { isOk() } }
        assertEquals(3, version(bearer))

        val educationId = id("SELECT education_id FROM core.education WHERE education_level = 'BACHELOR' AND field_of_study = 'Ciência da Computação'")
        mockMvc.post("/profile/educations") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(EducationRequest(educationId, "USP", LocalDate.of(2012, 2, 1), LocalDate.of(2016, 12, 1), false))
        }.andExpect { status { isCreated() } }
        assertEquals(4, version(bearer))

        val kotlinId = id("SELECT skill_id FROM core.skill WHERE skill_name = 'Kotlin'")
        mockMvc.post("/profile/skills") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(UserSkillRequest(kotlinId, SkillLevel.ADVANCED, 5.toBigDecimal()))
        }.andExpect { status { isCreated() } }
        assertEquals(5, version(bearer))
        mockMvc.post("/profile/skills") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(UserSkillRequest(kotlinId, SkillLevel.ADVANCED, null))
        }.andExpect { status { isConflict() } }

        val englishId = id("SELECT language_id FROM core.\"language\" WHERE language_name = 'Inglês'")
        mockMvc.post("/profile/languages") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(UserLanguageRequest(englishId, ProficiencyLevel.B2))
        }.andExpect { status { isCreated() } }
        assertEquals(6, version(bearer))

        mockMvc.put("/profile/preferences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(PreferencesRequest(preferredRoles = listOf("Desenvolvedor Backend"), preferredWorkMode = PreferredWorkMode.REMOTE))
        }.andExpect {
            status { isOk() }
            jsonPath("$.autoSearchEnabled") { value(true) }
            jsonPath("$.confirmed") { value(true) }
        }
        assertEquals(7, version(bearer))

        mockMvc.delete("/profile/experiences/${experience.id}") { header("Authorization", bearer) }
            .andExpect { status { isNoContent() } }
        assertEquals(8, version(bearer))

        mockMvc.get("/profile") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.educations.length()") { value(1) }
            jsonPath("$.skills[0].skillName") { value("Kotlin") }
        }
    }

    @Test
    fun `invalid data is 400 and other users records are 404`() {
        val owner = "Bearer ${signupAndLogin().token}"
        val other = "Bearer ${signupAndLogin().token}"
        mockMvc.post("/profile/experiences") {
            header("Authorization", owner)
            contentType = MediaType.APPLICATION_JSON
            content = json(ExperienceRequest("Dev", "Empresa", ExperienceType.EMPLOYMENT, null, LocalDate.of(2022, 1, 1), LocalDate.of(2021, 1, 1), false))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Data de término não pode ser anterior à data de início") }
        }
        val created = objectMapper.readValue(
            mockMvc.post("/profile/experiences") {
                header("Authorization", owner)
                contentType = MediaType.APPLICATION_JSON
                content = json(ExperienceRequest("Dev", "Empresa", ExperienceType.EMPLOYMENT, null, LocalDate.of(2021, 1, 1), null, true))
            }.andReturn().response.contentAsString,
            ExperienceResponse::class.java
        )
        mockMvc.delete("/profile/experiences/${created.id}") { header("Authorization", other) }
            .andExpect { status { isNotFound() } }
        mockMvc.patch("/profile/personal") {
            header("Authorization", owner)
            contentType = MediaType.APPLICATION_JSON
            content = """{"firstName":"Ana","location":{"countryCode":"br","state":"SP","city":"São Paulo"}}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.firstName") { value("Ana") }
            jsonPath("$.location.countryCode") { value("BR") }
        }
    }

    @Test
    fun `summary follows the profile and a manual seniority is kept`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        mockMvc.post("/profile/experiences") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = json(ExperienceRequest("Desenvolvedor Backend Sênior", "Stone", ExperienceType.EMPLOYMENT, null, LocalDate.now().minusYears(6), null, true))
        }.andExpect { status { isCreated() } }
        mockMvc.get("/profile/summary") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.experienceLevel") { value("SENIOR") }
            jsonPath("$.levelConfidence") { value("HIGH") }
            jsonPath("$.primaryRoleFamily") { value("BACKEND") }
        }

        mockMvc.put("/profile/summary/level") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"experienceLevel":"MID"}"""
        }.andExpect { jsonPath("$.levelSource") { value("MANUAL") } }
        mockMvc.post("/profile/summary/recompute") { header("Authorization", bearer) }
            .andExpect { jsonPath("$.experienceLevel") { value("MID") } }

        mockMvc.get("/profile/preferences/suggestion") { header("Authorization", bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.preferredRoles[0]") { value("Desenvolvedor Backend Sênior") }
            jsonPath("$.preferredExperienceLevel") { value("MID") }
        }
    }

    @Test
    fun `two users with the same area give demand score 2`() {
        val country = "Z" + ('A'..'Z').random()
        val body = """{"preferredRoles":["Desenvolvedor Backend"],"preferredWorkMode":"HYBRID","location":{"countryCode":"$country"}}"""
        repeat(2) {
            val bearer = "Bearer ${signupAndLogin().token}"
            mockMvc.put("/profile/preferences") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }
        }
        val score = jdbc.queryForObject(
            "SELECT demand_score FROM discovery.coverage_segment WHERE role_family = 'BACKEND' AND country_code = ? AND work_mode = 'HYBRID'",
            Int::class.java, country
        )
        assertEquals(2, score)
        jdbc.update("DELETE FROM discovery.coverage_segment WHERE country_code = ?", country)
    }

    @Test
    fun `linkedin import previews, applies and does not duplicate on reimport`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Positions.csv"))
                z.write("Company Name,Title,Description,Location,Started On,Finished On\nVTEX,Desenvolvedor Backend,Kotlin,Rio,Jan 2019,\n".toByteArray())
                z.putNextEntry(ZipEntry("Skills.csv"))
                z.write("Name\nKotlin\nHabilidade inexistente xyz\n".toByteArray())
                z.putNextEntry(ZipEntry("Languages.csv"))
                z.write("Name,Proficiency\nEnglish,Professional working proficiency\n".toByteArray())
                z.putNextEntry(ZipEntry("Education.csv"))
                z.write("School Name,Start Date,End Date,Notes,Degree Name,Activities\nUFRJ,2014,2018,,Bacharelado em Ciência da Computação,\n".toByteArray())
            }
        }.toByteArray()

        fun upload(): ImportResponse = objectMapper.readValue(
            mockMvc.multipart("/profile/import/linkedin") {
                file(MockMultipartFile("file", "linkedin.zip", "application/zip", zip))
                header("Authorization", bearer)
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
            ImportResponse::class.java
        )

        val first = upload()
        assertEquals(ItemStatus.NEW, first.preview.experiences.single().status)
        assertEquals(ItemStatus.UNRECOGNIZED, first.preview.skills.single { it.name.startsWith("Habilidade") }.status)
        assertEquals("Ciência da Computação", first.preview.educations.single().fieldOfStudy)

        val applied = objectMapper.readValue(
            mockMvc.post("/profile/import/${first.id}/apply") { header("Authorization", bearer) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
            ImportResponse::class.java
        )
        assertEquals("APPLIED", applied.status)
        assertEquals(4, applied.itemsApplied)
        mockMvc.post("/profile/import/${first.id}/apply") { header("Authorization", bearer) }
            .andExpect { status { isConflict() } }

        val second = upload()
        assertTrue(second.preview.experiences.all { it.status == ItemStatus.EXISTS })
        assertTrue(second.preview.educations.all { it.status == ItemStatus.EXISTS })
        assertTrue(second.preview.languages.all { it.status == ItemStatus.EXISTS })
        assertEquals(ItemStatus.EXISTS, second.preview.skills.single { it.name == "Kotlin" }.status)

        mockMvc.delete("/profile/import/${second.id}") { header("Authorization", bearer) }.andExpect { status { isNoContent() } }
        mockMvc.get("/profile/experiences") { header("Authorization", bearer) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].source") { value("LINKEDIN_IMPORT") }
        }

        mockMvc.multipart("/profile/import/linkedin") {
            file(MockMultipartFile("file", "x.zip", "application/zip", "nada".toByteArray()))
            header("Authorization", bearer)
        }.andExpect { status { isBadRequest() } }
    }
}
