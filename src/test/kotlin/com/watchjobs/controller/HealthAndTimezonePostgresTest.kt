package com.watchjobs.controller

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import java.time.ZoneOffset
import kotlin.test.assertEquals

class HealthAndTimezonePostgresTest : AuthSessionPostgresTestSupport() {
    @Test
    fun `health answers without a token`() {
        mockMvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `session expiry is an absolute instant in UTC`() {
        val login = signupAndLogin()
        assertEquals(ZoneOffset.UTC, login.expiresAt.offset)
    }
}
