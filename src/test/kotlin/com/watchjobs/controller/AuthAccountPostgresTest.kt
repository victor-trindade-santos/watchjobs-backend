package com.watchjobs.controller

import com.watchjobs.dto.request.DeleteAccountRequest
import com.watchjobs.dto.request.ForgotPasswordRequest
import com.watchjobs.dto.request.ResetPasswordRequest
import com.watchjobs.dto.request.SigninRequest
import com.watchjobs.mail.LogMailer
import com.watchjobs.mail.Mailer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestPropertySource(properties = ["app.mail.mode=log"])
class AuthAccountPostgresTest : AuthSessionPostgresTestSupport() {
    @Autowired
    private lateinit var mailer: Mailer

    @Test
    fun `unknown email still returns 202 and does not send mail`() {
        val before = (mailer as LogMailer).last
        mockMvc.post("/password/forgot") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(ForgotPasswordRequest("nao-existe-${UUID.randomUUID()}@example.com"))
        }.andExpect { status { isAccepted() } }
        assertEquals(before, (mailer as LogMailer).last)
    }

    @Test
    fun `reset changes the password and rejects the previous access and refresh`() {
        val login = signupAndLogin()
        val email = login.user.email
        forgot(email).andExpect { status { isAccepted() } }
        val code = lastCode()

        mockMvc.post("/password/reset") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                ResetPasswordRequest(email, code, "nova1234", "nova1234")
            )
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/me") {
            header("Authorization", "Bearer ${login.token}")
        }.andExpect { status { isUnauthorized() } }
        refresh(login.refreshToken).andExpect { status { isUnauthorized() } }
        mockMvc.post("/signin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SigninRequest(email, "nova1234"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `the sixth attempt is rejected even when the code is correct`() {
        val login = signupAndLogin()
        val email = login.user.email
        forgot(email)
        val code = lastCode()
        repeat(5) {
            mockMvc.post("/password/reset") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(
                    ResetPasswordRequest(email, "000000", "nova1234", "nova1234")
                )
            }.andExpect { status { isBadRequest() } }
        }
        mockMvc.post("/password/reset") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                ResetPasswordRequest(email, code, "nova1234", "nova1234")
            )
        }.andExpect { status { isBadRequest() } }
        mockMvc.post("/signin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SigninRequest(email, "teste1234"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `wrong password does not delete the account`() {
        val login = signupAndLogin()
        mockMvc.delete("/me") {
            header("Authorization", "Bearer ${login.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(DeleteAccountRequest("errada123"))
        }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/me") {
            header("Authorization", "Bearer ${login.token}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `delete account removes every row owned by the user`() {
        val login = signupAndLogin()
        val usersId = login.user.id
        seedOwnedRows(usersId)
        val owned = ownedTables()
        assertTrue(owned.isNotEmpty())
        owned.forEach { table ->
            assertTrue(count(table, usersId) > 0, "esperava dados em $table")
        }

        mockMvc.delete("/me") {
            header("Authorization", "Bearer ${login.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(DeleteAccountRequest("teste1234"))
        }.andExpect { status { isNoContent() } }

        assertEquals(0, jdbc.queryForObject("select count(*) from core.users where users_id = ?", Int::class.java, usersId))
        owned.forEach { table ->
            assertEquals(0, count(table, usersId), "ainda há linhas em $table")
        }
        mockMvc.get("/me") {
            header("Authorization", "Bearer ${login.token}")
        }.andExpect { status { isUnauthorized() } }
    }

    private fun forgot(email: String) = mockMvc.post("/password/forgot") {
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(ForgotPasswordRequest(email))
    }

    private fun lastCode(): String {
        val text = requireNotNull((mailer as LogMailer).last).text
        return requireNotNull(Regex("\\d{6}").find(text)).value
    }

    private fun ownedTables(): List<String> = jdbc.queryForList(
        """
        select table_schema || '.' || table_name
        from information_schema.columns
        where table_schema = 'core' and column_name = 'users_id' and table_name <> 'users'
        order by table_name
        """.trimIndent(),
        String::class.java
    ).filterNotNull()

    private fun count(table: String, usersId: Int): Int =
        jdbc.queryForObject("select count(*) from $table where users_id = ?", Int::class.java, usersId) ?: 0

    private fun seedOwnedRows(usersId: Int) {
        val suffix = UUID.randomUUID().toString().take(8)
        val skillId = insertId("insert into core.skill (skill_name, skill_category) values (?, 'Linguagem')", "skill-$suffix")
        val languageId = insertId("insert into core.\"language\" (language_name) values (?)", "Idioma $suffix")
        val educationId = insertId(
            "insert into core.education (education_level, field_of_study) values ('Graduação', ?)",
            "Curso $suffix"
        )
        val certificationId = insertId(
            "insert into core.certification (certification_name, issuer) values (?, 'Emissor')",
            "Cert $suffix"
        )
        val organizationId = insertId(
            "insert into core.organization (\"name\", organization_type) values (?, 'COMPANY')",
            "Empresa $suffix"
        )
        val contactId = insertId(
            "insert into core.contact (contact_type, contact_content) values ('EMAIL', ?)",
            "contato-$suffix@example.com"
        )
        val savedJobId = jdbc.queryForObject(
            """
            insert into core.saved_job (users_id, position_role, organization_name)
            values (?, 'Dev', 'ACME')
            returning saved_job_id
            """.trimIndent(),
            Long::class.java,
            usersId
        )
        assertNotNull(savedJobId)
        val preferenceId = jdbc.queryForObject(
            """
            insert into core.userpreference (preferred_work_mode, preferred_experience_level, preferred_roles, users_id)
            values ('REMOTE', 'JUNIOR', ARRAY['Dev'], ?)
            returning users_preference_id
            """.trimIndent(),
            Int::class.java,
            usersId
        )
        val stageId = jdbc.queryForObject("select min(application_stage_id) from core.applicationstage", Int::class.java)
        jdbc.update("insert into core.userscontact (contact_id, users_id) values (?, ?)", contactId, usersId)
        jdbc.update(
            "insert into core.usersjobinteraction (interaction_type, users_id, discovered_job_id) values ('VIEWED', ?, 1)",
            usersId
        )
        jdbc.update(
            "insert into core.userskill (skill_level, users_id, skill_id) values ('BEGINNER', ?, ?)",
            usersId, skillId
        )
        jdbc.update(
            """
            insert into core.experience (job_title, "current", experience_type, start_date, end_date, organization_id, users_id)
            values ('Dev', false, 'EMPLOYMENT', current_date, current_date, ?, ?)
            """.trimIndent(),
            organizationId, usersId
        )
        jdbc.update(
            "insert into core.saved_job_contact (saved_job_id, contact_name) values (?, 'Recrutadora')"
            , savedJobId
        )
        jdbc.update(
            """
            insert into core.usercertification (issue_date, expiration_date, users_id, certification_id)
            values (current_date, current_date, ?, ?)
            """.trimIndent(),
            usersId, certificationId
        )
        jdbc.update(
            """
            insert into core.usereducation (institution, start_date, end_date, "current", users_id, education_id)
            values ('Universidade', current_date, current_date, false, ?, ?)
            """.trimIndent(),
            usersId, educationId
        )
        jdbc.update(
            "insert into core.userlanguage (proficiency_level, language_id, users_id) values ('B2', ?, ?)",
            languageId, usersId
        )
        jdbc.update(
            """
            insert into core.application (application_status, start_date, application_stage_id, users_id, saved_job_id)
            values ('APPLIED', current_date, ?, ?, ?)
            """.trimIndent(),
            stageId, usersId, savedJobId
        )
        jdbc.update(
            "insert into core.coverletter (created_at, cover_letter_content, users_id, saved_job_id) values (current_date, 'Olá', ?, ?)",
            usersId, savedJobId
        )
        jdbc.update(
            "insert into core.resume (created_at, resume_content, users_id, saved_job_id) values (current_date, 'Currículo', ?, ?)",
            usersId, savedJobId
        )
        jdbc.update(
            "insert into core.skilluserpreference (users_preference_id, skill_id) values (?, ?)",
            preferenceId, skillId
        )
        jdbc.update(
            "insert into core.languageuserpreference (language_id, users_preference_id) values (?, ?)",
            languageId, preferenceId
        )
        jdbc.update(
            "insert into core.educationuserpreference (users_preference_id, education_id) values (?, ?)",
            preferenceId, educationId
        )
        jdbc.update(
            "insert into core.certificationuserpreference (users_preference_id, certification_id) values (?, ?)",
            preferenceId, certificationId
        )
        forgot(jdbc.queryForObject("select email from core.users where users_id = ?", String::class.java, usersId)!!)
    }

    private fun insertId(sql: String, value: String): Number =
        requireNotNull(jdbc.queryForObject("$sql returning ${idColumn(sql)}", Number::class.java, value))

    private fun idColumn(sql: String): String = when {
        "core.skill" in sql -> "skill_id"
        "language" in sql -> "language_id"
        "core.education" in sql -> "education_id"
        "core.certification" in sql -> "certification_id"
        "core.organization" in sql -> "organization_id"
        "core.contact" in sql -> "contact_id"
        else -> error(sql)
    }
}
