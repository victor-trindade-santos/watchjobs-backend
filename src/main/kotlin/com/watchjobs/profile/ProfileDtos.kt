package com.watchjobs.profile

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

enum class ExperienceType { EMPLOYMENT, INTERNSHIP, FREELANCE, VOLUNTEER, OTHER }

enum class SkillLevel { BEGINNER, INTERMEDIATE, ADVANCED, EXPERT }

enum class ProficiencyLevel { A1, A2, B1, B2, C1, C2, NATIVE }

enum class DataSource { MANUAL, LINKEDIN_IMPORT, RESUME_IMPORT, INFERRED }

enum class Availability { IMMEDIATE, TWO_WEEKS, ONE_MONTH, NOT_LOOKING }

// ANY = sem preferência de modo (só em preferências e segmentos).
enum class PreferredWorkMode { REMOTE, HYBRID, ON_SITE, ANY }

data class LocationInput(
    @field:Pattern(regexp = "^[A-Za-z]{2}$", message = "País deve ter 2 letras")
    val countryCode: String? = null,
    @field:Size(max = 100) val state: String? = null,
    @field:Size(max = 100) val city: String? = null
)

data class LocationResponse(val id: Long, val countryCode: String, val state: String?, val city: String?)

data class PersonalRequest(
    @field:Size(min = 1, max = 60, message = "Nome deve ter entre 1 e 60 caracteres") val firstName: String? = null,
    @field:Size(min = 1, max = 80, message = "Sobrenome deve ter entre 1 e 80 caracteres") val lastName: String? = null,
    @field:Past(message = "Data de nascimento deve estar no passado") val birthDate: LocalDate? = null,
    @field:Size(max = 2048) val profileImageUrl: String? = null,
    @field:Size(max = 500, message = "Objetivo deve ter no máximo 500 caracteres") val objective: String? = null,
    val availability: Availability? = null,
    val location: LocationInput? = null
)

data class PersonalResponse(
    val firstName: String,
    val lastName: String,
    val email: String,
    val birthDate: LocalDate,
    val profileImageUrl: String?,
    val location: LocationResponse?,
    val objective: String? = null,
    val availability: Availability? = null
)

data class ExperienceRequest(
    @field:NotBlank(message = "Cargo é obrigatório") @field:Size(max = 150) val jobTitle: String = "",
    @field:NotBlank(message = "Empresa é obrigatória") @field:Size(max = 150) val organizationName: String = "",
    @field:NotNull(message = "Tipo é obrigatório") val experienceType: ExperienceType? = ExperienceType.EMPLOYMENT,
    @field:Size(max = 10000) val description: String? = null,
    @field:NotNull(message = "Data de início é obrigatória") val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val current: Boolean = false
)

data class ExperienceResponse(
    val id: Int,
    val jobTitle: String,
    val organizationId: Long,
    val organizationName: String,
    val experienceType: ExperienceType,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val current: Boolean,
    val roleFamily: String?,
    val inferredLevel: ExperienceLevel?,
    val source: DataSource
)

data class EducationRequest(
    @field:NotNull(message = "Formação é obrigatória") val educationId: Long? = null,
    @field:NotBlank(message = "Instituição é obrigatória") @field:Size(max = 150) val institution: String = "",
    @field:NotNull(message = "Data de início é obrigatória") val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val current: Boolean = false
)

data class EducationResponse(
    val id: Int,
    val educationId: Long,
    val educationLevel: String,
    val fieldOfStudy: String,
    val institution: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val current: Boolean,
    val source: DataSource
)

data class UserSkillRequest(
    @field:NotNull(message = "Skill é obrigatória") val skillId: Long? = null,
    @field:NotNull(message = "Nível é obrigatório") val skillLevel: SkillLevel? = null,
    @field:DecimalMin(value = "0.0", message = "Anos de experiência não podem ser negativos") val yearsExperience: BigDecimal? = null
)

data class UserSkillResponse(
    val id: Int,
    val skillId: Long,
    val skillName: String,
    val skillLevel: SkillLevel,
    val yearsExperience: BigDecimal?,
    val source: DataSource
)

data class UserLanguageRequest(
    @field:NotNull(message = "Idioma é obrigatório") val languageId: Long? = null,
    @field:NotNull(message = "Nível é obrigatório") val proficiencyLevel: ProficiencyLevel? = null
)

data class UserLanguageResponse(
    val id: Int,
    val languageId: Long,
    val languageName: String,
    val proficiencyLevel: ProficiencyLevel,
    val source: DataSource
)

data class PreferencesRequest(
    @field:Size(max = 10, message = "No máximo 10 cargos") val preferredRoles: List<String> = emptyList(),
    @field:Size(max = 20, message = "No máximo 20 palavras-chave") val keywords: List<String> = emptyList(),
    @field:NotNull(message = "Modo de trabalho é obrigatório") val preferredWorkMode: PreferredWorkMode? = null,
    val preferredExperienceLevel: ExperienceLevel? = null,
    val preferredJobType: JobType? = null,
    @field:DecimalMin("0.0") val salaryMin: BigDecimal? = null,
    @field:DecimalMin("0.0") val salaryMax: BigDecimal? = null,
    val location: LocationInput? = null,
    @field:Size(max = 30) val desiredSkillIds: List<Long> = emptyList(),
    @field:Size(max = 10) val desiredLanguageIds: List<Long> = emptyList(),
    val autoSearchEnabled: Boolean? = null
)

data class PreferencesResponse(
    val preferredRoles: List<String>,
    val keywords: List<String>,
    val preferredWorkMode: PreferredWorkMode,
    val preferredExperienceLevel: ExperienceLevel?,
    val preferredJobType: JobType?,
    val salaryMin: BigDecimal?,
    val salaryMax: BigDecimal?,
    val location: LocationResponse?,
    val desiredSkills: List<SkillRef>,
    val desiredLanguages: List<LanguageRef>,
    val autoSearchEnabled: Boolean,
    val profileVersion: Int,
    val confirmed: Boolean,
    val roleFamilies: List<String>
)

data class SkillRef(val id: Long, val name: String)

data class LanguageRef(val id: Long, val name: String)

data class ProfileSummaryResponse(
    val primaryRoleFamily: String?,
    val experienceLevel: ExperienceLevel?,
    val levelConfidence: String?,
    val levelSource: String,
    val totalYears: Double,
    val internshipYears: Double,
    val yearsByFamily: Map<String, Double>,
    val yearsBySkill: Map<String, Double>,
    val reasons: List<String>,
    val rulesVersion: String?,
    val profileVersion: Int,
    val computedAt: OffsetDateTime?
)

data class LevelCorrectionRequest(val experienceLevel: ExperienceLevel? = null)

data class PreferencesSuggestion(
    val preferredRoles: List<String>,
    val roleFamily: String?,
    val preferredExperienceLevel: ExperienceLevel?,
    val desiredSkills: List<SkillRef>,
    val location: LocationResponse?
)

data class ProfileResponse(
    val personal: PersonalResponse,
    val experiences: List<ExperienceResponse>,
    val educations: List<EducationResponse>,
    val skills: List<UserSkillResponse>,
    val languages: List<UserLanguageResponse>,
    val preferences: PreferencesResponse?,
    val summary: ProfileSummaryResponse?
)
