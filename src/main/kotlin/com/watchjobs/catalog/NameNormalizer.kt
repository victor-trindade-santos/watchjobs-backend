package com.watchjobs.catalog

import java.util.Locale

// Nome normalizado de empresas (core.organization.normalized_name): minúsculas, sem acento,
// sem espaços repetidos e sem sufixo societário. "Nubank", "NUBANK " e "Nubank Ltda." viram "nubank".
object NameNormalizer {
    private val legalSuffixes = setOf("ltda", "ltd", "sa", "inc", "llc", "eireli", "me", "epp", "corp", "gmbh", "plc")
    private val sa = Regex("\\bs\\s*[./]\\s*a\\b\\.?")
    private val separators = Regex("[,;]")

    fun normalize(name: String): String {
        val folded = TextNormalizer.stripAccents(name).lowercase(Locale.ROOT)
            .replace(sa, " sa ")
            .replace(separators, " ")
        val tokens = TextNormalizer.collapseSpaces(folded).split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (tokens.size > 1 && tokens.last().trimEnd('.') in legalSuffixes) {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens.joinToString(" ") { it.trimEnd('.') }.ifEmpty { folded.trim() }.take(150)
    }
}
