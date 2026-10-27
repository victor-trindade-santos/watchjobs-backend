package com.watchjobs.profile.importer

import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.profile.ProficiencyLevel
import java.time.LocalDate
import java.util.Locale

// Lê o texto de um currículo (inclusive o PDF "Salvar como PDF" do LinkedIn) e separa em seções.
// É uma leitura aproximada: o usuário confere tudo na prévia antes de aplicar.
object ResumeParser {
    private enum class Section { HEADER, SUMMARY, EXPERIENCE, EDUCATION, SKILLS, LANGUAGES, CERTIFICATIONS, CONTACT }

    private val HEADINGS: Map<Section, Set<String>> = mapOf(
        Section.SUMMARY to setOf(
            "resumo", "resumo profissional", "sobre", "sobre mim", "perfil", "perfil profissional", "objetivo",
            "objetivo profissional", "summary", "about", "about me", "profile", "professional summary", "objective"
        ),
        Section.EXPERIENCE to setOf(
            "experiencia", "experiencias", "experiencia profissional", "experiencias profissionais", "historico profissional",
            "trajetoria profissional", "atuacao profissional", "experience", "work experience", "professional experience", "employment history"
        ),
        Section.EDUCATION to setOf(
            "formacao", "formacao academica", "formacao e cursos", "educacao", "escolaridade", "education", "academic background"
        ),
        Section.SKILLS to setOf(
            "competencias", "principais competencias", "competencias tecnicas", "habilidades", "habilidades tecnicas", "conhecimentos",
            "tecnologias", "skills", "top skills", "technical skills", "key skills"
        ),
        Section.LANGUAGES to setOf("idiomas", "linguas", "languages"),
        Section.CERTIFICATIONS to setOf(
            "certificacoes", "licencas e certificacoes", "cursos", "cursos e certificacoes", "certifications", "licenses & certifications", "courses"
        ),
        Section.CONTACT to setOf("contatar", "contato", "contatos", "contact", "dados pessoais")
    )

    private const val MONTH = "(?:janeiro|jan|fevereiro|fev|february|feb|marco|march|mar|abril|abr|april|apr|maio|mai|may|junho|june|jun|" +
        "julho|july|jul|agosto|august|ago|aug|setembro|september|sept|sep|set|outubro|october|out|oct|novembro|november|nov|" +
        "dezembro|december|dez|dec)"
    private const val TOKEN = "(?:\\b$MONTH\\.?\\s+(?:de\\s+)?)?\\b\\d{4}\\b|\\b\\d{1,2}/\\d{4}\\b"
    private val RANGE = Regex(
        "($TOKEN)\\s*(?:-|–|—|\\ba\\b|\\bate\\b|\\bto\\b|\\buntil\\b)\\s*($TOKEN|\\bpresente\\b|\\batual\\b|\\batualmente\\b|\\bhoje\\b|\\bpresent\\b|\\bcurrent\\b|\\bnow\\b|\\bo momento\\b)"
    )
    private val DURATION = Regex("^\\d+\\s*(anos?|years?|yrs?|mes(es)?|months?|mos?)(\\s+e?\\s*\\d+\\s*(anos?|years?|yrs?|mes(es)?|months?|mos?))?$")
    private val PAGE_FOOTER = Regex("(?i)^(page|p[aá]gina)\\s+\\d+\\s*(of|de|/)\\s*\\d+$")
    private val BULLET = Regex("^[•·▪●◦■□*\\-–—]\\s+")
    private val SCHOOL_WORDS = listOf(
        "universidade", "faculdade", "instituto", "centro universitario", "university", "college", "school", "escola",
        "senac", "senai", "fatec", "etec", "usp", "unicamp", "puc", "ufrj", "ufmg", "unesp", "uff", "ifsp", "fgv", "insper"
    )
    private val LOCATION_LINE = Regex("^\\p{L}[\\p{L} .'-]{1,40},\\s*(?:[A-Z]{2}(?:,\\s*(?:Brasil|Brazil))?|Brasil|Brazil)$")
    private val CURRENT_WORDS = setOf("presente", "atual", "atualmente", "hoje", "present", "current", "now", "o momento")
    private val KNOWN_LANGUAGES = setOf(
        "portugues", "ingles", "espanhol", "frances", "alemao", "italiano", "japones", "chines", "mandarim", "coreano", "russo",
        "arabe", "holandes", "libras", "portuguese", "english", "spanish", "french", "german", "italian", "japanese", "chinese",
        "korean", "russian", "arabic", "dutch"
    )

