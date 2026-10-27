package com.watchjobs.profile

import com.watchjobs.profile.importer.ResumeParser
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Leitura do texto do currículo (sem banco): o PDF do LinkedIn e um currículo comum.
class ResumeParserTest {
    private val linkedIn = """
        Contatar
        www.linkedin.com/in/ana-silva (LinkedIn)
        Principais competências
        Kotlin
        Spring Boot
        PostgreSQL
        Languages
        Português (Native or Bilingual)
        Inglês (Full Professional)
        Ana Silva
        Engenheira de Software na Nubank
        São Paulo, SP
        Resumo
        Engenheira backend com foco em APIs e dados.
        Experiência
        Nubank
        Engenheira de Software Sênior
        janeiro de 2021 - presente (3 anos 10 meses)
        São Paulo, Brasil
        Desenvolvimento de APIs em Kotlin e Spring Boot.
        Migração para microsserviços.
        VTEX
        Desenvolvedora Pleno
        março de 2018 - dezembro de 2020 (2 anos 10 meses)
        Rio de Janeiro, Brasil
        Backend em Java.
        Formação acadêmica
        Universidade de São Paulo
        Bacharelado, Ciência da Computação · (2013 - 2017)
        Page 1 of 2
    """.trimIndent()

    @Test
    fun `reads the LinkedIn PDF layout`() {
        val data = ResumeParser.parse(linkedIn)

        assertEquals(2, data.positions.size)
        val current = data.positions[0]
        assertEquals("Nubank", current.company)
        assertEquals("Engenheira de Software Sênior", current.title)
        assertEquals(LocalDate.of(2021, 1, 1), current.startDate)
        assertNull(current.endDate)
        assertEquals("São Paulo, Brasil", current.location)
        assertTrue(current.description.orEmpty().contains("Kotlin"))
        val previous = data.positions[1]
        assertEquals("VTEX", previous.company)
        assertEquals("Desenvolvedora Pleno", previous.title)
        assertEquals(LocalDate.of(2018, 3, 1), previous.startDate)
        assertEquals(LocalDate.of(2020, 12, 1), previous.endDate)

        val education = data.educations.single()
        assertEquals("Universidade de São Paulo", education.school)
        assertEquals("Bacharelado, Ciência da Computação", education.degree)
        assertEquals(LocalDate.of(2013, 1, 1), education.startDate)

        assertEquals(listOf("Kotlin", "Spring Boot", "PostgreSQL"), data.skills)
        assertEquals(listOf("Português", "Inglês"), data.languages.map { it.name })
        assertEquals(ProficiencyLevel.NATIVE, data.languages[0].level)
        assertEquals(ProficiencyLevel.C1, data.languages[1].level)
        assertEquals("Engenheira backend com foco em APIs e dados.", data.profile?.headline)
        assertEquals("São Paulo, SP", data.profile?.location)
    }

    @Test
    fun `reads a plain resume with the title and company on the date line`() {
        val data = ResumeParser.parse(
            """
            John Doe
            Curitiba, PR
            SUMMARY
            Backend developer.
            WORK EXPERIENCE
            Senior Backend Developer | Acme Corp | Jan 2020 - Present
            Built payment APIs in Kotlin.
            Backend Developer - Globex - 03/2017 - 12/2019
            Maintained Java services.
            EDUCATION
            BSc Computer Science - Universidade Federal do Paraná (2012 - 2016)
            SKILLS
            Kotlin, Java, PostgreSQL
            LANGUAGES
            English - Advanced, Portuguese - Native
            """.trimIndent()
        )

        assertEquals(listOf("Acme Corp", "Globex"), data.positions.map { it.company })
        assertEquals(listOf("Senior Backend Developer", "Backend Developer"), data.positions.map { it.title })
        assertNull(data.positions[0].endDate)
        assertEquals(LocalDate.of(2017, 3, 1), data.positions[1].startDate)
        assertEquals(LocalDate.of(2019, 12, 1), data.positions[1].endDate)
        assertEquals("Built payment APIs in Kotlin.", data.positions[0].description)

        val education = data.educations.single()
        assertEquals("Universidade Federal do Paraná", education.school)
        assertEquals("BSc Computer Science", education.degree)

        assertEquals(listOf("Kotlin", "Java", "PostgreSQL"), data.skills)
        assertEquals(ProficiencyLevel.C1, data.languages[0].level)
        assertEquals(ProficiencyLevel.NATIVE, data.languages[1].level)
        assertEquals("Curitiba, PR", data.profile?.location)
    }

    @Test
    fun `text without sections gives nothing instead of guessing`() {
        val data = ResumeParser.parse("Lorem ipsum dolor sit amet\nconsectetur adipiscing elit")
        assertTrue(data.positions.isEmpty() && data.educations.isEmpty() && data.skills.isEmpty() && data.languages.isEmpty())
    }
}
