package com.watchjobs.sources

// Origem e comportamento da fonte (3.6.2). Espelha o CHECK de core.job_source.source_type.
enum class SourceType(val defaultTrustRank: Int) {
    ATS_PUBLIC(10),
    AGGREGATOR(40),
    PARTNER_FEED(20),
    SCRAPING(30);

    // Agregador é uma busca: a vaga pode só ter saído do resultado, então nunca é fechada por ausência.
    fun closesMissingJobs(fullSnapshot: Boolean): Boolean = fullSnapshot && this != AGGREGATOR
}

enum class AccessMethod {
    PUBLIC_API,
    AUTHENTICATED_API,
    FILE_FEED,
    HTML_PAGE
}
