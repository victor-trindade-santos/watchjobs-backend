package com.watchjobs.ingestion.connector

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Ashby (ATS público): GET /posting-api/job-board/{board}?includeCompensation=true (retrato completo).
@Component
class AshbyConnector(
    private val http: HttpGateway,
    private val mapper: ObjectMapper
) : JobSourceConnector {
    override val code = "ASHBY"
    private val throttle = Throttle()

    override fun fetch(source: SourceContext, target: FetchTarget): FetchResult {
        val board = target.externalKey ?: throw ConnectorException("Alvo do Ashby sem board")
        throttle.pause(source.configLong("minDelayMs", 1000))
        val uri = UriComponentsBuilder.fromUriString(source.baseUrl ?: DEFAULT_BASE_URL)
            .pathSegment(board).queryParam("includeCompensation", "true").build().encode().toUri()
        val body = try {
            http.get(uri, emptyMap())
        } catch (ex: Exception) {
            throw httpError("Ashby ($board)", ex)
        }
        return FetchResult(parse(body, board, target.organizationName), fullSnapshot = true)
    }

    fun parse(json: String, board: String, organizationName: String?): List<RawPosting> {
        val response = try {
            mapper.readValue(json, AshbyResponse::class.java)
        } catch (ex: Exception) {
            throw ConnectorException("Resposta do Ashby ($board) em formato inesperado", ex)
        }
        return response.jobs.filter { it.isListed != false }.mapNotNull { job ->
            val id = job.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val salary = job.compensation?.summaryComponents?.firstOrNull { it.compensationType.equals("Salary", ignoreCase = true) }
            RawPosting(
                externalId = "$board:$id",
                title = job.title.orEmpty(),
                url = job.jobUrl ?: job.applyUrl,
                organizationName = organizationName ?: board,
                locationText = job.location,
                description = job.descriptionPlain ?: job.descriptionHtml,
                postedAt = job.publishedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() },
                salaryMin = salary?.minValue,
                salaryMax = salary?.maxValue,
                salaryCurrency = salary?.currencyCode,
                salaryPeriod = when (salary?.interval?.uppercase()) {
                    "1 YEAR" -> "YEAR"
                    "1 MONTH" -> "MONTH"
                    "1 HOUR" -> "HOUR"
                    "1 WEEK" -> "WEEK"
                    "1 DAY" -> "DAY"
                    else -> null
                },
                departments = listOfNotNull(job.department, job.team),
                workMode = StructuredHints.workMode(job.workplaceType) ?: if (job.isRemote == true) "REMOTE" else null,
                jobType = StructuredHints.jobType(job.employmentType)
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.ashbyhq.com/posting-api/job-board"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AshbyResponse(val jobs: List<AshbyJob> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class AshbyJob(
    val id: String? = null,
    val title: String? = null,
    val location: String? = null,
    val department: String? = null,
    val team: String? = null,
    val isListed: Boolean? = null,
    val isRemote: Boolean? = null,
    val workplaceType: String? = null,
    val employmentType: String? = null,
    val descriptionHtml: String? = null,
    val descriptionPlain: String? = null,
    val publishedAt: String? = null,
    val jobUrl: String? = null,
    val applyUrl: String? = null,
    val compensation: AshbyCompensation? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AshbyCompensation(val summaryComponents: List<AshbyComponent> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class AshbyComponent(
    val compensationType: String? = null,
    val interval: String? = null,
    val currencyCode: String? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null
)
