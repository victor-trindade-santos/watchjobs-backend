package com.watchjobs.ingestion.connector

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Faixa salarial em texto ("R$ 3.000 - R$ 5.000 por mês", "$80k-$100k"): só quando os números são claros.
object SalaryText {
    private val number = Regex("""(\d{1,3}(?:[.,]\d{3})+|\d+(?:[.,]\d+)?)\s*(k)?""", RegexOption.IGNORE_CASE)

    data class Parsed(val min: Double?, val max: Double?, val currency: String?, val period: String?)

    fun parse(text: String?, defaultCurrency: String? = null): Parsed? {
        if (text.isNullOrBlank()) return null
        val values = number.findAll(text).mapNotNull { m ->
            val raw = m.groupValues[1]
            val normalized = if (Regex("""\d{1,3}([.,]\d{3})+""").matches(raw)) raw.replace(".", "").replace(",", "") else raw.replace(",", ".")
            normalized.toDoubleOrNull()?.let { if (m.groupValues[2].isNotEmpty()) it * 1000 else it }
        }.filter { it >= 100 }.take(2).toList()
        if (values.isEmpty()) return null
        val lower = text.lowercase()
        val currency = when {
            "r$" in lower || "brl" in lower -> "BRL"
            "€" in lower || "eur" in lower -> "EUR"
            "£" in lower || "gbp" in lower -> "GBP"
            "$" in lower || "usd" in lower -> "USD"
            else -> defaultCurrency
        }
        val period = when {
            listOf("hora", "hour", "/h").any { it in lower } -> "HOUR"
            listOf("ano", "year", "annual", "anual", "/yr").any { it in lower } -> "YEAR"
            listOf("mês", "mes", "month", "mensal").any { it in lower } -> "MONTH"
            else -> null
        }
        return Parsed(values.min(), values.max().takeIf { values.size > 1 }, currency, period)
    }
}

// Jooble (agregador, API com chave): POST {base}/{chave} com palavras-chave e local. Sem chave, a fonte fica inativa.
@Component
class JoobleConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : QueryableConnector {
    override val code = "JOOBLE"
    private val throttle = Throttle()

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val text = target.keywords ?: target.roleFamily ?: throw ConnectorException("Segmento do Jooble sem termo")
        return search(source, FreeQuery(text = text, location = source.configString("defaultLocation"), workMode = target.workMode))
    }

    override fun search(source: SourceContext, query: FreeQuery): FetchResult {
        val key = source.credentials["JOOBLE_API_KEY"]
        if (key.isNullOrBlank()) throw ConnectorException("Chave do Jooble ausente (JOOBLE_API_KEY)")
        throttle.pause(source.configLong("minDelayMs", 1000))
        val keywords = if (query.workMode == "REMOTE" && !query.text.contains("remot", ignoreCase = true)) "${query.text} remoto" else query.text
        val payload = mapper.writeValueAsString(
            mapOf("keywords" to keywords, "location" to (query.location ?: ""), "page" to query.page.toString(), "ResultOnPage" to RESULTS_PER_PAGE.toString())
        )
        val uri: URI = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL).pathSegment(key).build().encode().toUri()
        // A URL leva a chave: a mensagem de erro nunca a repete.
        val body = try {
            http.post(uri, emptyMap(), payload)
        } catch (ex: Exception) {
            throw httpError("Jooble", ex)
        }
        return FetchResult(parse(body, source.configString("currency") ?: "BRL"), fullSnapshot = false)
    }

    fun parse(json: String, defaultCurrency: String?): List<RawPosting> {
        val response = try {
            mapper.readValue(json, JoobleResponse::class.java)
        } catch (ex: Exception) {
            throw ConnectorException("Resposta do Jooble em formato inesperado", ex)
        }
        return response.jobs.mapNotNull { job ->
            val id = job.id?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val salary = SalaryText.parse(job.salary, defaultCurrency)
            RawPosting(
                externalId = id,
                title = job.title.orEmpty(),
                url = job.link,
                organizationName = job.company,
                locationText = job.location,
                description = job.snippet,
                postedAt = job.updated?.let(::parseDate),
                salaryMin = salary?.min,
                salaryMax = salary?.max,
                salaryCurrency = salary?.currency,
                salaryPeriod = salary?.period,
                jobType = StructuredHints.jobType(job.type)
            )
        }
    }

    private fun parseDate(value: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(value) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value.take(19)).atOffset(ZoneOffset.UTC) }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://jooble.org/api"
        const val RESULTS_PER_PAGE = 50
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class JoobleResponse(val totalCount: Long? = null, val jobs: List<JoobleJob> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class JoobleJob(
    val id: Long? = null,
    val title: String? = null,
    val location: String? = null,
    val snippet: String? = null,
    val salary: String? = null,
    val source: String? = null,
    val type: String? = null,
    val link: String? = null,
    val company: String? = null,
    val updated: String? = null
)

// Remotive (agregador de vagas remotas, API pública com limite de poucas chamadas por dia e atribuição).
@Component
class RemotiveConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : QueryableConnector {
    override val code = "REMOTIVE"
    private val throttle = Throttle()

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val text = target.keywords ?: target.roleFamily ?: throw ConnectorException("Segmento do Remotive sem termo")
        return search(source, FreeQuery(text = text.replace(" remoto", "").trim(), workMode = "REMOTE"))
    }

    override fun search(source: SourceContext, query: FreeQuery): FetchResult {
        throttle.pause(source.configLong("minDelayMs", 1000))
        val uri = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL)
            .queryParam("search", query.text)
            .queryParam("limit", source.configLong("limit", 50))
            .build().encode().toUri()
        val body = try {
            http.get(uri, emptyMap())
        } catch (ex: Exception) {
            throw httpError("Remotive", ex)
        }
        return FetchResult(parse(body), fullSnapshot = false)
    }

    fun parse(json: String): List<RawPosting> {
        val response = try {
            mapper.readValue(json, RemotiveResponse::class.java)
        } catch (ex: Exception) {
            throw ConnectorException("Resposta do Remotive em formato inesperado", ex)
        }
        return response.jobs.mapNotNull { job ->
            val id = job.id?.toString() ?: return@mapNotNull null
            val salary = SalaryText.parse(job.salary, "USD")
            RawPosting(
                externalId = id,
                title = job.title.orEmpty(),
                url = job.url,
                organizationName = job.companyName,
                locationText = "Remote" + (job.candidateRequiredLocation?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                description = job.description,
                postedAt = job.publicationDate?.let { value ->
                    runCatching { OffsetDateTime.parse(value) }.getOrNull()
                        ?: runCatching { LocalDateTime.parse(value.take(19)).atOffset(ZoneOffset.UTC) }.getOrNull()
                },
                salaryMin = salary?.min,
                salaryMax = salary?.max,
                salaryCurrency = salary?.currency,
                salaryPeriod = salary?.period,
                departments = listOfNotNull(job.category),
                workMode = "REMOTE",
                jobType = StructuredHints.jobType(job.jobType)
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://remotive.com/api/remote-jobs"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemotiveResponse(val jobs: List<RemotiveJob> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class RemotiveJob(
    val id: Long? = null,
    val url: String? = null,
    val title: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("company_name") val companyName: String? = null,
    val category: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("job_type") val jobType: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("publication_date") val publicationDate: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("candidate_required_location") val candidateRequiredLocation: String? = null,
    val salary: String? = null,
    val description: String? = null
)
