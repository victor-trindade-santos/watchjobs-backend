package com.watchjobs.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "applicationstage", schema = "core")
class ApplicationStage(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "application_stage_id")
    var id: Int? = null,

    @field:Column(name = "name", length = 100, nullable = false)
    var name: String = ""
)
