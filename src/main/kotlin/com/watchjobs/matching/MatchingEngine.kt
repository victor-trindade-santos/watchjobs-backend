package com.watchjobs.matching

import com.watchjobs.catalog.TextNormalizer
import kotlin.math.abs
import kotlin.math.round

// Perfil já resolvido para o cálculo: preferências confirmadas e, quando faltam, o perfil deduzido.
data class MatchProfile(
    val preferredRoles: List<String> = emptyList(),
    val preferredFamilies: Set<String> = emptySet(),
    val inferredFamily: String? = null,
    val level: String? = null,
    val levelInferred: Boolean = false,
    val workMode: String? = null,
    val city: String? = null,
    val salaryMin: Double? = null,
    val salaryCurrency: String = "BRL",
    val jobType: String? = null,
    val skillIds: Set<Long> = emptySet()
)

data class MatchJob(
    val id: Long,
    val title: String,
    val roleFamily: String?,
    val level: String?,
    val workMode: String?,
    val city: String?,
    val salaryMin: Double?,
    val salaryMax: Double?,
    val salaryCurrency: String?,
    val salaryPeriod: String?,
    val jobType: String?,
    val requiredSkills: Map<Long, String> = emptyMap(),
    val optionalSkills: Map<Long, String> = emptyMap()
)

data class Criterion(
    val key: String,
    val weight: Int,
    val applicable: Boolean,
    val value: Double,
    val points: Double,
    val detail: String,
    val inferred: Boolean = false
)

data class MatchResult(
    val score: Double,
    val criteria: List<Criterion>,
    val missingSkills: List<String>,
    val algoVersion: String = MatchingEngine.ALGO_VERSION
)

// Nota de 0 a 100 de uma vaga para um perfil (3.7). Regra pura e determinística: critério sem dado
// sai do cálculo, e a nota é normalizada pelos pesos que se aplicam.
object MatchingEngine {
    const val ALGO_VERSION = "rules-1"

    const val ROLE = 25
    const val REQUIRED_SKILLS = 25
    const val OPTIONAL_SKILLS = 10
    const val SENIORITY = 15
    const val WORK_MODE = 10
    const val SALARY = 10
    const val CONTRACT = 5

    private const val SAME_FAMILY = 0.6
    private const val NEIGHBOR_LEVEL = 0.5
    private val LEVELS = listOf("INTERN", "TRAINEE", "JUNIOR", "MID", "SENIOR", "SPECIALIST", "LEAD")

    fun score(profile: MatchProfile, job: MatchJob): MatchResult {
        val criteria = listOf(
            role(profile, job),
            skills("requiredSkills", REQUIRED_SKILLS, job.requiredSkills, profile.skillIds, "obrigatórias"),
            skills("optionalSkills", OPTIONAL_SKILLS, job.optionalSkills, profile.skillIds, "desejáveis"),
            seniority(profile, job),
            workMode(profile, job),
            salary(profile, job),
            contract(profile, job)
        )
        val applicable = criteria.filter { it.applicable }
        val weight = applicable.sumOf { it.weight }
        val total = if (weight == 0) 0.0 else round(applicable.sumOf { it.points } / weight * 1000) / 10
        val missing = if (profile.skillIds.isEmpty()) emptyList()
        else job.requiredSkills.filterKeys { it !in profile.skillIds }.values.sorted()
        return MatchResult(total, criteria, missing)
    }

    private fun criterion(key: String, weight: Int, value: Double?, detail: String, inferred: Boolean = false): Criterion =
        if (value == null) Criterion(key, weight, false, 0.0, 0.0, detail, inferred)
        else Criterion(key, weight, true, value, round(weight * value * 100) / 100, detail, inferred)

