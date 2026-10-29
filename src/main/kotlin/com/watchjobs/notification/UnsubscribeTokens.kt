package com.watchjobs.notification

import com.watchjobs.security.JwtSecrets
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Token assinado (HMAC-SHA256) do link "desligar e-mails": funciona sem login e só serve para isso.
@Component
class UnsubscribeTokens(@Value("\${jwt.secret}") secret: String) {
    private val key = SecretKeySpec(JwtSecrets.decode(secret), "HmacSHA256")

    fun create(userId: Int): String = "$userId.${sign(userId)}"

    fun verify(token: String): Int? {
        val userId = token.substringBefore('.', "").toIntOrNull() ?: return null
        val signature = token.substringAfter('.', "")
        val expected = sign(userId)
        return userId.takeIf { MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray()) }
    }

    private fun sign(userId: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("unsubscribe:$userId".toByteArray()))
    }
}
