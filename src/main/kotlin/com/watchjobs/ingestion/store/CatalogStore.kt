package com.watchjobs.ingestion.store

import com.watchjobs.catalog.NameNormalizer
import com.watchjobs.ingestion.normalize.MatchPolicy
import com.watchjobs.ingestion.normalize.SkillAliasEntry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// Empresas e locais "criados ou reaproveitados" pela ingestão, sem duplicar (unicidades da V7).
@Repository
class CatalogStore(private val jdbc: JdbcClient) {

    fun organizationId(name: String): Long {
        val normalized = NameNormalizer.normalize(name)
        jdbc.sql(
            """
            INSERT INTO core.organization (name, organization_type, normalized_name)
            VALUES (:name, 'COMPANY', :normalized)
            ON CONFLICT (organization_type, normalized_name) DO NOTHING
            """
        )
            .param("name", name.trim().take(150))
            .param("normalized", normalized)
            .update()
        return jdbc.sql(
            "SELECT organization_id FROM core.organization WHERE organization_type = 'COMPANY' AND normalized_name = :normalized"
        )
            .param("normalized", normalized)
            .query(Long::class.java)
            .single()
    }

    // O país é obrigatório em core.location; sem ele, a vaga fica só com o texto original.
    fun locationId(countryCode: String?, state: String?, city: String?): Long? {
        if (countryCode == null || (state == null && city == null)) return null
        jdbc.sql(
            """
            INSERT INTO core."location" (country_code, state, city)
            VALUES (:country, :state, :city)
            ON CONFLICT (country_code, lower(COALESCE(state, '')), lower(COALESCE(city, '')), lower(COALESCE(neighborhood, '')))
            DO NOTHING
            """
        )
            .param("country", countryCode)
            .param("state", state)
            .param("city", city)
            .update()
        return jdbc.sql(
            """
            SELECT location_id FROM core."location"
             WHERE country_code = :country
               AND lower(COALESCE(state, '')) = lower(COALESCE(CAST(:state AS text), ''))
               AND lower(COALESCE(city, '')) = lower(COALESCE(CAST(:city AS text), ''))
               AND COALESCE(neighborhood, '') = ''
            """
        )
            .param("country", countryCode)
            .param("state", state)
            .param("city", city)
            .query(Long::class.java)
            .single()
    }

    fun skillAliases(): List<SkillAliasEntry> =
        jdbc.sql(
            """
            SELECT a.skill_id, s.skill_name, a.alias, a.normalized_alias, a.match_policy
              FROM core.skill_alias a
              JOIN core.skill s ON s.skill_id = a.skill_id
            """
        ).query { rs, _ ->
            SkillAliasEntry(
                skillId = rs.getLong("skill_id"),
                skillName = rs.getString("skill_name"),
                alias = rs.getString("alias"),
                normalizedAlias = rs.getString("normalized_alias"),
                policy = MatchPolicy.valueOf(rs.getString("match_policy"))
            )
        }.list()
}
