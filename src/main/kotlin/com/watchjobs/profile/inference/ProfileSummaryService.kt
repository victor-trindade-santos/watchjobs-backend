package com.watchjobs.profile.inference

import com.watchjobs.classification.ClassificationInput
import com.watchjobs.classification.ClassificationRuleLoader
import com.watchjobs.classification.JobClassifier
import com.watchjobs.classification.RuleTarget
import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.profile.ExperienceType
import com.watchjobs.profile.PreferencesSuggestion
import com.watchjobs.profile.ProfileRepository
import com.watchjobs.profile.ProfileSummaryResponse
import com.watchjobs.profile.ProfileVersions
import com.watchjobs.profile.SkillRef
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Grava e lê o perfil deduzido (core.user_profile_summary). O recálculo roda na mesma transação
// que sobe o profile_version (ProfileChangeTracker).
@Service
class ProfileSummaryService(
    private val jdbc: JdbcClient,
    private val profile: ProfileRepository,
    private val versions: ProfileVersions,
    private val classifier: JobClassifier,
    private val rules: ClassificationRuleLoader,
    private val catalog: CatalogStore,
    private val mapper: ObjectMapper
) {
    @Volatile
    private var extractor: SkillExtractor? = null

    private fun skillExtractor(): SkillExtractor =
        extractor ?: SkillExtractor(catalog.skillAliases()).also { extractor = it }

    @Transactional
    fun recompute(userId: Int): ProfileSummaryResponse = recompute(userId, versions.current(userId))

    @Transactional
    fun recompute(userId: Int, profileVersion: Int): ProfileSummaryResponse {
        val ruleSet = rules.current()
        val experiences = profile.experiences(userId).map { exp ->
            val classification = if (exp.roleFamily == null && exp.inferredLevel == null) {
                classifier.classify(ClassificationInput(exp.jobTitle, exp.description), RuleTarget.PROFILE)
            } else null
            val level = exp.inferredLevel?.name ?: classification?.seniority?.value
            InferenceExperience(
                title = exp.jobTitle,
                startDate = exp.startDate,
                endDate = exp.endDate,
                internship = exp.experienceType == ExperienceType.INTERNSHIP || level == "INTERN",
                roleFamily = exp.roleFamily ?: classification?.roleFamily?.value,
                keywordLevel = level,
                skills = skillExtractor().extract(listOfNotNull(exp.jobTitle, exp.description).joinToString(". "))
                    .map { it.skillName }.toSet()
            )
        }
        val inferred = ProfileInferenceEngine(ruleSet::levelForYears).infer(experiences)

        // Uma senioridade corrigida pelo usuário (MANUAL) vence o recálculo.
        val manualLevel = jdbc.sql(
            "SELECT experience_level FROM core.user_profile_summary WHERE users_id = :userId AND level_source = 'MANUAL'"
        ).param("userId", userId).query(String::class.java).optional().orElse(null)
        val reasons = if (manualLevel != null) inferred.reasons + "Senioridade definida por você: $manualLevel" else inferred.reasons

        jdbc.sql(
            """
            INSERT INTO core.user_profile_summary (users_id, primary_role_family, experience_level, level_confidence,
                level_source, total_years, internship_years, years_by_family, years_by_skill, reasons, rules_version,
                profile_version, computed_at)
            VALUES (:userId, :family, :level, :confidence, :levelSource, :totalYears, :internshipYears,
                CAST(:byFamily AS jsonb), CAST(:bySkill AS jsonb), CAST(:reasons AS jsonb), :rulesVersion, :version, now())
            ON CONFLICT (users_id) DO UPDATE SET
                primary_role_family = EXCLUDED.primary_role_family,
                experience_level = EXCLUDED.experience_level,
                level_confidence = EXCLUDED.level_confidence,
                level_source = EXCLUDED.level_source,
                total_years = EXCLUDED.total_years,
                internship_years = EXCLUDED.internship_years,
                years_by_family = EXCLUDED.years_by_family,
                years_by_skill = EXCLUDED.years_by_skill,
                reasons = EXCLUDED.reasons,
                rules_version = EXCLUDED.rules_version,
                profile_version = EXCLUDED.profile_version,
                computed_at = EXCLUDED.computed_at
            """
        )
            .param("userId", userId)
            .param("family", inferred.primaryRoleFamily)
            .param("level", manualLevel ?: inferred.experienceLevel)
            .param("confidence", if (manualLevel != null) "HIGH" else inferred.levelConfidence?.name)
            .param("levelSource", if (manualLevel != null) "MANUAL" else "RULES")
            .param("totalYears", inferred.totalYears.coerceAtMost(999.9))
            .param("internshipYears", inferred.internshipYears.coerceAtMost(999.9))
            .param("byFamily", mapper.writeValueAsString(inferred.yearsByFamily))
            .param("bySkill", mapper.writeValueAsString(inferred.yearsBySkill))
            .param("reasons", mapper.writeValueAsString(reasons))
            .param("rulesVersion", ruleSet.version)
            .param("version", profileVersion)
            .update()
        return requireNotNull(summary(userId))
    }

    @Transactional(readOnly = true)
    fun summary(userId: Int): ProfileSummaryResponse? =
        jdbc.sql(
            """
            SELECT primary_role_family, experience_level, level_confidence, level_source, total_years, internship_years,
                   CAST(years_by_family AS text) AS by_family, CAST(years_by_skill AS text) AS by_skill,
                   CAST(reasons AS text) AS reasons, rules_version, profile_version, computed_at
              FROM core.user_profile_summary WHERE users_id = :userId
            """
        ).param("userId", userId).query { rs, _ ->
            ProfileSummaryResponse(
                primaryRoleFamily = rs.getString("primary_role_family"),
                experienceLevel = rs.getString("experience_level")?.let(ExperienceLevel::valueOf),
                levelConfidence = rs.getString("level_confidence"),
                levelSource = rs.getString("level_source"),
                totalYears = rs.getBigDecimal("total_years").toDouble(),
                internshipYears = rs.getBigDecimal("internship_years").toDouble(),
                yearsByFamily = doubleMap(rs.getString("by_family")),
                yearsBySkill = doubleMap(rs.getString("by_skill")),
                reasons = stringList(rs.getString("reasons")),
                rulesVersion = rs.getString("rules_version"),
                profileVersion = rs.getInt("profile_version"),
                computedAt = rs.getObject("computed_at", OffsetDateTime::class.java)
            )
        }.optional().orElse(null)

    // Correção manual da senioridade (null volta para a dedução por regras).
    @Transactional
    fun setManualLevel(userId: Int, level: ExperienceLevel?): ProfileSummaryResponse {
        if (summary(userId) == null) recompute(userId)
        jdbc.sql(
            """
            UPDATE core.user_profile_summary
               SET level_source = CASE WHEN CAST(:level AS text) IS NULL THEN 'RULES' ELSE 'MANUAL' END,
                   experience_level = COALESCE(CAST(:level AS text), experience_level)
             WHERE users_id = :userId
            """
        ).param("level", level?.name).param("userId", userId).update()
        return recompute(userId, versions.bump(userId))
    }

    // Não grava nada: só devolve o que o app pode oferecer em "Usar sugestões".
    @Transactional(readOnly = true)
    fun suggestion(userId: Int): PreferencesSuggestion {
        val summary = summary(userId)
        val family = summary?.primaryRoleFamily
        val roles = profile.experiences(userId)
            .filter { family == null || it.roleFamily == family }
            .sortedByDescending { it.endDate ?: java.time.LocalDate.MAX }
            .map { it.jobTitle.trim() }
            .distinctBy { it.lowercase() }
            .take(2)
        val deduced = summary?.yearsBySkill.orEmpty().entries.sortedByDescending { it.value }.map { it.key }
        // Quem importou só a lista de skills (sem descrever as experiências) também recebe a sugestão.
        val topSkills = (deduced + profile.skills(userId).map { it.skillName }).distinct().take(10)
        val skillRefs = if (topSkills.isEmpty()) emptyList() else jdbc.sql(
            "SELECT skill_id, skill_name FROM core.skill WHERE skill_name IN (:names)"
        ).param("names", topSkills).query { rs, _ -> SkillRef(rs.getLong("skill_id"), rs.getString("skill_name")) }
            .list()
            .sortedBy { topSkills.indexOf(it.name) }
        return PreferencesSuggestion(
            preferredRoles = roles,
            roleFamily = family,
            preferredExperienceLevel = summary?.experienceLevel,
            desiredSkills = skillRefs,
            location = profile.personal(userId).location
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun doubleMap(json: String?): Map<String, Double> =
        runCatching { (mapper.readValue(json, Map::class.java) as Map<String, Number>).mapValues { it.value.toDouble() } }
            .getOrDefault(emptyMap())

    @Suppress("UNCHECKED_CAST")
    private fun stringList(json: String?): List<String> =
        runCatching { mapper.readValue(json, List::class.java) as List<String> }.getOrDefault(emptyList())
}
