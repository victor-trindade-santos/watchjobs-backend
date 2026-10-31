package com.watchjobs.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
}

// Uma linha do documento já classificada pelo formato de texto (ver DocumentFormat).
sealed interface Block {
    data class Title(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Subheading(val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data object Spacer : Block
}

object DocumentExporter {

    fun blocks(content: String): List<Block> = content.replace("\r", "").lines().map { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> Block.Spacer
            line.startsWith("### ") -> Block.Subheading(clean(line.removePrefix("### ")))
            line.startsWith("## ") -> Block.Heading(clean(line.removePrefix("## ")))
            line.startsWith("# ") -> Block.Title(clean(line.removePrefix("# ")))
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("• ") || line.trimStart().startsWith("* ") ->
                Block.Bullet(clean(line.trimStart().drop(2)))
            else -> Block.Paragraph(clean(line))
        }
    }

    // Tira marcações de ênfase do markdown que a IA às vezes usa (**negrito**, _itálico_).
    private fun clean(text: String): String = text.replace("**", "").replace("__", "").trim()

    fun export(content: String, format: ExportFormat): ByteArray = when (format) {
        ExportFormat.PDF -> pdf(blocks(content))
        ExportFormat.DOCX -> docx(blocks(content))
    }

    // --- PDF (PDFBox, fontes padrão com WinAnsi: cobre os acentos do português) ---

    private const val MARGIN = 56f
    private const val BODY_SIZE = 10.5f

    private fun pdf(blocks: List<Block>): ByteArray {
        val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        PDDocument().use { document ->
            val writer = PdfWriter(document, regular, bold)
            blocks.forEach { block ->
                when (block) {
                    is Block.Title -> writer.paragraph(block.text, bold, 18f, spacingAfter = 4f)
                    is Block.Heading -> {
                        writer.space(8f)
                        writer.paragraph(block.text.uppercase(), bold, 11.5f, spacingAfter = 2f)
                        writer.rule()
                    }
                    is Block.Subheading -> writer.paragraph(block.text, bold, BODY_SIZE + 0.5f, spacingAfter = 1f)
                    is Block.Bullet -> writer.paragraph(block.text, regular, BODY_SIZE, indent = 12f, bullet = true)
                    is Block.Paragraph -> writer.paragraph(block.text, regular, BODY_SIZE)
                    Block.Spacer -> writer.space(5f)
                }
            }
            writer.close()
            return ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }

    private class PdfWriter(private val document: PDDocument, private val regular: PDType1Font, private val bold: PDType1Font) {
        private val pageSize = PDRectangle.A4
        private val width = pageSize.width - 2 * MARGIN
        private var stream: PDPageContentStream? = null
        private var y = 0f

        private fun ensure(height: Float): PDPageContentStream {
            val current = stream
            if (current != null && y - height >= MARGIN) return current
            current?.close()
            val page = PDPage(pageSize)
            document.addPage(page)
            y = pageSize.height - MARGIN
            return PDPageContentStream(document, page).also { stream = it }
        }

        fun space(height: Float) {
            if (stream != null) y -= height
        }

        fun rule() {
            val s = ensure(4f)
            s.setLineWidth(0.6f)
            s.moveTo(MARGIN, y + 2f)
            s.lineTo(MARGIN + width, y + 2f)
            s.stroke()
            y -= 6f
        }

        fun paragraph(text: String, font: PDType1Font, size: Float, indent: Float = 0f, bullet: Boolean = false, spacingAfter: Float = 2f) {
            val leading = size * 1.35f
            val lines = wrap(safe(text, font), font, size, width - indent)
            lines.forEachIndexed { index, line ->
                val s = ensure(leading)
                y -= leading
                if (bullet && index == 0) {
                    s.beginText()
                    s.setFont(font, size)
                    s.newLineAtOffset(MARGIN + indent - 9f, y)
                    s.showText(safe("•", font))
                    s.endText()
                }
                s.beginText()
                s.setFont(font, size)
                s.newLineAtOffset(MARGIN + indent, y)
                s.showText(line)
                s.endText()
            }
            y -= spacingAfter
        }

        private fun wrap(text: String, font: PDType1Font, size: Float, maxWidth: Float): List<String> {
            if (text.isEmpty()) return listOf("")
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            text.split(' ').forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (widthOf(candidate, font, size) <= maxWidth || current.isEmpty()) {
                    current = StringBuilder(candidate)
                } else {
                    lines += current.toString()
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines += current.toString()
            return lines
        }

        private fun widthOf(text: String, font: PDType1Font, size: Float): Float = font.getStringWidth(text) / 1000f * size

        // Caracteres fora da codificação da fonte (emojis, setas) viram equivalentes simples ou somem.
        private fun safe(text: String, font: PDType1Font): String = buildString {
            text.replace("→", "->").replace("–", "-").replace("—", "-").replace("\t", " ").forEach { ch ->
                val ok = runCatching { font.encode(ch.toString()) }.isSuccess
                if (ok) append(ch) else if (ch.isWhitespace()) append(' ')
            }
        }

        fun close() {
            if (stream == null) ensure(0f)
            stream?.close()
            stream = null
        }
    }

    // --- DOCX (Office Open XML mínimo, sem dependências) ---

    private fun docx(blocks: List<Block>): ByteArray {
        val body = blocks.joinToString("") { block ->
            when (block) {
                is Block.Title -> paragraphXml(block.text, size = 36, bold = true)
                is Block.Heading -> paragraphXml(block.text.uppercase(), size = 23, bold = true, spaceBefore = 200, border = true)
                is Block.Subheading -> paragraphXml(block.text, size = 22, bold = true)
                is Block.Bullet -> paragraphXml("• " + block.text, size = 21, indent = 360)
                is Block.Paragraph -> paragraphXml(block.text, size = 21)
                Block.Spacer -> "<w:p/>"
            }
        }
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            listOf("[Content_Types].xml" to contentTypes, "_rels/.rels" to rels, "word/document.xml" to document).forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun paragraphXml(text: String, size: Int, bold: Boolean = false, indent: Int = 0, spaceBefore: Int = 0, border: Boolean = false): String {
        val props = buildString {
            append("<w:pPr>")
            if (border) append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"1B1B1B\"/></w:pBdr>")
            append("<w:spacing w:before=\"$spaceBefore\" w:after=\"60\"/>")
            if (indent > 0) append("<w:ind w:left=\"$indent\"/>")
            append("</w:pPr>")
        }
        val run = "<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"$size\"/></w:rPr>" +
            "<w:t xml:space=\"preserve\">${escape(text)}</w:t></w:r>"
        return "<w:p>$props$run</w:p>"
    }

    private fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch.code < 0x20 && ch != '\t' -> Unit
                else -> append(ch)
            }
        }
    }
}