    private fun role(profile: MatchProfile, job: MatchJob): Criterion {
        val title = TextNormalizer.forMatching(job.title)
        val roles = profile.preferredRoles.map(TextNormalizer::forMatching).filter { it.isNotEmpty() }
        val families = profile.preferredFamilies.ifEmpty { setOfNotNull(profile.inferredFamily) }
        val inferred = profile.preferredFamilies.isEmpty() && profile.inferredFamily != null && roles.isEmpty()
        if (roles.isEmpty() && families.isEmpty()) return criterion("role", ROLE, null, "Sem cargo desejado no perfil")
        val exact = roles.firstOrNull { " $title ".contains(" $it ") || " $it ".contains(" $title ") }
        return when {
            exact != null -> criterion("role", ROLE, 1.0, "Cargo igual ao desejado")
            job.roleFamily != null && job.roleFamily in families ->
                criterion("role", ROLE, SAME_FAMILY, "Mesma área (${job.roleFamily})", inferred)
            else -> criterion("role", ROLE, 0.0, "Área diferente da desejada", inferred)
        }
    }

    private fun skills(key: String, weight: Int, wanted: Map<Long, String>, has: Set<Long>, label: String): Criterion {
        if (wanted.isEmpty() || has.isEmpty()) return criterion(key, weight, null, "Sem skills $label para comparar")
        val matched = wanted.keys.count { it in has }
        return criterion(key, weight, matched.toDouble() / wanted.size, "$matched de ${wanted.size} skills $label")
    }

    private fun seniority(profile: MatchProfile, job: MatchJob): Criterion {
        val wanted = profile.level
        val offered = job.level
        if (wanted == null || offered == null) return criterion("seniority", SENIORITY, null, "Nível não informado")
        val distance = abs(LEVELS.indexOf(wanted) - LEVELS.indexOf(offered))
        val value = when (distance) {
            0 -> 1.0
            1 -> NEIGHBOR_LEVEL
            else -> 0.0
        }
        val source = if (profile.levelInferred) " (nível deduzido do perfil)" else ""
        return criterion("seniority", SENIORITY, value, "Vaga $offered, perfil $wanted$source", profile.levelInferred)
    }

    private fun workMode(profile: MatchProfile, job: MatchJob): Criterion {
        val wanted = profile.workMode
        val offered = job.workMode
        if (wanted == null || offered == null) return criterion("workMode", WORK_MODE, null, "Modo não informado")
        var value = when {
            wanted == offered -> 1.0
            wanted == "REMOTE" && offered == "ON_SITE" || wanted == "ON_SITE" && offered == "REMOTE" -> 0.0
            else -> 0.5
        }
        val city = profile.city?.let(TextNormalizer::forMatching)
        if (offered != "REMOTE" && city != null && job.city != null && TextNormalizer.forMatching(job.city) != city) {
            value /= 2
        }
        return criterion("workMode", WORK_MODE, value, "Vaga $offered, preferência $wanted")
    }

    private fun salary(profile: MatchProfile, job: MatchJob): Criterion {
        val wanted = profile.salaryMin
        val offered = (job.salaryMax ?: job.salaryMin)?.let { monthly(it, job.salaryPeriod) }
        if (wanted == null || wanted <= 0 || offered == null) return criterion("salary", SALARY, null, "Salário não informado")
        if (job.salaryCurrency != null && job.salaryCurrency != profile.salaryCurrency) {
            return criterion("salary", SALARY, null, "Salário em outra moeda (${job.salaryCurrency})")
        }
        val value = if (offered >= wanted) 1.0 else round(offered / wanted * 1000) / 1000
        return criterion("salary", SALARY, value, if (value == 1.0) "Salário dentro do desejado" else "Salário abaixo do mínimo desejado")
    }

    private fun contract(profile: MatchProfile, job: MatchJob): Criterion {
        val wanted = profile.jobType
        val offered = job.jobType
        if (wanted == null || offered == null) return criterion("contract", CONTRACT, null, "Contrato não informado")
        return criterion("contract", CONTRACT, if (wanted == offered) 1.0 else 0.0, "Vaga $offered, preferência $wanted")
    }

    private fun monthly(value: Double, period: String?): Double = when (period?.uppercase()) {
        "YEAR", "YEARLY", "ANNUAL" -> value / 12
        "HOUR", "HOURLY" -> value * 160
        "DAY", "DAILY" -> value * 21
        else -> value
    }
}
