package com.watchjobs.classification

import com.watchjobs.catalog.TextNormalizer
import org.springframework.stereotype.Component
import kotlin.math.round

data class Classified(val value: String, val source: ClassificationSource, val confidence: Double)

data class ClassificationInput(
    val title: String,
    val description: String? = null,
    val location: String? = null
)

data class JobClassification(
    val roleFamily: Classified?,
    val seniority: Classified?,
    val workMode: Classified?,
    val jobType: Classified?,
    val rulesVersion: String
)

// Ponto único de classificação de vagas e experiências. Hoje por regras; um classificador por
// modelo (Laya, 3.13) pode entrar depois atrás desta interface, sem mudar quem a usa.
interface JobClassifier {
    val rulesVersion: String

    fun classify(input: ClassificationInput, target: RuleTarget = RuleTarget.JOB): JobClassification
}

@Component
class RuleBasedJobClassifier(private val rules: ClassificationRuleLoader) : JobClassifier {
    override val rulesVersion: String
        get() = rules.current().version

    override fun classify(input: ClassificationInput, target: RuleTarget): JobClassification =
        classifyWith(rules.current(), input, target)

    companion object {
        // A descrição só entra quando o cargo não bastou, com metade da confiança.
        private const val DESCRIPTION_WEIGHT = 0.5

        fun classifyWith(ruleSet: RuleSet, input: ClassificationInput, target: RuleTarget = RuleTarget.JOB): JobClassification {
            val title = TextNormalizer.forMatching(input.title)
            val titleAndLocation = TextNormalizer.forMatching(listOfNotNull(input.title, input.location).joinToString(" "))
            val description = TextNormalizer.forMatching(input.description)

            fun pick(type: RuleType, primary: String, useDescription: Boolean): Classified? {
                ruleSet.bestMatch(type, primary, target)?.let { return it.toClassified(1.0) }
                if (!useDescription) return null
                return ruleSet.bestMatch(type, description, target)?.toClassified(DESCRIPTION_WEIGHT)
            }

            return JobClassification(
                roleFamily = pick(RuleType.ROLE_FAMILY, title, useDescription = true)
                    ?.takeIf { it.value in ruleSet.roleFamilies },
                seniority = pick(RuleType.SENIORITY, title, useDescription = false),
                workMode = pick(RuleType.WORK_MODE, titleAndLocation, useDescription = true),
                jobType = pick(RuleType.JOB_TYPE, titleAndLocation, useDescription = true),
                rulesVersion = ruleSet.version
            )
        }

        private fun RuleHit.toClassified(weight: Double) =
            Classified(rule.targetValue, ClassificationSource.RULES, round(confidence * weight * 1000) / 1000)
    }
}
