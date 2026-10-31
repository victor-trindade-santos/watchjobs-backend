package com.watchjobs.analytics

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.dto.request.SavedJobRequest
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.enums.SavedJobStatus
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F09: indicadores vêm dos eventos reais do Kanban e de cada usuário isoladamente.
class AnalyticsPostgresTest : AuthSessionPostgresTestSupport() {

    private fun create(bearer: String, role: String): Long = objectMapper.readValue(
        mockMvc.post("/saved-jobs") {
            header("Authorization", bearer)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SavedJobRequest(positionRole = role, organizationName = "ACME", status = SavedJobStatus.SAVED))
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString,
        SavedJobResponse::class.java
    ).id

    private fun move(bearer: String, id: Long, body: String) = mockMvc.patch("/saved-jobs/$id/move") {
        header("Authorization", bearer)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }.andExpect { status { isOk() } }

    @Test
    fun `analytics reflects recorded events and is scoped to the user`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val a = create(bearer, "Backend A")
        val b = create(bearer, "Backend B")
        create(bearer, "Backend C")
        jdbc.update("UPDATE core.saved_job SET role_family = 'BACKEND' WHERE saved_job_id IN (?, ?)", a, b)

        move(bearer, a, """{"toColumn":"APPLIED"}""")
        move(bearer, a, """{"toColumn":"INTERVIEW"}""")
        move(bearer, a, """{"toColumn":"OFFER"}""")
        move(bearer, b, """{"toColumn":"APPLIED"}""")
        move(bearer, b, """{"toColumn":"CLOSED","outcome":"REJECTED"}""")

        mockMvc.get("/analytics") { header("Authorization", bearer); param("weeks", "8") }.andExpect {
            status { isOk() }
            jsonPath("$.weeks") { value(8) }
            jsonPath("$.totals.saved") { value(3) }
            jsonPath("$.totals.applied") { value(2) }
            jsonPath("$.totals.rejected") { value(1) }
            jsonPath("$.funnel[0].stage") { value("SAVED") }
            jsonPath("$.funnel[0].count") { value(3) }
            jsonPath("$.funnel[1].count") { value(2) }
            jsonPath("$.funnel[3].stage") { value("INTERVIEW") }
            jsonPath("$.funnel[3].count") { value(1) }
            jsonPath("$.funnel[4].count") { value(1) }
            jsonPath("$.weekly.length()") { value(8) }
            jsonPath("$.monthly.length()") { value(12) }
            jsonPath("$.weekly[7].saved") { value(3) }
            jsonPath("$.weekly[7].applied") { value(2) }
            jsonPath("$.weekly[0].events") { value(0) }
            jsonPath("$.byStage.length()") { value(7) }
            jsonPath("$.byRoleFamily[0].roleFamily") { value("BACKEND") }
            jsonPath("$.byRoleFamily[0].saved") { value(2) }
        }

        val history = objectMapper.readTree(
            mockMvc.get("/analytics") { header("Authorization", bearer) }.andReturn().response.contentAsString
        )["history"].values().toList()
        assertTrue(history.isNotEmpty())
        assertTrue(history.all { it["organizationName"].asString() == "ACME" })
        assertTrue(history.none { it["description"].asString() == "Card reordenado" })

        // Outro usuário não enxerga nada do primeiro.
        val other = "Bearer ${signupAndLogin().token}"
        mockMvc.get("/analytics") { header("Authorization", other) }.andExpect {
            status { isOk() }
            jsonPath("$.totals.saved") { value(0) }
            jsonPath("$.history.length()") { value(0) }
            jsonPath("$.funnel[1].conversionFromPrevious") { doesNotExist() }
        }
        mockMvc.get("/analytics") { header("Authorization", other); param("weeks", "100") }.andExpect { status { isBadRequest() } }
        assertEquals(12, objectMapper.readTree(
            mockMvc.get("/analytics") { header("Authorization", other) }.andReturn().response.contentAsString
        )["weekly"].size())
    }
}