    private data class Dated(val index: Int, val range: DateRange, val leftover: String)
    private data class DateRange(val start: LocalDate?, val end: LocalDate?, val current: Boolean, val span: IntRange)
    private data class Header(val title: String, val company: String, val start: Int, val dateIndex: Int, val range: DateRange)

    fun parse(text: String): LinkedInData {
        val lines = clean(text)
        val sections = split(lines)
        return LinkedInData(
            positions = positions(sections[Section.EXPERIENCE].orEmpty()),
            educations = educations(sections[Section.EDUCATION].orEmpty()),
            skills = skills(sections[Section.SKILLS].orEmpty()),
            languages = languages(sections[Section.LANGUAGES].orEmpty()),
            profile = LinkedInProfile(summary(sections[Section.SUMMARY].orEmpty()), location(lines))
        )
    }

    private fun clean(text: String): List<String> =
        text.replace('\u00A0', ' ').replace("\r", "").lines()
            .map { line -> line.filter { it == '\t' || it >= ' ' }.replace('\t', ' ').trim() }
            .filter { it.isNotEmpty() && !PAGE_FOOTER.matches(it) }

    private fun fold(value: String): String =
        TextNormalizer.stripAccents(value).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9& ]"), " ").trim().replace(Regex("\\s+"), " ")

    private fun heading(line: String): Section? {
        if (line.length > 45) return null
        val key = fold(line)
        return HEADINGS.entries.firstOrNull { key in it.value }?.key
    }

    private fun split(lines: List<String>): Map<Section, List<String>> {
        val sections = LinkedHashMap<Section, MutableList<String>>()
        var current = Section.HEADER
        lines.forEach { line ->
            val next = heading(line)
            if (next != null) current = next else sections.getOrPut(current) { mutableListOf() }.add(line)
        }
        return sections
    }

    private fun unbullet(line: String): String = line.replace(BULLET, "").trim()

    private fun dateRange(line: String): DateRange? {
        val plain = TextNormalizer.stripAccents(line).lowercase(Locale.ROOT)
        val match = RANGE.find(plain) ?: return null
        val start = tokenDate(match.groupValues[1], isEnd = false) ?: return null
        val endText = match.groupValues[2].trim()
        val current = endText in CURRENT_WORDS
        val end = if (current) null else tokenDate(endText, isEnd = true)
        if (!current && end == null) return null
        return DateRange(start, end, current, match.range)
    }

    private fun tokenDate(token: String, isEnd: Boolean): LocalDate? {
        val text = token.trim()
        val date = LinkedInZipReader.parseDate(text) ?: return null
        return if (isEnd && text.matches(Regex("\\d{4}"))) LocalDate.of(date.year, 12, 1) else date
    }

    private fun isDuration(line: String): Boolean = DURATION.matches(fold(line))

    // O que sobra na linha da data, sem a data e sem a duração entre parênteses ("(3 anos 4 meses)").
    private fun leftover(line: String, range: DateRange): String {
        val rest = (line.take(range.span.first) + " " + line.drop(range.span.last + 1))
            .replace(Regex("\\(\\s*\\d+[^)]*\\)"), " ")
            .replace(Regex("[()]"), " ")
        return rest.trim().trim('·', '•', '|', '-', '–', '—', ',', ':', ' ').trim()
    }

    private fun looksLikeCompany(line: String): Boolean =
        line.length in 2..70 && !line.endsWith(".") && line.split(' ').size <= 9 && dateRange(line) == null && !isDuration(line)

    private fun looksLikeLocation(line: String): Boolean =
        line.length <= 60 && !line.endsWith(".") && dateRange(line) == null &&
            (LOCATION_LINE.matches(line) || Regex("(?i)\\b(brasil|brazil|remoto|remote|h[ií]brido|hybrid)\\b").containsMatchIn(line))

    private fun datedLines(lines: List<String>): List<Dated> =
        lines.mapIndexedNotNull { index, line -> dateRange(line)?.let { Dated(index, it, leftover(line, it)) } }

    private fun positions(lines: List<String>): List<LinkedInPosition> {
        val dated = datedLines(lines)
        val headers = mutableListOf<Header>()
        var groupCompany: String? = null
        var previousDate = -1
        dated.forEach { d ->
            val header = if (d.leftover.length >= 3) sameLineHeader(lines, d, previousDate, groupCompany) else ownLineHeader(lines, d, previousDate, groupCompany)
            if (header != null) {
                groupCompany = header.company
                headers += header
            }
            previousDate = d.index
        }
        return headers.mapIndexed { n, h ->
            val end = headers.getOrNull(n + 1)?.start ?: lines.size
            var body = lines.subList((h.dateIndex + 1).coerceAtMost(lines.size), end.coerceAtLeast(h.dateIndex + 1).coerceAtMost(lines.size)).toList()
            val location = body.firstOrNull()?.takeIf { looksLikeLocation(it) }
            if (location != null) body = body.drop(1)
            LinkedInPosition(
                title = h.title.take(150),
                company = h.company.take(150),
                location = location,
                description = body.joinToString("\n") { unbullet(it) }.trim().take(3000).ifBlank { null },
                startDate = h.range.start,
                endDate = h.range.end
            )
        }
    }

    // "Desenvolvedor Backend | Empresa XYZ | jan 2020 - presente": cargo e empresa na mesma linha da data.
    private fun sameLineHeader(lines: List<String>, d: Dated, previousDate: Int, groupCompany: String?): Header? {
        val parts = d.leftover.split(Regex("\\s+(?:-|–|—|@|at|na)\\s+|\\s*\\|\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        val title = parts.firstOrNull() ?: return null
        parts.getOrNull(1)?.let { return Header(title, it.substringBefore(" · "), d.index, d.index, d.range) }
        val before = lines.getOrNull(d.index - 1)?.takeIf { d.index - 1 > previousDate && looksLikeCompany(it) }
        return if (before != null) Header(title, before.substringBefore(" · "), d.index - 1, d.index, d.range)
        else groupCompany?.let { Header(title, it, d.index, d.index, d.range) }
    }

    // Formato do LinkedIn: empresa, cargo e depois a data (com a duração do grupo, quando há vários cargos na empresa).
    private fun ownLineHeader(lines: List<String>, d: Dated, previousDate: Int, groupCompany: String?): Header? {
        val titleLine = lines.getOrNull(d.index - 1)?.takeIf { d.index - 1 > previousDate && dateRange(it) == null && !isDuration(it) } ?: return null
        val above = lines.getOrNull(d.index - 2)?.takeIf { d.index - 2 > previousDate }
        val aboveThat = lines.getOrNull(d.index - 3)?.takeIf { d.index - 3 > previousDate }
        return when {
            above != null && isDuration(above) && aboveThat != null ->
                Header(titleLine, aboveThat.substringBefore(" · "), d.index - 3, d.index, d.range)
            above != null && looksLikeCompany(above) && (groupCompany == null || !looksLikeDescriptionTail(above)) ->
                Header(titleLine, above.substringBefore(" · "), d.index - 2, d.index, d.range)
            groupCompany != null -> Header(titleLine, groupCompany, d.index - 1, d.index, d.range)
            else -> null
        }
    }

    private fun looksLikeDescriptionTail(line: String): Boolean = line.length > 45 || line.endsWith(";") || line.endsWith(",")

    private fun hasSchoolWord(text: String): Boolean {
        val folded = fold(text)
        return SCHOOL_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(folded) }
    }

    private fun splitParts(text: String): List<String> =
        text.split(Regex("\\s+(?:\\||-|–|—)\\s+|\\s*\\|\\s*|\\s*·\\s*")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun educations(lines: List<String>): List<LinkedInEducation> {
        val dated = datedLines(lines)
        if (dated.isEmpty()) {
            return lines.mapIndexedNotNull { i, line ->
                if (!hasSchoolWord(line)) null
                else LinkedInEducation(line.take(150), lines.getOrNull(i + 1)?.takeUnless { hasSchoolWord(it) }, null, null, null)
            }
        }
        var previousDate = -1
        return dated.mapNotNull { d ->
            val candidates = splitParts(d.leftover).toMutableList()
            if (candidates.size < 2) lines.getOrNull(d.index - 1)?.takeIf { d.index - 1 > previousDate && dateRange(it) == null }?.let { candidates.add(0, it) }
            if (candidates.size < 2 && d.leftover.isBlank()) {
                lines.getOrNull(d.index - 2)?.takeIf { d.index - 2 > previousDate && dateRange(it) == null }?.let { candidates.add(0, it) }
            }
            previousDate = d.index
            val school = candidates.firstOrNull { hasSchoolWord(it) } ?: candidates.firstOrNull() ?: return@mapNotNull null
            val degree = candidates.firstOrNull { it != school }
            LinkedInEducation(school.take(150), degree, null, d.range.start, d.range.end)
        }
    }

    private fun skills(lines: List<String>): List<String> =
        lines.flatMap { unbullet(it).split(Regex("[,;•|·●▪]")) }
            .map { it.trim().trim('.', ' ') }
            .filter { it.length in 1..40 && it.count { c -> c == ' ' } <= 4 }
            .distinctBy { it.lowercase(Locale.ROOT) }

    private fun languages(lines: List<String>): List<LinkedInLanguage> =
        lines.flatMap { unbullet(it).split(Regex(",(?![^(]*\\))")) }.mapNotNull { raw ->
            val entry = raw.trim()
            val name = entry.substringBefore('(').substringBefore(" - ").substringBefore(" – ").substringBefore(':').trim()
            if (name.isBlank() || name.length > 30 || name.split(' ').size > 3) return@mapNotNull null
            val rest = entry.removePrefix(name).trim(' ', '(', ')', '-', '–', ':').ifBlank { null }
            // Sem nível e fora da lista de idiomas conhecidos, é outra coisa (nome, cidade) que caiu na seção.
            if (rest == null && fold(name) !in KNOWN_LANGUAGES) return@mapNotNull null
            LinkedInLanguage(name, rest, proficiency(rest))
        }

    private fun proficiency(raw: String?): ProficiencyLevel? {
        val text = fold(raw.orEmpty())
        return when {
            text.isEmpty() -> null
            "nativ" in text || "bilingu" in text || "native" in text || "bilingual" in text -> ProficiencyLevel.NATIVE
            "intermediario avancado" in text || "upper" in text -> ProficiencyLevel.B2
            "fluente" in text || "fluent" in text || "avancado" in text || "advanced" in text -> ProficiencyLevel.C1
            "intermediario" in text || "intermediate" in text -> ProficiencyLevel.B1
            "iniciante" in text || "beginner" in text -> ProficiencyLevel.A2
            else -> LinkedInZipReader.proficiencyLevel(raw)
        }
    }

    private fun summary(lines: List<String>): String? =
        lines.joinToString(" ") { unbullet(it) }.trim().take(500).ifBlank { null }

    private fun location(lines: List<String>): String? = lines.take(30).firstOrNull { LOCATION_LINE.matches(it) }
}
