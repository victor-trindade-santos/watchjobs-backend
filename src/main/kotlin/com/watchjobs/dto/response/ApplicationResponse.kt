package com.watchjobs.dto.response

import java.time.LocalDate
import java.time.LocalDateTime

data class ApplicationResponse(
    val id: Int,
    val savedJobId: Long,
    val positionRole: String,
    val organizationName: String,
    val stageId: Int,
    val stageName: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val interviewAt: LocalDateTime?,
    val notes: String?,
    val feedback: String?
)


