package com.watchjobs.ingestion.connector

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Agregador Adzuna: busca por palavra-chave. Não é retrato completo, então nunca fecha vagas.
// Só metadados, trecho e link são guardados (allows_storage = false), com atribuição obrigatória.
@Component
class AdzunaConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : QueryableConnector {
    override val code = "ADZUNA"

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val text = target.keywords ?: target.roleFamily ?: throw ConnectorException("Segmento do Adzuna sem termo")
        return search(source, FreeQuery(text = text, workMode = target.workMode))
    }

    override fun search(source: SourceContext, query: FreeQuery): FetchResult {
        val appId = source.credentials["ADZUNA_APP_ID"]
        val appKey = source.credentials["ADZUNA_APP_KEY"]
        if (appId.isNullOrBlank() || appKey.isNullOrBlank()) {
            throw ConnectorException("Credenciais do Adzuna ausentes (ADZUNA_APP_ID e ADZUNA_APP_KEY)")
        }
        val country = source.configString("country") ?: "br"
        val text = if (query.workMode == "REMOTE" && !query.text.contains("remot", ignoreCase = true)) {
            "${query.text} remoto"
        } else {
            query.text
        }
        val builder = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL)
            .pathSegment(country, "search", query.page.toString())
            .queryParam("app_id", appId)
            .queryParam("app_key", appKey)
            .queryParam("results_per_page", RESULTS_PER_PAGE)
            .queryParam("what", text)
        query.location?.takeIf { it.isNotBlank() }?.let { builder.queryParam("where", it) }
        // A URL leva a chave: nenhuma mensagem de erro pode repeti-la.
        val body = try {
            http.get(builder.build().encode().toUri(), emptyMap())
        } catch (ex: RestClientResponseException) {
            throw ConnectorException("Adzuna respondeu HTTP ${ex.statusCode.value()}")
        } catch (ex: Exception) {
            throw ConnectorException("Adzuna indisponível: ${ex.javaClass.simpleName}")
        }
        return FetchResult(parse(body, currencyOf(country)), fullSnapshot = false)
    }

    fun parse(json: String, currency: String?): List<RawPosting> {
        val response = try {
            mapper.readValue(json, AdzunaResponse::class.java)
        } catch (ex: Exception) {
            throw ConnectorException("Resposta do Adzuna em formato inesperado", ex)
        }
        return response.results.mapNotNull { job ->
            val id = job.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RawPosting(
                externalId = id,
                title = job.title.orEmpty(),
                url = job.redirectUrl,
                organizationName = job.company?.displayName,
                locationText = job.location?.displayName,
                description = job.description,
                postedAt = job.created?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                salaryMin = job.salaryMin,
                salaryMax = job.salaryMax,
                salaryCurrency = if (job.salaryMin != null || job.salaryMax != null) currency else null
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.adzuna.com/v1/api/jobs"
        const val RESULTS_PER_PAGE = 50

        fun currencyOf(country: String): String? = when (country.lowercase()) {
            "br" -> "BRL"
            "us" -> "USD"
            "gb" -> "GBP"
            "ca" -> "CAD"
            "mx" -> "MXN"
            "de", "fr", "es", "it", "nl", "at", "be", "pl" -> "EUR"
            else -> null
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AdzunaResponse(val results: List<AdzunaJob> = emptyList(), val count: Long? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AdzunaJob(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    @JsonProperty("redirect_url") val redirectUrl: String? = null,
    val created: String? = null,
    val company: AdzunaCompany? = null,
    val location: AdzunaLocation? = null,
    @JsonProperty("salary_min") val salaryMin: Double? = null,
    @JsonProperty("salary_max") val salaryMax: Double? = null,
    @JsonProperty("contract_type") val contractType: String? = null,
    @JsonProperty("contract_time") val contractTime: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AdzunaCompany(@JsonProperty("display_name") val displayName: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AdzunaLocation(
    @JsonProperty("display_name") val displayName: String? = null,
    val area: List<String> = emptyList()
)
