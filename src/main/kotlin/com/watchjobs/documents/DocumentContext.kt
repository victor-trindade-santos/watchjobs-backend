package com.watchjobs.documents

import com.watchjobs.profile.ProfileResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DocumentType { RESUME, COVER_LETTER }

// Dados da vaga salva usados na geração (cópia guardada em core.saved_job no momento em que foi salva).
data class JobContext(
    val savedJobId: Long,
    val positionRole: String,
    val organizationName: String,
    val location: String?,
    val workMode: String?,
    val experienceLevel: String?,
    val description: String?,
    val requirements: String?
)

// Formato de texto dos documentos, entendido pelo editor e pelos exportadores (PDF/DOCX):
// "# " título, "## " seção, "### " subtítulo, "- " tópico, linha em branco separa parágrafos.
object DocumentFormat {
    private val MONTH = DateTimeFormatter.ofPattern("MM/yyyy", Locale.ROOT)

    fun period(start: LocalDate?, end: LocalDate?, current: Boolean): String =
        listOfNotNull(start?.format(MONTH), if (current || end == null) "atual" else end.format(MONTH)).joinToString(" – ")

    // Currículo base: o perfil cadastrado, sem IA. Serve de fonte para a geração e pode ser exportado à parte.
    // descriptionLimit encurta descrições muito longas só no contexto enviado à IA (nunca no perfil).
    fun baseResume(profile: ProfileResponse, descriptionLimit: Int = Int.MAX_VALUE): String = buildString {
        val p = profile.personal
        appendLine("# ${p.firstName} ${p.lastName}")
        appendLine(listOfNotNull(p.email, p.location?.let { listOfNotNull(it.city, it.state, it.countryCode).joinToString(", ") }).joinToString(" · "))
        p.objective?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("## Resumo profissional")
            appendLine(it.trim())
        }
        if (profile.experiences.isNotEmpty()) {
            appendLine()
            appendLine("## Experiência profissional")
            profile.experiences.forEach { e ->
                appendLine("### ${e.jobTitle} — ${e.organizationName} (${period(e.startDate, e.endDate, e.current)})")
                e.description?.takeIf { it.isNotBlank() }?.let { appendLine(shorten(it.trim(), descriptionLimit)) }
                appendLine()
            }
        }
        if (profile.educations.isNotEmpty()) {
            appendLine("## Formação acadêmica")
            profile.educations.forEach { ed ->
                appendLine("- ${ed.educationLevel} em ${ed.fieldOfStudy} · ${ed.institution} (${period(ed.startDate, ed.endDate, ed.current)})")
            }
            appendLine()
        }
        if (profile.skills.isNotEmpty()) {
            appendLine("## Habilidades")
            appendLine(profile.skills.joinToString(", ") { s -> s.skillName + (s.yearsExperience?.let { " (${it.stripTrailingZeros().toPlainString()} anos)" } ?: "") })
            appendLine()
        }
        if (profile.languages.isNotEmpty()) {
            appendLine("## Idiomas")
            profile.languages.forEach { appendLine("- ${it.languageName} (${it.proficiencyLevel})") }
        }
    }.trim()

    private fun shorten(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit).substringBeforeLast(' ') + " […]"
}

// Limites de contexto: o perfil e a vaga são encurtados (nunca o perfil salvo) até caber no orçamento.
object ContextBudget {
    const val PROFILE_CHARS = 24_000
    const val JOB_CHARS = 8_000
    private val DESCRIPTION_STEPS = listOf(Int.MAX_VALUE, 3000, 1500, 800, 400)

    fun profileText(profile: ProfileResponse): String {
        for (limit in DESCRIPTION_STEPS) {
            val text = DocumentFormat.baseResume(profile, limit)
            if (text.length <= PROFILE_CHARS) return text
        }
        return DocumentFormat.baseResume(profile, DESCRIPTION_STEPS.last()).take(PROFILE_CHARS)
    }

    fun jobText(job: JobContext): String = buildString {
        appendLine("Cargo: ${job.positionRole}")
        appendLine("Empresa: ${job.organizationName}")
        job.location?.let { appendLine("Local: $it") }
        job.workMode?.let { appendLine("Modelo de trabalho: $it") }
        job.experienceLevel?.let { appendLine("Nível: $it") }
        job.requirements?.takeIf { it.isNotBlank() }?.let { appendLine("Requisitos e tecnologias: ${it.take(2000)}") }
        job.description?.takeIf { it.isNotBlank() }?.let {
            appendLine("Descrição da vaga:")
            appendLine(it.take(JOB_CHARS))
        }
    }.trim()
}

