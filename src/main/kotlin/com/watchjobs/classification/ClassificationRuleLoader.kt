package com.watchjobs.classification

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

// Carrega as regras ativas do banco no primeiro uso (depois do Flyway) e guarda em memória.
// O motor e a API usam a mesma instância; reload() relê depois de uma mudança nas regras.
@Component
class ClassificationRuleLoader(private val jdbc: JdbcClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cached: RuleSet? = null

    fun current(): RuleSet = cached ?: synchronized(this) { cached ?: reload() }

    @Synchronized
    fun reload(): RuleSet {
        val families = jdbc.sql("SELECT code FROM core.role_family WHERE is_active")
            .query(String::class.java)
            .list()
            .filterNotNull()
            .toSet()
        val rules = jdbc.sql(
            """
            SELECT rule_type, pattern, match_type, target_value, priority, language_code, applies_to
              FROM core.classification_rule
             WHERE is_active
            """
        ).query { rs, _ ->
            ClassificationRule(
                type = RuleType.valueOf(rs.getString("rule_type")),
                pattern = rs.getString("pattern"),
                matchType = MatchType.valueOf(rs.getString("match_type")),
                targetValue = rs.getString("target_value"),
                priority = rs.getInt("priority"),
                languageCode = rs.getString("language_code"),
                appliesTo = rs.getString("applies_to")
            )
        }.list()
        val bands = jdbc.sql("SELECT role_family, min_years, max_years, experience_level FROM core.seniority_band")
            .query { rs, _ ->
                SeniorityBand(
                    roleFamily = rs.getString("role_family"),
                    minYears = rs.getBigDecimal("min_years").toDouble(),
                    maxYears = rs.getBigDecimal("max_years")?.toDouble(),
                    level = rs.getString("experience_level")
                )
            }.list()

        val unknown = rules.filter { it.type == RuleType.ROLE_FAMILY && it.targetValue !in families }
        if (unknown.isNotEmpty()) {
            log.warn("Regras de área com valor inexistente em core.role_family, ignoradas: {}", unknown.map { it.pattern })
        }
        val ruleSet = RuleSet(rules - unknown.toSet(), families, bands)
        log.info("Regras de classificação carregadas: {} regras, versão {}", ruleSet.rules.size, ruleSet.version)
        cached = ruleSet
        return ruleSet
    }
}
