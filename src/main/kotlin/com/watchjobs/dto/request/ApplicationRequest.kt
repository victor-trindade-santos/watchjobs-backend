package com.watchjobs.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PastOrPresent
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.AssertTrue
import java.time.LocalDate
import java.time.LocalDateTime

data class ApplicationRequest(
    @field:NotNull(message = "Vaga é obrigatória")
    @field:Positive(message = "Vaga inválida")
    val savedJobId: Long,

    @field:NotNull(message = "Etapa da candidatura é obrigatória")
    @field:Positive(message = "Etapa da candidatura inválida")
    val stageId: Int,

    @field:NotBlank(message = "Status da candidatura é obrigatório")
    @field:Pattern(
        regexp = "^(APPLIED|IN_PROCESS|OFFER_RECEIVED|ACCEPTED|REJECTED|WITHDRAWN)$",
        message = "Status da candidatura inválido"
    )
    val status: String,

    @field:NotNull(message = "Data da candidatura é obrigatória")
    @field:PastOrPresent(message = "Data da candidatura não pode ser futura")
    val applicationDate: LocalDate,

    val endDate: LocalDate? = null,

    val interviewAt: LocalDateTime? = null,

    @field:Size(max = 4000, message = "Notas devem ter no máximo 4000 caracteres")
    val notes: String? = null,

    @field:Size(max = 4000, message = "Feedback deve ter no máximo 4000 caracteres")
    val feedback: String? = null,
){
    @AssertTrue(message = "Data de término não pode ser anterior à data da candidatura")
    fun isEndDateOnOrAfterApplicationDate(): Boolean =
        endDate == null || !endDate.isBefore(applicationDate)
}