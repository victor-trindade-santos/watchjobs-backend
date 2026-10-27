package com.watchjobs.profile.importer

import com.watchjobs.exception.BusinessException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

// Texto do currículo em memória (PDF, DOCX ou TXT), com limites de páginas, tamanho e entradas do ZIP.
// O formato vem do conteúdo do arquivo, não da extensão.
object ResumeTextExtractor {
    const val MAX_CHARS = 200_000
    private const val MAX_PAGES = 15
    private const val MAX_DOCX_XML_BYTES = 20 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 300

    fun extract(bytes: ByteArray): String {
        val text = when {
            bytes.startsWith("%PDF") -> pdf(bytes)
            bytes.startsWith("PK") -> docx(bytes)
            else -> plain(bytes)
        }.take(MAX_CHARS)
        if (text.isBlank()) {
            throw BusinessException("Não encontramos texto no arquivo. Se for um PDF escaneado (imagem), envie uma versão com texto selecionável.")
        }
        return text
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun pdf(bytes: ByteArray): String =
        try {
            Loader.loadPDF(bytes).use { document ->
                if (document.isEncrypted) throw BusinessException("O PDF está protegido por senha. Salve uma cópia sem senha e envie de novo.")
                // Sem ordenar por posição: no PDF do LinkedIn a coluna lateral vem inteira antes da principal.
                PDFTextStripper().apply { endPage = MAX_PAGES }.getText(document)
            }
        } catch (ex: BusinessException) {
            throw ex
        } catch (ex: IOException) {
            throw BusinessException("Não foi possível ler o PDF. Confira se o arquivo não está corrompido ou protegido.")
        } catch (ex: RuntimeException) {
            throw BusinessException("Não foi possível ler o PDF. Confira se o arquivo não está corrompido ou protegido.")
        }

    private fun docx(bytes: ByteArray): String {
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ZIP_ENTRIES) throw BusinessException("O arquivo tem entradas demais para ser um currículo")
                    if (entry.name != "word/document.xml") continue
                    val out = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = zip.read(chunk)
                        if (read < 0) break
                        if (out.size() + read > MAX_DOCX_XML_BYTES) throw BusinessException("O documento é grande demais para ser lido")
                        out.write(chunk, 0, read)
                    }
                    return xmlToText(out.toString(Charsets.UTF_8))
                }
            }
        } catch (ex: IOException) {
            throw BusinessException("O arquivo enviado não é um DOCX válido")
        }
        throw BusinessException("Formato não suportado. Envie o currículo em PDF, DOCX ou TXT")
    }

    // Parágrafos, linhas e células viram quebras de linha; o resto das marcas some.
    internal fun xmlToText(xml: String): String =
        xml.replace(Regex("</w:p>|</w:tc>|<w:br[^>]*/>"), "\n")
            .replace(Regex("<w:tab[^>]*/>"), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
            .replace("&amp;", "&")

    private fun plain(bytes: ByteArray): String {
        val sample = bytes.take(4096)
        val binary = sample.count {
            val value = it.toInt() and 0xFF
            value < 9 || value in 14..31
        }
        if (sample.isNotEmpty() && binary * 100 / sample.size > 2) {
            throw BusinessException("Formato não suportado. Envie o currículo em PDF, DOCX ou TXT")
        }
        val utf8 = bytes.toString(Charsets.UTF_8)
        return if ('\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
    }
}
