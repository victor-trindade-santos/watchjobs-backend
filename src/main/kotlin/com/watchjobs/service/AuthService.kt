package com.watchjobs.service

import com.watchjobs.dto.request.SigninRequest
import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.response.AuthResponse
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.entity.User
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.ConflictException
import com.watchjobs.repository.UserRepository
import com.watchjobs.security.JwtUtil
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import com.watchjobs.mapper.UserMapper


@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val jwtUtil: JwtUtil,
    private val userMapper: UserMapper,
    private val refreshTokenService: RefreshTokenService
) {
    @Transactional
    fun signup(request: SignupRequest): UserResponse {
        val email = request.email.trim().lowercase()
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ConflictException("Email já cadastrado")
        }
        if (request.password != request.confirmPassword) {
            throw BusinessException("A senha e a confirmação de senha não coincidem")
        }
        val user = userRepository.save(
            userMapper.toEntity(request, requireNotNull(passwordEncoder.encode(request.password)){
                "Não foi possível gerar o hash da senha"
            })
        )
        return userMapper.toResponse(user)
}    

@Transactional
    fun signin(request: SigninRequest): AuthResponse {
        val email = request.email.trim().lowercase()
        try {
           authenticationManager.authenticate(UsernamePasswordAuthenticationToken(email, request.password))  
        } catch (ex: AuthenticationException) {
            throw BadCredentialsException("E-mail ou senha inválidos")
        }
        val user = userRepository.findByEmailIgnoreCase(email)
            ?: throw BadCredentialsException("E-mail ou senha inválidos")
        user.lastLoginAt = LocalDateTime.now()
        userRepository.save(user)
        val refresh = refreshTokenService.issue(user)
        return authResponse(user, refresh.value)
    }

    @Transactional
    fun refresh(value: String): AuthResponse {
        val refresh = refreshTokenService.rotate(value)
        return authResponse(refresh.token.user, refresh.value)
    }

    @Transactional
    fun logout(user: User, value: String) {
        refreshTokenService.revoke(value, requireNotNull(user.id))
    }

    private fun authResponse(user: User, refreshToken: String): AuthResponse =
        AuthResponse(
            token = jwtUtil.generateToken(user),
            refreshToken = refreshToken,
            expiresAt = LocalDateTime.now().plusMinutes(jwtUtil.expirationMinutes),
            user = userMapper.toResponse(user)
        )

    fun currentUser(user: User): UserResponse = userMapper.toResponse(user)

    @Transactional
    fun deleteAccount(user: User, password: String) {
        if (!passwordEncoder.matches(password, user.password)) {
            throw BadCredentialsException("Senha incorreta")
        }
        userRepository.delete(user)
    }
}