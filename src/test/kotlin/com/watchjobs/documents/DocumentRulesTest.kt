package com.watchjobs.documents

import com.watchjobs.profile.DataSource
import com.watchjobs.profile.ExperienceResponse
import com.watchjobs.profile.ExperienceType
import com.watchjobs.profile.PersonalResponse
import com.watchjobs.profile.ProfileResponse
import com.watchjobs.profile.SkillLevel
import com.watchjobs.profile.UserSkillResponse
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// F10: contexto, regras do prompt, validação anti-invenção e exportação (sem banco e sem IA).
class DocumentRulesTest {
    private fun profile(description: String = "APIs em Kotlin e Spring Boot. Reduzi a latência em 40%.") = ProfileResponse(
        personal = PersonalResponse("Ana", "Silva", "ana@example.com", LocalDate.of(1995, 1, 1), null, null, "Engenheira backend.", null),
        experiences = listOf(
            ExperienceResponse(1, "Engenheira de Software", 1, "ACME", ExperienceType.EMPLOYMENT, description,
                LocalDate.of(2020, 1, 1), null, true, "BACKEND", null, DataSource.MANUAL)
        ),
        educations = emptyList(),
        skills = listOf(UserSkillResponse(1, 10, "Kotlin", SkillLevel.ADVANCED, null, DataSource.MANUAL)),
        languages = emptyList(),
        preferences = null,
        summary = null
    )

    @Test
    fun `base resume uses only profile data in the shared text format`() {
        val text = DocumentFormat.baseResume(profile())
        assertTrue(text.startsWith("# Ana Silva"))
        assertTrue("## Experiência profissional" in text)
        assertTrue("### Engenheira de Software — ACME (01/2020 – atual)" in text)
        assertTrue("Kotlin" in text)
    }

    @Test
    fun `context budget shortens long descriptions only for the prompt`() {
        val long = "Descrição muito longa de responsabilidades. ".repeat(1500)
        val text = ContextBudget.profileText(profile(long))
        assertTrue(text.length <= ContextBudget.PROFILE_CHARS)
        assertTrue("[…]" in text)
    }

    @Test
    fun `prompt forbids invented facts and carries the job`() {
        val job = JobContext(1, "Backend Kotlin", "Globex", "São Paulo", "REMOTE", "SENIOR", "Construir APIs.", "skills: [Kotlin, AWS]")
        val system = PromptBuilder.system(DocumentType.RESUME)
        assertTrue("Não invente" in system)
        val user = PromptBuilder.user(DocumentType.COVER_LETTER, "# Ana", ContextBudget.jobText(job), "Destaque liderança")
        assertTrue("Globex" in user && "Construir APIs." in user && "Destaque liderança" in user)
    }

    @Test
    fun `validator flags technologies and numbers missing from the profile`() {
        val source = DocumentFormat.baseResume(profile())
        val output = "Experiência com Kotlin, AWS e Go. Reduzi a latência em 40% e os custos em 25%."
        val warnings = DocumentValidator.warnings(output, source, listOf("Kotlin", "AWS", "Go", "Java"))
        assertEquals(2, warnings.size)
        assertTrue("AWS" in warnings[0] && "Go" in warnings[0] && "Kotlin" !in warnings[0])
        assertTrue("25%" in warnings[1] && "40%" !in warnings[1])
        assertTrue(DocumentValidator.warnings("Experiência com Kotlin.", source, listOf("Kotlin", "go")).isEmpty())
    }

    @Test
    fun `validator strips code fences`() {
        assertEquals("# Título\nTexto", DocumentValidator.clean("```markdown\n# Título\nTexto\n```"))
    }

    private val sample = """
        # Ana Silva
        ana@example.com · São Paulo
        ## Experiência profissional
        ### Engenheira de Software — ACME (01/2020 – atual)
        - Construí APIs em **Kotlin** → produção
        Parágrafo com acentuação: ação, coração, você.
    """.trimIndent()

    @Test
    fun `pdf export keeps the text`() {
        val bytes = DocumentExporter.export(sample, ExportFormat.PDF)
        val text = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }
        assertTrue("Ana Silva" in text)
        assertTrue("EXPERIÊNCIA PROFISSIONAL" in text)
        assertTrue("ação, coração, você" in text)
        assertTrue("Construí APIs em Kotlin -> produção" in text)
    }

    @Test
    fun `docx export is a valid package with the text`() {
        val bytes = DocumentExporter.export(sample + "\nA & B <c>", ExportFormat.DOCX)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), entries.keys)
        val xml = entries.getValue("word/document.xml")
        assertTrue("Ana Silva" in xml && "coração" in xml && "A &amp; B &lt;c&gt;" in xml)
    }

    @Test
    fun `blocks classify the text format`() {
        val blocks = DocumentExporter.blocks("# T\n## S\n### Sub\n- item\n\nTexto")
        assertEquals(
            listOf(Block.Title("T"), Block.Heading("S"), Block.Subheading("Sub"), Block.Bullet("item"), Block.Spacer, Block.Paragraph("Texto")),
            blocks
        )
    }
}
