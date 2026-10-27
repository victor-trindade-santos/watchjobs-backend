package com.watchjobs.profile

import com.watchjobs.entity.enums.ExperienceLevel
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate

data class ExperienceData(
    val jobTitle: String,
    val organizationId: Long,
    val experienceType: ExperienceType,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val current: Boolean,
    val roleFamily: String?,
    val inferredLevel: String?,
    val classificationConfidence: Double?,
    val source: DataSource = DataSource.MANUAL,
    val externalRef: String? = null
)

data class EducationData(
    val educationId: Long,
    val institution: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val current: Boolean,
    val source: DataSource = DataSource.MANUAL,
    val externalRef: String? = null
)

// Acesso às tabelas do perfil em core. Toda consulta filtra pelo dono (users_id vem do token).
@Repository
class ProfileRepository(private val jdbc: JdbcClient) {

    fun personal(userId: Int): PersonalResponse =
        jdbc.sql(
            """
            SELECT u.first_name, u.last_name, u.email, u.birth_date, u.profile_image_url,
                   u.professional_objective, u.availability,
                   l.location_id, l.country_code, l.state, l.city
              FROM core.users u
              LEFT JOIN core."location" l ON l.location_id = u.location_id
             WHERE u.users_id = :userId
            """
        ).param("userId", userId).query { rs, _ ->
            PersonalResponse(
                firstName = rs.getString("first_name"),
                lastName = rs.getString("last_name"),
                email = rs.getString("email"),
                birthDate = rs.getObject("birth_date", LocalDate::class.java),
                profileImageUrl = rs.getString("profile_image_url"),
                location = location(rs),
                objective = rs.getString("professional_objective"),
                availability = rs.getString("availability")?.let(Availability::valueOf)
            )
        }.single()

    fun updatePersonal(userId: Int, request: PersonalRequest, locationId: Long?, setLocation: Boolean) {
        jdbc.sql(
            """
            UPDATE core.users
               SET first_name = COALESCE(:firstName, first_name),
                   last_name = COALESCE(:lastName, last_name),
                   birth_date = COALESCE(CAST(:birthDate AS date), birth_date),
                   profile_image_url = CASE WHEN :setImage THEN CAST(:image AS text) ELSE profile_image_url END,
                   professional_objective = CASE WHEN :setObjective THEN CAST(:objective AS text) ELSE professional_objective END,
                   availability = COALESCE(CAST(:availability AS text), availability),
                   location_id = CASE WHEN :setLocation THEN CAST(:locationId AS int8) ELSE location_id END
             WHERE users_id = :userId
            """
        )
            .param("firstName", request.firstName?.trim())
            .param("lastName", request.lastName?.trim())
            .param("birthDate", request.birthDate)
            .param("setImage", request.profileImageUrl != null)
            .param("image", request.profileImageUrl?.trim()?.ifEmpty { null })
            .param("setObjective", request.objective != null)
            .param("objective", request.objective?.trim()?.ifEmpty { null })
            .param("availability", request.availability?.name)
            .param("setLocation", setLocation)
            .param("locationId", locationId)
            .param("userId", userId)
            .update()
    }

    fun locationById(id: Long?): LocationResponse? = id?.let {
        jdbc.sql("SELECT location_id, country_code, state, city FROM core.\"location\" WHERE location_id = :id")
            .param("id", it)
            .query { rs, _ -> location(rs) }
            .optional().orElse(null)
    }

    // --- Experiências ---

    fun experiences(userId: Int): List<ExperienceResponse> =
        jdbc.sql("$EXPERIENCE_SELECT WHERE e.users_id = :userId ORDER BY e.\"current\" DESC, e.start_date DESC, e.experience_id DESC")
            .param("userId", userId)
            .query { rs, _ -> experience(rs) }
            .list()

    fun experience(userId: Int, id: Int): ExperienceResponse? =
        jdbc.sql("$EXPERIENCE_SELECT WHERE e.users_id = :userId AND e.experience_id = :id")
            .param("userId", userId)
            .param("id", id)
            .query { rs, _ -> experience(rs) }
            .optional().orElse(null)

