package com.watchjobs.ingestion.normalize

import com.watchjobs.catalog.NameNormalizer
import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.classification.ClassificationInput
import com.watchjobs.classification.JobClassifier
import com.watchjobs.ingestion.connector.RawPosting
import com.watchjobs.sources.SourceType
import java.time.OffsetDateTime

data class NormalizeContext(
    val sourceId: Long,
    val sourceType: SourceType,
    val allowsStorage: Boolean,
    val sourceCompanyId: Long? = null,
    val organizationName: String? = null,
    val defaultCountry: String? = null
)

data class NormalizedJob(
    val sourceId: Long,
    val sourceCompanyId: Long?,
    val externalId: String,
    val title: String,
    val organizationName: String,
    val jobUrl: String,
    val city: String?,
    val state: String?,
    val countryCode: String?,
    val rawLocation: String?,
    val workMode: String?,
    val experienceLevel: String?,
    val jobType: String?,
    val roleFamily: String?,
    val classificationConfidence: Double?,
    val rulesVersion: String,
    val salaryMin: Double?,
    val salaryMax: Double?,
    val salaryCurrency: String?,
    val salaryPeriod: String?,
    val fullDescription: String?,
    val descriptionSnippet: String?,
    val skills: List<ExtractedSkill>,
    val postedAt: OffsetDateTime?,
    val contentHash: String,
    val fingerprint: String
)

// Converte a vaga da fonte na vaga do corpus. Regra pura: recebe o classificador e o extrator prontos.
class JobNormalizer(
    private val classifier: JobClassifier,
    private val skills: SkillExtractor
) {
    // Devolve null quando a vaga não tem o mínimo para entrar no corpus (conta como rejeitada).
    fun normalize(raw: RawPosting, context: NormalizeContext): NormalizedJob? {
        val title = HtmlText.toText(raw.title)?.take(255)
        val organization = (context.organizationName ?: raw.organizationName)?.trim()?.take(150)
        val url = raw.url?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (raw.externalId.isBlank() || title.isNullOrBlank() || organization.isNullOrBlank() || url == null) return null

        val description = HtmlText.toText(raw.description)
        val location = LocationNormalizer.normalize(raw.locationText, context.defaultCountry)
        val classification = classifier.classify(
            ClassificationInput(title = title, description = description, location = raw.locationText)
        )
        val workMode = classification.workMode?.value ?: if (location.remote) "REMOTE" else null
        val salary = SalaryNormalizer.normalize(raw.salaryMin, raw.salaryMax, raw.salaryCurrency, raw.salaryPeriod)
        val extracted = skills.extract(listOfNotNull(title, description).joinToString(". "))

        return NormalizedJob(
            sourceId = context.sourceId,
            sourceCompanyId = context.sourceCompanyId,
            externalId = raw.externalId.take(255),
            title = title,
            organizationName = organization,
            jobUrl = url,
            city = location.city?.take(100),
            state = location.state?.take(100),
            countryCode = location.countryCode,
            rawLocation = raw.locationText?.trim()?.ifEmpty { null },
            workMode = workMode,
            experienceLevel = classification.seniority?.value,
            jobType = classification.jobType?.value,
            roleFamily = classification.roleFamily?.value,
            classificationConfidence = classification.roleFamily?.confidence,
            rulesVersion = classification.rulesVersion,
            salaryMin = salary?.min,
            salaryMax = salary?.max,
            salaryCurrency = salary?.currency,
            salaryPeriod = salary?.period,
            fullDescription = description.takeIf { context.allowsStorage && context.sourceType != SourceType.SCRAPING },
            descriptionSnippet = HtmlText.snippet(description),
            skills = extracted,
            postedAt = raw.postedAt,
            contentHash = Hashing.sha256(
                title, organization, raw.locationText, workMode, classification.seniority?.value,
                classification.jobType?.value, salary?.min, salary?.max, salary?.currency, description, url,
                extracted.joinToString(",") { "${it.skillId}:${it.required}" }
            ),
            fingerprint = Hashing.sha256(
                TextNormalizer.forMatching(title),
                NameNormalizer.normalize(organization),
                TextNormalizer.forMatching(location.city)
            )
        )
    }
}
