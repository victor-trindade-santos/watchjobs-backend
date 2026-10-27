package com.watchjobs.profile

import com.watchjobs.profile.inference.ProfileSummaryService
import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/profile")
class ProfileController(
    private val service: ProfileService,
    private val preferences: PreferenceService,
    private val summaries: ProfileSummaryService,
    private val stats: ProfileStatsService
) {
    @GetMapping
    fun profile(@AuthenticationPrincipal principal: AppUserDetails) = service.profile(principal.id)

    @GetMapping("/stats")
    fun stats(@AuthenticationPrincipal principal: AppUserDetails) = stats.stats(principal.id)

    @GetMapping("/personal")
    fun personal(@AuthenticationPrincipal principal: AppUserDetails) = service.personal(principal.id)

    // POST também vale: o HttpURLConnection do Android não envia PATCH.
    @RequestMapping("/personal", method = [RequestMethod.PATCH, RequestMethod.POST])
    fun updatePersonal(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: PersonalRequest) =
        service.updatePersonal(principal.id, request)

    // --- Experiências ---

    @GetMapping("/experiences")
    fun experiences(@AuthenticationPrincipal principal: AppUserDetails) = service.experiences(principal.id)

    @PostMapping("/experiences")
    fun createExperience(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: ExperienceRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.createExperience(principal.id, request))

    @PutMapping("/experiences/{id}")
    fun updateExperience(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int,
        @Valid @RequestBody request: ExperienceRequest
    ) = service.updateExperience(principal.id, id, request)

    @DeleteMapping("/experiences/{id}")
    fun deleteExperience(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Int): ResponseEntity<Void> {
        service.deleteExperience(principal.id, id)
        return ResponseEntity.noContent().build()
    }

    // --- Formações ---

    @GetMapping("/educations")
    fun educations(@AuthenticationPrincipal principal: AppUserDetails) = service.educations(principal.id)

    @PostMapping("/educations")
    fun createEducation(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: EducationRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.createEducation(principal.id, request))

    @PutMapping("/educations/{id}")
    fun updateEducation(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int,
        @Valid @RequestBody request: EducationRequest
    ) = service.updateEducation(principal.id, id, request)

    @DeleteMapping("/educations/{id}")
    fun deleteEducation(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Int): ResponseEntity<Void> {
        service.deleteEducation(principal.id, id)
        return ResponseEntity.noContent().build()
    }

    // --- Skills ---

    @GetMapping("/skills")
    fun skills(@AuthenticationPrincipal principal: AppUserDetails) = service.skills(principal.id)

    @PostMapping("/skills")
    fun createSkill(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: UserSkillRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.createSkill(principal.id, request))

    @PutMapping("/skills/{id}")
    fun updateSkill(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int,
        @Valid @RequestBody request: UserSkillRequest
    ) = service.updateSkill(principal.id, id, request)

    @DeleteMapping("/skills/{id}")
    fun deleteSkill(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Int): ResponseEntity<Void> {
        service.deleteSkill(principal.id, id)
        return ResponseEntity.noContent().build()
    }

    // --- Idiomas ---

    @GetMapping("/languages")
    fun languages(@AuthenticationPrincipal principal: AppUserDetails) = service.languages(principal.id)

    @PostMapping("/languages")
    fun createLanguage(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: UserLanguageRequest) =
        ResponseEntity.status(HttpStatus.CREATED).body(service.createLanguage(principal.id, request))

    @PutMapping("/languages/{id}")
    fun updateLanguage(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int,
        @Valid @RequestBody request: UserLanguageRequest
    ) = service.updateLanguage(principal.id, id, request)

    @DeleteMapping("/languages/{id}")
    fun deleteLanguage(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Int): ResponseEntity<Void> {
        service.deleteLanguage(principal.id, id)
        return ResponseEntity.noContent().build()
    }

    // --- Preferências e perfil deduzido ---

    @GetMapping("/preferences")
    fun preferences(@AuthenticationPrincipal principal: AppUserDetails): ResponseEntity<PreferencesResponse> =
        preferences.find(principal.id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PutMapping("/preferences")
    fun savePreferences(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: PreferencesRequest) =
        preferences.save(principal.id, request)

    @GetMapping("/preferences/suggestion")
    fun suggestion(@AuthenticationPrincipal principal: AppUserDetails) = summaries.suggestion(principal.id)

    @GetMapping("/summary")
    fun summary(@AuthenticationPrincipal principal: AppUserDetails) =
        summaries.summary(principal.id) ?: summaries.recompute(principal.id)

    @PostMapping("/summary/recompute")
    fun recompute(@AuthenticationPrincipal principal: AppUserDetails) = summaries.recompute(principal.id)

    @PutMapping("/summary/level")
    fun correctLevel(@AuthenticationPrincipal principal: AppUserDetails, @RequestBody request: LevelCorrectionRequest) =
        summaries.setManualLevel(principal.id, request.experienceLevel)
}
