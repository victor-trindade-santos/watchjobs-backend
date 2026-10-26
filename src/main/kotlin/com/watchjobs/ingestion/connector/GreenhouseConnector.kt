package com.watchjobs.ingestion.connector

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// ATS público do Greenhouse: uma chamada por board traz todas as vagas abertas (retrato completo).
@Component
class GreenhouseConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : JobSourceConnector {
    override val code = "GREENHOUSE"

    private var lastCallAt = 0L

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val board = target.externalKey ?: throw ConnectorException("Alvo do Greenhouse sem board")
        pause(source.configLong("minDelayMs", 1000))
        val uri = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL)
            .pathSegment(board, "jobs")
            .queryParam("content", "true")
            .build()
            .encode()
            .toUri()
        val body = try {
            http.get(uri, emptyMap())
        } catch (ex: RestClientResponseException) {
            throw ConnectorException("Greenhouse ($board) respondeu HTTP ${ex.statusCode.value()}", ex)
        } catch (ex: Exception) {
            throw ConnectorException("Greenhouse ($board) indisponível: ${ex.javaClass.simpleName}", ex)
        }
        return FetchResult(parse(body, board, target.organizationName), fullSnapshot = true)
    }

    fun parse(json: String, board: String, organizationName: String?): List<RawPosting> {
        val response = try {
            mapper.readValue(json, GreenhouseResponse::class.java)
        } catch (ex: Exception) {
            throw ConnectorException("Resposta do Greenhouse ($board) em formato inesperado", ex)
        }
        return response.jobs.mapNotNull { job ->
            val id = job.id ?: return@mapNotNull null
            RawPosting(
                externalId = "$board:$id",
                title = job.title.orEmpty(),
                url = job.absoluteUrl,
                organizationName = organizationName ?: job.companyName,
                locationText = job.location?.name,
                description = job.content,
                postedAt = parseDate(job.firstPublished) ?: parseDate(job.updatedAt),
                departments = job.departments.mapNotNull { it.name }
            )
        }
    }

    @Synchronized
    private fun pause(minDelayMs: Long) {
        val wait = lastCallAt + minDelayMs - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }

    private fun parseDate(value: String?): OffsetDateTime? =
        value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    companion object {
        const val DEFAULT_BASE_URL = "https://boards-api.greenhouse.io/v1/boards"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GreenhouseResponse(val jobs: List<GreenhouseJob> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class GreenhouseJob(
    val id: Long? = null,
    val title: String? = null,
    @JsonProperty("absolute_url") val absoluteUrl: String? = null,
    @JsonProperty("updated_at") val updatedAt: String? = null,
    @JsonProperty("first_published") val firstPublished: String? = null,
    @JsonProperty("company_name") val companyName: String? = null,
    val location: GreenhouseNamed? = null,
    val content: String? = null,
    val departments: List<GreenhouseNamed> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GreenhouseNamed(val name: String? = null)
