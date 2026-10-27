package com.watchjobs.matching

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// Opções dos filtros, geradas do corpus ativo (só vale o que existe nas vagas de hoje).
@Repository
class JobFacetQueries(private val jdbc: JdbcClient) {

    fun facets(): FeedFacets = FeedFacets(
        roleFamilies = facet("j.role_family", join = "", limit = 50),
        countries = facet("j.country_code", join = "", limit = 50),
        states = facet("l.state", join = LOCATION_JOIN, limit = 60),
        cities = facet("l.city", join = LOCATION_JOIN, limit = 100),
        companies = facet("j.organization_name", join = "", limit = 100)
    )

    private fun facet(column: String, join: String, limit: Int): List<FacetValue> =
        jdbc.sql(
            """
            SELECT $column AS value, count(*) AS total
              FROM discovery.discovered_job j $join
             WHERE j.corpus_status = 'ACTIVE' AND j.expires_at > now() AND $column IS NOT NULL AND $column <> ''
             GROUP BY $column
             ORDER BY total DESC, $column
             LIMIT :limit
            """
        ).param("limit", limit).query { rs, _ -> FacetValue(rs.getString("value").trim(), rs.getLong("total")) }.list()

    // Skills que aparecem em vagas ativas, com o número de vagas; paginado para o autocompletar.
    fun skills(q: String?, page: Int, size: Int): List<JobSkillFacet> {
        val term = q?.trim()?.takeIf { it.isNotEmpty() }
        val filter = if (term != null) " AND s.skill_name ILIKE :q ESCAPE '\\'" else ""
        var statement = jdbc.sql(
            """
            SELECT s.skill_id, s.skill_name, count(*) AS total
              FROM discovery.discovered_job_skill js
              JOIN discovery.discovered_job j ON j.discovered_job_id = js.discovered_job_id AND j.discovered_at = js.discovered_at
              JOIN core.skill s ON s.skill_id = js.skill_id
             WHERE j.corpus_status = 'ACTIVE' AND j.expires_at > now() $filter
             GROUP BY s.skill_id, s.skill_name
             ORDER BY total DESC, s.skill_name
             LIMIT :size OFFSET :offset
            """
        ).param("size", size).param("offset", page.toLong() * size)
        if (term != null) {
            statement = statement.param("q", "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        }
        return statement.query { rs, _ -> JobSkillFacet(rs.getLong("skill_id"), rs.getString("skill_name"), rs.getLong("total")) }.list()
    }

    private companion object {
        const val LOCATION_JOIN = """LEFT JOIN core."location" l ON l.location_id = j.location_id"""
    }
}