    fun insertExperience(userId: Int, data: ExperienceData): Int =
        jdbc.sql(
            """
            INSERT INTO core.experience (job_title, "current", experience_type, description, start_date, end_date,
                organization_id, users_id, source, external_ref, role_family, inferred_level,
                classification_source, classification_confidence)
            VALUES (:title, :current, :type, :description, :startDate, :endDate, :organizationId, :userId, :source,
                :externalRef, :roleFamily, :level, :classificationSource, :confidence)
            RETURNING experience_id
            """
        )
            .experienceParams(data)
            .param("userId", userId)
            .param("source", data.source.name)
            .param("externalRef", data.externalRef)
            .query(Int::class.java)
            .single()

    fun updateExperience(userId: Int, id: Int, data: ExperienceData): Boolean =
        jdbc.sql(
            """
            UPDATE core.experience
               SET job_title = :title, "current" = :current, experience_type = :type, description = :description,
                   start_date = :startDate, end_date = :endDate, organization_id = :organizationId,
                   role_family = :roleFamily, inferred_level = :level, classification_source = :classificationSource,
                   classification_confidence = :confidence, source = 'MANUAL'
             WHERE users_id = :userId AND experience_id = :id
            """
        )
            .experienceParams(data)
            .param("userId", userId)
            .param("id", id)
            .update() == 1

    fun deleteExperience(userId: Int, id: Int): Boolean =
        jdbc.sql("DELETE FROM core.experience WHERE users_id = :userId AND experience_id = :id")
            .param("userId", userId).param("id", id).update() == 1

    fun experienceRefExists(userId: Int, ref: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.experience WHERE users_id = :userId AND external_ref = :ref)")
            .param("userId", userId).param("ref", ref).query(Boolean::class.java).single()

    // --- Formações ---

    fun educations(userId: Int): List<EducationResponse> =
        jdbc.sql("$EDUCATION_SELECT WHERE ue.users_id = :userId ORDER BY ue.\"current\" DESC, ue.start_date DESC")
            .param("userId", userId)
            .query { rs, _ -> education(rs) }
            .list()

    fun education(userId: Int, id: Int): EducationResponse? =
        jdbc.sql("$EDUCATION_SELECT WHERE ue.users_id = :userId AND ue.user_education_id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ -> education(rs) }
            .optional().orElse(null)

    fun educationCatalogExists(id: Long): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.education WHERE education_id = :id)")
            .param("id", id).query(Boolean::class.java).single()

    fun insertEducation(userId: Int, data: EducationData): Int =
        jdbc.sql(
            """
            INSERT INTO core.usereducation (institution, start_date, end_date, "current", users_id, education_id,
                source, external_ref)
            VALUES (:institution, :startDate, :endDate, :current, :userId, :educationId, :source, :externalRef)
            RETURNING user_education_id
            """
        )
            .educationParams(data)
            .param("userId", userId)
            .param("source", data.source.name)
            .param("externalRef", data.externalRef)
            .query(Int::class.java)
            .single()

    fun updateEducation(userId: Int, id: Int, data: EducationData): Boolean =
        jdbc.sql(
            """
            UPDATE core.usereducation
               SET institution = :institution, start_date = :startDate, end_date = :endDate, "current" = :current,
                   education_id = :educationId, source = 'MANUAL'
             WHERE users_id = :userId AND user_education_id = :id
            """
        )
            .educationParams(data)
            .param("userId", userId).param("id", id)
            .update() == 1

    fun deleteEducation(userId: Int, id: Int): Boolean =
        jdbc.sql("DELETE FROM core.usereducation WHERE users_id = :userId AND user_education_id = :id")
            .param("userId", userId).param("id", id).update() == 1

    fun educationRefExists(userId: Int, ref: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.usereducation WHERE users_id = :userId AND external_ref = :ref)")
            .param("userId", userId).param("ref", ref).query(Boolean::class.java).single()

    // --- Skills ---

    fun skills(userId: Int): List<UserSkillResponse> =
        jdbc.sql("$SKILL_SELECT WHERE us.users_id = :userId ORDER BY s.skill_name")
            .param("userId", userId)
            .query { rs, _ -> skill(rs) }
            .list()

    fun skill(userId: Int, id: Int): UserSkillResponse? =
        jdbc.sql("$SKILL_SELECT WHERE us.users_id = :userId AND us.user_skill_id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ -> skill(rs) }
            .optional().orElse(null)

    fun skillCatalogExists(id: Long): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.skill WHERE skill_id = :id)")
            .param("id", id).query(Boolean::class.java).single()

