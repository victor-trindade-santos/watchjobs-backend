package com.watchjobs.catalog

import java.text.Normalizer
import java.util.Locale

// Normalização de texto num lugar só: usada na ingestão, na classificação e no autocompletar.
object TextNormalizer {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")
    private val genderMarkers = Regex("\\((a|o|as|os|e)\\)|/(a|as)\\b", RegexOption.IGNORE_CASE)
    private val nonToken = Regex("[^a-z0-9+#]+")

    // Formas femininas viram a masculina, para uma regra só valer as duas ("desenvolvedora" = "desenvolvedor").
    private val tokenAliases = mapOf(
        "desenvolvedora" to "desenvolvedor",
        "engenheira" to "engenheiro",
        "estagiaria" to "estagiario",
        "coordenadora" to "coordenador",
        "lideranca" to "lider"
    )

    fun stripAccents(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(marks, "")

    fun collapseSpaces(value: String): String = value.replace(spaces, " ").trim()

    // Para casar regras: minúsculas, sem acento, sem marcador de gênero, só letras, dígitos, "+" e "#".
    fun forMatching(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val folded = stripAccents(value.replace(genderMarkers, "")).lowercase(Locale.ROOT)
        return collapseSpaces(folded.replace(nonToken, " "))
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { tokenAliases[it] ?: it }
    }

    // Para achar skills: mantém a pontuação ("node.js", "c++", "ci/cd") e as maiúsculas (versão "cased").
    fun forSkillsCased(value: String?): String = if (value.isNullOrBlank()) "" else collapseSpaces(stripAccents(value))

    fun forSkills(value: String?): String = forSkillsCased(value).lowercase(Locale.ROOT)
}
