package com.watchjobs.catalog

import com.watchjobs.jobs.JobCorpusQueries
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

data class SkillOption(val id: Long, val name: String, val category: String)

data class OrganizationOption(val id: Long, val name: String, val type: String)

data class LocationOption(val id: Long, val countryCode: String, val state: String?, val city: String?) {
    val label: String
        get() = listOfNotNull(city, state, countryCode).joinToString(", ")
}

data class LanguageOption(val id: Long, val name: String)

data class EducationOption(val id: Long, val level: String, val fieldOfStudy: String)

data class RoleFamilyOption(val code: String, val name: String)

// Autocompletar dos catálogos: no máximo 20 itens, os que começam com o termo primeiro.
@Repository
class CatalogQueries(private val jdbc: JdbcClient) {

    fun skills(q: String?): List<SkillOption> {
        val term = TextNormalizer.forSkills(q)
        return jdbc.sql(
            """
            SELECT s.skill_id, s.skill_name, s.skill_category,
                   min(CASE WHEN lower(s.skill_name) LIKE :prefix ESCAPE '\' OR a.normalized_alias LIKE :prefix ESCAPE '\'
                            THEN 0 ELSE 1 END) AS rank
              FROM core.skill s
              LEFT JOIN core.skill_alias a ON a.skill_id = s.skill_id AND a.match_policy <> 'DISABLED'
             WHERE lower(s.skill_name) LIKE :contains ESCAPE '\' OR a.normalized_alias LIKE :contains ESCAPE '\'
             GROUP BY s.skill_id, s.skill_name, s.skill_category
             ORDER BY rank, length(s.skill_name), s.skill_name
             LIMIT $LIMIT
            """
        )
            .param("prefix", prefix(term))
            .param("contains", JobCorpusQueries.likePattern(term))
            .query { rs, _ -> SkillOption(rs.getLong("skill_id"), rs.getString("skill_name"), rs.getString("skill_category")) }
            .list()
    }

    fun organizations(q: String?, type: String?): List<OrganizationOption> {
        val term = q?.let { NameNormalizer.normalize(it) }.orEmpty()
        return jdbc.sql(
            """
            SELECT organization_id, name, organization_type
              FROM core.organization
             WHERE normalized_name LIKE :contains ESCAPE '\'
               AND (CAST(:type AS text) IS NULL OR organization_type = CAST(:type AS text))
             ORDER BY CASE WHEN normalized_name LIKE :prefix ESCAPE '\' THEN 0 ELSE 1 END, length(name), name
             LIMIT $LIMIT
            """
        )
            .param("contains", JobCorpusQueries.likePattern(term))
            .param("prefix", prefix(term))
            .param("type", type)
            .query { rs, _ -> OrganizationOption(rs.getLong("organization_id"), rs.getString("name"), rs.getString("organization_type")) }
            .list()
    }

    fun locations(q: String?): List<LocationOption> {
        val term = q?.trim().orEmpty()
        return jdbc.sql(
            """
            SELECT location_id, country_code, state, city
              FROM core."location"
             WHERE neighborhood IS NULL
               AND (city ILIKE :contains ESCAPE '\' OR state ILIKE :contains ESCAPE '\' OR country_code ILIKE :contains ESCAPE '\')
             ORDER BY CASE WHEN city ILIKE :prefix ESCAPE '\' THEN 0 ELSE 1 END, city NULLS FIRST, state
             LIMIT $LIMIT
            """
        )
            .param("contains", JobCorpusQueries.likePattern(term))
            .param("prefix", prefix(term))
            .query { rs, _ -> LocationOption(rs.getLong("location_id"), rs.getString("country_code"), rs.getString("state"), rs.getString("city")) }
            .list()
    }

    fun languages(): List<LanguageOption> =
        jdbc.sql("SELECT language_id, language_name FROM core.\"language\" ORDER BY language_name")
            .query { rs, _ -> LanguageOption(rs.getLong("language_id"), rs.getString("language_name")) }
            .list()

    fun educations(): List<EducationOption> =
        jdbc.sql("SELECT education_id, education_level, field_of_study FROM core.education ORDER BY education_level, field_of_study")
            .query { rs, _ -> EducationOption(rs.getLong("education_id"), rs.getString("education_level"), rs.getString("field_of_study")) }
            .list()

    fun roleFamilies(): List<RoleFamilyOption> =
        jdbc.sql("SELECT code, name FROM core.role_family WHERE is_active ORDER BY sort_order, name")
            .query { rs, _ -> RoleFamilyOption(rs.getString("code"), rs.getString("name")) }
            .list()

    private fun prefix(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    companion object {
        const val LIMIT = 20
    }
}
