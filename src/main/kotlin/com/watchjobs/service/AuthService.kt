package com.watchjobs.service

import com.watchjobs.dto.request.SigninRequest
import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.response.AuthResponse
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.entity.User
import com.watchjobs.repository.UserRepository
import com.watchjobs.security.JwtUtil
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val jwtUtil: JwtUtil
) {
    @Transactional
    fun signup(request: SignupRequest): UserResponse {
        val email = request.email.trim().lowercase()
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email já cadastrado")
        }
        if (request.password != request.confirmPassword) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A senha e a confirmação de senha não coincidem")
        }
        val user = userRepository.save(
            User(
                firstName = request.firstName.trim(),
                lastName = request.lastName.trim(),
                birthDate = request.birthDate,
                email = email,
                password = requireNotNull(passwordEncoder.encode(request.password)){
                    "Erro ao codificar a senha"
                }
            )
        )
        return toUserResponse(user)
}    

@Transactional
    fun signin(request: SigninRequest): AuthResponse {
        val email = request.email.trim().lowercase()
        try {
           authenticationManager.authenticate(UsernamePasswordAuthenticationToken(email, request.password))  
        } catch (ex: BadCredentialsException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos")
        }
        val user = userRepository.findByEmailIgnoreCase(email)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos")
        user.lastLoginAt = LocalDateTime.now()
        userRepository.save(user)
        return AuthResponse(
            token = jwtUtil.generateToken(user),
            expiresAt = LocalDateTime.now().plusMinutes(jwtUtil.expirationMinutes),
            user = toUserResponse(user)
        )    
    }

    fun toUserResponse(user: User): UserResponse {
        return UserResponse(
            id = user.id ?: error("Usuário sem id"),
            firstName = user.firstName,
            lastName = user.lastName,
            email = user.email,
            birthDate = user.birthDate
        )
    }
}