package com.watchjobs.controller

import com.watchjobs.notification.ReminderService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Avisos (F04): lembretes sem duplicar, aparelhos de push e preferência de push.
class NotificationRemindersPostgresTest : AuthSessionPostgresTestSupport() {
    @Autowired
    private lateinit var reminders: ReminderService

    private fun createSavedJob(token: String, role: String): Long {
        val result = mockMvc.post("/saved-jobs") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"positionRole":"$role","organizationName":"ACME","status":"SAVED"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asLong()
    }

    private fun count(userId: Int, type: String): Int = requireNotNull(
        jdbc.queryForObject("select count(*) from core.notification where users_id = ? and notification_type = ?", Int::class.java, userId, type)
    )

    @Test
    fun `interview reminder is created once`() {
        val login = signupAndLogin()
        val savedJobId = createSavedJob(login.token, "Dev Kotlin")
        jdbc.update("update core.saved_job set status = 'APPLIED' where saved_job_id = ?", savedJobId)
        jdbc.update(
            """
            insert into core.application (application_status, start_date, application_stage_id, users_id, saved_job_id, interview_at)
            values ('APPLIED', current_date, (select min(application_stage_id) from core.applicationstage), ?, ?,
                    (now() at time zone 'America/Sao_Paulo') + interval '2 hours')
            """.trimIndent(),
            login.user.id, savedJobId
        )

        reminders.interviews()
        reminders.interviews()

        assertEquals(1, count(login.user.id, "INTERVIEW_REMINDER"))
    }

    @Test
    fun `stale application reminder is created once per period`() {
        val login = signupAndLogin()
        val savedJobId = createSavedJob(login.token, "Dev Java")
        jdbc.update("update core.saved_job set status = 'APPLIED' where saved_job_id = ?", savedJobId)
        jdbc.update("update core.saved_job set status_updated_at = now() - interval '20 days' where saved_job_id = ?", savedJobId)

        reminders.staleApplications()
        reminders.staleApplications()

        assertEquals(1, count(login.user.id, "STALE_APPLICATION"))
        mockMvc.get("/notifications?size=5") {
            header("Authorization", "Bearer ${login.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items[0].type") { value("STALE_APPLICATION") }
            jsonPath("$.items[0].payload.savedJobId") { value(savedJobId.toInt()) }
        }
    }

    @Test
    fun `push device moves to the last user that registered it`() {
        val first = signupAndLogin()
        val second = signupAndLogin()
        val token = "fcm-${UUID.randomUUID()}"
        listOf(first, second).forEach { login ->
            mockMvc.post("/notifications/devices") {
                header("Authorization", "Bearer ${login.token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"token":"$token","platform":"ANDROID"}"""
            }.andExpect { status { isNoContent() } }
        }
        assertEquals(second.user.id, jdbc.queryForObject("select users_id from core.push_device where token = ?", Int::class.java, token))

        mockMvc.post("/notifications/devices/remove") {
            header("Authorization", "Bearer ${second.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"token":"$token"}"""
        }.andExpect { status { isNoContent() } }
        assertEquals(0, jdbc.queryForObject("select count(*) from core.push_device where token = ?", Int::class.java, token))
    }

    @Test
    fun `push preference can be turned off`() {
        val login = signupAndLogin()
        mockMvc.put("/notifications/preferences") {
            header("Authorization", "Bearer ${login.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"pushEnabled":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.pushEnabled") { value(false) }
        }
        assertTrue(jdbc.queryForObject("select not push_enabled from core.notification_preference where users_id = ?", Boolean::class.java, login.user.id) == true)
    }
}
