package com.watchjobs.profile.inference

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.round

data class InferenceExperience(
    val title: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val internship: Boolean,
    val roleFamily: String?,
    val keywordLevel: String?,
    val skills: Set<String> = emptySet()
)

enum class LevelConfidence { HIGH, MEDIUM, LOW }

data class InferredProfile(
    val primaryRoleFamily: String?,
    val experienceLevel: String?,
    val levelConfidence: LevelConfidence?,
    val totalYears: Double,
    val internshipYears: Double,
    val yearsByFamily: Map<String, Double>,
    val yearsBySkill: Map<String, Double>,
    val reasons: List<String>
)

// Regra pura do perfil automático (3.12.3): recebe as experiências já classificadas e devolve a dedução.
// Não acessa banco; as faixas de senioridade chegam como função (RuleSet.levelForYears).
class ProfileInferenceEngine(
    private val levelForYears: (years: Double, roleFamily: String?) -> String?,
    private val today: LocalDate = LocalDate.now()
) {
    fun infer(experiences: List<InferenceExperience>): InferredProfile {
        val valid = experiences.filter { !it.startDate.isAfter(today) }
        if (valid.isEmpty()) {
            return InferredProfile(null, null, null, 0.0, 0.0, emptyMap(), emptyMap(), listOf("Nenhuma experiência cadastrada ainda"))
        }
        val work = valid.filterNot { it.internship }
        val internships = valid.filter { it.internship }

        val totalYears = years(work.map { it.period() })
        val internshipYears = years(internships.map { it.period() })
        val yearsByFamily = work.filter { it.roleFamily != null }
            .groupBy { it.roleFamily!! }
            .mapValues { (_, list) -> years(list.map { it.period() }) }
            .filterValues { it > 0 }

        val primary = primaryFamily(work.ifEmpty { internships })
        val reasons = mutableListOf<String>()
        reasons += "${format(totalYears)} anos de experiência profissional no total" +
            if (internshipYears > 0) " (mais ${format(internshipYears)} em estágio)" else ""
        primary?.let { reasons += "Área principal $it: ${format(yearsByFamily[it] ?: 0.0)} anos na área" }

        // (a) palavra-chave no cargo mais recente, se terminou há no máximo 12 meses
        val recentLimit = today.minusMonths(12)
        val mostRecent = valid.maxWith(compareBy<InferenceExperience> { it.endDate ?: today }.thenBy { it.startDate })
        val keyword = mostRecent.keywordLevel?.takeIf { (mostRecent.endDate ?: today) >= recentLimit }
        keyword?.let { reasons += "Cargo mais recente (\"${mostRecent.title}\") indica $it" }

        // (b) faixa de anos na área principal; só estágios = INTERN
        val band = when {
            work.isEmpty() -> "INTERN"
            else -> levelForYears(yearsByFamily[primary] ?: totalYears, primary)
        }
        band?.let {
            if (work.isEmpty()) reasons += "Só há experiências de estágio"
            else reasons += "Faixa de anos na área indica $it"
        }

        val level = keyword ?: band
        val history = totalYears + internshipYears
        val confidence = when {
            level == null -> null
            history < 1.0 -> LevelConfidence.LOW
            keyword != null && band != null && keyword == band -> LevelConfidence.HIGH
            else -> LevelConfidence.MEDIUM
        }
        if (keyword != null && band != null && keyword != band) {
            reasons += "O cargo e os anos indicam níveis diferentes; vale o cargo"
        }

        val skillNames = valid.flatMap { it.skills }.toSet()
        val yearsBySkill = skillNames.associateWith { skill ->
            years(valid.filter { skill in it.skills }.map { it.period() })
        }.filterValues { it > 0 }

        return InferredProfile(
            primaryRoleFamily = primary,
            experienceLevel = level,
            levelConfidence = confidence,
            totalYears = totalYears,
            internshipYears = internshipYears,
            yearsByFamily = yearsByFamily,
            yearsBySkill = yearsBySkill,
            reasons = reasons
        )
    }

    // Área com mais tempo nos últimos 5 anos; em empate, a mais recente.
    private fun primaryFamily(experiences: List<InferenceExperience>): String? {
        val windowStart = today.minusYears(5)
        val candidates = experiences.filter { it.roleFamily != null }
        if (candidates.isEmpty()) return null
        return candidates.groupBy { it.roleFamily!! }
            .map { (family, list) ->
                val clipped = list.mapNotNull { exp ->
                    val (start, end) = exp.period()
                    val from = maxOf(start, windowStart)
                    if (end.isAfter(from)) from to end else null
                }
                Triple(family, days(clipped), list.maxOf { it.endDate ?: today })
            }
            .sortedWith(compareByDescending<Triple<String, Long, LocalDate>> { it.second }.thenByDescending { it.third })
            .first().first
    }

    private fun InferenceExperience.period(): Pair<LocalDate, LocalDate> {
        val end = minOf(endDate ?: today, today)
        return startDate to maxOf(end, startDate)
    }

    private fun years(periods: List<Pair<LocalDate, LocalDate>>): Double = round(days(periods) / 365.25 * 10) / 10

    // Junta sobreposições: dois empregos ao mesmo tempo não contam em dobro.
    private fun days(periods: List<Pair<LocalDate, LocalDate>>): Long {
        var total = 0L
        var currentStart: LocalDate? = null
        var currentEnd: LocalDate? = null
        for ((start, end) in periods.sortedBy { it.first }) {
            if (currentEnd == null || start.isAfter(currentEnd)) {
                if (currentStart != null) total += ChronoUnit.DAYS.between(currentStart, currentEnd)
                currentStart = start
                currentEnd = end
            } else if (end.isAfter(currentEnd)) {
                currentEnd = end
            }
        }
        if (currentStart != null) total += ChronoUnit.DAYS.between(currentStart, currentEnd)
        return total
    }

    private fun format(value: Double): String = "%.1f".format(value).replace('.', ',')
}
