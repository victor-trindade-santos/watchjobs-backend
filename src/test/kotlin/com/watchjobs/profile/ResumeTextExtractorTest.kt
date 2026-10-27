package com.watchjobs.profile

import com.watchjobs.exception.BusinessException
import com.watchjobs.profile.importer.ResumeTextExtractor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Texto do currículo em memória: PDF, DOCX e TXT, sem banco.
class ResumeTextExtractorTest {
    private fun pdf(vararg lines: String): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.setLeading(16f)
                stream.newLineAtOffset(50f, 700f)
                lines.forEach { stream.showText(it); stream.newLine() }
                stream.endText()
            }
            document.save(out)
        }
        return out.toByteArray()
    }

    private fun docx(xml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `reads a PDF keeping the line order`() {
        val text = ResumeTextExtractor.extract(pdf("Experience", "Acme Corp", "Jan 2020 - Present"))
        assertEquals(listOf("Experience", "Acme Corp", "Jan 2020 - Present"), text.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test
    fun `reads a DOCX turning paragraphs into lines`() {
        val text = ResumeTextExtractor.extract(
            docx("<w:document><w:body><w:p><w:r><w:t>Skills</w:t></w:r></w:p><w:p><w:r><w:t>Kotlin &amp; Java</w:t></w:r></w:p></w:body></w:document>")
        )
        assertEquals(listOf("Skills", "Kotlin & Java"), text.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test
    fun `reads plain text in UTF-8 and Latin-1`() {
        assertEquals("Formação", ResumeTextExtractor.extract("Formação".toByteArray(Charsets.UTF_8)).trim())
        assertEquals("Formação", ResumeTextExtractor.extract("Formação".toByteArray(Charsets.ISO_8859_1)).trim())
    }

    @Test
    fun `rejects unsupported and empty content with a clear message`() {
        assertThrows<BusinessException> { ResumeTextExtractor.extract(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 1, 2)) }
        val empty = assertThrows<BusinessException> { ResumeTextExtractor.extract("   \n  ".toByteArray()) }
        assertTrue(empty.message.orEmpty().contains("texto"))
        assertThrows<BusinessException> { ResumeTextExtractor.extract(docx("<w:document/>").copyOf(20)) }
        assertThrows<BusinessException> { ResumeTextExtractor.extract("%PDF-1.4 não é um pdf de verdade".toByteArray()) }
    }
}
