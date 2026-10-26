package com.watchjobs.sources

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

// Mapeia só as colunas que o código usa; config, capabilities e saúde são mantidos pelo motor via SQL.
@Entity
@Table(name = "job_source", schema = "core")
class JobSource(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "job_source_id")
    var id: Long? = null,

    @field:Column(name = "name", length = 100, nullable = false)
    var name: String = "",

    @field:Column(name = "code", length = 50, nullable = false)
    var code: String = "",

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "source_type", length = 20, nullable = false)
    var sourceType: SourceType = SourceType.ATS_PUBLIC,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "access_method", length = 20, nullable = false)
    var accessMethod: AccessMethod = AccessMethod.PUBLIC_API,

    @field:Column(name = "base_url", length = 2048)
    var baseUrl: String? = null,

    @field:Column(name = "is_active", nullable = false)
    var active: Boolean = false,

    @field:Column(name = "allows_storage", nullable = false)
    var allowsStorage: Boolean = false,

    @field:Column(name = "requires_attribution", nullable = false)
    var requiresAttribution: Boolean = false,

    @field:Column(name = "trust_rank", nullable = false)
    var trustRank: Short = 50,

    @field:Column(name = "rate_limit_per_day")
    var rateLimitPerDay: Int? = null,

    @field:Column(name = "terms_url", length = 2048)
    var termsUrl: String? = null,

    @field:Column(name = "terms_reviewed_at")
    var termsReviewedAt: OffsetDateTime? = null,

    @field:Column(name = "credentials_ref", length = 100)
    var credentialsRef: String? = null,

    @field:Column(name = "consecutive_failures", nullable = false)
    var consecutiveFailures: Int = 0,

    @field:Column(name = "next_attempt_at")
    var nextAttemptAt: OffsetDateTime? = null,

    @field:Column(name = "last_success_at")
    var lastSuccessAt: OffsetDateTime? = null
)
