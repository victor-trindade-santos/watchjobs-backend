package com.watchjobs.profile

import com.watchjobs.exception.BusinessException
import com.watchjobs.profile.importer.LinkedInZipReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

// F3-09: leitura do ZIP do LinkedIn em memória (sem banco).
class LinkedInZipReaderTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `reads positions, education, skills and languages`() {
        val data = LinkedInZipReader().read(
            zip(
                "Positions.csv" to "Company Name,Title,Description,Location,Started On,Finished On\n" +
                    "Nubank,Desenvolvedora Sênior,\"Kotlin, Java\ne \"\"microsserviços\"\"\",\"São Paulo, Brasil\",Mar 2021,\n" +
                    "VTEX,Desenvolvedor Pleno,,Rio de Janeiro,Jan 2018,Feb 2021\n",
                "Education.csv" to "School Name,Start Date,End Date,Notes,Degree Name,Activities\nUSP,2013,2017,,\"Bacharelado, Ciência da Computação\",\n",
                "Skills.csv" to "Name\nKotlin\nNodeJS\nComunicação\n",
                "Languages.csv" to "Name,Proficiency\nEnglish,Full professional proficiency\nPortuguese,Native or bilingual proficiency\n",
                "Profile.csv" to "First Name,Last Name,Headline,Geo Location\nAna,Silva,Backend Engineer,São Paulo\n",
                "Connections.csv" to "Notes:\nignored\n"
            )
        )
        assertEquals(2, data.positions.size)
        val current = data.positions.first()
        assertEquals("Nubank", current.company)
        assertEquals(LocalDate.of(2021, 3, 1), current.startDate)
        assertNull(current.endDate)
        assertEquals("Kotlin, Java\ne \"microsserviços\"", current.description)
        assertEquals(LocalDate.of(2021, 2, 1), data.positions[1].endDate)
        assertEquals(LocalDate.of(2013, 1, 1), data.educations.single().startDate)
        assertEquals(listOf("Kotlin", "NodeJS", "Comunicação"), data.skills)
        assertEquals(ProficiencyLevel.C1, data.languages[0].level)
        assertEquals(ProficiencyLevel.NATIVE, data.languages[1].level)
        assertEquals("Backend Engineer", data.profile?.headline)
    }

    @Test
    fun `accepts portuguese headers and files inside a folder`() {
        val data = LinkedInZipReader().read(
            zip("export/Positions.csv" to "Nome da empresa,Cargo,Descrição,Localidade,Data de início,Data de término\nStone,Engenheiro de Dados,,,fev. de 2020,dez. de 2022\n")
        )
        val position = data.positions.single()
        assertEquals(LocalDate.of(2020, 2, 1), position.startDate)
        assertEquals(LocalDate.of(2022, 12, 1), position.endDate)
    }

    @Test
    fun `zip without expected files is a clear error`() {
        val ex = assertThrows<BusinessException> { LinkedInZipReader().read(zip("foto.jpg" to "x")) }
        assertEquals(true, ex.message?.contains("Positions.csv"))
    }

    @Test
    fun `not a zip is a clear error`() {
        assertThrows<BusinessException> { LinkedInZipReader().read("não é zip".toByteArray()) }
    }

    @Test
    fun `too many entries is refused`() {
        val files = (1..60).map { "f$it.txt" to "x" }.toTypedArray()
        assertThrows<BusinessException> { LinkedInZipReader().read(zip(*files)) }
    }

    @Test
    fun `date formats`() {
        assertEquals(LocalDate.of(2021, 3, 1), LinkedInZipReader.parseDate("Mar 2021"))
        assertEquals(LocalDate.of(2021, 1, 1), LinkedInZipReader.parseDate("2021"))
        assertEquals(LocalDate.of(2021, 5, 1), LinkedInZipReader.parseDate("05/2021"))
        assertEquals(LocalDate.of(2021, 5, 1), LinkedInZipReader.parseDate("2021-05"))
        assertEquals(LocalDate.of(2021, 5, 15), LinkedInZipReader.parseDate("15/05/2021"))
        assertNull(LinkedInZipReader.parseDate(""))
    }
}
