package com.watchjobs.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.OneToOne
import java.time.LocalDateTime
import java.time.LocalDate

@Entity
@Table(name = "application", schema = "core")
class Application(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "application_id")
    var id: Int? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "users_id", nullable = false)
    var user: User? = null,

    @field:OneToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "saved_job_id", nullable = false, unique = true)
    var savedJob: SavedJob? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "application_stage_id", nullable = false)
    var stage: ApplicationStage? = null,

    @field:Column(name = "application_status", length = 30, nullable = false)
    var status: String = "",

    @field:Column(name = "start_date", nullable = false)
    var startDate: LocalDate = LocalDate.now(),

    @field:Column(name = "end_date")
    var endDate: LocalDate? = null,

    @field:Column(name = "interview_at")
    var interviewAt: LocalDateTime? = null,

    @field:Column(name = "notes", length = 4000)
    var notes: String? = null,

    @field:Column(name = "feedback", length = 4000)
    var feedback: String? = null
)    
