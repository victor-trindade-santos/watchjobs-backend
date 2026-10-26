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
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Entity
@Table(name = "password_reset_code", schema = "core")
class PasswordResetCode(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "password_reset_code_id")
    var id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "users_id", nullable = false)
    var user: User,

    @field:Column(name = "code_hash", nullable = false, length = 100)
    var codeHash: String,

    @field:Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime,

    @field:Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),

    @field:Column(name = "attempts", nullable = false)
    var attempts: Short = 0,

    @field:Column(name = "used_at")
    var usedAt: OffsetDateTime? = null,

    @field:Column(name = "revoked_at")
    var revokedAt: OffsetDateTime? = null
)