package com.watchjobs.ingestion.connector

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// SmartRecruiters (ATS público): GET /v1/companies/{empresa}/postings, paginado (limit/offset) até o total.
@Component
class SmartRecruitersConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : JobSourceConnector {
    override val code = "SMARTRECRUITERS"
    private val throttle = Throttle()

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val company = target.externalKey ?: throw ConnectorException("Alvo do SmartRecruiters sem empresa")
        val maxPages = source.configLong("maxPages", 10).toInt()
        val postings = mutableListOf<RawPosting>()
        var offset = 0
        var complete = false
        for (page in 0 until maxPages) {
            throttle.pause(source.configLong("minDelayMs", 1000))
            val uri = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL)
                .pathSegment(company, "postings")
                .queryParam("limit", PAGE_SIZE)
                .queryParam("offset", offset)
                .build().encode().toUri()
            val body = try {
                http.get(uri, emptyMap())
            } catch (ex: Exception) {
                throw httpError("SmartRecruiters ($company)", ex)
            }
            val response = parsePage(body, company)
            postings += toPostings(response, company, target.organizationName)
            offset += response.content.size
            if (response.content.isEmpty() || offset >= (response.totalFound ?: offset)) {
                complete = true
                break
            }
        }
        // Só é retrato completo se todas as páginas foram lidas (senão, vagas das páginas seguintes não podem ser fechadas).
        return FetchResult(postings, fullSnapshot = complete)
    }

    fun parsePage(json: String, company: String): SmartRecruitersPage = try {
        mapper.readValue(json, SmartRecruitersPage::class.java)
    } catch (ex: Exception) {
        throw ConnectorException("Resposta do SmartRecruiters ($company) em formato inesperado", ex)
    }

    fun toPostings(page: SmartRecruitersPage, company: String, organizationName: String?): List<RawPosting> =
        page.content.mapNotNull { p ->
            val id = p.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val location = p.location
            RawPosting(
                externalId = "$company:$id",
                title = p.name.orEmpty(),
                url = "https://jobs.smartrecruiters.com/$company/$id",
                organizationName = organizationName ?: p.company?.name ?: company,
                locationText = location?.fullLocation ?: listOfNotNull(location?.city, location?.region, location?.country?.uppercase()).joinToString(", ").ifBlank { null },
                description = null,
                postedAt = p.releasedDate?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                departments = listOfNotNull(p.department?.label, p.function?.label),
                workMode = when {
                    location?.remote == true -> "REMOTE"
                    location?.hybrid == true -> "HYBRID"
                    else -> null
                },
                jobType = StructuredHints.jobType(p.typeOfEmployment?.label),
                countryCode = StructuredHints.country(location?.country)
            )
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.smartrecruiters.com/v1/companies"
        const val PAGE_SIZE = 100
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmartRecruitersPage(val offset: Int? = null, val limit: Int? = null, val totalFound: Int? = null, val content: List<SmartRecruitersPosting> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmartRecruitersPosting(
    val id: String? = null,
    val name: String? = null,
    val releasedDate: String? = null,
    val company: SmartRecruitersNamed? = null,
    val location: SmartRecruitersLocation? = null,
    val department: SmartRecruitersLabel? = null,
    val function: SmartRecruitersLabel? = null,
    val typeOfEmployment: SmartRecruitersLabel? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmartRecruitersNamed(val name: String? = null, val identifier: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmartRecruitersLabel(val label: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SmartRecruitersLocation(
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
    val remote: Boolean? = null,
    val hybrid: Boolean? = null,
    val fullLocation: String? = null
)
