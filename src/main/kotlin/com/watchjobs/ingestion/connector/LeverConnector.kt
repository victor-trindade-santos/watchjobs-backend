package com.watchjobs.ingestion.connector

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.watchjobs.catalog.TextNormalizer
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

// Pausa mínima entre chamadas à mesma fonte (respeita os limites de uso das APIs públicas).
internal class Throttle {
    private var lastCallAt = 0L

    @Synchronized
    fun pause(minDelayMs: Long) {
        val wait = lastCallAt + minDelayMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }
}

// Erro HTTP da fonte em mensagem curta, sem repetir a URL (que pode levar chave).
internal fun httpError(source: String, ex: Exception): ConnectorException = when (ex) {
    is RestClientResponseException -> ConnectorException("$source respondeu HTTP ${ex.statusCode.value()}")
    is ConnectorException -> ex
    else -> ConnectorException("$source indisponível: ${ex.javaClass.simpleName}")
}

// Modalidade e contrato informados pela fonte, no vocabulário do corpus.
internal object StructuredHints {
    private val isoCountries = Locale.getISOCountries().toSet()

    // Nomes em inglês e português ("United States", "Brasil") para o código do país.
    private val countryByName: Map<String, String> by lazy {
        isoCountries.flatMap { code ->
            listOf(Locale.ENGLISH, Locale.forLanguageTag("pt-BR")).map { TextNormalizer.forMatching(Locale.of("", code).getDisplayCountry(it)) to code }
        }.toMap()
    }

    fun country(value: String?): String? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (v.length == 2) return v.uppercase(Locale.ROOT).takeIf { it in isoCountries }
        return countryByName[TextNormalizer.forMatching(v)]
    }

    fun workMode(value: String?): String? = when (value?.lowercase()?.replace("-", "")?.replace("_", "")?.replace(" ", "")) {
        "remote", "remoto", "telecommute", "fullyremote" -> "REMOTE"
        "hybrid", "hibrido", "híbrido" -> "HYBRID"
        "onsite", "inoffice", "presencial", "office" -> "ON_SITE"
        else -> null
    }

    fun jobType(value: String?): String? {
        val v = value?.lowercase()?.replace("-", "")?.replace("_", "")?.replace(" ", "") ?: return null
        return when {
            "intern" in v || "estagio" in v || "estágio" in v -> "INTERNSHIP"
            "temporary" in v || "temporario" in v || "temporário" in v || "temp" == v -> "TEMPORARY"
            "freelance" in v -> "FREELANCE"
            "contract" in v || v == "pj" -> "PJ"
            v == "clt" || "efetivo" in v -> "CLT"
            else -> null
        }
    }
}

// Lever (ATS público): GET /v0/postings/{empresa}?mode=json traz todas as vagas abertas (retrato completo).
@org.springframework.stereotype.Component
class LeverConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : JobSourceConnector {
    override val code = "LEVER"
    private val throttle = Throttle()

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val company = target.externalKey ?: throw ConnectorException("Alvo do Lever sem empresa")
        throttle.pause(source.configLong("minDelayMs", 1000))
        val uri = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL)
            .pathSegment(company).queryParam("mode", "json").build().encode().toUri()
        val body = try {
            http.get(uri, emptyMap())
        } catch (ex: Exception) {
            throw httpError("Lever ($company)", ex)
        }
        return FetchResult(parse(body, company, target.organizationName), fullSnapshot = true)
    }

    fun parse(json: String, company: String, organizationName: String?): List<RawPosting> {
        val postings = try {
            mapper.readValue(json, Array<LeverPosting>::class.java).toList()
        } catch (ex: Exception) {
            throw ConnectorException("Resposta do Lever ($company) em formato inesperado", ex)
        }
        return postings.mapNotNull { p ->
            val id = p.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val salary = p.salaryRange
            RawPosting(
                externalId = "$company:$id",
                title = p.text.orEmpty(),
                url = p.hostedUrl ?: p.applyUrl,
                organizationName = organizationName ?: company,
                locationText = p.categories?.location ?: p.categories?.allLocations?.firstOrNull(),
                description = listOfNotNull(p.descriptionPlain ?: p.description, p.additionalPlain).joinToString("\n\n").ifBlank { null },
                postedAt = p.createdAt?.let { OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC) },
                salaryMin = salary?.min,
                salaryMax = salary?.max,
                salaryCurrency = salary?.currency,
                salaryPeriod = when (salary?.interval?.lowercase()) {
                    "per-year-salary" -> "YEAR"
                    "per-month-salary" -> "MONTH"
                    "per-hour-wage" -> "HOUR"
                    else -> null
                },
                departments = listOfNotNull(p.categories?.team, p.categories?.department),
                workMode = StructuredHints.workMode(p.workplaceType),
                jobType = StructuredHints.jobType(p.categories?.commitment),
                countryCode = StructuredHints.country(p.country)
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.lever.co/v0/postings"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LeverPosting(
    val id: String? = null,
    val text: String? = null,
    val hostedUrl: String? = null,
    val applyUrl: String? = null,
    val createdAt: Long? = null,
    val workplaceType: String? = null,
    val country: String? = null,
    val descriptionPlain: String? = null,
    val description: String? = null,
    val additionalPlain: String? = null,
    val categories: LeverCategories? = null,
    val salaryRange: LeverSalary? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LeverCategories(
    val location: String? = null,
    val commitment: String? = null,
    val team: String? = null,
    val department: String? = null,
    val allLocations: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LeverSalary(val min: Double? = null, val max: Double? = null, val currency: String? = null, val interval: String? = null)
