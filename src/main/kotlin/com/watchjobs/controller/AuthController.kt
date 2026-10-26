package com.watchjobs.controller

import com.watchjobs.dto.request.DeleteAccountRequest
import com.watchjobs.dto.request.ForgotPasswordRequest
import com.watchjobs.dto.request.LogoutRequest
import com.watchjobs.dto.request.RefreshRequest
import com.watchjobs.dto.request.ResetPasswordRequest
import com.watchjobs.dto.request.SigninRequest
import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.response.AuthResponse
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.security.AppUserDetails
import com.watchjobs.service.AuthService
import com.watchjobs.service.PasswordResetService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(
    private val authService: AuthService,
    private val passwordResetService: PasswordResetService
) {
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<UserResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request))
    
    @PostMapping("/signin")
    fun signin(@Valid @RequestBody  request: SigninRequest): AuthResponse = authService.signin(request)

    @PostMapping("/auth/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): AuthResponse =
        authService.refresh(request.refreshToken)

    @PostMapping("/auth/logout")
    fun logout(
        @Valid @RequestBody request: LogoutRequest,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val principal = authentication.principal as AppUserDetails
        authService.logout(principal.user, request.refreshToken)
        return ResponseEntity.noContent().build()
    }
    
    @GetMapping("/me")
    fun me(authentication: Authentication): UserResponse {
        val principal = authentication.principal as AppUserDetails
        return authService.currentUser(principal.user)
    }

    @PostMapping("/password/forgot")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): ResponseEntity<Void> {
        passwordResetService.forgot(request.email)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/password/reset")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): ResponseEntity<Void> {
        passwordResetService.reset(request.email, request.code, request.newPassword, request.confirmPassword)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/me")
    fun deleteMe(
        @Valid @RequestBody request: DeleteAccountRequest,
        authentication: Authentication
    ): ResponseEntity<Void> {
        val principal = authentication.principal as AppUserDetails
        authService.deleteAccount(principal.user, request.password)
        return ResponseEntity.noContent().build()
    }
}   