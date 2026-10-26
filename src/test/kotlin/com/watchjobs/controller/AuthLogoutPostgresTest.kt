package com.watchjobs.controller

import com.watchjobs.dto.request.LogoutRequest
import com.watchjobs.dto.request.SigninRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuthLogoutPostgresTest : AuthSessionPostgresTestSupport() {
    @Test
    fun `logout revokes the refresh token and rejects its next use`() {
        val login = signupAndLogin()
        mockMvc.post("/auth/logout") {
            header("Authorization", "Bearer ${login.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(LogoutRequest(login.refreshToken))
        }.andExpect { status { isNoContent() } }
        val token = jdbc.queryForMap(
            "select revoked_at from core.refresh_token where refresh_token_id = ?", tokenId(login.refreshToken)
        )
        assertNotNull(token["revoked_at"])
        refresh(login.refreshToken).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `logout without access or with another owner does not revoke the session`() {
        val owner = signupAndLogin()
        val other = signupAndLogin()
        val body = objectMapper.writeValueAsString(LogoutRequest(owner.refreshToken))
        mockMvc.post("/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }
        mockMvc.post("/auth/logout") {
            header("Authorization", "Bearer ${other.token}")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }
        val token = jdbc.queryForMap(
            "select revoked_at from core.refresh_token where refresh_token_id = ?", tokenId(owner.refreshToken)
        )
        assertNull(token["revoked_at"])
        refresh(owner.refreshToken).andExpect { status { isOk() } }
    }

    @Test
    fun `wrong login password does not create another session`() {
        val login = signupAndLogin()
        mockMvc.post("/signin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SigninRequest(login.user.email, "errada123"))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("E-mail ou senha inválidos") }
        }
        assertEquals(1L, jdbc.queryForObject(
            "select count(*) from core.refresh_token where users_id = ?", Long::class.java, login.user.id
        ))
    }
}