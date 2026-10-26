package com.watchjobs.sources

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "source_company", schema = "core")
class SourceCompany(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "source_company_id")
    var id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "job_source_id", nullable = false)
    var jobSource: JobSource? = null,

    @field:Column(name = "organization_id", nullable = false)
    var organizationId: Long = 0,

    @field:Column(name = "external_key", length = 150, nullable = false)
    var externalKey: String = "",

    @field:Column(name = "careers_url", length = 2048)
    var careersUrl: String? = null,

    @field:Column(name = "is_active", nullable = false)
    var active: Boolean = true,

    @field:Column(name = "fetch_interval_minutes", nullable = false)
    var fetchIntervalMinutes: Int = 360,

    @field:Column(name = "last_fetched_at")
    var lastFetchedAt: OffsetDateTime? = null,

    @field:Column(name = "last_success_at")
    var lastSuccessAt: OffsetDateTime? = null,

    @field:Column(name = "consecutive_failures", nullable = false)
    var consecutiveFailures: Int = 0,

    @field:Column(name = "last_job_count")
    var lastJobCount: Int? = null
)
