package com.watchjobs.profile

import com.watchjobs.exception.BusinessException
import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class SetupRequirements(
    val summary: Boolean,
    val experiences: Int,
    val skills: Int,
    val preferencesConfirmed: Boolean
) {
    val met: Boolean
        get() = summary && experiences >= MIN_EXPERIENCES && skills >= MIN_SKILLS && preferencesConfirmed

    val missing: List<String>
        get() = buildList {
            if (!summary) add("Escrever o resumo profissional")
            if (experiences < MIN_EXPERIENCES) add("Cadastrar ou importar pelo menos uma experiência")
            if (skills < MIN_SKILLS) add("Adicionar pelo menos $MIN_SKILLS skills (faltam ${MIN_SKILLS - skills})")
            if (!preferencesConfirmed) add("Salvar as preferências de vagas")
        }

    companion object {
        const val MIN_EXPERIENCES = 1
        const val MIN_SKILLS = 3
    }
}

data class ProfileSetupResponse(
    val completed: Boolean,
    val completedAt: OffsetDateTime?,
    val step: String?,
    val requirements: SetupRequirements,
    val missing: List<String>,
    val canComplete: Boolean
)

data class SetupStepRequest(
    @field:Pattern(regexp = "^[A-Z_]{1,30}$", message = "Etapa inválida")
    val step: String = ""
)

// Assistente de configuração do perfil (F05): o backend guarda a etapa e só conclui com os requisitos mínimos.
@Service
class ProfileSetupService(
    private val jdbc: JdbcClient,
    private val repository: ProfileRepository,
    private val preferences: PreferenceService
) {
    @Transactional(readOnly = true)
    fun requirements(userId: Int): SetupRequirements = SetupRequirements(
        summary = !repository.personal(userId).objective.isNullOrBlank(),
        experiences = repository.experiences(userId).size,
        skills = repository.skills(userId).size,
        preferencesConfirmed = preferences.find(userId)?.confirmed == true
    )

    @Transactional(readOnly = true)
    fun status(userId: Int): ProfileSetupResponse {
        data class Row(val step: String?, val completedAt: OffsetDateTime?)
        val row = jdbc.sql("SELECT profile_setup_step, profile_setup_completed_at FROM core.users WHERE users_id = :userId")
            .param("userId", userId)
            .query { rs, _ -> Row(rs.getString("profile_setup_step"), rs.getObject("profile_setup_completed_at", OffsetDateTime::class.java)) }
            .single()
        val requirements = requirements(userId)
        return ProfileSetupResponse(
            completed = row.completedAt != null,
            completedAt = row.completedAt,
            step = row.step,
            requirements = requirements,
            missing = requirements.missing,
            canComplete = requirements.met
        )
    }

    @Transactional
    fun saveStep(userId: Int, step: String): ProfileSetupResponse {
        jdbc.sql("UPDATE core.users SET profile_setup_step = :step WHERE users_id = :userId")
            .param("step", step).param("userId", userId).update()
        return status(userId)
    }

    @Transactional
    fun complete(userId: Int): ProfileSetupResponse {
        val requirements = requirements(userId)
        if (!requirements.met) throw BusinessException("Complete o perfil antes de continuar: " + requirements.missing.joinToString("; "))
        jdbc.sql(
            "UPDATE core.users SET profile_setup_completed_at = COALESCE(profile_setup_completed_at, now()), profile_setup_step = 'DONE' WHERE users_id = :userId"
        ).param("userId", userId).update()
        return status(userId)
    }
}

@RestController
@RequestMapping("/profile/setup")
class ProfileSetupController(private val service: ProfileSetupService) {
    @GetMapping
    fun status(@AuthenticationPrincipal principal: AppUserDetails) = service.status(principal.id)

    @PutMapping("/step")
    fun saveStep(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: SetupStepRequest) =
        service.saveStep(principal.id, request.step)

    @PostMapping("/complete")
    fun complete(@AuthenticationPrincipal principal: AppUserDetails) = service.complete(principal.id)
}
