package com.watchjobs.controller

import com.watchjobs.dto.request.SigninRequest
import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.response.AuthResponse
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.service.AuthService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid

@RestController
class AuthController(
    private val authService: AuthService
) {
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<UserResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request))
    
    @PostMapping("/signin")
    fun signin(@Valid @RequestBody  request: SigninRequest): AuthResponse = authService.signin(request)
    
    @GetMapping("/me")
    fun me(authentication: Authentication): UserResponse {
        val principal = authentication.principal as AppUserDetails
        return authService.currentUser(principal.user)
    }
}   