    fun userHasSkill(userId: Int, skillId: Long, exceptId: Int? = null): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM core.userskill
                            WHERE users_id = :userId AND skill_id = :skillId
                              AND (CAST(:exceptId AS int4) IS NULL OR user_skill_id <> CAST(:exceptId AS int4)))
            """
        ).param("userId", userId).param("skillId", skillId).param("exceptId", exceptId).query(Boolean::class.java).single()

    fun insertSkill(userId: Int, skillId: Long, level: SkillLevel, years: BigDecimal?, source: DataSource): Int? =
        jdbc.sql(
            """
            INSERT INTO core.userskill (skill_level, years_experience, users_id, skill_id, source, years_source)
            VALUES (:level, :years, :userId, :skillId, :source, CASE WHEN CAST(:years AS numeric) IS NULL THEN NULL ELSE 'MANUAL' END)
            ON CONFLICT (users_id, skill_id) DO NOTHING
            RETURNING user_skill_id
            """
        )
            .param("level", level.name)
            .param("years", years)
            .param("userId", userId)
            .param("skillId", skillId)
            .param("source", source.name)
            .query(Int::class.java)
            .optional().orElse(null)

    fun updateSkill(userId: Int, id: Int, skillId: Long, level: SkillLevel, years: BigDecimal?): Boolean =
        jdbc.sql(
            """
            UPDATE core.userskill
               SET skill_id = :skillId, skill_level = :level, years_experience = :years,
                   years_source = CASE WHEN CAST(:years AS numeric) IS NULL THEN NULL ELSE 'MANUAL' END, source = 'MANUAL'
             WHERE users_id = :userId AND user_skill_id = :id
            """
        )
            .param("skillId", skillId).param("level", level.name).param("years", years)
            .param("userId", userId).param("id", id)
            .update() == 1

    fun deleteSkill(userId: Int, id: Int): Boolean =
        jdbc.sql("DELETE FROM core.userskill WHERE users_id = :userId AND user_skill_id = :id")
            .param("userId", userId).param("id", id).update() == 1

    // --- Idiomas ---

    fun languages(userId: Int): List<UserLanguageResponse> =
        jdbc.sql("$LANGUAGE_SELECT WHERE ul.users_id = :userId ORDER BY l.language_name")
            .param("userId", userId)
            .query { rs, _ -> language(rs) }
            .list()

    fun language(userId: Int, id: Int): UserLanguageResponse? =
        jdbc.sql("$LANGUAGE_SELECT WHERE ul.users_id = :userId AND ul.user_language_id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ -> language(rs) }
            .optional().orElse(null)

    fun languageCatalogExists(id: Long): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.\"language\" WHERE language_id = :id)")
            .param("id", id).query(Boolean::class.java).single()

    fun userHasLanguage(userId: Int, languageId: Long, exceptId: Int? = null): Boolean =
        jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM core.userlanguage
                            WHERE users_id = :userId AND language_id = :languageId
                              AND (CAST(:exceptId AS int4) IS NULL OR user_language_id <> CAST(:exceptId AS int4)))
            """
        ).param("userId", userId).param("languageId", languageId).param("exceptId", exceptId).query(Boolean::class.java).single()

    fun insertLanguage(userId: Int, languageId: Long, level: ProficiencyLevel, source: DataSource): Int? =
        jdbc.sql(
            """
            INSERT INTO core.userlanguage (proficiency_level, language_id, users_id, source)
            VALUES (:level, :languageId, :userId, :source)
            ON CONFLICT (users_id, language_id) DO NOTHING
            RETURNING user_language_id
            """
        )
            .param("level", level.name).param("languageId", languageId).param("userId", userId).param("source", source.name)
            .query(Int::class.java)
            .optional().orElse(null)

    fun updateLanguage(userId: Int, id: Int, languageId: Long, level: ProficiencyLevel): Boolean =
        jdbc.sql(
            """
            UPDATE core.userlanguage SET language_id = :languageId, proficiency_level = :level, source = 'MANUAL'
             WHERE users_id = :userId AND user_language_id = :id
            """
        ).param("languageId", languageId).param("level", level.name).param("userId", userId).param("id", id).update() == 1

    fun deleteLanguage(userId: Int, id: Int): Boolean =
        jdbc.sql("DELETE FROM core.userlanguage WHERE users_id = :userId AND user_language_id = :id")
            .param("userId", userId).param("id", id).update() == 1

    // --- Mapeamento ---

    private fun JdbcClient.StatementSpec.experienceParams(data: ExperienceData) = this
        .param("title", data.jobTitle)
        .param("current", data.current)
        .param("type", data.experienceType.name)
        .param("description", data.description)
        .param("startDate", data.startDate)
        .param("endDate", data.endDate)
        .param("organizationId", data.organizationId)
        .param("roleFamily", data.roleFamily)
        .param("level", data.inferredLevel)
        .param("classificationSource", if (data.roleFamily != null || data.inferredLevel != null) "RULES" else null)
        .param("confidence", data.classificationConfidence)

    private fun JdbcClient.StatementSpec.educationParams(data: EducationData) = this
        .param("institution", data.institution)
        .param("startDate", data.startDate)
        .param("endDate", data.endDate)
        .param("current", data.current)
        .param("educationId", data.educationId)

    private fun location(rs: ResultSet): LocationResponse? {
        val id = rs.getObject("location_id") as Number? ?: return null
        return LocationResponse(id.toLong(), rs.getString("country_code"), rs.getString("state"), rs.getString("city"))
    }

    private fun experience(rs: ResultSet) = ExperienceResponse(
        id = rs.getInt("experience_id"),
        jobTitle = rs.getString("job_title"),
        organizationId = rs.getLong("organization_id"),
        organizationName = rs.getString("organization_name"),
        experienceType = ExperienceType.valueOf(rs.getString("experience_type")),
        description = rs.getString("description"),
        startDate = rs.getObject("start_date", LocalDate::class.java),
        endDate = rs.getObject("end_date", LocalDate::class.java),
        current = rs.getBoolean("current"),
        roleFamily = rs.getString("role_family"),
        inferredLevel = rs.getString("inferred_level")?.let(ExperienceLevel::valueOf),
        source = DataSource.valueOf(rs.getString("source"))
    )

    private fun education(rs: ResultSet) = EducationResponse(
        id = rs.getInt("user_education_id"),
        educationId = rs.getLong("education_id"),
        educationLevel = rs.getString("education_level"),
        fieldOfStudy = rs.getString("field_of_study"),
        institution = rs.getString("institution"),
        startDate = rs.getObject("start_date", LocalDate::class.java),
        endDate = rs.getObject("end_date", LocalDate::class.java),
        current = rs.getBoolean("current"),
        source = DataSource.valueOf(rs.getString("source"))
    )

    private fun skill(rs: ResultSet) = UserSkillResponse(
        id = rs.getInt("user_skill_id"),
        skillId = rs.getLong("skill_id"),
        skillName = rs.getString("skill_name"),
        skillLevel = SkillLevel.valueOf(rs.getString("skill_level")),
        yearsExperience = rs.getBigDecimal("years_experience"),
        source = DataSource.valueOf(rs.getString("source"))
    )

    private fun language(rs: ResultSet) = UserLanguageResponse(
        id = rs.getInt("user_language_id"),
        languageId = rs.getLong("language_id"),
        languageName = rs.getString("language_name"),
        proficiencyLevel = ProficiencyLevel.valueOf(rs.getString("proficiency_level")),
        source = DataSource.valueOf(rs.getString("source"))
    )

    companion object {
        private val EXPERIENCE_SELECT = """
            SELECT e.experience_id, e.job_title, e.organization_id, o.name AS organization_name, e.experience_type,
                   e.description, e.start_date, e.end_date, e."current", e.role_family, e.inferred_level, e.source
              FROM core.experience e
              JOIN core.organization o ON o.organization_id = e.organization_id
        """.trimIndent()

        private val EDUCATION_SELECT = """
            SELECT ue.user_education_id, ue.education_id, ed.education_level, ed.field_of_study, ue.institution,
                   ue.start_date, ue.end_date, ue."current", ue.source
              FROM core.usereducation ue
              JOIN core.education ed ON ed.education_id = ue.education_id
        """.trimIndent()

        private val SKILL_SELECT = """
            SELECT us.user_skill_id, us.skill_id, s.skill_name, us.skill_level, us.years_experience, us.source
              FROM core.userskill us
              JOIN core.skill s ON s.skill_id = us.skill_id
        """.trimIndent()

        private val LANGUAGE_SELECT = """
            SELECT ul.user_language_id, ul.language_id, l.language_name, ul.proficiency_level, ul.source
              FROM core.userlanguage ul
              JOIN core."language" l ON l.language_id = ul.language_id
        """.trimIndent()
    }
}
