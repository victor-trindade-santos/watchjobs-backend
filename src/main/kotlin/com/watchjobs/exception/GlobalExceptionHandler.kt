package com.watchjobs.exception

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ErrorResponse(val code: String, val message: String, val details: Map<String, String?>?)

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidation(ex: MethodArgumentNotValidException) = ErrorResponse(
        "VALIDATION_ERROR",
        "Dados inválidos",
        ex.bindingResult.fieldErrors.associate { it.field to it.defaultMessage }
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleUnreadable(ex: HttpMessageNotReadableException) =
        ErrorResponse("MALFORMED_REQUEST", "Corpo da requisição inválido", null)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException) =
        ErrorResponse("INVALID_PARAMETER", "Parâmetro inválido: ${ex.name}", null)

    @ExceptionHandler(BusinessException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBusiness(ex: BusinessException) =
        ErrorResponse("BUSINESS_RULE", ex.message ?: "Regra de negócio violada", null)

    @ExceptionHandler(BadCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleBadCredentials(ex: BadCredentialsException) =
        ErrorResponse("INVALID_CREDENTIALS", "E-mail ou senha inválidos", null)

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(ex: NotFoundException) =
        ErrorResponse("NOT_FOUND", ex.message ?: "Registro não encontrado", null)

    @ExceptionHandler(ConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleConflict(ex: ConflictException) =
        ErrorResponse("CONFLICT", ex.message ?: "Conflito com o estado atual", null)

    @ExceptionHandler(DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDataIntegrity(ex: DataIntegrityViolationException) =
        ErrorResponse("DATA_INTEGRITY", "Os dados violam uma regra do banco", null)
}
