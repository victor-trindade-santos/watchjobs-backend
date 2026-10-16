package com.watchjobs.dto.request

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.entity.enums.WorkMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class SavedJobRequest(
    @field:NotBlank(message = "Cargo é obrigatório")
    @field:Size(max = 255, message = "Cargo deve ter no máximo 255 caracteres")
    val positionRole: String,

    @field:NotBlank(message = "Empresa é obrigatória")
    @field:Size(max = 150, message = "Empresa deve ter no máximo 150 caracteres")
    val organizationName: String,

    @field:Size(max = 2048, message = "URL da vaga deve ter no máximo 2048 caracteres")
    val jobUrl: String? = null,

    @field:Size(max = 100, message = "Cidade deve ter no máximo 100 caracteres")
    val locationCity: String? = null,

    @field:Size(max = 100, message = "Estado deve ter no máximo 100 caracteres")
    val locationState: String? = null,

    @field:Pattern(regexp = "^[A-Z]{2}$", message = "País deve ter 2 letras maiúsculas")
    val locationCountryCode: String? = null,

    val workMode: WorkMode? = null,

    val experienceLevel: ExperienceLevel? = null,

    val jobType: JobType? = null,

    @field:PositiveOrZero(message = "Salário mínimo não pode ser negativo")
    val salaryMin: Double? = null,

    @field:PositiveOrZero(message = "Salário máximo não pode ser negativo")
    val salaryMax: Double? = null,

    @field:Pattern(regexp = "^[A-Z]{3}$", message = "Moeda deve ter 3 letras maiúsculas")
    val salaryCurrency: String? = null,

    @field:NotNull(message = "Status da vaga é obrigatório")
    val status: SavedJobStatus,

    @field:Size(max = 4000, message = "Notas devem ter no máximo 4000 caracteres")
    val notes: String? = null
)