package com.watchjobs.classification

import com.watchjobs.catalog.TextNormalizer
import java.security.MessageDigest

enum class RuleType { ROLE_FAMILY, SENIORITY, WORK_MODE, JOB_TYPE }

// Confiança padrão de cada forma de casar (3.12.1): expressão exata vale mais que palavra solta.
enum class MatchType(val confidence: Double) {
    WORD(0.6),
    PHRASE(1.0),
    REGEX(0.8)
}

enum class ClassificationSource { RULES, MODEL, MANUAL }

enum class RuleTarget { JOB, PROFILE }

data class ClassificationRule(
    val type: RuleType,
    val pattern: String,
    val matchType: MatchType,
    val targetValue: String,
    val priority: Int,
    val languageCode: String? = null,
    val appliesTo: String = "BOTH"
)

data class SeniorityBand(
    val roleFamily: String?,
    val minYears: Double,
    val maxYears: Double?,
    val level: String
)

data class RuleHit(val rule: ClassificationRule, val confidence: Double)

// Regras ativas já compiladas. A versão é o SHA-256 (12 primeiros caracteres) das regras,
// gravada em cada vaga e perfil classificados.
class RuleSet(
    rules: List<ClassificationRule>,
    val roleFamilies: Set<String>,
    val seniorityBands: List<SeniorityBand> = emptyList()
) {
    private class Compiled(val rule: ClassificationRule, val phrase: String, val regex: Regex?)

    val rules: List<ClassificationRule> = rules.sortedWith(
        compareBy<ClassificationRule>({ it.type }, { it.pattern }, { it.languageCode ?: "" })
    )

    val version: String = sha256(
        this.rules.joinToString("\n") {
            listOf(it.type, it.pattern, it.matchType, it.targetValue, it.priority, it.languageCode ?: "", it.appliesTo)
                .joinToString("|")
        } + "\n" + seniorityBands.sortedWith(compareBy({ it.roleFamily ?: "" }, { it.minYears }))
            .joinToString("\n") { "${it.roleFamily}|${it.minYears}|${it.maxYears}|${it.level}" }
    ).take(12)

    private val byType: Map<RuleType, List<Compiled>> = this.rules.groupBy { it.type }.mapValues { (_, list) ->
        list.map { rule ->
            when (rule.matchType) {
                MatchType.REGEX -> Compiled(rule, rule.pattern, Regex(rule.pattern))
                else -> Compiled(rule, TextNormalizer.forMatching(rule.pattern), null)
            }
        }.filter { it.regex != null || it.phrase.isNotEmpty() }
    }

    // Recebe o texto já normalizado (TextNormalizer.forMatching). Menor prioridade vence; em empate, o padrão mais longo.
    fun bestMatch(type: RuleType, normalizedText: String, target: RuleTarget): RuleHit? {
        if (normalizedText.isBlank()) return null
        val padded = " $normalizedText "
        return byType[type].orEmpty()
            .asSequence()
            .filter { it.rule.appliesTo == "BOTH" || it.rule.appliesTo == target.name }
            .filter { compiled ->
                compiled.regex?.containsMatchIn(normalizedText) ?: padded.contains(" ${compiled.phrase} ")
            }
            .sortedWith(compareBy<Compiled> { it.rule.priority }.thenByDescending { it.phrase.length })
            .firstOrNull()
            ?.let { RuleHit(it.rule, it.rule.matchType.confidence) }
    }

    fun levelForYears(years: Double, roleFamily: String?): String? {
        val candidates = seniorityBands.filter { it.roleFamily == roleFamily }.ifEmpty {
            seniorityBands.filter { it.roleFamily == null }
        }
        return candidates.firstOrNull { years >= it.minYears && (it.maxYears == null || years < it.maxYears) }?.level
    }

    companion object {
        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
