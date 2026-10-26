package com.watchjobs.service

import com.watchjobs.entity.RefreshToken
import com.watchjobs.entity.User
import com.watchjobs.repository.RefreshTokenRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

data class IssuedRefreshToken(val value: String, val token: RefreshToken)

@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    @Value("\${jwt.refresh-expiration-days}") private val expirationDays: Long
) {
    init {
        require(expirationDays > 0) { "A validade do refresh token deve ser positiva" }
    }

    @Transactional
    fun issue(user: User): IssuedRefreshToken =
        create(user, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC))

    @Transactional
    fun rotate(value: String): IssuedRefreshToken {
        val token = repository.findByTokenHash(hash(value)) ?: invalidToken()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        validateActive(token, now)
        val replacement = create(token.user, token.familyId, now, token.deviceLabel)
        token.revokedAt = now
        token.lastUsedAt = now
        token.replacedBy = replacement.token
        return replacement
    }

    @Transactional
    fun revokeAll(usersId: Int) {
        repository.revokeByUsersId(usersId, OffsetDateTime.now(ZoneOffset.UTC))
    }

    @Transactional
    fun revoke(value: String, usersId: Int) {
        val token = repository.findByTokenHash(hash(value)) ?: invalidToken()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        if (token.user.id != usersId) invalidToken()
        validateActive(token, now)
        token.revokedAt = now
    }

    fun hash(value: String): String = sha256(value)

    private fun create(
        user: User,
        familyId: UUID,
        now: OffsetDateTime,
        deviceLabel: String? = null
    ): IssuedRefreshToken {
        val value = newRefreshValue()
        val token = repository.save(
            RefreshToken(
                user = user,
                tokenHash = hash(value),
                familyId = familyId,
                expiresAt = now.plusDays(expirationDays),
                createdAt = now,
                deviceLabel = deviceLabel
            )
        )
        return IssuedRefreshToken(value, token)
    }

    private fun validateActive(token: RefreshToken, now: OffsetDateTime) {
        if (token.revokedAt != null || !token.expiresAt.isAfter(now)) invalidToken()
    }

    private fun invalidToken(): Nothing =
        throw BadCredentialsException("Refresh token inválido ou expirado")

    private fun newRefreshValue(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}