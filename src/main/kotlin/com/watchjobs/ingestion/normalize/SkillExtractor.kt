package com.watchjobs.ingestion.normalize

import com.watchjobs.catalog.TextNormalizer

enum class MatchPolicy { WORD, EXACT_CASE, CONTEXT_REQUIRED, DISABLED }

data class SkillAliasEntry(
    val skillId: Long,
    val skillName: String,
    val alias: String,
    val normalizedAlias: String,
    val policy: MatchPolicy
)

data class ExtractedSkill(val skillId: Long, val skillName: String, val required: Boolean)

// Procura os apelidos de core.skill_alias no texto. Nomes curtos ("Go", "R", "C") só contam
// com o caso exato e com contexto: numa lista ("Java, Go"), ou perto de palavras como "linguagem".
class SkillExtractor(aliases: List<SkillAliasEntry>) {
    private class Compiled(val entry: SkillAliasEntry, val regex: Regex)

    private val compiled: List<Compiled> = aliases
        .filter { it.policy != MatchPolicy.DISABLED && it.normalizedAlias.isNotBlank() }
        .map { entry ->
            val literal = if (entry.policy == MatchPolicy.WORD) entry.normalizedAlias else entry.alias
            Compiled(entry, Regex("(?<![\\p{L}\\p{N}+#])" + Regex.escape(literal) + "(?![\\p{L}\\p{N}+#])"))
        }

    fun extract(text: String?): List<ExtractedSkill> {
        val cased = TextNormalizer.forSkillsCased(text)
        if (cased.isEmpty()) return emptyList()
        val folded = TextNormalizer.forSkills(text)
        val optionalFrom = optionalSectionStart(folded)

        val firstIndex = linkedMapOf<Long, Pair<SkillAliasEntry, Int>>()
        fun record(entry: SkillAliasEntry, index: Int) {
            val current = firstIndex[entry.skillId]
            if (current == null || index < current.second) firstIndex[entry.skillId] = entry to index
        }

        compiled.filter { it.entry.policy != MatchPolicy.CONTEXT_REQUIRED }.forEach { c ->
            val source = if (c.entry.policy == MatchPolicy.WORD) folded else cased
            c.regex.find(source)?.let { record(c.entry, it.range.first) }
        }
        val othersFound = firstIndex.isNotEmpty()
        compiled.filter { it.entry.policy == MatchPolicy.CONTEXT_REQUIRED }.forEach { c ->
            c.regex.findAll(cased).firstOrNull { hasContext(cased, it.range, othersFound) }
                ?.let { record(c.entry, it.range.first) }
        }

        return firstIndex.values
            .sortedBy { it.second }
            .map { (entry, index) -> ExtractedSkill(entry.skillId, entry.skillName, optionalFrom == null || index < optionalFrom) }
    }

    private fun hasContext(text: String, range: IntRange, othersFound: Boolean): Boolean {
        val before = text.substring(0, range.first).trimEnd()
        val after = text.substring(range.last + 1).trimStart()
        if (before.lastOrNull() in listSeparators || after.firstOrNull() in listSeparators) return true
        val previousWord = TextNormalizer.forMatching(before.takeLast(30)).substringAfterLast(' ')
        val nextWord = TextNormalizer.forMatching(after.take(30)).substringBefore(' ')
        if (previousWord in contextWords || nextWord in contextWords) return true
        return othersFound && previousWord in conjunctions
    }

    private fun optionalSectionStart(folded: String): Int? =
        optionalMarkers.mapNotNull { marker -> folded.indexOf(marker).takeIf { it >= 0 } }.minOrNull()

    companion object {
        private val listSeparators = setOf(',', '/', ';', '(', ')', '|')
        private val contextWords = setOf(
            "language", "languages", "linguagem", "linguagens", "developer", "developers", "desenvolvedor",
            "engineer", "engenheiro", "programming", "programacao", "with", "using", "usando", "em", "lang"
        )
        private val conjunctions = setOf("e", "and", "ou", "or")
        private val optionalMarkers = listOf(
            "nice to have", "nice-to-have", "desejavel", "desejaveis", "diferencial", "diferenciais",
            "bonus points", "is a plus", "are a plus", "preferred qualifications", "pontos extras"
        )
    }
}
