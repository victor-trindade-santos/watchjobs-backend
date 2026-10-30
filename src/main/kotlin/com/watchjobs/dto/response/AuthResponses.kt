package com.watchjobs.dto.response

import java.time.LocalDate
import java.time.OffsetDateTime

data class UserResponse(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val email: String,
    val birthDate: LocalDate,
)

data class AuthResponse(
    val token: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: OffsetDateTime,
    val user: UserResponse
)

