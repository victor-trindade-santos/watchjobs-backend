package com.watchjobs.entity

import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.entity.enums.WorkMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "saved_job", schema = "core")
class SavedJob(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "saved_job_id")
    var id: Long? = null,

    @field:Column(name = "position_role", length = 255, nullable = false)
    var positionRole: String = "",

    @field:Column(name = "organization_name", length = 150, nullable = false)
    var organizationName: String = "",

    @field:Column(name = "job_url", length = 2048)
    var jobUrl: String? = null,

    @field:Column(name = "location_city", length = 100)
    var locationCity: String? = null,

    @field:Column(name = "location_state", length = 100)
    var locationState: String? = null,

    @field:Column(name = "location_country_code", length = 2)
    var locationCountryCode: String? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "work_mode", length = 30)
    var workMode: WorkMode? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "experience_level", length = 30)
    var experienceLevel: ExperienceLevel? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "job_type", length = 50)
    var jobType: JobType? = null,

    @field:Column(name = "salary_min", columnDefinition = "numeric(10,2)")
    var salaryMin: Double? = null,

    @field:Column(name = "salary_max", columnDefinition = "numeric(10,2)")
    var salaryMax: Double? = null,

    @field:Column(name = "salary_currency", length = 3, columnDefinition = "bpchar(3)")
    var salaryCurrency: String? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", length = 30, nullable = false)
    var status: SavedJobStatus = SavedJobStatus.SAVED,

    @field:Column(name = "notes", length = 4000)
    var notes: String? = null,

    @field:Column(name = "saved_at", nullable = false, columnDefinition = "timestamptz")
    var savedAt: LocalDateTime = LocalDateTime.now(),

    @field:Column(name = "status_updated_at", nullable = false, columnDefinition = "timestamptz")
    var statusUpdatedAt: LocalDateTime = LocalDateTime.now()
)