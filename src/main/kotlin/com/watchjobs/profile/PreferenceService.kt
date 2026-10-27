package com.watchjobs.profile

import com.watchjobs.classification.ClassificationInput
import com.watchjobs.classification.JobClassifier
import com.watchjobs.classification.RuleTarget
import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.profile.inference.ProfileSummaryService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

// Preferências (uma linha por usuário) e os segmentos de coleta que nascem delas (3.12.4).
@Service
class PreferenceService(
    private val jdbc: JdbcClient,
    private val catalog: CatalogStore,
    private val classifier: JobClassifier,
    private val versions: ProfileVersions,
    private val summaries: ProfileSummaryService,
    private val mapper: ObjectMapper
) {
    @Transactional(readOnly = true)
    fun find(userId: Int): PreferencesResponse? =
        jdbc.sql(
            """
            SELECT p.users_preference_id, array_to_json(p.preferred_roles)::text AS roles,
                   array_to_json(COALESCE(p.keywords, '{}'))::text AS keywords, p.preferred_work_mode,
                   p.preferred_experience_level, p.preferred_job_type, p.preferred_salary_min, p.preferred_salary_max,
                   p.auto_search_enabled, p.profile_version, p.confirmed_at,
                   l.location_id, l.country_code, l.state, l.city
              FROM core.userpreference p
              LEFT JOIN core."location" l ON l.location_id = p.preferred_location_id
             WHERE p.users_id = :userId
            """
        ).param("userId", userId).query { rs, _ ->
            val preferenceId = rs.getInt("users_preference_id")
            val roles = strings(rs.getString("roles"))
            PreferencesResponse(
                preferredRoles = roles,
                keywords = strings(rs.getString("keywords")),
                preferredWorkMode = PreferredWorkMode.valueOf(rs.getString("preferred_work_mode")),
                preferredExperienceLevel = rs.getString("preferred_experience_level")?.let(ExperienceLevel::valueOf),
                preferredJobType = rs.getString("preferred_job_type")?.let(JobType::valueOf),
                salaryMin = rs.getBigDecimal("preferred_salary_min"),
                salaryMax = rs.getBigDecimal("preferred_salary_max"),
                location = (rs.getObject("location_id") as Number?)?.let {
                    LocationResponse(it.toLong(), rs.getString("country_code"), rs.getString("state"), rs.getString("city"))
                },
                desiredSkills = desiredSkills(preferenceId),
                desiredLanguages = desiredLanguages(preferenceId),
                autoSearchEnabled = rs.getBoolean("auto_search_enabled"),
                profileVersion = rs.getInt("profile_version"),
                confirmed = rs.getObject("confirmed_at") != null,
                roleFamilies = roleFamilies(roles)
            )
        }.optional().orElse(null)

    @Transactional
    fun save(userId: Int, request: PreferencesRequest): PreferencesResponse {
        val min = request.salaryMin
        val max = request.salaryMax
        if (min != null && max != null && min > max) throw BusinessException("Salário mínimo não pode ser maior que o máximo")
        val roles = cleanList(request.preferredRoles, 100)
        val keywords = cleanList(request.keywords, 60)
        val locationId = request.location?.let { location ->
            location.countryCode?.trim()?.uppercase()?.ifEmpty { null }?.let { country ->
                val state = location.state?.trim()?.ifEmpty { null }
                val city = location.city?.trim()?.ifEmpty { null }
                catalog.locationId(country, state, city) ?: catalog.countryLocationId(country)
            }
        }
        checkCatalog("core.skill", "skill_id", request.desiredSkillIds, "Skill desejada não encontrada no catálogo")
        checkCatalog("core.\"language\"", "language_id", request.desiredLanguageIds, "Idioma desejado não encontrado no catálogo")

        val version = versions.bump(userId)
        // Na primeira confirmação, a busca automática é ligada (o usuário pode desligar depois).
        jdbc.sql(
            """
            UPDATE core.userpreference
               SET preferred_roles = ARRAY(SELECT jsonb_array_elements_text(CAST(:roles AS jsonb))),
                   keywords = ARRAY(SELECT jsonb_array_elements_text(CAST(:keywords AS jsonb))),
                   preferred_work_mode = :workMode,
                   preferred_experience_level = :level,
                   preferred_job_type = :jobType,
                   preferred_salary_min = :salaryMin,
                   preferred_salary_max = :salaryMax,
                   preferred_location_id = :locationId,
                   auto_search_enabled = COALESCE(CAST(:autoSearch AS boolean),
                                                  CASE WHEN confirmed_at IS NULL THEN true ELSE auto_search_enabled END),
                   next_run_at = CASE WHEN COALESCE(CAST(:autoSearch AS boolean),
                                                    CASE WHEN confirmed_at IS NULL THEN true ELSE auto_search_enabled END)
                                      THEN COALESCE(next_run_at, now()) ELSE NULL END,
                   confirmed_at = COALESCE(confirmed_at, now())
             WHERE users_id = :userId
            """
        )
            .param("roles", mapper.writeValueAsString(roles))
            .param("keywords", mapper.writeValueAsString(keywords))
            .param("workMode", requireNotNull(request.preferredWorkMode).name)
            .param("level", request.preferredExperienceLevel?.name)
            .param("jobType", request.preferredJobType?.name)
            .param("salaryMin", min)
            .param("salaryMax", max)
            .param("locationId", locationId)
            .param("autoSearch", request.autoSearchEnabled)
            .param("userId", userId)
            .update()

        val preferenceId = preferenceId(userId)
        replaceLinks("core.skilluserpreference", "skill_id", preferenceId, request.desiredSkillIds.distinct())
        replaceLinks("core.languageuserpreference", "language_id", preferenceId, request.desiredLanguageIds.distinct())

        summaries.recompute(userId, version)
        registerSegments(userId, preferenceId, roles, requireNotNull(request.preferredWorkMode), locationId)
        return requireNotNull(find(userId))
    }

    // F3-08: cada área desejada (ou a principal deduzida) vira um segmento (área + país + modo) ligado à
    // preferência; o demand_score é quantas preferências pedem o segmento.
    fun registerSegments(userId: Int, preferenceId: Int, roles: List<String>, workMode: PreferredWorkMode, locationId: Long?) {
        val families = roleFamiliesWithTerms(roles).ifEmpty {
            summaries.summary(userId)?.primaryRoleFamily?.let { mapOf(it to null) }.orEmpty()
        }
        val country = locationId?.let {
            jdbc.sql("SELECT country_code FROM core.\"location\" WHERE location_id = :id").param("id", it)
                .query(String::class.java).optional().orElse(null)
        } ?: "BR"

        val previous = jdbc.sql("SELECT segment_id FROM discovery.segment_demand WHERE users_preference_id = :id")
            .param("id", preferenceId).query(Long::class.java).list()
        jdbc.sql("DELETE FROM discovery.segment_demand WHERE users_preference_id = :id").param("id", preferenceId).update()

        val current = families.map { (family, term) ->
            jdbc.sql(
                """
                INSERT INTO discovery.coverage_segment (role_family, country_code, work_mode, keywords)
                VALUES (:family, :country, :mode, :keywords)
                ON CONFLICT (role_family, country_code, work_mode) DO UPDATE SET is_active = true
                RETURNING segment_id
                """
            )
                .param("family", family)
                .param("country", country)
                .param("mode", workMode.name)
                .param("keywords", term?.take(200))
                .query(Long::class.java)
                .single()
                .also { segmentId ->
                    jdbc.sql(
                        """
                        INSERT INTO discovery.segment_demand (segment_id, users_preference_id) VALUES (:segmentId, :preferenceId)
                        ON CONFLICT (segment_id, users_preference_id) DO NOTHING
                        """
                    ).param("segmentId", segmentId).param("preferenceId", preferenceId).update()
                }
        }
        val affected = (previous + current).distinct()
        if (affected.isNotEmpty()) {
            jdbc.sql(
                """
                UPDATE discovery.coverage_segment s
                   SET demand_score = (SELECT count(*) FROM discovery.segment_demand d WHERE d.segment_id = s.segment_id)
                 WHERE s.segment_id IN (:ids)
                """
            ).param("ids", affected).update()
        }
    }

    fun roleFamilies(roles: List<String>): List<String> = roleFamiliesWithTerms(roles).keys.toList()

    // Área de cada cargo desejado, com o primeiro cargo da área como termo de busca do segmento.
    private fun roleFamiliesWithTerms(roles: List<String>): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        roles.forEach { role ->
            classifier.classify(ClassificationInput(role), RuleTarget.PROFILE).roleFamily?.value?.let { result.putIfAbsent(it, role) }
        }
        return result
    }

    private fun preferenceId(userId: Int): Int =
        jdbc.sql("SELECT users_preference_id FROM core.userpreference WHERE users_id = :userId")
            .param("userId", userId).query(Int::class.java).single()

    private fun replaceLinks(table: String, column: String, preferenceId: Int, ids: List<Long>) {
        jdbc.sql("DELETE FROM $table WHERE users_preference_id = :id").param("id", preferenceId).update()
        ids.forEach { id ->
            jdbc.sql("INSERT INTO $table (users_preference_id, $column) VALUES (:preferenceId, :id) ON CONFLICT DO NOTHING")
                .param("preferenceId", preferenceId).param("id", id).update()
        }
    }

    private fun checkCatalog(table: String, column: String, ids: List<Long>, message: String) {
        if (ids.isEmpty()) return
        val found = jdbc.sql("SELECT count(DISTINCT $column) FROM $table WHERE $column IN (:ids)")
            .param("ids", ids.distinct()).query(Int::class.java).single()
        if (found != ids.distinct().size) throw NotFoundException(message)
    }

    private fun desiredSkills(preferenceId: Int): List<SkillRef> =
        jdbc.sql(
            """
            SELECT s.skill_id, s.skill_name FROM core.skilluserpreference p
              JOIN core.skill s ON s.skill_id = p.skill_id
             WHERE p.users_preference_id = :id ORDER BY s.skill_name
            """
        ).param("id", preferenceId).query { rs, _ -> SkillRef(rs.getLong("skill_id"), rs.getString("skill_name")) }.list()

    private fun desiredLanguages(preferenceId: Int): List<LanguageRef> =
        jdbc.sql(
            """
            SELECT l.language_id, l.language_name FROM core.languageuserpreference p
              JOIN core."language" l ON l.language_id = p.language_id
             WHERE p.users_preference_id = :id ORDER BY l.language_name
            """
        ).param("id", preferenceId).query { rs, _ -> LanguageRef(rs.getLong("language_id"), rs.getString("language_name")) }.list()

    private fun cleanList(values: List<String>, maxLength: Int): List<String> =
        values.map { it.trim().take(maxLength) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    @Suppress("UNCHECKED_CAST")
    private fun strings(json: String?): List<String> =
        runCatching { mapper.readValue(json, List::class.java) as List<String> }.getOrDefault(emptyList())
}
