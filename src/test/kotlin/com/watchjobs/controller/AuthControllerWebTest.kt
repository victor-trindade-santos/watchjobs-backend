package com.watchjobs.controller

import com.watchjobs.config.SecurityConfig
import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.entity.User
import com.watchjobs.exception.GlobalExceptionHandler
import com.watchjobs.security.AppUserDetails
import com.watchjobs.security.AppUserDetailsService
import com.watchjobs.security.JwtUtil
import com.watchjobs.service.AuthService
import com.watchjobs.service.PasswordResetService
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate

// Sobe só a camada web (controller + segurança + handler), sem banco.
@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, JwtUtil::class)
class AuthControllerWebTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtUtil: JwtUtil

    @MockitoBean
    lateinit var authService: AuthService

    @MockitoBean
    lateinit var passwordResetService: PasswordResetService

    @MockitoBean
    lateinit var appUserDetailsService: AppUserDetailsService

    @Test
    fun `signup invalido responde 400 com os erros de validacao`() {
        mockMvc.post("/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"firstName":"","lastName":"Silva","birthDate":"2999-01-01",
                 "email":"nao-e-email","password":"abc","confirmPassword":"abc"}
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
            jsonPath("$.details.firstName") { exists() }
            jsonPath("$.details.birthDate") { exists() }
            jsonPath("$.details.email") { exists() }
            jsonPath("$.details.password") { exists() }
        }
        verifyNoInteractions(authService)
    }

    @Test
    fun `signup com corpo vazio responde 400`() {
        mockMvc.post("/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
        }
    }

    @Test
    fun `signup com JSON quebrado responde 400`() {
        mockMvc.post("/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"firstName":"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
        }
    }

    @Test
    fun `signup valido responde 201`() {
        val request = SignupRequest(
            firstName = "Ana",
            lastName = "Silva",
            birthDate = LocalDate.of(1995, 5, 10),
            email = "ana@teste.com",
            password = "senha1234",
            confirmPassword = "senha1234"
        )
        given(authService.signup(request)).willReturn(
            UserResponse(1, "Ana", "Silva", "ana@teste.com", LocalDate.of(1995, 5, 10))
        )

        mockMvc.post("/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"firstName":"Ana","lastName":"Silva","birthDate":"1995-05-10",
                 "email":"ana@teste.com","password":"senha1234","confirmPassword":"senha1234"}
            """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.email") { value("ana@teste.com") }
        }
    }

    @Test
    fun `rota protegida sem token responde 401`() {
        mockMvc.get("/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHORIZED") }
        }
    }

    @Test
    fun `rota protegida com token valido responde 200`() {
        val user = User(id = 1, firstName = "Ana", lastName = "Silva",
            birthDate = LocalDate.of(1995, 5, 10), email = "ana@teste.com", password = "hash")
        given(appUserDetailsService.loadUserByUsername("ana@teste.com")).willReturn(AppUserDetails(user))
        given(authService.currentUser(user)).willReturn(
            UserResponse(1, "Ana", "Silva", "ana@teste.com", LocalDate.of(1995, 5, 10))
        )

        mockMvc.get("/me") {
            header("Authorization", "Bearer ${jwtUtil.generateToken(user)}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("ana@teste.com") }
        }
    }
}
