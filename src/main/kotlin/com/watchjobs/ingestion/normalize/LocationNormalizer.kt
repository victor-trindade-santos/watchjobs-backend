package com.watchjobs.ingestion.normalize

import com.watchjobs.catalog.TextNormalizer

data class NormalizedLocation(
    val city: String? = null,
    val state: String? = null,
    val countryCode: String? = null,
    val remote: Boolean = false
)

// Local a partir do texto livre da fonte ("São Paulo, Brazil", "Remote - Brazil", "Rio de Janeiro - RJ").
object LocationNormalizer {
    private val separators = Regex("\\s*[,;|/()\\[\\]]\\s*|\\s+[-–]\\s+|\\s*[–]\\s*")

    private val remoteWords = setOf(
        "remote", "remoto", "anywhere", "home office", "work from home", "wfh", "teletrabalho", "fully remote",
        "100 remoto", "remote first"
    )
    private val modeWords = setOf("hybrid", "hibrido", "on site", "onsite", "presencial", "in office")
    private val ignoredWords = setOf(
        "latam", "latin america", "americas", "emea", "apac", "worldwide", "global", "multiple locations",
        "various locations", "other"
    )

    private val countries = mapOf(
        "brazil" to "BR", "brasil" to "BR", "br" to "BR",
        "portugal" to "PT",
        "united states" to "US", "usa" to "US", "us" to "US", "estados unidos" to "US",
        "united kingdom" to "GB", "uk" to "GB", "reino unido" to "GB",
        "mexico" to "MX", "argentina" to "AR", "colombia" to "CO", "chile" to "CL", "peru" to "PE",
        "uruguay" to "UY", "uruguai" to "UY",
        "spain" to "ES", "espana" to "ES", "espanha" to "ES",
        "germany" to "DE", "alemanha" to "DE", "canada" to "CA", "ireland" to "IE", "irlanda" to "IE",
        "netherlands" to "NL", "france" to "FR", "franca" to "FR", "italy" to "IT", "india" to "IN"
    )

    private val twoLetterCountries = setOf("us", "br", "uk")

    private val ufCodes = setOf(
        "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
        "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"
    )

    private val stateNames = mapOf(
        "acre" to "AC", "alagoas" to "AL", "amapa" to "AP", "amazonas" to "AM", "bahia" to "BA", "ceara" to "CE",
        "distrito federal" to "DF", "espirito santo" to "ES", "goias" to "GO", "maranhao" to "MA",
        "mato grosso" to "MT", "mato grosso do sul" to "MS", "minas gerais" to "MG", "para" to "PA",
        "paraiba" to "PB", "parana" to "PR", "pernambuco" to "PE", "piaui" to "PI", "rio de janeiro" to "RJ",
        "rio grande do norte" to "RN", "rio grande do sul" to "RS", "rondonia" to "RO", "roraima" to "RR",
        "santa catarina" to "SC", "sao paulo" to "SP", "sergipe" to "SE", "tocantins" to "TO",
        "state of sao paulo" to "SP"
    )

    // Cidades brasileiras comuns em vagas, para deduzir o país quando ele não vem.
    private val brazilianCities = mapOf(
        "sao paulo" to "SP", "rio de janeiro" to "RJ", "belo horizonte" to "MG", "curitiba" to "PR",
        "porto alegre" to "RS", "florianopolis" to "SC", "recife" to "PE", "salvador" to "BA",
        "fortaleza" to "CE", "brasilia" to "DF", "campinas" to "SP", "goiania" to "GO", "manaus" to "AM",
        "belem" to "PA", "vitoria" to "ES", "joinville" to "SC", "sao jose dos campos" to "SP",
        "ribeirao preto" to "SP", "uberlandia" to "MG", "londrina" to "PR", "barueri" to "SP",
        "sao carlos" to "SP", "blumenau" to "SC", "natal" to "RN", "joao pessoa" to "PB", "maceio" to "AL"
    )

    fun normalize(raw: String?, defaultCountry: String? = null): NormalizedLocation {
        if (raw.isNullOrBlank()) return NormalizedLocation(countryCode = defaultCountry)
        var remote = false
        var city: String? = null
        var state: String? = null
        var country: String? = null

        raw.split(separators).map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            var key = TextNormalizer.forMatching(token)
            remoteWords.firstOrNull { key == it || key.startsWith("$it ") || key.endsWith(" $it") }?.let { word ->
                remote = true
                key = key.removePrefix(word).removeSuffix(word).trim()
            }
            modeWords.firstOrNull { key == it || key.startsWith("$it ") }?.let { word -> key = key.removePrefix(word).trim() }
            if (key.isEmpty() || key in ignoredWords) return@forEach

            val upper = token.trim().uppercase()
            // "US" e "BR" só valem como país quando escritos em maiúsculas ("us" pode ser palavra).
            val countryCode = countries[key]?.takeIf { key !in twoLetterCountries || token.trim() == upper }
            if (countryCode != null) {
                if (country == null) country = countryCode
            } else if (token.trim().length == 2 && token.trim() == upper && upper in ufCodes) {
                if (state == null) state = upper
            } else if (city == null) {
                city = originalCase(token, key)
            } else if (state == null && stateNames[key] != null) {
                state = stateNames[key]
            }
        }

        val cityKey = city?.let { TextNormalizer.forMatching(it) }
        if (state == null && cityKey != null && country in setOf(null, "BR")) {
            state = brazilianCities[cityKey]
        }
        if (country == null && (state != null || (cityKey != null && cityKey in brazilianCities))) country = "BR"
        if (country == null) country = defaultCountry
        return NormalizedLocation(city = city, state = state, countryCode = country, remote = remote)
    }

    // Remove do texto original as palavras de modo de trabalho, mantendo acentos e maiúsculas.
    private fun originalCase(token: String, key: String): String {
        val words = token.trim().split(Regex("\\s+"))
        val keep = key.split(' ').size
        return words.takeLast(keep).joinToString(" ").ifBlank { token.trim() }
    }
}
