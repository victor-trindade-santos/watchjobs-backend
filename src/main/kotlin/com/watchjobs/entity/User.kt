package com.watchjobs.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Entity
@Table(name = "users", schema = "core")
class User(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "users_id")
    var id: Int? = null,

    @field:Column(name = "first_name", length = 60, nullable = false)
    var firstName: String = "",

    @field:Column(name = "last_name", length = 80, nullable = false)
    var lastName: String = "",

    @field:Column(name = "birth_date", nullable = false)
    var birthDate: LocalDate = LocalDate.now(),

    @field:Column(name = "email", length = 254, nullable = false)
    var email: String = "",

    @field:Column(name = "password", length = 255, nullable = false)
    var password: String = "",

    @field:Column(name = "last_login_at", columnDefinition = "timestamptz")
    var lastLoginAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),

    @Column(name = "email_verified_at")
    var emailVerifiedAt: OffsetDateTime? = null,

    @Column(name = "password_changed_at")
    var passwordChangedAt: OffsetDateTime? = null
)