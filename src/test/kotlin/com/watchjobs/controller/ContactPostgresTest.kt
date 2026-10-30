package com.watchjobs.controller

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals

class ContactPostgresTest : AuthSessionPostgresTestSupport() {

    private fun createSavedJob(token: String): Long {
        val body = mockMvc.post("/saved-jobs") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"positionRole":"Dev Kotlin","organizationName":"ACME","status":"SAVED"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return objectMapper.readTree(body)["id"].asLong()
    }

    @Test
    fun `contact crud on an owned saved job`() {
        val login = signupAndLogin()
        val jobId = createSavedJob(login.token)
        val auth = "Bearer ${login.token}"

        val created = mockMvc.post("/saved-jobs/$jobId/contacts") {
            header("Authorization", auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactName":"  Maria  ","contactRole":"Recrutadora","email":"maria@acme.com","notes":""}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.contactName") { value("Maria") }
        }.andReturn().response.contentAsString
        val id = objectMapper.readTree(created)["id"].asLong()
        assertEquals(true, objectMapper.readTree(created)["notes"].isNull)

        mockMvc.get("/saved-jobs/$jobId/contacts") { header("Authorization", auth) }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(1) } }

        mockMvc.put("/saved-jobs/$jobId/contacts/$id") {
            header("Authorization", auth)
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactName":"Maria Silva","phone":"+55 11 99999-0000"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.contactName") { value("Maria Silva") }
            jsonPath("$.email") { isEmpty() }
        }

        mockMvc.delete("/saved-jobs/$jobId/contacts/$id") { header("Authorization", auth) }
            .andExpect { status { isNoContent() } }
        mockMvc.delete("/saved-jobs/$jobId/contacts/$id") { header("Authorization", auth) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `contact needs at least one identifying field`() {
        val login = signupAndLogin()
        val jobId = createSavedJob(login.token)
        mockMvc.post("/saved-jobs/$jobId/contacts") {
            header("Authorization", "Bearer ${login.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactRole":"Gestor","notes":"sem nome nem contato"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `another user cannot read or change the contacts`() {
        val owner = signupAndLogin()
        val other = signupAndLogin()
        val jobId = createSavedJob(owner.token)
        mockMvc.post("/saved-jobs/$jobId/contacts") {
            header("Authorization", "Bearer ${owner.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactName":"Ana"}"""
        }.andExpect { status { isCreated() } }

        mockMvc.get("/saved-jobs/$jobId/contacts") { header("Authorization", "Bearer ${other.token}") }
            .andExpect { status { isNotFound() } }
        mockMvc.post("/saved-jobs/$jobId/contacts") {
            header("Authorization", "Bearer ${other.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"contactName":"Intruso"}"""
        }.andExpect { status { isNotFound() } }
    }
}
