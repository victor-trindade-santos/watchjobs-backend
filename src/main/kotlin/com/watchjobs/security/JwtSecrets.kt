package com.watchjobs.security

import java.security.MessageDigest
import java.util.Base64

/**
 * O segredo de produção é Base64 (openssl rand -base64 32) ou Base64URL.
 * O decodificador estrito do jjwt rejeita '_' e derruba o container na subida.
 */
object JwtSecrets {
    fun decode(secret: String): ByteArray {
        val trimmed = secret.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.isEmpty()) {
            throw IllegalStateException("JWT_SECRET vazio.")
        }
        if (trimmed.startsWith("secretref:")) {
            throw IllegalStateException(
                "JWT_SECRET chegou literal como referencia de secret (${fingerprint(trimmed)}). " +
                    "O Container App nao resolveu secretref:jwt-secret."
            )
        }
        val normalized = buildString(trimmed.length + 2) {
            for (ch in trimmed) {
                when (ch) {
                    '-' -> append('+')
                    '_' -> append('/')
                    else -> append(ch)
                }
            }
        }
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        try {
            val bytes = Base64.getDecoder().decode(padded)
            if (bytes.size < 32) {
                throw IllegalStateException(
                    "JWT_SECRET decodificou para ${bytes.size} bytes; HS256 exige pelo menos 32. ${fingerprint(trimmed)}"
                )
            }
            return bytes
        } catch (ex: IllegalArgumentException) {
            throw IllegalStateException(
                "JWT_SECRET nao e Base64 nem Base64URL. ${fingerprint(trimmed)}",
                ex
            )
        }
    }

    /** Identifica o valor sem grava-lo em log. '_' so existe em Base64URL. */
    fun fingerprint(secret: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        val prefix = digest.take(4).joinToString("") { "%02x".format(it) }
        return "len=${secret.length} sha256_8=$prefix " +
            "underscore=${secret.contains('_')} plus=${secret.contains('+')} " +
            "slash=${secret.contains('/')} dash=${secret.contains('-')} " +
            "dot=${secret.contains('.')} padding=${secret.endsWith('=')}"
    }
}
