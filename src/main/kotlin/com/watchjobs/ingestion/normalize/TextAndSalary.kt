package com.watchjobs.ingestion.normalize

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.util.Locale

data class NormalizedSalary(
    val min: Double?,
    val max: Double?,
    val currency: String?,
    val period: String?
)

// Salário só quando a fonte informa. Valores zerados, negativos ou absurdos são descartados.
object SalaryNormalizer {
    private const val MAX_VALUE = 99_999_999.0
    private val periods = setOf("HOUR", "DAY", "WEEK", "MONTH", "YEAR")

    fun normalize(min: Double?, max: Double?, currency: String?, period: String?): NormalizedSalary? {
        val low = min?.takeIf { it > 0 && it < MAX_VALUE }
        val high = max?.takeIf { it > 0 && it < MAX_VALUE }
        if (low == null && high == null) return null
        val (first, second) = if (low != null && high != null && low > high) high to low else low to high
        return NormalizedSalary(
            min = first,
            max = second,
            currency = currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("^[A-Z]{3}$")) },
            period = period?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in periods }
        )
    }
}

object HtmlText {
    private const val SNIPPET_LENGTH = 500

    // HTML (inclusive o escapado do Greenhouse, "&lt;p&gt;") vira texto limpo. Nunca devolve HTML.
    fun toText(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val html = if (value.contains("&lt;")) Parser.unescapeEntities(value, false) else value
        return Jsoup.parse(html).text().replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    fun snippet(text: String?, max: Int = SNIPPET_LENGTH): String? {
        if (text.isNullOrBlank()) return null
        if (text.length <= max) return text
        val cut = text.substring(0, max).substringBeforeLast(' ').ifBlank { text.substring(0, max) }
        return "$cut…"
    }
}

object Hashing {
    fun sha256(vararg parts: Any?): String =
        MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u001F") { it?.toString() ?: "" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
