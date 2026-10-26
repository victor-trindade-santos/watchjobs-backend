package com.watchjobs.ingestion.connector

import java.net.URI
import java.time.OffsetDateTime

// Vaga como veio da fonte, só com os campos que o motor usa.
data class RawPosting(
    val externalId: String,
    val title: String,
    val url: String?,
    val organizationName: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val postedAt: OffsetDateTime? = null,
    val salaryMin: Double? = null,
    val salaryMax: Double? = null,
    val salaryCurrency: String? = null,
    val salaryPeriod: String? = null,
    val departments: List<String> = emptyList()
)

// Um alvo da coleta: uma empresa (ATS, scraping) ou um segmento (agregador).
data class FetchTarget(
    val sourceCompanyId: Long? = null,
    val externalKey: String? = null,
    val organizationId: Long? = null,
    val organizationName: String? = null,
    val lastJobCount: Int? = null,
    val segmentId: Long? = null,
    val keywords: String? = null,
    val roleFamily: String? = null,
    val countryCode: String? = null,
    val workMode: String? = null
) {
    val label: String
        get() = externalKey ?: keywords ?: roleFamily ?: "alvo"
}

data class FetchResult(val postings: List<RawPosting>, val fullSnapshot: Boolean)

// Fonte já carregada do banco, com as credenciais resolvidas pelas variáveis de credentials_ref.
data class SourceContext(
    val sourceId: Long,
    val code: String,
    val baseUrl: String?,
    val config: Map<String, Any?> = emptyMap(),
    val credentials: Map<String, String> = emptyMap()
) {
    fun configLong(key: String, default: Long): Long = (config[key] as? Number)?.toLong() ?: default

    fun configString(key: String): String? = config[key] as? String
}

data class FreeQuery(
    val text: String,
    val location: String? = null,
    val workMode: String? = null,
    val page: Int = 1
)

class ConnectorException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// Cada fonte entra atrás desta interface; o pipeline não conhece detalhes de nenhuma.
interface JobSourceConnector {
    val code: String

    fun fetch(source: SourceContext, target: FetchTarget): FetchResult
}

// Fontes que buscam por palavra-chave (agregadores): base da busca sob demanda (F5).
interface QueryableConnector : JobSourceConnector {
    fun search(source: SourceContext, query: FreeQuery): FetchResult
}

fun interface HttpGateway {
    fun get(uri: URI, headers: Map<String, String>): String
}
