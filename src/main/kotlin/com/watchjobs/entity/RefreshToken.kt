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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Entity
@Table(name = "refresh_token", schema = "core")
class RefreshToken(
	@field:Id
	@field:GeneratedValue(strategy = GenerationType.IDENTITY)
	@field:Column(name = "refresh_token_id")
	var id: Long? = null,

	@field:ManyToOne(fetch = FetchType.LAZY, optional = false)
	@field:JoinColumn(name = "users_id", nullable = false)
	var user: User,

	@field:Column(name = "token_hash", nullable = false, unique = true, columnDefinition = "char(64)")
	@field:JdbcTypeCode(SqlTypes.CHAR)
	var tokenHash: String,

	@field:Column(name = "family_id", nullable = false)
	var familyId: UUID,

	@field:Column(name = "expires_at", nullable = false)
	var expiresAt: OffsetDateTime,

	@field:Column(name = "device_label", length = 100)
	var deviceLabel: String? = null,

	@field:Column(name = "created_at", nullable = false)
	var createdAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),

	@field:Column(name = "last_used_at")
	var lastUsedAt: OffsetDateTime? = null,

	@field:Column(name = "revoked_at")
	var revokedAt: OffsetDateTime? = null,

	@field:ManyToOne(fetch = FetchType.LAZY)
	@field:JoinColumn(name = "replaced_by_id")
	var replacedBy: RefreshToken? = null
)