object PromptBuilder {
    private const val RULES = """
REGRAS OBRIGATÓRIAS:
1. Use SOMENTE fatos presentes no PERFIL DO CANDIDATO. Não invente experiências, empresas, cargos, datas, formações,
   certificações, tecnologias, ferramentas, idiomas, números ou resultados que não estejam lá.
2. Se a vaga pede algo que o candidato não tem, NÃO afirme que ele tem. Pode omitir ou, na carta, mencionar
   interesse em aprender, sem exagero.
3. Pode reordenar, resumir, reescrever com clareza e destacar o que for mais relevante para a vaga.
4. Escreva em português do Brasil, tom profissional e natural, sem clichês, sem frases genéricas repetidas.
5. Responda apenas com o documento, sem comentários, sem blocos de código.
6. Formato: "# " para o título, "## " para seções, "### " para subtítulos, "- " para tópicos, linha em branco entre parágrafos."""

    fun system(type: DocumentType): String = when (type) {
        DocumentType.RESUME ->
            "Você é um especialista em currículos que adapta o currículo de um candidato para uma vaga específica.\n$RULES\n" +
                "Estrutura: nome e contato; resumo profissional voltado à vaga; experiências (as mais relevantes primeiro, " +
                "com tópicos que destaquem responsabilidades e tecnologias pedidas pela vaga e presentes no perfil); formação; " +
                "habilidades (as pedidas pela vaga primeiro, somente as que o candidato tem); idiomas."
        DocumentType.COVER_LETTER ->
            "Você escreve cartas de apresentação objetivas e específicas para cada vaga.\n$RULES\n" +
                "Estrutura: saudação; 3 a 5 parágrafos curtos conectando experiências e habilidades reais do candidato " +
                "às responsabilidades e requisitos da vaga e à empresa; fechamento cordial com o nome do candidato. " +
                "No máximo 400 palavras. Use \"# \" apenas para a linha de título (ex.: \"# Carta de apresentação — Cargo\")."
    }

    fun user(type: DocumentType, profileText: String, jobText: String, instructions: String?): String = buildString {
        appendLine(if (type == DocumentType.RESUME) "Adapte o currículo abaixo para a vaga." else "Escreva a carta de apresentação para a vaga.")
        appendLine()
        appendLine("=== VAGA ===")
        appendLine(jobText)
        appendLine()
        appendLine("=== PERFIL DO CANDIDATO (única fonte de fatos) ===")
        appendLine(profileText)
        instructions?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("=== PEDIDO DO CANDIDATO (siga, desde que não contrarie as regras) ===")
            appendLine(it.trim())
        }
    }

    fun maxTokens(type: DocumentType): Int = if (type == DocumentType.RESUME) 2200 else 900
}

// Validação depois da geração: aponta tecnologias e números que não aparecem no perfil, para o usuário revisar.
object DocumentValidator {
    private val PERCENT = Regex("\\d+(?:[.,]\\d+)?\\s?%")
    private val FENCE = Regex("^```[a-zA-Z]*\\s*|\\s*```$")

    fun clean(raw: String): String = raw.trim().replace(FENCE, "").trim().take(MAX_CONTENT)

    fun warnings(output: String, source: String, catalogSkills: Collection<String>): List<String> {
        val warnings = mutableListOf<String>()
        val missingSkills = catalogSkills
            .filter { it.length >= 2 }
            .filter { mentions(output, it) && !mentions(source, it) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sorted()
        if (missingSkills.isNotEmpty()) {
            warnings += "Tecnologias citadas que não estão no seu perfil: ${missingSkills.take(15).joinToString(", ")}. Revise antes de usar."
        }
        val numbers = PERCENT.findAll(output).map { it.value.replace(" ", "") }.toSet()
            .filter { it !in source.replace(" %", "%") }
        if (numbers.isNotEmpty()) {
            warnings += "Resultados numéricos que não estão no seu perfil: ${numbers.joinToString(", ")}. Confirme se são reais."
        }
        return warnings
    }

    // Palavra inteira; nomes curtos (até 3 letras, como "Go" ou "C#") exigem a mesma grafia.
    fun mentions(text: String, skill: String): Boolean {
        val pattern = "(?<![\\p{L}\\p{N}])${Regex.escape(skill)}(?![\\p{L}\\p{N}+#])"
        val regex = if (skill.length <= 3) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(text)
    }

    const val MAX_CONTENT = 60_000
}
