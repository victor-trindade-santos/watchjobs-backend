package com.watchjobs.dto.request

import java.time.LocalDate

data class SignupRequest(
    val firstName: String,
    val lastName: String,
    val birthDate: LocalDate,
    val email: String,
    val password: String,
    val confirmPassword: String
)

data class SigninRequest(
    val email: String,
    val password: String
)

