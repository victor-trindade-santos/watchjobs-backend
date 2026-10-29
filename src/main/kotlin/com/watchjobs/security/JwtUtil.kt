package com.watchjobs.security

import com.watchjobs.entity.User
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtUtil(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-minutes}") val expirationMinutes: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(JwtSecrets.decode(secret))

    fun generateToken(user: User): String {
        val now = OffsetDateTime.now(ZoneOffset.UTC).toInstant()
        val minimum = user.passwordChangedAt?.plusSeconds(1)?.toInstant()
        val issued = if (minimum != null && now.isBefore(minimum)) minimum else now
        val issuedAt = Date.from(issued)
        val expiration = Date.from(issued.plusSeconds(expirationMinutes * 60))

        return Jwts.builder()
            .subject(user.email)
            .claim("uid", user.id)
            .issuedAt(issuedAt)
            .expiration(expiration)
            .signWith(key)
            .compact()
    }

    fun isValid(token: String): Boolean =
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            true
        } catch (ex: JwtException) {
            false
        } catch (ex: IllegalArgumentException) {
            false
        }

    fun extractUsername(token: String): String =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject

    fun issuedAt(token: String): OffsetDateTime =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.issuedAt
            .toInstant()
            .atOffset(ZoneOffset.UTC)
}