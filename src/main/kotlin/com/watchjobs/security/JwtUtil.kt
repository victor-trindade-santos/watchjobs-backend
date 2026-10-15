package com.watchjobs.security

import com.watchjobs.entity.User
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtUtil(
    @Value("\${jwt.secret}") secret: String,
    @Value("\${jwt.expiration-minutes}") val expirationMinutes: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))

    fun generateToken(user: User): String {
        val now = Date()
        val expiration = Date(now.time + expirationMinutes * 60_000)

        return Jwts.builder()
            .subject(user.email)
            .claim("uid", user.id)
            .issuedAt(now)
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
}
