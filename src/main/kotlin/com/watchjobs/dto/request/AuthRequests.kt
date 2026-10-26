package com.watchjobs.dto.request

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class SignupRequest(
    @field:NotBlank(message = "Nome é obrigatório")
    @field:Size(max = 60, message = "Nome deve ter no máximo 60 caracteres")
    val firstName: String,

    @field:NotBlank(message = "Sobrenome é obrigatório")
    @field:Size(max = 80, message = "Sobrenome deve ter no máximo 80 caracteres")
    val lastName: String,

    @field:NotNull(message = "Data de nascimento é obrigatória")
    @field:Past(message = "Data de nascimento deve ser uma data passada")
    val birthDate: LocalDate,

    @field:NotBlank(message = "E-mail é obrigatório")
    @field:Email(message = "E-mail inválido")
    @field:Size(max = 254, message = "E-mail deve ter no máximo 254 caracteres")
    val email: String,

    @field:NotBlank(message = "Senha é obrigatória")
    @field:Size(min = 8, max = 255, message = "Senha deve ter entre 8 e 255 caracteres")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
        message = "Senha deve conter pelo menos uma letra e um número"
    )
    val password: String,

    @field:NotBlank(message = "Confirmação de senha é obrigatória")
    val confirmPassword: String
)

data class SigninRequest(
    @field:NotBlank(message = "E-mail é obrigatório")
    @field:Email(message = "E-mail inválido")
    val email: String,

    @field:NotBlank(message = "Senha é obrigatória")
    val password: String
)

data class RefreshRequest(
    @field:NotBlank(message = "Refresh token é obrigatório")
    val refreshToken: String
)

data class LogoutRequest(
    @field:NotBlank(message = "Refresh token é obrigatório")
    val refreshToken: String
)

data class ForgotPasswordRequest(
    @field:NotBlank(message = "E-mail é obrigatório")
    @field:Email(message = "E-mail inválido")
    @field:Size(max = 254, message = "E-mail deve ter no máximo 254 caracteres")
    val email: String
)

data class ResetPasswordRequest(
    @field:NotBlank(message = "E-mail é obrigatório")
    @field:Email(message = "E-mail inválido")
    val email: String,

    @field:NotBlank(message = "Código é obrigatório")
    @field:Pattern(regexp = "\\d{6}", message = "Código deve ter 6 dígitos")
    val code: String,

    @field:NotBlank(message = "Senha é obrigatória")
    @field:Size(min = 8, max = 255, message = "Senha deve ter entre 8 e 255 caracteres")
    @field:Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
        message = "Senha deve conter pelo menos uma letra e um número"
    )
    val newPassword: String,

    @field:NotBlank(message = "Confirmação de senha é obrigatória")
    val confirmPassword: String
)

data class DeleteAccountRequest(
    @field:NotBlank(message = "Senha é obrigatória")
    val password: String
)



