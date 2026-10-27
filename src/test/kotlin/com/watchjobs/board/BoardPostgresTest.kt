package com.watchjobs.board

import com.watchjobs.controller.AuthSessionPostgresTestSupport
import com.watchjobs.dto.request.SavedJobRequest
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.enums.SavedJobStatus
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F6-03 e F6-04: quadro, mover com ordem persistida, candidatura criada ao andar, eventos e timeline.
class BoardPostgresTest : AuthSessionPostgresTestSupport() {

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
    }

    private fun column(bearer: String, name: String): List<Long> {
        val board = objectMapper.readTree(mockMvc.get("/saved-jobs/board") { header("Authorization", bearer) }.andReturn().response.contentAsString)
        return board["columns"].values().first { it["column"].asString() == name }["cards"].values().map { it["id"].asLong() }.toList()
    }

    @Test
    fun `moving persists order, creates the application and records events`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val a = create(bearer, "Vaga A")
        val b = create(bearer, "Vaga B")
        val c = create(bearer, "Vaga C")
        assertEquals(listOf(a, b, c), column(bearer, "SAVED"))

        move(bearer, c, """{"toColumn":"SAVED","beforeId":$a,"afterId":$b}""").andExpect { status { isOk() } }
        assertEquals(listOf(a, c, b), column(bearer, "SAVED"))
        move(bearer, b, """{"toColumn":"SAVED","afterId":$a}""").andExpect { status { isOk() } }
        assertEquals(listOf(b, a, c), column(bearer, "SAVED"))

        move(bearer, a, """{"toColumn":"APPLIED"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("APPLIED") }
            jsonPath("$.stageName") { value("Triagem") }
        }
        assertEquals(listOf(a), column(bearer, "APPLIED"))

        move(bearer, a, """{"toColumn":"TECH_TEST"}""").andExpect {
            jsonPath("$.status") { value("IN_PROCESS") }
            jsonPath("$.stageName") { value("Teste técnico") }
        }
        assertEquals(listOf(a), column(bearer, "TECH_TEST"))
        move(bearer, a, """{"toColumn":"INTERVIEW"}""").andExpect { jsonPath("$.stageName") { value("Entrevista RH") } }
        move(bearer, a, """{"toColumn":"OFFER"}""").andExpect { jsonPath("$.status") { value("OFFER_RECEIVED") } }
        assertEquals(listOf(a), column(bearer, "OFFER"))

        move(bearer, a, """{"toColumn":"CLOSED"}""").andExpect { status { isBadRequest() } }
        move(bearer, a, """{"toColumn":"CLOSED","outcome":"REJECTED"}""").andExpect { jsonPath("$.status") { value("REJECTED") } }
        move(bearer, a, """{"toColumn":"SAVED"}""").andExpect { status { isConflict() } }

        val timeline = objectMapper.readTree(
            mockMvc.get("/saved-jobs/$a/timeline") { header("Authorization", bearer) }.andReturn().response.contentAsString
        )
        val types = timeline.values().map { it["type"].asString() }.toList()
        assertTrue(types.containsAll(listOf("SAVED", "MOVED", "APPLICATION_CREATED")))
        assertEquals("SAVED", types.last())
    }

    @Test
    fun `prova services also write events`() {
        val bearer = "Bearer ${signupAndLogin().token}"
        val id = create(bearer, "Vaga D")
        val stage = requireNotNull(jdbc.queryForObject("SELECT application_stage_id FROM core.applicationstage ORDER BY sort_order LIMIT 1", Int::class.java))
        val application = objectMapper.readTree(
            mockMvc.post("/applications") {
                header("Authorization", bearer)
                contentType = MediaType.APPLICATION_JSON
                content = """{"savedJobId":$id,"stageId":$stage,"status":"APPLIED","applicationDate":"2026-10-01"}"""
            }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        )
        mockMvc.delete("/applications/${application["id"].asInt()}") { header("Authorization", bearer) }
            .andExpect { status { isNoContent() } }
        val types = jdbc.queryForList("SELECT event_type FROM core.application_event WHERE saved_job_id = ? ORDER BY application_event_id", String::class.java, id)
        assertEquals(listOf("SAVED", "APPLICATION_CREATED", "APPLICATION_DELETED"), types)
        assertEquals(listOf(id), column(bearer, "SAVED"))
    }
}
