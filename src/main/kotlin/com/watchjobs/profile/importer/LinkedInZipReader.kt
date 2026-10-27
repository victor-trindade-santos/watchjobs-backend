package com.watchjobs.profile.importer

import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.exception.BusinessException
import com.watchjobs.profile.ProficiencyLevel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class LinkedInPosition(
    val title: String,
    val company: String,
    val location: String?,
    val description: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?
)

data class LinkedInEducation(
    val school: String,
    val degree: String?,
    val notes: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?
)

data class LinkedInLanguage(val name: String, val proficiency: String?, val level: ProficiencyLevel?)

data class LinkedInProfile(val headline: String?, val location: String?)

data class LinkedInData(
    val positions: List<LinkedInPosition>,
    val educations: List<LinkedInEducation>,
    val skills: List<String>,
    val languages: List<LinkedInLanguage>,
    val profile: LinkedInProfile?
)

// Lê o ZIP de "Obter uma cópia dos seus dados" do LinkedIn em memória, com limites contra "ZIP bomba".
// Só os CSVs esperados são lidos; as colunas são achadas pelo cabeçalho (inglês ou português).
class LinkedInZipReader(
    private val maxEntries: Int = 50,
    private val maxUncompressedBytes: Long = 50L * 1024 * 1024
) {
    fun read(bytes: ByteArray): LinkedInData {
        val files = unzip(bytes)
        val positionList = files.pick(POSITIONS)?.let { positions(it) }.orEmpty()
        val educationList = files.pick(EDUCATION)?.let { educations(it) }.orEmpty()
        val skillList = files.pick(SKILLS)?.let { skills(it) }.orEmpty()
        val languageList = files.pick(LANGUAGES)?.let { languages(it) }.orEmpty()
        val profileRow = files.pick(PROFILE)?.let { profile(it) }
        if (positionList.isEmpty() && educationList.isEmpty() && skillList.isEmpty() && languageList.isEmpty()) {
            throw BusinessException(
                "O arquivo não parece ser a exportação do LinkedIn: não encontramos Positions.csv, Education.csv, Skills.csv nem Languages.csv"
            )
        }
        return LinkedInData(positionList, educationList, skillList, languageList, profileRow)
    }

    private fun Map<String, String>.pick(names: Set<String>): String? = entries.firstOrNull { it.key in names }?.value

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = mutableMapOf<String, String>()
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > maxEntries) throw BusinessException("O ZIP tem arquivos demais (máximo $maxEntries)")
                    if (entry.isDirectory) continue
                    val name = fileKey(entry.name)
                    val wanted = name in ALL_NAMES
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        total += read
                        if (total > maxUncompressedBytes) throw BusinessException("O ZIP descompactado passa de 50 MB")
                        if (wanted) buffer.write(chunk, 0, read)
                    }
                    if (wanted) files[name] = buffer.toString(Charsets.UTF_8)
                }
            }
        } catch (ex: ZipException) {
            throw BusinessException("O arquivo enviado não é um ZIP válido")
        }
        if (entries == 0) throw BusinessException("O arquivo enviado não é um ZIP válido")
        return files
    }

    private fun table(csv: String, required: Set<String>): List<Map<String, String>> {
        val rows = CsvParser.parse(csv)
        // Alguns CSVs do LinkedIn têm notas antes do cabeçalho: o cabeçalho é a primeira linha com uma coluna esperada.
        val headerIndex = rows.indexOfFirst { row -> row.any { header(it) in required } }
        if (headerIndex < 0) return emptyList()
        val headers = rows[headerIndex].map(::header)
        return rows.drop(headerIndex + 1).map { row ->
            headers.mapIndexedNotNull { i, h -> row.getOrNull(i)?.let { h to it.trim() } }.toMap()
        }
    }

    private fun Map<String, String>.value(aliases: Set<String>): String? =
        aliases.firstNotNullOfOrNull { this[it]?.takeIf { v -> v.isNotBlank() } }

    private fun positions(csv: String): List<LinkedInPosition> =
        table(csv, POSITION_TITLE + POSITION_COMPANY).mapNotNull { row ->
            val title = row.value(POSITION_TITLE) ?: return@mapNotNull null
            val company = row.value(POSITION_COMPANY) ?: return@mapNotNull null
            LinkedInPosition(
                title = title.take(150),
                company = company.take(150),
                location = row.value(LOCATION),
                description = row.value(DESCRIPTION),
                startDate = parseDate(row.value(START)),
                endDate = parseDate(row.value(END))
            )
        }

    private fun educations(csv: String): List<LinkedInEducation> =
        table(csv, SCHOOL).mapNotNull { row ->
            val school = row.value(SCHOOL) ?: return@mapNotNull null
            LinkedInEducation(
                school = school.take(150),
                degree = row.value(DEGREE),
                notes = row.value(NOTES),
                startDate = parseDate(row.value(START)),
                endDate = parseDate(row.value(END))
            )
        }

    private fun skills(csv: String): List<String> =
        table(csv, NAME).mapNotNull { it.value(NAME)?.take(100) }.distinctBy { it.lowercase() }

    private fun languages(csv: String): List<LinkedInLanguage> =
        table(csv, NAME).mapNotNull { row ->
            val name = row.value(NAME) ?: return@mapNotNull null
            val proficiency = row.value(PROFICIENCY)
            LinkedInLanguage(name, proficiency, proficiencyLevel(proficiency))
        }

    private fun profile(csv: String): LinkedInProfile? =
        table(csv, HEADLINE + GEO).firstOrNull()?.let { LinkedInProfile(it.value(HEADLINE), it.value(GEO)) }

    companion object {
        private val POSITIONS = setOf("positions.csv", "posicoes.csv", "cargos.csv")
        private val EDUCATION = setOf("education.csv", "educacao.csv", "formacao academica.csv", "formacao.csv")
        private val SKILLS = setOf("skills.csv", "competencias.csv")
        private val LANGUAGES = setOf("languages.csv", "idiomas.csv")
        private val PROFILE = setOf("profile.csv", "perfil.csv")
        private val ALL_NAMES = POSITIONS + EDUCATION + SKILLS + LANGUAGES + PROFILE

        private val POSITION_TITLE = setOf("title", "cargo", "titulo")
        private val POSITION_COMPANY = setOf("company name", "nome da empresa", "empresa")
        private val LOCATION = setOf("location", "localidade", "local", "localizacao")
        private val DESCRIPTION = setOf("description", "descricao")
        private val START = setOf("started on", "start date", "data de inicio", "inicio", "iniciado em")
        private val END = setOf("finished on", "end date", "data de termino", "termino", "data de conclusao", "concluido em")
        private val SCHOOL = setOf("school name", "nome da instituicao", "instituicao de ensino", "instituicao", "escola")
        private val DEGREE = setOf("degree name", "nome do diploma", "diploma", "grau")
        private val NOTES = setOf("notes", "observacoes", "notas")
        private val NAME = setOf("name", "nome", "competencia", "idioma")
        private val PROFICIENCY = setOf("proficiency", "proficiencia", "nivel")
        private val HEADLINE = setOf("headline", "titulo")
        private val GEO = setOf("geo location", "localizacao geografica", "location", "localidade")

        private val MONTHS = mapOf(
            "jan" to 1, "feb" to 2, "fev" to 2, "mar" to 3, "apr" to 4, "abr" to 4, "may" to 5, "mai" to 5,
            "jun" to 6, "jul" to 7, "aug" to 8, "ago" to 8, "sep" to 9, "set" to 9, "oct" to 10, "out" to 10,
            "nov" to 11, "dec" to 12, "dez" to 12
        )
        private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
        private val DAY_MONTH_YEAR = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$")
        private val MONTH_YEAR = Regex("^(\\d{1,2})/(\\d{4})$")
        private val ISO = Regex("^(\\d{4})-(\\d{2})(-(\\d{2}))?$")

        fun fileKey(path: String): String =
            TextNormalizer.stripAccents(path.substringAfterLast('/').substringAfterLast('\\')).lowercase().trim()

        fun header(value: String): String = TextNormalizer.forMatching(value)

        // "Mar 2021", "mar. de 2021", "2021", "03/2021", "2021-03", "15/03/2021" (dia 1 do mês quando não há dia).
        fun parseDate(raw: String?): LocalDate? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            DAY_MONTH_YEAR.matchEntire(text)?.let { m ->
                return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
            }
            MONTH_YEAR.matchEntire(text)?.let { m ->
                return runCatching { LocalDate.of(m.groupValues[2].toInt(), m.groupValues[1].toInt(), 1) }.getOrNull()
            }
            ISO.matchEntire(text)?.let { m ->
                val day = m.groupValues[4].ifEmpty { "1" }.toInt()
                return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), day) }.getOrNull()
            }
            val year = YEAR.find(text)?.value?.toInt() ?: return null
            val normalized = TextNormalizer.forMatching(text)
            val month = normalized.split(' ').firstNotNullOfOrNull { MONTHS[it.take(3)] } ?: 1
            return LocalDate.of(year, month, 1)
        }

        fun proficiencyLevel(raw: String?): ProficiencyLevel? {
            val text = TextNormalizer.forMatching(raw)
            return when {
                text.isEmpty() -> null
                "native" in text || "bilingual" in text || "nativ" in text || "bilingue" in text -> ProficiencyLevel.NATIVE
                "limited" in text || "limitada" in text -> ProficiencyLevel.B1
                "full professional" in text || "completa" in text -> ProficiencyLevel.C1
                "professional" in text || "profissional" in text -> ProficiencyLevel.B2
                "elementary" in text || "elementar" in text || "basic" in text || "basico" in text -> ProficiencyLevel.A2
                else -> null
            }
        }
    }
}
