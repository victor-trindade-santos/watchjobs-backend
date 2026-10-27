package com.watchjobs.matching

import com.watchjobs.profile.PreferenceService
import com.watchjobs.profile.ProfileVersions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.OffsetDateTime

data class ScoredJob(val job: MatchJob, val expiresAt: OffsetDateTime, val result: MatchResult)

// Leitura do perfil e das vagas para o cálculo da nota, e gravação em discovery.user_job_match.
@Repository
class MatchStore(
    private val jdbc: JdbcClient,
    private val template: JdbcTemplate,
    private val versions: ProfileVersions,
    private val preferences: PreferenceService,
    private val mapper: ObjectMapper
) {
    data class LoadedProfile(val profile: MatchProfile, val version: Int)

    fun profile(userId: Int): LoadedProfile {
        val version = versions.current(userId)
        val pref = jdbc.sql(
            """
            SELECT array_to_json(p.preferred_roles)::text AS roles, p.preferred_work_mode, p.preferred_experience_level,
                   p.preferred_job_type, p.preferred_salary_min, p.confirmed_at, l.city
              FROM core.userpreference p
              LEFT JOIN core."location" l ON l.location_id = p.preferred_location_id
             WHERE p.users_id = :userId AND p.confirmed_at IS NOT NULL
            """
        ).param("userId", userId).query { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            val roles = runCatching { mapper.readValue(rs.getString("roles"), List::class.java) as List<String> }.getOrDefault(emptyList())
            MatchProfile(
                preferredRoles = roles,
                workMode = rs.getString("preferred_work_mode").takeIf { it != "ANY" },
                level = rs.getString("preferred_experience_level"),
                jobType = rs.getString("preferred_job_type"),
                salaryMin = rs.getBigDecimal("preferred_salary_min")?.toDouble(),
                city = rs.getString("city")
            )
        }.optional().orElse(MatchProfile())

        val summary = jdbc.sql(
            "SELECT primary_role_family, experience_level, CAST(years_by_skill AS text) AS skills FROM core.user_profile_summary WHERE users_id = :userId"
        ).param("userId", userId).query { rs, _ ->
            Triple(rs.getString("primary_role_family"), rs.getString("experience_level"), rs.getString("skills"))
        }.optional().orElse(null)

        @Suppress("UNCHECKED_CAST")
        val inferredSkillNames = summary?.third
            ?.let { runCatching { (mapper.readValue(it, Map::class.java) as Map<String, Any?>).keys }.getOrNull() }
            .orEmpty()
        val skillIds = jdbc.sql(
            """
            SELECT skill_id FROM core.userskill WHERE users_id = :userId
            UNION
            SELECT skill_id FROM core.skill WHERE skill_name IN (:names)
            """
        ).param("userId", userId).param("names", inferredSkillNames.ifEmpty { setOf("") })
            .query(Long::class.java).list().filterNotNull().toSet()

        val profile = pref.copy(
            preferredFamilies = preferences.roleFamilies(pref.preferredRoles).toSet(),
            inferredFamily = summary?.first,
            level = pref.level ?: summary?.second,
            levelInferred = pref.level == null && summary?.second != null,
            skillIds = skillIds
        )
        return LoadedProfile(profile, version)
    }

    // Pré-filtro em SQL (3.7): vagas ativas, da área e do modo, ainda sem nota para esta versão do perfil.
    fun unscoredCandidates(userId: Int, version: Int, profile: MatchProfile, onlyIds: Collection<Long>? = null, limit: Int = 500): List<Pair<MatchJob, OffsetDateTime>> {
        val where = StringBuilder("WHERE j.corpus_status = 'ACTIVE' AND j.expires_at > now()")
        val families = profile.preferredFamilies.ifEmpty { setOfNotNull(profile.inferredFamily) }
        val params = mutableMapOf<String, Any?>("userId" to userId, "version" to version, "limit" to limit)
        if (onlyIds != null) {
            if (onlyIds.isEmpty()) return emptyList()
            where.append(" AND j.discovered_job_id IN (:ids)")
            params["ids"] = onlyIds
        } else {
            if (families.isNotEmpty()) {
                where.append(" AND j.role_family IN (:families)")
                params["families"] = families
            }
            if (profile.workMode == "REMOTE") where.append(" AND (j.work_mode = 'REMOTE' OR j.work_mode IS NULL)")
        }
        val spec = jdbc.sql(
            """
            SELECT j.discovered_job_id, j.title, j.role_family, j.experience_level, j.work_mode, l.city,
                   j.salary_min, j.salary_max, j.salary_currency, j.salary_period, j.job_type, j.expires_at
              FROM discovery.discovered_job j
              LEFT JOIN core."location" l ON l.location_id = j.location_id
            $where
               AND NOT EXISTS (SELECT 1 FROM discovery.user_job_match m
                                WHERE m.users_id = :userId AND m.discovered_job_id = j.discovered_job_id
                                  AND m.profile_version = :version)
             ORDER BY COALESCE(j.posted_at, j.discovered_at) DESC
             LIMIT :limit
            """
        ).params(params)
        val jobs = spec.query { rs, _ ->
            MatchJob(
                id = rs.getLong("discovered_job_id"),
                title = rs.getString("title"),
                roleFamily = rs.getString("role_family"),
                level = rs.getString("experience_level"),
                workMode = rs.getString("work_mode"),
                city = rs.getString("city"),
                salaryMin = rs.getBigDecimal("salary_min")?.toDouble(),
                salaryMax = rs.getBigDecimal("salary_max")?.toDouble(),
                salaryCurrency = rs.getString("salary_currency")?.trim(),
                salaryPeriod = rs.getString("salary_period"),
                jobType = rs.getString("job_type")
            ) to rs.getObject("expires_at", OffsetDateTime::class.java)
        }.list()
        return withSkills(jobs)
    }

    private fun withSkills(jobs: List<Pair<MatchJob, OffsetDateTime>>): List<Pair<MatchJob, OffsetDateTime>> {
        if (jobs.isEmpty()) return jobs
        data class Row(val jobId: Long, val skillId: Long, val name: String, val required: Boolean)
        val rows = jdbc.sql(
            """
            SELECT js.discovered_job_id, js.skill_id, s.skill_name, js.is_required
              FROM discovery.discovered_job_skill js
              JOIN core.skill s ON s.skill_id = js.skill_id
             WHERE js.discovered_job_id IN (:ids)
            """
        ).param("ids", jobs.map { it.first.id }).query { rs, _ ->
            Row(rs.getLong("discovered_job_id"), rs.getLong("skill_id"), rs.getString("skill_name"), rs.getBoolean("is_required"))
        }.list().groupBy { it.jobId }
        return jobs.map { (job, expires) ->
            val skills = rows[job.id].orEmpty()
            job.copy(
                requiredSkills = skills.filter { it.required }.associate { it.skillId to it.name },
                optionalSkills = skills.filterNot { it.required }.associate { it.skillId to it.name }
            ) to expires
        }
    }

    fun save(userId: Int, version: Int, scored: List<ScoredJob>) {
        if (scored.isEmpty()) return
        template.batchUpdate(
            """
            INSERT INTO discovery.user_job_match (users_id, discovered_job_id, profile_version, score_total, breakdown,
                algo_version, computed_at, expires_at)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, now(), ?)
            ON CONFLICT (users_id, discovered_job_id, profile_version) DO UPDATE
               SET score_total = EXCLUDED.score_total, breakdown = EXCLUDED.breakdown,
                   algo_version = EXCLUDED.algo_version, computed_at = now(), expires_at = EXCLUDED.expires_at
            """,
            scored.map { s ->
                arrayOf<Any?>(
                    userId, s.job.id, version, s.result.score.toBigDecimal(), mapper.writeValueAsString(s.result),
                    s.result.algoVersion, Timestamp.from(s.expiresAt.toInstant())
                )
            }
        )
    }

    fun match(userId: Int, version: Int, jobId: Long): MatchResult? =
        jdbc.sql(
            """
            SELECT CAST(breakdown AS text) FROM discovery.user_job_match
             WHERE users_id = :userId AND discovered_job_id = :jobId AND profile_version = :version
            """
        ).param("userId", userId).param("jobId", jobId).param("version", version)
            .query(String::class.java).optional().orElse(null)
            ?.let { runCatching { mapper.readValue(it, MatchResult::class.java) }.getOrNull() }

    fun deleteVersion(userId: Int, version: Int) {
        jdbc.sql("DELETE FROM discovery.user_job_match WHERE users_id = :userId AND profile_version = :version")
            .param("userId", userId).param("version", version).update()
    }
}
