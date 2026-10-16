package com.watchjobs.mapper

import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.entity.User
import org.springframework.stereotype.Component

@Component
class UserMapper {
    fun toEntity(request: SignupRequest, encodedPassword: String): User = User(
        firstName = request.firstName.trim(),
        lastName = request.lastName.trim(),
        birthDate = request.birthDate,
        email = request.email.trim().lowercase(),
        password = encodedPassword
    )

    fun updateEntity(entity: User, request: SignupRequest) {
        entity.firstName = request.firstName.trim()
        entity.lastName = request.lastName.trim()
        entity.birthDate = request.birthDate
        entity.email = request.email.trim().lowercase()
    }

    fun toResponse(entity: User): UserResponse = UserResponse(
            id = entity.id ?: error("Usuário não possui ID"),
            firstName = entity.firstName,
            lastName = entity.lastName,
            birthDate = entity.birthDate,
            email = entity.email
        )
}