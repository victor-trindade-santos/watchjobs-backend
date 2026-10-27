package com.watchjobs.profile

import com.watchjobs.classification.ClassificationInput
import com.watchjobs.classification.JobClassifier
import com.watchjobs.classification.RuleTarget
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.ConflictException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.profile.inference.ProfileSummaryService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

// Dados pessoais e os quatro CRUDs do perfil. Toda escrita chama o ProfileChangeTracker.
@Service
class ProfileService(
    private val repository: ProfileRepository,
    private val catalog: CatalogStore,
    private val classifier: JobClassifier,
    private val tracker: ProfileChangeTracker,
    private val summaries: ProfileSummaryService,
    private val preferences: PreferenceService
) {
    @Transactional(readOnly = true)
    fun profile(userId: Int): ProfileResponse = ProfileResponse(
        personal = repository.personal(userId),
        experiences = repository.experiences(userId),
        educations = repository.educations(userId),
        skills = repository.skills(userId),
        languages = repository.languages(userId),
        preferences = preferences.find(userId),
        summary = summaries.summary(userId)
    )

    @Transactional(readOnly = true)
    fun personal(userId: Int): PersonalResponse = repository.personal(userId)

    @Transactional
    fun updatePersonal(userId: Int, request: PersonalRequest): PersonalResponse {
        if (request.firstName?.isBlank() == true || request.lastName?.isBlank() == true) {
            throw BusinessException("Nome e sobrenome não podem ficar em branco")
        }
        val location = request.location
        val locationId = location?.let { locationId(it) }
        repository.updatePersonal(userId, request, locationId, setLocation = location != null)
        return repository.personal(userId)
    }

    // País obrigatório; sem cidade nem estado, o local é só o país (cidade e estado vazios).
    fun locationId(input: LocationInput): Long? {
        val country = input.countryCode?.trim()?.uppercase()?.ifEmpty { null } ?: return null
        val state = input.state?.trim()?.ifEmpty { null }
        val city = input.city?.trim()?.ifEmpty { null }
        return catalog.locationId(country, state, city) ?: catalog.countryLocationId(country)
    }

    // --- Experiências ---

    @Transactional(readOnly = true)
    fun experiences(userId: Int) = repository.experiences(userId)

    @Transactional
    fun createExperience(userId: Int, request: ExperienceRequest): ExperienceResponse {
        val id = repository.insertExperience(userId, experienceData(request))
        tracker.changed(userId)
        return requireNotNull(repository.experience(userId, id))
    }

    @Transactional
    fun updateExperience(userId: Int, id: Int, request: ExperienceRequest): ExperienceResponse {
        if (!repository.updateExperience(userId, id, experienceData(request))) throw NotFoundException(EXPERIENCE_NOT_FOUND)
        tracker.changed(userId)
        return requireNotNull(repository.experience(userId, id))
    }

    @Transactional
    fun deleteExperience(userId: Int, id: Int) {
        if (!repository.deleteExperience(userId, id)) throw NotFoundException(EXPERIENCE_NOT_FOUND)
        tracker.changed(userId)
    }

    fun experienceData(request: ExperienceRequest): ExperienceData {
        val start = requireNotNull(request.startDate)
        validatePeriod(start, request.endDate, request.current)
        val title = request.jobTitle.trim()
        val description = request.description?.trim()?.ifEmpty { null }
        val classification = classifier.classify(ClassificationInput(title, description), RuleTarget.PROFILE)
        return ExperienceData(
            jobTitle = title,
            organizationId = catalog.organizationId(request.organizationName.trim()),
            experienceType = request.experienceType ?: ExperienceType.EMPLOYMENT,
            description = description,
            startDate = start,
            endDate = if (request.current) null else request.endDate,
            current = request.current,
            roleFamily = classification.roleFamily?.value,
            inferredLevel = classification.seniority?.value,
            classificationConfidence = classification.roleFamily?.confidence ?: classification.seniority?.confidence
        )
    }

    // --- Formações ---

    @Transactional(readOnly = true)
    fun educations(userId: Int) = repository.educations(userId)

    @Transactional
    fun createEducation(userId: Int, request: EducationRequest): EducationResponse {
        val id = repository.insertEducation(userId, educationData(request))
        tracker.changed(userId)
        return requireNotNull(repository.education(userId, id))
    }

    @Transactional
    fun updateEducation(userId: Int, id: Int, request: EducationRequest): EducationResponse {
        if (!repository.updateEducation(userId, id, educationData(request))) throw NotFoundException(EDUCATION_NOT_FOUND)
        tracker.changed(userId)
        return requireNotNull(repository.education(userId, id))
    }

    @Transactional
    fun deleteEducation(userId: Int, id: Int) {
        if (!repository.deleteEducation(userId, id)) throw NotFoundException(EDUCATION_NOT_FOUND)
        tracker.changed(userId)
    }

    private fun educationData(request: EducationRequest): EducationData {
        val educationId = requireNotNull(request.educationId)
        if (!repository.educationCatalogExists(educationId)) throw NotFoundException("Formação não encontrada no catálogo")
        val start = requireNotNull(request.startDate)
        if (!request.current && request.endDate == null) {
            throw BusinessException("Informe a data de término ou marque como cursando")
        }
        if (request.endDate != null && request.endDate.isBefore(start)) {
            throw BusinessException("Data de término não pode ser anterior à data de início")
        }
        val institution = request.institution.trim()
        // Registra a instituição no catálogo para o autocompletar.
        catalog.organizationId(institution, "EDUCATIONAL_INSTITUTION")
        return EducationData(educationId, institution, start, request.endDate, request.current)
    }

    // --- Skills ---

    @Transactional(readOnly = true)
    fun skills(userId: Int) = repository.skills(userId)

    @Transactional
    fun createSkill(userId: Int, request: UserSkillRequest): UserSkillResponse {
        val skillId = requireNotNull(request.skillId)
        if (!repository.skillCatalogExists(skillId)) throw NotFoundException("Skill não encontrada no catálogo")
        val id = repository.insertSkill(userId, skillId, requireNotNull(request.skillLevel), request.yearsExperience, DataSource.MANUAL)
            ?: throw ConflictException("Você já cadastrou esta skill")
        tracker.changed(userId)
        return requireNotNull(repository.skill(userId, id))
    }

    @Transactional
    fun updateSkill(userId: Int, id: Int, request: UserSkillRequest): UserSkillResponse {
        val skillId = requireNotNull(request.skillId)
        if (repository.skill(userId, id) == null) throw NotFoundException(SKILL_NOT_FOUND)
        if (!repository.skillCatalogExists(skillId)) throw NotFoundException("Skill não encontrada no catálogo")
        if (repository.userHasSkill(userId, skillId, exceptId = id)) throw ConflictException("Você já cadastrou esta skill")
        repository.updateSkill(userId, id, skillId, requireNotNull(request.skillLevel), request.yearsExperience)
        tracker.changed(userId)
        return requireNotNull(repository.skill(userId, id))
    }

    @Transactional
    fun deleteSkill(userId: Int, id: Int) {
        if (!repository.deleteSkill(userId, id)) throw NotFoundException(SKILL_NOT_FOUND)
        tracker.changed(userId)
    }

    // --- Idiomas ---

    @Transactional(readOnly = true)
    fun languages(userId: Int) = repository.languages(userId)

    @Transactional
    fun createLanguage(userId: Int, request: UserLanguageRequest): UserLanguageResponse {
        val languageId = requireNotNull(request.languageId)
        if (!repository.languageCatalogExists(languageId)) throw NotFoundException("Idioma não encontrado no catálogo")
        val id = repository.insertLanguage(userId, languageId, requireNotNull(request.proficiencyLevel), DataSource.MANUAL)
            ?: throw ConflictException("Você já cadastrou este idioma")
        tracker.changed(userId)
        return requireNotNull(repository.language(userId, id))
    }

    @Transactional
    fun updateLanguage(userId: Int, id: Int, request: UserLanguageRequest): UserLanguageResponse {
        val languageId = requireNotNull(request.languageId)
        if (repository.language(userId, id) == null) throw NotFoundException(LANGUAGE_NOT_FOUND)
        if (!repository.languageCatalogExists(languageId)) throw NotFoundException("Idioma não encontrado no catálogo")
        if (repository.userHasLanguage(userId, languageId, exceptId = id)) throw ConflictException("Você já cadastrou este idioma")
        repository.updateLanguage(userId, id, languageId, requireNotNull(request.proficiencyLevel))
        tracker.changed(userId)
        return requireNotNull(repository.language(userId, id))
    }

    @Transactional
    fun deleteLanguage(userId: Int, id: Int) {
        if (!repository.deleteLanguage(userId, id)) throw NotFoundException(LANGUAGE_NOT_FOUND)
        tracker.changed(userId)
    }

    private fun validatePeriod(start: LocalDate, end: LocalDate?, current: Boolean) {
        if (start.isAfter(LocalDate.now())) throw BusinessException("Data de início não pode estar no futuro")
        if (current && end != null) throw BusinessException("Experiência atual não tem data de término")
        if (!current && end == null) throw BusinessException("Informe a data de término ou marque como atual")
        if (end != null && end.isBefore(start)) throw BusinessException("Data de término não pode ser anterior à data de início")
    }

    companion object {
        const val EXPERIENCE_NOT_FOUND = "Experiência não encontrada"
        const val EDUCATION_NOT_FOUND = "Formação não encontrada"
        const val SKILL_NOT_FOUND = "Skill não encontrada"
        const val LANGUAGE_NOT_FOUND = "Idioma não encontrado"
    }
}
