package com.watchjobs.exception

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.sql.SQLException
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
        ErrorResponse("INVALID_CREDENTIALS", ex.message ?: "Credenciais inválidas", null)

    @ExceptionHandler(NotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(ex: NotFoundException) =
        ErrorResponse("NOT_FOUND", ex.message ?: "Registro não encontrado", null)

    @ExceptionHandler(ConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleConflict(ex: ConflictException) =
        ErrorResponse("CONFLICT", ex.message ?: "Conflito com o estado atual", null)

    @ExceptionHandler(TooManyRequestsException::class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun handleTooManyRequests(ex: TooManyRequestsException) =
        ErrorResponse("TOO_MANY_REQUESTS", ex.message ?: "Muitas requisições", null)

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleUploadSize(ex: MaxUploadSizeExceededException) =
        ErrorResponse("FILE_TOO_LARGE", "O arquivo passa do tamanho máximo de 10 MB", null)

    // CHECK do banco (SQLState 23514) é dado inválido: 400 com o nome da regra; o resto continua 409.
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        val cause = ex.mostSpecificCause as? SQLException
        if (cause?.sqlState == "23514") {
            val constraint = CHECK_NAME.find(cause.message.orEmpty())?.groupValues?.get(1)
            return ResponseEntity.badRequest().body(
                ErrorResponse("VALIDATION_ERROR", "Os dados não respeitam a regra ${constraint ?: "do banco"}", mapOf("constraint" to constraint))
            )
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("DATA_INTEGRITY", "Os dados violam uma regra do banco", null))
    }

    companion object {
        private val CHECK_NAME = Regex("constraint \"([^\"]+)\"")
    }
}
